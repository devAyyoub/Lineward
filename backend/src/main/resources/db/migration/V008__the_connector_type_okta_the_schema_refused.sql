-- Okta becomes a connector type the schema will accept.
--
-- The value was deliberately missing. ConnectorType's comment has said since
-- KAN-6 that OKTA is absent on purpose and needs a migration before the enum
-- can carry it, because an enum value the CHECK rejects does not fail when
-- somebody writes it in code: it fails at the end of the first aggregation,
-- when the run tries to record what it read, which is the worst place to find
-- out and the hardest to attribute.
--
-- A CHECK and not a native enum type, for the reason V001 gives for all five:
-- adding a value to a PostgreSQL enum cannot be done inside a transaction that
-- also uses it, and dropping one cannot be done at all. A CHECK is rewritten
-- by a migration like this one, in a transaction, and it is read by a human
-- without a catalogue query.
--
-- Nothing is seeded here. An application row for Okta belongs to whoever
-- installs Lineward and points it at their own organization, and V003's seed
-- exists to give birthright rules something to grant, not to invent target
-- systems. KAN-10 adds the synthetic Okta organization and will bring its own
-- row with it.

ALTER TABLE application DROP CONSTRAINT application_connector_type_check;

ALTER TABLE application
    ADD CONSTRAINT application_connector_type_check
    CHECK (connector_type IN ('KEYCLOAK', 'LDAP', 'OKTA', 'SCIM', 'FLAKY'));
