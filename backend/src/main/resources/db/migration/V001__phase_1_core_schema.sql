-- Phase 1 core schema. The model and the reasoning behind every choice here are
-- in ADR-013; tenant isolation is ADR-011; module boundaries are ADR-001.
--
-- Three conventions apply to every business table and are not repeated in
-- comments below:
--
--   1. org_id on every table (ADR-011). Row level security is enabled AND
--      forced, and the policy omits WITH CHECK on purpose: PostgreSQL then
--      reuses the USING expression for new rows, so a row cannot be inserted
--      into another tenant either. Verified 2026-10-14.
--   2. Every unique constraint starts with org_id (ADR-011). A global unique
--      constraint leaks the existence of other tenants' rows through the
--      duplicate-key error, even though row level security hides the row.
--   3. Foreign keys only inside a module (ADR-001). Columns ending in _id that
--      point at another module carry no foreign key and are marked as soft
--      references. The phase 1 referential integrity test verifies them.
--
-- Primary keys use uuidv7(), which is in core PostgreSQL 18 (verified
-- 2026-10-14): the value starts with a timestamp, so inserts land at the end of
-- the index instead of scattering across it.


-- ---------------------------------------------------------------------------
-- Shared
-- ---------------------------------------------------------------------------

-- The tenant registry. ADR-011 only requires the org_id column; the table
-- exists so that an org_id resolves to something when reading data by hand.
--
-- Its own policy discriminates on the primary key, so the application can never
-- enumerate the organizations that are not its own. The unique constraint on
-- code is global rather than per tenant because this IS the tenant table, and
-- creating organizations is an owner operation, not an application one.
CREATE TABLE organization (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    code        text        NOT NULL,
    name        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX organization_code_key ON organization (code);

ALTER TABLE organization ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_tenant_isolation ON organization
    USING (id = current_setting('app.current_org')::uuid);


-- Keeps updated_at honest without every writer having to remember it.
CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER organization_set_updated_at BEFORE UPDATE ON organization
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- ---------------------------------------------------------------------------
-- Module: identity (M1)
-- ---------------------------------------------------------------------------

-- The catalogue of authoritative sources. ADR-013 decision 3 models the
-- authoritative source as data rather than as a branch per identity type, so
-- that governing AI agents in phase 6 means inserting a row here and not
-- creating tables.
--
-- kind is what the identity lifecycle rule reads (ADR-013 decision 6):
--   EXTERNAL  the truth lives in another system, so the identity survives its
--             responsible party going away. Losing one is a finding to fix.
--   INTERNAL  Lineward itself holds the truth, so the only authority for the
--             identity existing is its responsible party. Losing one suspends
--             the identity. This is what suspends an agent when its sponsor
--             leaves, without a code path specific to agents.
CREATE TABLE source_system (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    org_id      uuid        NOT NULL REFERENCES organization (id),
    code        text        NOT NULL,
    name        text        NOT NULL,
    kind        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT source_system_kind_check CHECK (kind IN ('EXTERNAL', 'INTERNAL'))
);

CREATE UNIQUE INDEX source_system_org_code_key ON source_system (org_id, code);

ALTER TABLE source_system ENABLE ROW LEVEL SECURITY;
ALTER TABLE source_system FORCE ROW LEVEL SECURITY;
CREATE POLICY source_system_tenant_isolation ON source_system
    USING (org_id = current_setting('app.current_org')::uuid);

CREATE TRIGGER source_system_set_updated_at BEFORE UPDATE ON source_system
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- The governed view of a person, service account or AI agent.
--
-- type plus a one-to-one extension table per type (ADR-013 decision 7). Phase 1
-- only uses HUMAN and needs no extension; agent_profile arrives in phase 6.
--
-- The typed attribute columns are the ones rules decide on (ADR-013 decision
-- 12). Everything else the source sends stays in source_record.payload, so this
-- table needs no attribute bag of its own.
CREATE TABLE identity (
    id                uuid        PRIMARY KEY DEFAULT uuidv7(),
    org_id            uuid        NOT NULL REFERENCES organization (id),
    type              text        NOT NULL,
    status            text        NOT NULL,
    source_system_id  uuid        NOT NULL REFERENCES source_system (id),
    display_name      text        NOT NULL,
    email             text,
    department        text,
    job_title         text,
    location          text,
    cost_center       text,
    employment_type   text,
    hire_date         date,
    termination_date  date,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_type_check CHECK (type IN ('HUMAN', 'SERVICE', 'AGENT')),
    -- PENDING covers the future-dated joiner that M1 lists as an edge case:
    -- the identity exists and holds no access yet.
    CONSTRAINT identity_status_check CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'TERMINATED'))
);

