-- The applications and entitlements the birthright rules of phase 1 grant.
--
-- Why this exists at all. access_assignment.entitlement_id is a real foreign
-- key to entitlement, so birthright cannot write a single row until there is a
-- catalogue to point at. Nothing created one: V002 registers the organization
-- and the HR source, and the read connectors that would discover entitlements
-- from a target system are KAN-6 to KAN-8, still ahead.
--
-- What is installation data here and what is a stand-in, said plainly because
-- the two have different futures:
--
--   application   registering which systems an organization governs is a
--                 decision somebody makes, not something discovered. These two
--                 rows stay, and M4 adds an owner to each of them.
--   entitlement   a real deployment learns these from the target system, which
--                 is what aggregation is for (M2). The rows below are a
--                 stand-in so that phase 1 has something to grant, and the day
--                 a connector reads Keycloak or LDAP for real they become
--                 either confirmed or drift.
--
-- Why a migration and not application code, same as V002: FORCE ROW LEVEL
-- SECURITY applies to the owner too, so the set_config below is what lets the
-- inserts past the policies, and Flyway wraps each migration in a transaction
-- on PostgreSQL, which is what makes a transaction scoped setting reach the
-- statements after it.
--
-- requestable is false on every row, and that is not an oversight. Birthright
-- is what you get without asking; a catalogue somebody can request from is
-- phase 3, and marking these requestable would offer people a button for access
-- they already hold.

SELECT set_config('app.current_org', '00000000-0000-7000-8000-000000000001', true);


-- The directory every employee has an account in. Groups here are the coarse
-- grained kind: who you are, not what you may do.
INSERT INTO application (org_id, code, name, connector_type, criticality)
VALUES ('00000000-0000-7000-8000-000000000001', 'corporate-directory',
        'Corporate directory', 'LDAP', 'HIGH');

-- The internal applications behind one sign-on, where the entitlements that
-- carry real authority live.
INSERT INTO application (org_id, code, name, connector_type, criticality)
VALUES ('00000000-0000-7000-8000-000000000001', 'intranet',
        'Intranet', 'KEYCLOAK', 'MEDIUM');


-- Directory groups. The distinguished name is how LDAP names a group, and it is
-- stored as the native value because that is the string a connector will send:
-- a catalogue that keeps a prettified name instead cannot provision anything.
INSERT INTO entitlement (org_id, application_id, native_value, display_name, description, risk)
SELECT '00000000-0000-7000-8000-000000000001', a.id, v.native_value, v.display_name, v.description, v.risk
  FROM application a,
       (VALUES
          ('cn=all-staff,ou=groups,dc=lineward,dc=example',
           'All staff',
           'Every active member of staff. The base package nobody requests.',
           'LOW'),
          ('cn=managers,ou=groups,dc=lineward,dc=example',
           'Managers',
           'Anybody with people reporting to them, by job title. Used by approval routing in phase 3.',
           'MEDIUM'),
          ('cn=remote-vpn,ou=groups,dc=lineward,dc=example',
           'Remote VPN',
           'Access to the network from outside an office. Granted by work location, which is why a location change has to recalculate.',
           'MEDIUM'),
          ('cn=dept-engineering,ou=groups,dc=lineward,dc=example',
           'Department: Engineering', 'Department group for Engineering.', 'LOW'),
          ('cn=dept-operations,ou=groups,dc=lineward,dc=example',
           'Department: Operations', 'Department group for Operations.', 'LOW'),
          ('cn=dept-treasury,ou=groups,dc=lineward,dc=example',
           'Department: Treasury', 'Department group for Treasury.', 'LOW'),
          ('cn=dept-risk,ou=groups,dc=lineward,dc=example',
           'Department: Risk', 'Department group for Risk.', 'LOW'),
          ('cn=dept-finance,ou=groups,dc=lineward,dc=example',
           'Department: Finance', 'Department group for Finance.', 'LOW'),
          ('cn=dept-procurement,ou=groups,dc=lineward,dc=example',
           'Department: Procurement', 'Department group for Procurement.', 'LOW'),
          ('cn=dept-information-security,ou=groups,dc=lineward,dc=example',
           'Department: Information Security', 'Department group for Information Security.', 'LOW'),
          ('cn=dept-human-resources,ou=groups,dc=lineward,dc=example',
           'Department: Human Resources', 'Department group for Human Resources.', 'LOW'),
          ('cn=dept-legal-and-compliance,ou=groups,dc=lineward,dc=example',
           'Department: Legal and Compliance', 'Department group for Legal and Compliance.', 'LOW'),
          ('cn=dept-internal-audit,ou=groups,dc=lineward,dc=example',
           'Department: Internal Audit', 'Department group for Internal Audit.', 'LOW')
       ) AS v (native_value, display_name, description, risk)
 WHERE a.org_id = '00000000-0000-7000-8000-000000000001'
   AND a.code = 'corporate-directory';


-- Intranet roles. The last two are the pair M6 needs a population for: creating
-- a supplier and approving a payment to it in the same hands is the textbook
-- segregation of duties conflict, and the synthetic population already puts
-- Procurement and Finance in different departments so that the conflict has to
-- be built out of two birthright grants rather than invented for a demo.
INSERT INTO entitlement (org_id, application_id, native_value, display_name, description, risk)
SELECT '00000000-0000-7000-8000-000000000001', a.id, v.native_value, v.display_name, v.description, v.risk
  FROM application a,
       (VALUES
          ('employee',
           'Employee',
           'Sign-in to the intranet. The second half of the base package.',
           'LOW'),
          ('treasury-read',
           'Treasury: read',
           'Read access to treasury positions. Granted by department, which makes a move out of Treasury a revocation.',
           'MEDIUM'),
          ('supplier-create',
           'Supplier: create',
           'Creates and amends suppliers. Half of the segregation of duties conflict with payment-approve.',
           'HIGH'),
          ('payment-approve',
           'Payment: approve',
           'Approves outgoing payments. The other half of the conflict with supplier-create.',
           'HIGH')
       ) AS v (native_value, display_name, description, risk)
 WHERE a.org_id = '00000000-0000-7000-8000-000000000001'
   AND a.code = 'intranet';
