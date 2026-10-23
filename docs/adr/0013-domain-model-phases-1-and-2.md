# ADR-013 · Domain model for phases 1 and 2

- **Status:** Accepted
- **Date:** 2026-10-14

## Context

The first Flyway migration is the last phase 0 task blocking real work, and it cannot be written without first deciding the shape of the entities. ADR-011 already fixed that every business table carries an organisation discriminator and that isolation is applied with row level security, but it did not say which tables there are.

The scope of this record is modules M1 (identities and authoritative source), M2 (aggregation, correlation and reconciliation) and M3 (connectors and provisioning), which are the modules of phases 1 and 2. The modules from phase 3 onwards are not modelled here, but the model is designed knowing they are coming.

Two seams were flagged as blocking and are resolved here: the **authoritative source per identity type** and the responsibility relationship, that is, whether **manager and sponsor are the same piece**. They are resolved together because they are the same question seen from two sides, and that was not visible until they were put side by side.

A point of method that shapes the scope: Flyway is versioned, so there is no need to create every table in the first migration. What is needed is for the model to be decided. The phase 1 tables go in the first migration and the phase 2 tables in theirs.

## Decisions

### 1. The entities, and which module each table belongs to

The ownership of each table matters because section 7.3 of the working document forbids a module from reading another module's tables, and because it decides where a foreign key may exist (decision 9).

| Module | Tables | Phase |
|---|---|---|
| `shared` | `organization` | 1 |
| `identity` | `source_system`, `source_record`, `identity`, `identity_responsibility` | 1 |
| `access` | `application`, `entitlement`, `access_assignment` | 1 |
| `aggregation` | `account`, `account_entitlement`, `aggregation_run`, `reconciliation_finding` | 1 |
| `audit` | `audit_event`, `audit_chain` | 1 |
| `provisioning` | `provisioning_task`, `outbox_message` | 2 |

Left out, for belonging to separate services with their own store: the HR simulator, the SCIM demonstration application and the flaky application. If their tables lived in Lineward's schema, the authoritative source and the governance platform would be the same database, and then aggregation would consist of reading itself, which is precisely what the project wants to demonstrate it does not do.

### 2. `organization` exists as a table

ADR-011 requires the `org_id` column, not a table. The table is created anyway, with the minimum: identifier, name and creation date. The reason is not referential integrity, which ADR-011 already warns protects nothing across organisations because foreign keys bypass RLS, but that an `org_id` with no destination is a UUID that cannot be resolved to anything when looking at data by hand.

The table carries its own RLS policy, with the discriminator on its primary key:

```sql
CREATE POLICY organization_tenant_isolation ON organization
    USING (id = current_setting('app.current_org')::uuid);
```

That way the application can never enumerate the organisations that are not its own. It would not have been a leak of access data, but it would have been a leak of the client list.

### 3. Every identity has exactly one authoritative source, and it is modelled as data

The M1 sheet describes the authoritative source as a property per identity type: HR for a person, a service account registry for a service account, the agent registry for an agent. The temptation is to write that as a `switch` on type in the code. Rejected, because then every new type is a new branch in the lifecycle logic, which is exactly what principle 8 of section 3.3 forbids.

It is modelled with two tables:

- **`source_system`**: the catalogue of sources. Each row has a code (`hr`, `agent-registry`, `service-registry`), a name and a **kind**: `EXTERNAL` if the truth lives in an outside system, `INTERNAL` if the truth is written by Lineward.
- **`source_record`**: what that source asserts about an identity. One row per identity per source, updated in place, with the external identifier, the raw payload in JSONB, a hash of the payload to detect change without comparing field by field, and the instant of the assertion.

`source_record` is **mandatory for every identity, internal ones included.** For an employee, the assertion is written by the HR connector. For a phase 6 AI agent, it is written by the console at registration. The difference between the two cases is who writes the row, not that one exists and the other does not. That leaves a single code path for "what is known about this identity and who says so", which is what makes the governance one rather than three.

In phase 6, bringing agent governance online is **inserting a row into `source_system`**, not creating tables. The candidate concepts table in section 7.4 says that every new agent specific entity is an alarm signal; this decision is what means there is none.

### 4. Store what the source said, and not only what Lineward decided

It is the consequence of decision 3 and it deserves its own section because the alternative looked cheaper. The alternative was detecting JML events by comparing the HR payload with the `identity` table itself, storing nothing else.

Rejected for a concrete failure. `identity` is the governed view, and Lineward writes it: a manual correction, a certification campaign or a future module can change it. If JML detection compares against it, the next HR extract sees a difference HR did not cause and **fires a false mover event**, with its access recalculation and its provisioning behind it. The failure gives no warning: it produces plausible work.

