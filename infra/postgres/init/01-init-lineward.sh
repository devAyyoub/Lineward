#!/bin/bash
#
# Creates the Lineward and Keycloak databases and the two database roles that
# ADR-011 requires for tenant isolation.
#
# Why two roles and not one, which is what most projects do:
#
#   lineward_owner  owns the tables. Flyway uses it to run migrations. Being the
#                   owner, PostgreSQL lets it bypass row level security, which is
#                   exactly what a migration needs.
#   lineward_app    is what the application uses at runtime. It owns nothing, so
#                   it is subject to row level security and cannot escape the
#                   tenant policies.
#
# If the application connected as the owner, every row level security policy
# would be silently ignored: the control would look installed and do nothing.
# That is the trap documented in ADR-011.
#
# This script runs once, as the superuser, and only when the data directory is
# empty. To force it again: docker compose down -v
set -euo pipefail

: "${LINEWARD_DB:=lineward}"
: "${LINEWARD_OWNER_USER:=lineward_owner}"
: "${LINEWARD_OWNER_PASSWORD:=lineward_owner_local}"
: "${LINEWARD_APP_USER:=lineward_app}"
: "${LINEWARD_APP_PASSWORD:=lineward_app_local}"
: "${KEYCLOAK_DB:=keycloak}"
: "${KEYCLOAK_DB_USER:=keycloak}"
: "${KEYCLOAK_DB_PASSWORD:=keycloak_local}"

echo "init: creating roles and databases"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<SQL
    CREATE ROLE ${LINEWARD_OWNER_USER} LOGIN PASSWORD '${LINEWARD_OWNER_PASSWORD}';
    CREATE ROLE ${LINEWARD_APP_USER}   LOGIN PASSWORD '${LINEWARD_APP_PASSWORD}';

    CREATE DATABASE ${LINEWARD_DB} OWNER ${LINEWARD_OWNER_USER};

    CREATE ROLE ${KEYCLOAK_DB_USER} LOGIN PASSWORD '${KEYCLOAK_DB_PASSWORD}';
    CREATE DATABASE ${KEYCLOAK_DB} OWNER ${KEYCLOAK_DB_USER};
SQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "${LINEWARD_DB}" <<SQL
    -- The migration role owns the schema, so Flyway can create objects in it.
    ALTER SCHEMA public OWNER TO ${LINEWARD_OWNER_USER};

    -- Nobody creates objects in public by default. Without this, any role could
    -- create a table here, and a table created by the application role would be
    -- owned by it and therefore exempt from row level security.
    REVOKE CREATE ON SCHEMA public FROM PUBLIC;

    -- The application role can read and write, but not define.
    GRANT USAGE ON SCHEMA public TO ${LINEWARD_APP_USER};

    -- Applies to tables the owner creates IN THE FUTURE. Without this, every
    -- migration would have to remember to grant privileges to the application
    -- role, and the one that forgets breaks production rather than the build.
    ALTER DEFAULT PRIVILEGES FOR ROLE ${LINEWARD_OWNER_USER} IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${LINEWARD_APP_USER};
    ALTER DEFAULT PRIVILEGES FOR ROLE ${LINEWARD_OWNER_USER} IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO ${LINEWARD_APP_USER};
SQL

echo "init: done. databases=[${LINEWARD_DB}, ${KEYCLOAK_DB}] roles=[${LINEWARD_OWNER_USER}, ${LINEWARD_APP_USER}, ${KEYCLOAK_DB_USER}]"