-- Lets source_record point at (identity, source) as one unit, below.
ALTER TABLE identity ADD CONSTRAINT identity_id_source_key UNIQUE (id, source_system_id);

-- email is deliberately NOT unique. It is an attribute the source asserts and a
-- correlation criterion, and M1 requires ingesting incomplete or contradictory
-- HR data. A constraint here would block ingesting bad data, when what is
-- wanted is to ingest it and raise a finding.
CREATE INDEX identity_org_email_idx      ON identity (org_id, email);
CREATE INDEX identity_org_status_idx     ON identity (org_id, status);
CREATE INDEX identity_org_department_idx ON identity (org_id, department);
CREATE INDEX identity_org_type_idx       ON identity (org_id, type);

ALTER TABLE identity ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity FORCE ROW LEVEL SECURITY;
CREATE POLICY identity_tenant_isolation ON identity
    USING (org_id = current_setting('app.current_org')::uuid);

CREATE TRIGGER identity_set_updated_at BEFORE UPDATE ON identity
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- What the authoritative source asserts about an identity, kept apart from what
-- Lineward decided (ADR-013 decision 4).
--
-- Without this table, JML detection would compare the HR payload against the
-- identity table, which Lineward also writes. A manual correction would then
-- look like a change coming from HR and fire a false Mover event, with its
-- access recalculation behind it. That failure produces plausible work instead
-- of an error.
--
-- One row per identity, updated in place, so it does not grow per run. The
-- history of assertions lives in audit_event, which is append-only anyway.
--
-- payload_hash makes re-ingesting the same payload a hash comparison instead of
-- a field-by-field walk. asserted_at is when the source says it is true, which
-- is not when we heard it: that is what makes a retroactive termination
-- reconstructible.
CREATE TABLE source_record (
    id                uuid        PRIMARY KEY DEFAULT uuidv7(),
    org_id            uuid        NOT NULL REFERENCES organization (id),
    source_system_id  uuid        NOT NULL REFERENCES source_system (id),
    identity_id       uuid        NOT NULL,
    external_id       text        NOT NULL,
    payload           jsonb       NOT NULL,
    payload_hash      bytea       NOT NULL,
    asserted_at       timestamptz NOT NULL,
    ingested_at       timestamptz NOT NULL DEFAULT now(),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    -- A composite foreign key, so the source named here and the source named on
    -- the identity cannot disagree. Cheaper than a check that runs in the
    -- application and can be forgotten.
    CONSTRAINT source_record_identity_source_fk
        FOREIGN KEY (identity_id, source_system_id) REFERENCES identity (id, source_system_id)
);

-- The natural key of the whole model: the employee number, the agent slug, the
-- service account name. It lives here and not on identity so there is one place
-- that owns it. This is also the lookup path during ingestion, which has to find
-- the identity before it knows which one it is.
CREATE UNIQUE INDEX source_record_org_source_external_key
    ON source_record (org_id, source_system_id, external_id);

-- Exactly one authoritative assertion per identity (ADR-013 decision 3).
CREATE UNIQUE INDEX source_record_org_identity_key ON source_record (org_id, identity_id);

ALTER TABLE source_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE source_record FORCE ROW LEVEL SECURITY;
CREATE POLICY source_record_tenant_isolation ON source_record
    USING (org_id = current_setting('app.current_org')::uuid);

