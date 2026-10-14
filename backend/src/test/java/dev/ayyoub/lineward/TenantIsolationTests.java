/*
 * Copyright 2026 Ayyoub Amjahed Abed
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ayyoub.lineward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies that the tenant isolation of ADR-011 actually isolates, on the real
 * schema produced by the real migration.
 *
 * <p>ADR-011 warns that in the default setup, where the application connects
 * with the role that created the tables, row level security is enabled, raises
 * no error and filters nothing. A control that looks installed and is not. The
 * only way to tell the two apart is to try to break it, so these tests assert
 * both directions: that a tenant sees its own rows, and that it cannot reach or
 * write anybody else's.
 *
 * <p>Two organizations in the fixtures is not decoration either. With a single
 * one, a query that forgets the filter returns exactly what a correct query
 * returns, and the test passes while testing nothing.
 */
@Testcontainers
class TenantIsolationTests {

    private static final String OWNER_USER = "lineward_owner";
    private static final String OWNER_PASSWORD = "lineward_owner_local";
    private static final String APP_USER = "lineward_app";
    private static final String APP_PASSWORD = "lineward_app_local";

    /** Pinned in ADR-006 and the same image infra/docker-compose.yml runs. */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6")
            .withInitScript("db/test-roles.sql");

    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .placeholders(Map.of("app_role", APP_USER))
                .load()
                .migrate();

        seedOrganization(ORG_A, "acme", "Acme Corp", "Ada Lovelace");
        seedOrganization(ORG_B, "globex", "Globex", "Grace Hopper");
    }

    /**
     * Note that the migration role has to set the tenant too. ADR-011 claimed
     * Flyway needs no change because the owner bypasses row level security,
     * which is only true until {@code FORCE ROW LEVEL SECURITY} is on. Verified
     * on 2026-10-14 and corrected in that ADR.
     */
    private static void seedOrganization(UUID orgId, String code, String name, String personName)
            throws SQLException {
        try (Connection connection = owner()) {
            connection.setAutoCommit(false);
            setTenant(connection, orgId);

            execute(connection, """
                    INSERT INTO organization (id, code, name) VALUES ('%s', '%s', '%s')
                    """.formatted(orgId, code, name));

            execute(connection, """
                    INSERT INTO source_system (id, org_id, code, name, kind)
                    VALUES ('%s', '%s', 'hr', 'HR simulator', 'EXTERNAL')
                    """.formatted(UUID.randomUUID(), orgId));

            execute(connection, """
                    INSERT INTO identity (org_id, type, status, source_system_id, display_name, department)
                    SELECT '%s', 'HUMAN', 'ACTIVE', s.id, '%s', 'Treasury'
                      FROM source_system s WHERE s.org_id = '%s' AND s.code = 'hr'
                    """.formatted(orgId, personName, orgId));

            connection.commit();
        }
    }

    @Test
    void aTenantSeesOnlyItsOwnRows() throws SQLException {
        try (Connection connection = app()) {
            connection.setAutoCommit(false);
            setTenant(connection, ORG_A);

            assertThat(queryStrings(connection, "SELECT display_name FROM identity"))
                    .containsExactly("Ada Lovelace");
            assertThat(queryStrings(connection, "SELECT code FROM organization"))
                    .containsExactly("acme");
        }
    }

    @Test
    void theOtherTenantSeesItsOwnAndNotTheFirst() throws SQLException {
        try (Connection connection = app()) {
            connection.setAutoCommit(false);
            setTenant(connection, ORG_B);

            assertThat(queryStrings(connection, "SELECT display_name FROM identity"))
                    .containsExactly("Grace Hopper");
        }
    }

    /**
     * ADR-011 chose the noisy failure on purpose: {@code current_setting} is
     * called without its second argument, so forgetting the tenant is an error
     * that names the problem instead of an empty list that reads as "no data"
     * and costs half an hour.
     */
    @Test
    void forgettingTheTenantFailsLoudlyInsteadOfReturningNothing() throws SQLException {
        try (Connection connection = app()) {
            assertThatThrownBy(() -> queryStrings(connection, "SELECT display_name FROM identity"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("app.current_org");
        }
    }

    /**
     * The policy omits {@code WITH CHECK}, so PostgreSQL reuses the
     * {@code USING} expression for new rows. Writing into another tenant is
     * therefore rejected, not just hidden.
     */
    @Test
    void writingIntoAnotherTenantIsRejected() throws SQLException {
        try (Connection connection = app()) {
            connection.setAutoCommit(false);
            setTenant(connection, ORG_A);

            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO identity (org_id, type, status, source_system_id, display_name)
                    VALUES ('%s', 'HUMAN', 'ACTIVE', '%s', 'Smuggled')
                    """.formatted(ORG_B, UUID.randomUUID())))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");

            connection.rollback();
        }
    }

    /**
     * Every business table has to carry both defences. {@code ENABLE} alone
     * does nothing against the owner, and an application role that stops being
     * a non-owner by accident would leave the system open. This test is what
     * stops a future migration from adding a table without them: a new table
     * with no policy fails here instead of in production with the second
     * client.
     */
    @Test
    void everyBusinessTableEnforcesRowLevelSecurity() throws SQLException {
        List<String> unprotected = new ArrayList<>();
        try (Connection connection = owner();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT c.relname,
                               c.relrowsecurity,
                               c.relforcerowsecurity,
                               (SELECT count(*) FROM pg_policy p WHERE p.polrelid = c.oid) AS policies
                          FROM pg_class c
                          JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'public'
                           AND c.relkind = 'r'
                           AND c.relname <> 'flyway_schema_history'
                         ORDER BY c.relname
                        """)) {
            while (rows.next()) {
                String table = rows.getString(1);
                if (!rows.getBoolean(2) || !rows.getBoolean(3) || rows.getInt(4) == 0) {
                    unprotected.add("%s (enabled=%s forced=%s policies=%d)"
                            .formatted(table, rows.getBoolean(2), rows.getBoolean(3), rows.getInt(4)));
                }
            }
        }

        assertThat(unprotected).as("tables without ENABLE, FORCE and a policy").isEmpty();
    }

    /** A log the application can edit is not evidence (M8). */
    @Test
    void theApplicationCannotRewriteTheAuditLog() throws SQLException {
        try (Connection connection = app()) {
            connection.setAutoCommit(false);
            setTenant(connection, ORG_A);

            assertThatThrownBy(() -> execute(connection, "UPDATE audit_event SET type = 'tampered'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");

            connection.rollback();
            setTenant(connection, ORG_A);

            assertThatThrownBy(() -> execute(connection, "DELETE FROM audit_event"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");

            connection.rollback();
        }
    }

    /**
     * A table created by the application role would be owned by it and
     * therefore exempt from the policies, which is the quietest way to lose the
     * isolation.
     */
    @Test
    void theApplicationCannotCreateTables() throws SQLException {
        try (Connection connection = app()) {
            assertThatThrownBy(() -> execute(connection, "CREATE TABLE escape_hatch (id uuid)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
    }

    private static Connection app() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
    }

    /**
     * Transaction scoped, which is what the third argument of {@code set_config}
     * means. Session scope would stick to the pooled connection and the next
     * request, from another client, would inherit this tenant: the isolation
     * mechanism would become the leak.
     */
    private static void setTenant(Connection connection, UUID orgId) throws SQLException {
        execute(connection, "SELECT set_config('app.current_org', '%s', true)".formatted(orgId));
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> queryStrings(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
