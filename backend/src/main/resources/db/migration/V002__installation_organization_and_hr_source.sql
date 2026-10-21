-- The organization this installation serves, and the authoritative source of
-- phase 1.
--
-- Why a migration and not application code. The policy on the organization
-- table discriminates on its primary key, so the application role can only
-- insert an organization whose id it has already declared as its current
-- tenant, and the comment on that table in V001 is explicit that creating
-- organizations is an owner operation. Flyway runs as the owner, so this is
-- where it belongs.
--
-- Why a fixed uuid, for the same reason. app.current_org has to be set before
-- any query, including the query that would look this row up by code, so the id
-- cannot be discovered at runtime. Being able to discover it would mean being
-- able to enumerate the other tenants. It is configuration, and
-- lineward.tenant.org-id in application.yaml has to match the value below.
--
-- Why set_config here. FORCE ROW LEVEL SECURITY applies to the table owner too,
-- so this migration is subject to the policies like anybody else. ADR-011 used
-- to claim the owner was exempt and was corrected on 2026-10-14 after testing
-- it. Flyway wraps each migration in a transaction on PostgreSQL, which is what
-- makes the transaction scoped setting below reach the statements after it.
--
-- One organization until multi-tenancy exists, which section 2.3 says is not a
-- goal. The discriminator is in every table from the first migration so that
-- adding tenants later does not mean rewriting every table and every query.

SELECT set_config('app.current_org', '00000000-0000-7000-8000-000000000001', true);

INSERT INTO organization (id, code, name)
VALUES ('00000000-0000-7000-8000-000000000001', 'lineward', 'Lineward');

-- kind EXTERNAL because the truth about who works here lives in the HR system,
-- not in Lineward. That single column is what decision 6 of ADR-013 reads to
-- decide whether an identity survives the party responsible for it going away:
-- a person does, because HR keeps asserting them, and an agent will not,
-- because nothing outside Lineward asserts an agent. The rule is written
-- against this column and not against the identity type, so that phase 6 adds a
-- row here instead of a branch in the lifecycle code.
INSERT INTO source_system (org_id, code, name, kind)
VALUES ('00000000-0000-7000-8000-000000000001', 'hr', 'HR system', 'EXTERNAL');