CREATE TRIGGER source_record_set_updated_at BEFORE UPDATE ON source_record
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- Who answers for an identity. One table for manager, owner and sponsor
-- (ADR-013 decision 5), which is what makes the leaver cascade one piece of
-- logic instead of two that drift apart.
--
-- origin separates what the source asserts from what somebody decided here. A
-- manager comes from HR and gets overwritten on every ingest; a sponsor is
-- assigned in Lineward and an HR ingest must not wipe it. With a single
-- manager_id column there is no way to tell them apart.
--
-- valid_from and valid_to give history, which is what answers "who was their
-- manager when this access was approved". A column would lose that on every
-- change, and M1 lists "manager change without a job change" as an edge case.
CREATE TABLE identity_responsibility (
    id                       uuid        PRIMARY KEY DEFAULT uuidv7(),
    org_id                   uuid        NOT NULL REFERENCES organization (id),
    subject_identity_id      uuid        NOT NULL REFERENCES identity (id),
    responsible_identity_id  uuid        NOT NULL REFERENCES identity (id),
    role                     text        NOT NULL,
    origin                   text        NOT NULL,
    valid_from               timestamptz NOT NULL DEFAULT now(),
    valid_to                 timestamptz,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_responsibility_role_check   CHECK (role IN ('MANAGER', 'OWNER', 'SPONSOR')),
    CONSTRAINT identity_responsibility_origin_check CHECK (origin IN ('ASSERTED', 'ASSIGNED')),
    CONSTRAINT identity_responsibility_period_check CHECK (valid_to IS NULL OR valid_to > valid_from),
    -- Nobody answers for themselves. M5 will need the same rule for approvals.
    CONSTRAINT identity_responsibility_not_self_check CHECK (subject_identity_id <> responsible_identity_id)
);

-- At most one current holder of each role per identity, which is what the logic
-- needing exactly one responsible party relies on. Historical rows are closed
-- with valid_to and do not collide.
CREATE UNIQUE INDEX identity_responsibility_current_key
    ON identity_responsibility (org_id, subject_identity_id, role)
    WHERE valid_to IS NULL;

CREATE INDEX identity_responsibility_responsible_idx
    ON identity_responsibility (org_id, responsible_identity_id)
    WHERE valid_to IS NULL;

ALTER TABLE identity_responsibility ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_responsibility FORCE ROW LEVEL SECURITY;
CREATE POLICY identity_responsibility_tenant_isolation ON identity_responsibility
    USING (org_id = current_setting('app.current_org')::uuid);

CREATE TRIGGER identity_responsibility_set_updated_at BEFORE UPDATE ON identity_responsibility
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- ---------------------------------------------------------------------------
-- Module: access (M4, minimal part needed by phases 1 and 2)
-- ---------------------------------------------------------------------------