Storing the assertion separately also solves two edge cases the M1 sheet already required:

- **Retroactive termination.** On the 10th, HR says somebody left on the 3rd. The assertion carries the dates the source declares, so "when it happened" can be told apart from "when we were told", which is an auditor's first question.
- **Idempotent re-ingestion.** Processing the same extract again compares two hashes and does nothing, instead of walking fields.

The cost is a table with one row per identity per source. It does not grow with each run: the assertion is updated in place and the history lives in the audit log, which is append only by design.

### 5. One single responsibility relationship: manager and sponsor are the same piece

The decision is **one `identity_responsibility` table** with a role, instead of a `manager_id` column on `identity` and a `sponsor_id` on the agent profile.

```text
identity_responsibility(
    org_id, id,
    subject_identity_id,        -- the governed identity
    responsible_identity_id,    -- who answers for it
    role,                       -- MANAGER | OWNER | SPONSOR
    origin,                     -- ASSERTED (the source says so) | ASSIGNED (somebody decided it inside Lineward)
    valid_from, valid_to
)
```

What it buys, and this is what settles the choice:

- **The leaver logic is written once.** It is the criterion section 7.4 explicitly asked for.
- **It solves the "manager change with no job change" edge case** without touching `identity`, and it leaves history, which is what allows answering "who was their manager when this access was approved". With a column, that fact is lost on every change.
- **Phase 6 adds no tables.** A sponsor is a row with `role = SPONSOR`.
- **`origin` separates what the source asserts from what was decided here.** A manager comes from HR and is overwritten on every extract; a sponsor is assigned inside Lineward and an HR extract must not stamp on it. With a single `manager_id` column there is no way to tell them apart, and the connector would end up deleting human assignments.

The cost is a join to find out who answers for an identity, and a uniqueness constraint for the cases where the logic needs exactly one responsible party: `UNIQUE (org_id, subject_identity_id, role)` among the rows in force.

### 6. The rule that joins the two seams

Decisions 3 and 5 looked like two tasks and are one. The difference between an employee and an agent is not in the responsibility relationship or in the identity type, but in **where the authority for that identity to exist comes from**:

> An identity whose authority to exist is an external system survives its responsible party's vacancy. An identity whose authority to exist is a person does not.

Written against the model: if `source_system.kind = EXTERNAL`, being left without a responsible party is a finding to resolve, and the identity stays active because the external source keeps asserting that it exists. If `kind = INTERNAL`, being left without a responsible party **suspends the identity**, because nobody is left asserting that it should exist.

This has three consequences worth having:

1. The automatic suspension of an agent when its sponsor leaves, which principle 2 of section 6.2 requires, **is not implemented as a special case for agents.** It falls out of the general rule. A code path that only runs for agents is a path nobody tests until it fails.
2. The service account with no owner, which the M2 sheet asks to detect, is the same finding as the orphaned agent. One report, not two.
3. The rule is expressed against `source_system.kind`, not against `identity.type`. Adding an identity type in the future does not force touching it.

### 7. The three identity types: a common table and an extension per type

`identity` carries a `type` column with `HUMAN`, `SERVICE` and `AGENT`, and the particulars of each type go in a one to one extension table when its phase arrives (`agent_profile` in phase 6). It is what section 7.4 already hinted at, and it is confirmed.

Both alternatives are rejected: a single table with every column of every type ends up with half of them null and with no way to require an agent's sponsor to exist; a table per type forces writing every certification, SoD and audit query three times, which is the failure principle 8 describes.

In phase 1 only `HUMAN` exists and no extension is needed.

### 8. Expected state and actual state are different tables

It is the heart of M2: drift can only be detected if the model stores the two things separately.

| | Table | Level | Who writes it |
|---|---|---|---|
| **Actual** | `account`, `account_entitlement` | Account | The connector, when aggregating |
| **Expected** | `access_assignment` | Identity | Lineward, when calculating |

`access_assignment` relates an identity to an entitlement and carries **why they hold it**: `source` (`BIRTHRIGHT`, `REQUEST`, `ROLE`, `MANUAL`, `LEGITIMATED`) and `source_detail` with the code of the specific rule, request or role. That column is the implementation of principle 2 of section 3.3, the lineage of an access: the "why do they hold this access" screen is a query against this table, not a calculation.

In phase 1 only `BIRTHRIGHT` and `LEGITIMATED` are used. In phase 3, business roles and requests become new origins of rows in this same table, not a parallel table.

Drift is the difference between the two sets, calculated per identity and application once correlation has joined the accounts to the identities. There are two directions and they are two different findings: an actual access that is not expected (somebody granted it outside the platform) and an expected access that is not in the target system (provisioning failed or somebody removed it by hand).

