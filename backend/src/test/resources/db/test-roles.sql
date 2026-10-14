-- Mirrors the role setup of infra/postgres/init/01-init-lineward.sh, which is
-- the real one and carries the full reasoning. Kept separate and minimal
-- because that script also creates databases, which Testcontainers already did.
--
-- The two roles are not a detail of the test: without a non-owner application
-- role, row level security is silently bypassed and the test would pass while
-- testing nothing (ADR-011).

CREATE ROLE lineward_owner LOGIN PASSWORD 'lineward_owner_local';
CREATE ROLE lineward_app   LOGIN PASSWORD 'lineward_app_local';

ALTER SCHEMA public OWNER TO lineward_owner;

-- Nobody creates objects in public by default. A table created by the
-- application role would be owned by it and therefore exempt from the policies.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

GRANT USAGE ON SCHEMA public TO lineward_app;

ALTER DEFAULT PRIVILEGES FOR ROLE lineward_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO lineward_app;
ALTER DEFAULT PRIVILEGES FOR ROLE lineward_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO lineward_app;