-- A target system and the connector that talks to it.
CREATE TABLE application (
    id                 uuid        PRIMARY KEY DEFAULT uuidv7(),
    org_id             uuid        NOT NULL REFERENCES organization (id),
    code               text        NOT NULL,
    name               text        NOT NULL,
    connector_type     text        NOT NULL,
    criticality        text        NOT NULL DEFAULT 'MEDIUM',
    enabled            boolean     NOT NULL DEFAULT true,
    owner_identity_id  uuid,       -- soft reference: module identity
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT application_connector_type_check CHECK (connector_type IN ('KEYCLOAK', 'LDAP', 'SCIM', 'FLAKY')),
    CONSTRAINT application_criticality_check     CHECK (criticality IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

CREATE UNIQUE INDEX application_org_code_key ON application (org_id, code);

ALTER TABLE application ENABLE ROW LEVEL SECURITY;
ALTER TABLE application FORCE ROW LEVEL SECURITY;
CREATE POLICY application_tenant_isolation ON application
    USING (org_id = current_setting('app.current_org')::uuid);

CREATE TRIGGER application_set_updated_at BEFORE UPDATE ON application
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- A concrete technical permission in a target system: an LDAP group, a Keycloak
-- role. native_value is how the target system names it.
CREATE TABLE entitlement (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    org_id          uuid        NOT NULL REFERENCES organization (id),
    application_id  uuid        NOT NULL REFERENCES application (id),
    native_value    text        NOT NULL,
    display_name    text,
    description     text,
    risk            text        NOT NULL DEFAULT 'LOW',
    requestable     boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT entitlement_risk_check CHECK (risk IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

CREATE UNIQUE INDEX entitlement_org_application_native_key
    ON entitlement (org_id, application_id, native_value);

ALTER TABLE entitlement ENABLE ROW LEVEL SECURITY;
ALTER TABLE entitlement FORCE ROW LEVEL SECURITY;
CREATE POLICY entitlement_tenant_isolation ON entitlement
    USING (org_id = current_setting('app.current_org')::uuid);

CREATE TRIGGER entitlement_set_updated_at BEFORE UPDATE ON entitlement
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- Expected access: what Lineward believes an identity should have, at identity
-- level. The real state lives in account_entitlement, at account level, and
-- drift is the difference between the two (ADR-013 decision 8).
--
-- source and source_detail are the access lineage of principle 2 in section 3.3
-- of the working document: the "why does this identity have this" screen is a
-- query against this table, not a computation. Phase 1 only writes BIRTHRIGHT
-- and LEGITIMATED; phase 3 adds REQUEST and ROLE as new origins of rows in this
-- same table, not a parallel one.
CREATE TABLE access_assignment (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    org_id          uuid        NOT NULL REFERENCES organization (id),
    identity_id     uuid        NOT NULL,  -- soft reference: module identity
    entitlement_id  uuid        NOT NULL REFERENCES entitlement (id),
    source          text        NOT NULL,
    source_detail   text,
    state           text        NOT NULL,
    granted_at      timestamptz,
    expires_at      timestamptz,
    revoked_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT access_assignment_source_check CHECK (source IN ('BIRTHRIGHT', 'REQUEST', 'ROLE', 'MANUAL', 'LEGITIMATED')),
    CONSTRAINT access_assignment_state_check  CHECK (state IN ('PENDING', 'ACTIVE', 'REVOKED', 'EXPIRED'))
);

-- At most one live assignment per identity, entitlement and reason. Two reasons
-- for the same access are legitimate and are exactly what lineage is for: a
-- birthright grant and an approved request can coexist, and revoking one must
-- not silently remove the access.
--
-- NULLS NOT DISTINCT is needed because source_detail is nullable and PostgreSQL
-- otherwise treats two NULLs as different values, which would let duplicates
-- through.
CREATE UNIQUE INDEX access_assignment_live_key
    ON access_assignment (org_id, identity_id, entitlement_id, source, source_detail)
    NULLS NOT DISTINCT
    WHERE state IN ('PENDING', 'ACTIVE');

CREATE INDEX access_assignment_org_identity_idx ON access_assignment (org_id, identity_id);
CREATE INDEX access_assignment_expiry_idx ON access_assignment (org_id, expires_at)
    WHERE state = 'ACTIVE' AND expires_at IS NOT NULL;

ALTER TABLE access_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_assignment FORCE ROW LEVEL SECURITY;
CREATE POLICY access_assignment_tenant_isolation ON access_assignment
    USING (org_id = current_setting('app.current_org')::uuid);

CREATE TRIGGER access_assignment_set_updated_at BEFORE UPDATE ON access_assignment
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- ---------------------------------------------------------------------------
-- Module: aggregation (M2)
-- ---------------------------------------------------------------------------

-- A user as it really exists inside a target system. This is the real state: it
-- is written by the connector, never inferred.
--
-- identity_id NULL means the account could not be matched to any identity,
-- which is an orphan account, the most repeated audit finding in the sector.
--
-- correlation_rule_code and correlation_method record which rule matched it and
-- how. The rules themselves live in versioned configuration and not in a table
-- (ADR-013 decision 10), but their result is persisted, because without it
-- there is no lineage.
--
-- first_seen_at and last_seen_at are what let an aggregation run notice that an
-- account stopped appearing in the target system.
CREATE TABLE account (
    id                     uuid        PRIMARY KEY DEFAULT uuidv7(),
    org_id                 uuid        NOT NULL REFERENCES organization (id),
    application_id         uuid        NOT NULL,  -- soft reference: module access
    native_id              text        NOT NULL,
    username               text,
    email                  text,
    display_name           text,
    status                 text        NOT NULL DEFAULT 'UNKNOWN',
    last_activity_at       timestamptz,
    identity_id            uuid,       -- soft reference: module identity. NULL = orphan
    correlation_method     text,
    correlation_rule_code  text,
    correlated_at          timestamptz,
    first_seen_at          timestamptz NOT NULL DEFAULT now(),
    last_seen_at           timestamptz NOT NULL DEFAULT now(),
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT account_status_check CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED', 'DELETED', 'UNKNOWN')),
    CONSTRAINT account_correlation_method_check CHECK (correlation_method IS NULL OR correlation_method IN ('RULE', 'MANUAL')),
    -- A correlated account has to say how and when. Keeps half-written
    -- correlations out of the table instead of discovering them later.
    CONSTRAINT account_correlation_complete_check CHECK (
        (identity_id IS NULL AND correlation_method IS NULL AND correlated_at IS NULL)
        OR (identity_id IS NOT NULL AND correlation_method IS NOT NULL AND correlated_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX account_org_application_native_key ON account (org_id, application_id, native_id);
CREATE INDEX account_org_identity_idx ON account (org_id, identity_id);
CREATE INDEX account_org_email_idx    ON account (org_id, email);
-- Drives the orphan account report.
CREATE INDEX account_orphan_idx ON account (org_id, application_id) WHERE identity_id IS NULL;

ALTER TABLE account ENABLE ROW LEVEL SECURITY;
ALTER TABLE account FORCE ROW LEVEL SECURITY;
CREATE POLICY account_tenant_isolation ON account
    USING (org_id = current_setting('app.current_org')::uuid);

CREATE TRIGGER account_set_updated_at BEFORE UPDATE ON account
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- Real state: the permissions an account actually holds in the target system.
CREATE TABLE account_entitlement (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    org_id          uuid        NOT NULL REFERENCES organization (id),
    account_id      uuid        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    entitlement_id  uuid        NOT NULL,  -- soft reference: module access
    first_seen_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at    timestamptz NOT NULL DEFAULT now(),
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX account_entitlement_key ON account_entitlement (org_id, account_id, entitlement_id);
CREATE INDEX account_entitlement_entitlement_idx ON account_entitlement (org_id, entitlement_id);

ALTER TABLE account_entitlement ENABLE ROW LEVEL SECURITY;
ALTER TABLE account_entitlement FORCE ROW LEVEL SECURITY;
CREATE POLICY account_entitlement_tenant_isolation ON account_entitlement
    USING (org_id = current_setting('app.current_org')::uuid);


-- One execution of an aggregation, with its resume point.
--
-- checkpoint is opaque JSON on purpose: what "where I got to" means is the
-- connector's business. An LDAP connector resumes from a paged cookie and a
-- SCIM one from an index, and the aggregation module must not know the
-- difference.
CREATE TABLE aggregation_run (
    id                 uuid        PRIMARY KEY DEFAULT uuidv7(),
    org_id             uuid        NOT NULL REFERENCES organization (id),
    application_id     uuid        NOT NULL,  -- soft reference: module access
    mode               text        NOT NULL,
    state              text        NOT NULL,
    started_at         timestamptz NOT NULL DEFAULT now(),
    finished_at        timestamptz,
    checkpoint         jsonb,
    accounts_seen      integer     NOT NULL DEFAULT 0,
    entitlements_seen  integer     NOT NULL DEFAULT 0,
    error              text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT aggregation_run_mode_check  CHECK (mode IN ('FULL', 'INCREMENTAL')),
    CONSTRAINT aggregation_run_state_check CHECK (state IN ('RUNNING', 'INTERRUPTED', 'COMPLETED', 'FAILED', 'CANCELLED'))
);

-- One run at a time per application. Two concurrent aggregations of the same
-- target system would fight over last_seen_at and invent drift.
CREATE UNIQUE INDEX aggregation_run_active_key ON aggregation_run (org_id, application_id)
    WHERE state IN ('RUNNING', 'INTERRUPTED');

CREATE INDEX aggregation_run_history_idx ON aggregation_run (org_id, application_id, started_at DESC);

ALTER TABLE aggregation_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE aggregation_run FORCE ROW LEVEL SECURITY;
CREATE POLICY aggregation_run_tenant_isolation ON aggregation_run
    USING (org_id = current_setting('app.current_org')::uuid);

CREATE TRIGGER aggregation_run_set_updated_at BEFORE UPDATE ON aggregation_run
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- What reconciliation found, with its state (ADR-013 decision 9).
--
-- Findings are persisted rather than computed on read, because acting on drift
-- (revoke, legitimate, accept as an exception) is state, and "since when has
-- this been true" is an auditor's question. The price is that every run has to
-- reconcile them: what still holds updates last_seen_at, what no longer holds
-- is closed as RESOLVED_EXTERNALLY. Without that step the list becomes a
-- graveyard nobody reads, which is the same failure as rubber stamping in M7.
CREATE TABLE reconciliation_finding (
    id                       uuid        PRIMARY KEY DEFAULT uuidv7(),
    org_id                   uuid        NOT NULL REFERENCES organization (id),
    type                     text        NOT NULL,
    state                    text        NOT NULL DEFAULT 'OPEN',
    account_id               uuid        REFERENCES account (id) ON DELETE CASCADE,
    identity_id              uuid,       -- soft reference: module identity
    entitlement_id           uuid,       -- soft reference: module access
    detected_by_run_id       uuid        REFERENCES aggregation_run (id),
    first_detected_at        timestamptz NOT NULL DEFAULT now(),
    last_seen_at             timestamptz NOT NULL DEFAULT now(),
    resolved_at              timestamptz,
    resolved_by_identity_id  uuid,       -- soft reference: module identity
    resolution_note          text,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT reconciliation_finding_type_check CHECK (type IN (
        'ORPHAN_ACCOUNT',          -- an account that matches no identity
        'DORMANT_ACCOUNT',         -- no activity for the configured period
        'UNOWNED_IDENTITY',        -- nobody answers for it. Same finding for a
                                   -- service account and for an orphan agent
        'DRIFT_UNEXPECTED_GRANT',  -- real but not expected: granted outside the platform
        'DRIFT_MISSING_GRANT'      -- expected but not real: provisioning failed or was undone
    )),
    CONSTRAINT reconciliation_finding_state_check CHECK (state IN (
        'OPEN', 'RESOLVED_REVOKED', 'RESOLVED_LEGITIMATED', 'ACCEPTED_EXCEPTION', 'RESOLVED_EXTERNALLY'
    )),
    -- Each type has to carry the subject it is about. Without this, a finding
    -- with every reference null is insertable and means nothing.
    CONSTRAINT reconciliation_finding_subject_check CHECK (
        (type IN ('ORPHAN_ACCOUNT', 'DORMANT_ACCOUNT') AND account_id IS NOT NULL)
        OR (type = 'UNOWNED_IDENTITY' AND identity_id IS NOT NULL)
        OR (type IN ('DRIFT_UNEXPECTED_GRANT', 'DRIFT_MISSING_GRANT')
            AND identity_id IS NOT NULL AND entitlement_id IS NOT NULL)
    ),
    CONSTRAINT reconciliation_finding_resolution_check CHECK (
        (state = 'OPEN' AND resolved_at IS NULL) OR (state <> 'OPEN' AND resolved_at IS NOT NULL)
    )
);

-- One open finding per subject, so re-running aggregation updates instead of
-- piling up duplicates.
CREATE UNIQUE INDEX reconciliation_finding_open_key
    ON reconciliation_finding (org_id, type, account_id, identity_id, entitlement_id)
    NULLS NOT DISTINCT
    WHERE state = 'OPEN';

CREATE INDEX reconciliation_finding_open_idx ON reconciliation_finding (org_id, type) WHERE state = 'OPEN';

ALTER TABLE reconciliation_finding ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation_finding FORCE ROW LEVEL SECURITY;
CREATE POLICY reconciliation_finding_tenant_isolation ON reconciliation_finding
    USING (org_id = current_setting('app.current_org')::uuid);

CREATE TRIGGER reconciliation_finding_set_updated_at BEFORE UPDATE ON reconciliation_finding
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- ---------------------------------------------------------------------------
-- Module: audit (M8, phase 1 part)
-- ---------------------------------------------------------------------------

-- The head of the hash chain, one per organization (ADR-013 decision 14).
--
-- Per organization and not global because evidence is exported per client, and
-- proving a global chain would mean handing over hashes of other clients'
-- events for one client's verification to add up. That turns the deliverable
-- that closes an audit into a leak.
--
-- The writer locks this row with SELECT ... FOR UPDATE inside the same
-- transaction, which serializes audit writers within an organization. That cost
-- is the price of a verifiable chain: a chain with gaps, or with two events on
-- the same link, is not verifiable. Measured in phase 8.
CREATE TABLE audit_chain (
    org_id         uuid        PRIMARY KEY REFERENCES organization (id),
    last_sequence  bigint      NOT NULL DEFAULT 0,
    last_hash      bytea,
    updated_at     timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE audit_chain ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_chain FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_chain_tenant_isolation ON audit_chain
    USING (org_id = current_setting('app.current_org')::uuid);


-- The append-only event log. Every _id column here is a soft reference: audit
-- listens to all modules and nobody may depend on audit (section 7.3), so it
-- cannot hold a foreign key to anything.
--
-- actor_identity_id plus on_behalf_of_identity_id is the double attribution M8
-- and M10 need: who acted, and in whose name. It is the only way to answer "who
-- did this" when the answer has two parts, and it is what makes an AI agent
-- auditable.
--
-- prev_hash and hash are nullable and unused until phase 4. Two columns now
-- cost nothing; adding them later is a migration over written data.
CREATE TABLE audit_event (
    id                        uuid        PRIMARY KEY DEFAULT uuidv7(),
    org_id                    uuid        NOT NULL REFERENCES organization (id),
    sequence                  bigint      NOT NULL,
    occurred_at               timestamptz NOT NULL DEFAULT now(),
    type                      text        NOT NULL,
    actor_identity_id         uuid,
    on_behalf_of_identity_id  uuid,
    object_type               text        NOT NULL,
    object_id                 uuid,
    payload                   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    prev_hash                 bytea,
    hash                      bytea
);

-- No gaps and no duplicates in the chain, enforced by the database rather than
-- by the writer being careful.
CREATE UNIQUE INDEX audit_event_org_sequence_key ON audit_event (org_id, sequence);
CREATE INDEX audit_event_object_idx ON audit_event (org_id, object_type, object_id);
CREATE INDEX audit_event_actor_idx  ON audit_event (org_id, actor_identity_id, occurred_at DESC);
CREATE INDEX audit_event_type_idx   ON audit_event (org_id, type, occurred_at DESC);

ALTER TABLE audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_event FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_event_tenant_isolation ON audit_event
    USING (org_id = current_setting('app.current_org')::uuid);

-- Append-only by permissions, not by good intentions. infra/postgres/init grants
-- SELECT, INSERT, UPDATE and DELETE on every future table to the application
-- role, so this revocation is necessary and is easy to forget. A log the
-- application can edit is not evidence.
REVOKE UPDATE, DELETE ON audit_event FROM ${app_role};