### 9. Findings are persisted, with their state and their reconciliation

`reconciliation_finding` is created with a `type` (`ORPHAN_ACCOUNT`, `DORMANT_ACCOUNT`, `UNOWNED_IDENTITY`, `DRIFT_UNEXPECTED_GRANT`, `DRIFT_MISSING_GRANT`), the reference to the object, a state, and the `first_detected_at` and `last_seen_at` marks.

Computing them at query time would have been simpler and never goes stale, but M2 requires **acting** on drift (revoking, legitimating by creating the request that was missing, or marking it as an exception) and that is state that has to be stored. Besides, "since when has this finding existed" is an audit question and cannot be answered without persisting it.

The trade-off is that a persisted finding can go stale, so every aggregation run **reconciles**: what still holds updates `last_seen_at`, and what no longer holds is closed as `RESOLVED_EXTERNALLY`. Without that step, the findings list turns into a graveyard and nobody looks at it, which is the same failure as M7's bulk approval without looking.

### 10. The rules live in the repository, not in tables

The correlation rules and the birthright rules are declared in versioned configuration files and loaded as Java types, not as rows.

The M2 sheet asks for them to be "configurable and ordered by priority", and that admits both forms. Files are chosen for three reasons:

- **Real frequency.** A correlation rule is adjusted when a system is onboarded and then almost never. It does not justify an administration interface with its CRUD and its edit auditing.
- **They are reserved ground and they have to be tested.** In the repository they are tested in CI by construction. In a table, the case that matters is the production data, which no test sees.
- **Change control.** A rule change in git has a reviewer, a diff and a way back. In a table it has an audit row and no reviewer. And a rule editable while the system runs invites touching it so that a demonstration goes well, which is how a correlation engine gets poisoned.

What **is** persisted is the result of applying them, because without that there is no lineage: `account` stores which rule correlated it, when, and by which method (`RULE` or `MANUAL`), and `access_assignment` stores which birthright rule originated each access.

It stays revisable in phase 3 if the console needs to edit them, and then it will be a new record.

### 11. Keys, identifiers and uniqueness

- **Primary key:** a surrogate UUID in every table, generated with **`uuidv7()`**. Verified on 2026-10-14 against the real container (PostgreSQL 18.6): `uuidv7()`, `uuidv4()` and `uuid_extract_timestamp()` are in the core, with no extensions. v7 is chosen over `gen_random_uuid()`, which is v4, for two reasons: a v7 UUID starts with a timestamp, so inserts land at the end of the index instead of scattering at random across the tree, and the key also carries its creation date inside, recoverable with `uuid_extract_timestamp()`. The second does not replace a `created_at` column, which still exists because an identifier is not a business fact, but it helps when diagnosing.
- **Every uniqueness constraint starts with `org_id`**, by ADR-011's rule: a global uniqueness leaks the existence of other organisations' rows through the duplicate constraint error, even though RLS hides the row.
- **The natural keys live in `source_record`**, with `UNIQUE (org_id, source_system_id, external_id)`. `identity` carries no external identifier: it is reached through the assertion. That way there is a single place where the employee number lives and not two that can disagree.
- The rest: `UNIQUE (org_id, code)` on `application` and `source_system`, `UNIQUE (org_id, application_id, native_id)` on `account`, `UNIQUE (org_id, application_id, native_value)` on `entitlement`, `UNIQUE (org_id, account_id, entitlement_id)` on `account_entitlement`.
- **Email carries no uniqueness constraint**, and that is deliberate. It is an attribute the source asserts and a correlation criterion, and the M1 sheet asks for incomplete or contradictory HR data to be handled. A constraint here would prevent ingesting bad data, when what is wanted is to ingest it and flag it. One M2 edge case is exactly that: two identities matching the same account.

### 12. Attributes: typed columns for what governs, JSONB for the rest

`identity` carries typed columns only for the attributes something is decided on: department, job title, location, cost centre, employment type and the hire and termination dates. Those are the ones the correlation and birthright rules read, so they need an index and they need a typo in an attribute name to be a compile error rather than a silent null.

Everything else the source sends stays in `source_record.payload`, which is JSONB. The pleasant consequence of decision 4 is that `identity` **needs no attribute bag** of its own: the raw truth is already stored opposite it.

This slightly reinterprets section 7.2 of the working document, which justifies PostgreSQL with "JSONB for flexible attributes". JSONB is used, but in the source assertion and not in the governed identity. It is said here so the contradiction does not pass unnoticed.

### 13. Foreign keys only inside a module

Section 7.3 forbids foreign keys crossing a module boundary: the reference is stored as a loose identifier and coherence is guaranteed by the domain. Applied to this schema, the consequence is wider than it looks and is worth seeing written down:

| Reference | Crosses | Foreign key |
|---|---|---|
| `source_record.identity_id` → `identity` | No, both in `identity` | Yes |
| `identity_responsibility.*_identity_id` → `identity` | No | Yes |
| `account_entitlement.account_id` → `account` | No, both in `aggregation` | Yes |
| any `.org_id` → `organization` | No, `organization` is in `shared` and everybody may depend on `shared` | Yes |
| `account.identity_id` → `identity` | Yes, `aggregation` → `identity` | **No** |
| `account.application_id` → `application` | Yes, `aggregation` → `access` | **No** |
| `account_entitlement.entitlement_id` → `entitlement` | Yes | **No** |
| `access_assignment.identity_id` → `identity` | Yes, `access` → `identity` | **No** |
| `provisioning_task.account_id` → `account` | Yes | **No** |
| `audit_event.*` → anything | Yes, and besides nobody may depend on `audit` | **No** |

That is: **most of the interesting references in this schema cannot be foreign keys.** That is consistent with ADR-001 and with ADR-011, which already moved cross organisation coherence into the domain, but it has a real cost that is not going to be glossed over: rows can be left pointing at nothing, and the value of the platform is precisely knowing who has access to what.

The mitigation is explicit and arrives with phase 1 rather than later: a naming convention (`_id` columns without a foreign key are soft references) and **a referential integrity test that checks all of them**, run in CI against the test database. Without that test, ADR-001's rule is a renunciation of integrity rather than a decoupling.

### 14. Audit: one chain per organisation

Section 16 of the working document left open whether the audit log hash chain is one global chain or one per organisation, and tied it to this task. The decision is **one per organisation.**

The reason is the one ADR-011 already anticipated: evidence is exported per client, and demonstrating a global chain would require handing over hashes of other organisations' events for the verification to add up. That turns the deliverable that closes an audit into a leak.

Implications for the phase 1 schema, even though the hash is not computed until phase 4:

- `audit_event` carries `sequence` (monotonic per organisation), `prev_hash` and `hash`, the last two null for now. Providing for them costs two columns; adding them later is a migration over data already written.
- `audit_chain` holds one row per organisation with the last sequence number and the last hash. The writer locks it with `SELECT ... FOR UPDATE` in the same transaction, which **serialises the audit writers within an organisation**. It is a known cost and it is written down here: it is the price of a verifiable chain, because a chain with gaps or with two events in the same link is not verifiable. It is measured in phase 8.
- The log is append only by permissions, not by good intentions: the migration revokes `UPDATE` and `DELETE` on `audit_event` from the application role. The initialisation script grants all four permissions by default on future tables, so this revocation is necessary and easy to forget.

## Consequences

**What is gained**

- Both blocking seams are closed, and resolved with a single rule (decision 6) instead of two special cases.
- Phase 6 needs no new tables for agent governance: it needs a row in `source_system` and a one to one extension. It is the test section 7.4 proposed for telling whether the governance model is one or two.
- The lineage of an access and the lineage of a correlation are in the schema from the start, not added later.
- The hash chain decision is no longer open and the first migration already has the right shape.

**What is lost, or what has to be watched**

- **One more join** for almost everything: the employee number is in `source_record` and the responsible party in `identity_responsibility`. At demonstration scale (around 5,000 identities) that is not a problem, and it is something to measure rather than assume.
- **Partial referential integrity.** ADR-001's cost applied to the schema. It depends on decision 13's integrity test, which becomes a mandatory phase 1 task.
- **Audit writers serialised per organisation.** Decision 14.
- **Reconciling findings is work on every run.** Decision 9.
- **Rules in files require a deployment to change them.** Decision 10, revisable in phase 3.

## What stays open

- The concrete shape of the phase 2 tables (`provisioning_task` and `outbox_message`) is settled in their migration. What is decided here is which module they belong to and that `outbox_message` carries an organisation discriminator, with the consequence that the publisher has to iterate organisations explicitly, which is the delicate point ADR-011 flags for processes with no user signed in.
- The `agent_profile` extension and the delegated authority table belong to phase 6 and are not designed here.

## Verification against the real container

Run on 2026-10-14 against the PostgreSQL 18.6 of `infra/docker-compose.yml`, connecting as the owner role:

| Test | Result |
|---|---|
| `SELECT version()` | `PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2)`, consistent with ADR-006 |
| `SELECT uuidv7()` | Returns a version 7 UUID. Available in the core, with no extensions |
| `SELECT gen_random_uuid()` | Available, rejected in favour of v7 |
| The `pg_proc` catalogue | Confirms `uuidv4`, `uuidv7` and `uuid_extract_timestamp` as core functions |
