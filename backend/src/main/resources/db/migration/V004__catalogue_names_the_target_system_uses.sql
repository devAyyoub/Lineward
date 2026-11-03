-- The intranet roles of V003, renamed to what the Keycloak connector reads.
--
-- The defect, found on 2026-11-03 while closing KAN-8 and eleven days old by
-- then. V003 seeded the intranet catalogue with bare names, 'employee' and
-- 'treasury-read', written before any connector existed and therefore written
-- from the idea of a permission rather than from a system. Keycloak names a
-- group by its path: '/employee', and '/intranet/treasury-read' for one nested
-- under a parent. Same permission, two names, and nothing compared them,
-- because the only test on the catalogue checked that the birthright rules
-- point at rows that exist, which they did.
--
-- What it would have cost, which is why this is a migration and not a note on a
-- backlog. KAN-12 reconciles expected access against real access by native
-- value. With two names for one permission nothing matches: every birthright
-- grant reads as missing from the target system, and every group of the realm
-- reads as unexpected. The first drift report of the platform would have been
-- false positives end to end, which is the failure mode that teaches people to
-- ignore the report.
--
-- Why the connector's name wins, decided by Ayyoub on 2026-11-03. The native
-- value is the target system's own name for the thing, and the target system is
-- the one that will not change. Making the connector translate instead would
-- put a mapping table between the catalogue and reality, and a mapping table
-- that nobody maintains is the same defect with more moving parts. V003 says as
-- much about its own rows: it calls them a stand-in that a connector turns into
-- confirmed or into drift.
--
-- Why an UPDATE and not a delete and reinsert. access_assignment.entitlement_id
-- is a foreign key to entitlement.id, and birthright has already written 15.899
-- grants against these four rows in the synthetic population. A rename keeps the
-- id, so every grant survives and no migration of the assignments is needed.
-- The unique index is (org_id, application_id, native_value), so what the rename
-- has to avoid is colliding with a value already there, and the WHERE clause of
-- each statement is what makes it do nothing twice rather than fail.
--
-- The directory application is not touched, and that is not an oversight: V003
-- named those thirteen rows with full distinguished names, which is exactly what
-- the LDAP connector reads, so that half was right from the start. Verified by
-- ShippedCatalogueTests, which compares the catalogue against the configuration
-- this repository ships for both systems.
--
-- Why a migration and not application code, same as V002 and V003: FORCE ROW
-- LEVEL SECURITY applies to the owner too, so the set_config below is what lets
-- the statements past the policies, and Flyway wraps a migration in a
-- transaction on PostgreSQL, which is what makes a transaction scoped setting
-- reach the statements after it.

SELECT set_config('app.current_org', '00000000-0000-7000-8000-000000000001', true);


-- One statement per role rather than a CASE over four, because each one is a
-- separate fact about what Keycloak calls that group, and a reader checking the
-- realm file against this migration should be able to check them one at a time.
--
-- The join is on the application code and not on an id, because the id is
-- whatever uuidv7() produced when V003 ran.
UPDATE entitlement e
   SET native_value = '/employee',
       updated_at   = now()
  FROM application a
 WHERE a.org_id = e.org_id
   AND a.id     = e.application_id
   AND a.code   = 'intranet'
   AND e.native_value = 'employee';

UPDATE entitlement e
   SET native_value = '/intranet/treasury-read',
       updated_at   = now()
  FROM application a
 WHERE a.org_id = e.org_id
   AND a.id     = e.application_id
   AND a.code   = 'intranet'
   AND e.native_value = 'treasury-read';

UPDATE entitlement e
   SET native_value = '/intranet/supplier-create',
       updated_at   = now()
  FROM application a
 WHERE a.org_id = e.org_id
   AND a.id     = e.application_id
   AND a.code   = 'intranet'
   AND e.native_value = 'supplier-create';

UPDATE entitlement e
   SET native_value = '/intranet/payment-approve',
       updated_at   = now()
  FROM application a
 WHERE a.org_id = e.org_id
   AND a.id     = e.application_id
   AND a.code   = 'intranet'
   AND e.native_value = 'payment-approve';
