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
package com.lineward.access.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.lineward.access.EntitlementRegistry;
import com.lineward.access.EntitlementRegistry.Declaration;
import com.lineward.access.GovernedApplication;
import com.lineward.shared.TenantTransactions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The two things M4 answers for an aggregation: which systems to read, and what
 * to do with a permission one of them turns out to have.
 *
 * <p>Against the real schema, because every property worth asserting here is
 * the schema's. That a second registration of the same value does not duplicate
 * the row is the unique key; that registering a permission keeps the grants
 * pointing at it is the foreign key; that an application nobody switched on
 * stays out of a run is one column. A test with a stubbed database would assert
 * the code's opinion of all three.
 */
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DiscoveredCatalogueTests {

    private static final String OWNER_USER = "lineward_owner";
    private static final String OWNER_PASSWORD = "lineward_owner_local";
    private static final String APP_USER = "lineward_app";
    private static final String APP_PASSWORD = "lineward_app_local";

    /** Inserted by V002, and the organization the seed migrations write into. */
    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    /**
     * Values no migration seeds, so that the cleanup below can delete exactly
     * what a test created and nothing a test depends on.
     */
    private static final String DISCOVERED = "/intranet/newly-discovered";

    private static final String UNDOCUMENTED = "/intranet/undocumented";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6")
            .withInitScript("db/test-roles.sql");

    private static JdbcClient jdbc;
    private static TenantTransactions tenants;
    private static JdbcApplicationLookup applications;
    private static JdbcEntitlementRegistry registry;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .placeholders(Map.of("app_role", APP_USER))
                .load()
                .migrate();

        DataSource appDataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
        jdbc = JdbcClient.create(appDataSource);
        tenants = new TenantTransactions(new JdbcTransactionManager(appDataSource), jdbc);
        applications = new JdbcApplicationLookup(jdbc);
        registry = new JdbcEntitlementRegistry(jdbc);
    }

    /**
     * Only what the tests add, and only by name. A blanket delete of the
     * entitlements would take the seeded catalogue with it, and the seeded
     * catalogue is half of what is under test.
     */
    @BeforeEach
    void undoWhatTheTestsWrite() {
        tenants.runInTenant(ORG, () -> {
            jdbc.sql("DELETE FROM entitlement WHERE org_id = :orgId AND native_value IN (:values)")
                    .param("orgId", ORG)
                    .param("values", List.of(DISCOVERED, UNDOCUMENTED))
                    .update();
            jdbc.sql("UPDATE application SET enabled = true WHERE org_id = :orgId")
                    .param("orgId", ORG)
                    .update();
        });
    }

    @Test
    void names_every_application_the_installation_has_switched_on() {
        List<GovernedApplication> enabled = tenants.inTenant(ORG, () -> applications.enabledApplications(ORG));

        assertThat(enabled)
                .extracting(GovernedApplication::code, GovernedApplication::connectorType)
                .containsExactlyInAnyOrder(
                        tuple("intranet", "KEYCLOAK"), tuple("corporate-directory", "LDAP"));
        assertThat(enabled).allSatisfy(application -> {
            assertThat(application.id()).as("an aggregation writes rows against this id").isNotNull();
            assertThat(application.name()).isNotBlank();
        });
    }

    /**
     * The flag is how an operator stops Lineward reading a system, which is the
     * first thing anybody does when a target system is in trouble. A lookup
     * that returned it anyway would make that switch decorative.
     */
    @Test
    void leaves_out_an_application_somebody_switched_off() {
        tenants.runInTenant(ORG, () -> jdbc.sql(
                        "UPDATE application SET enabled = false WHERE org_id = :orgId AND code = 'intranet'")
                .param("orgId", ORG)
                .update());

        List<GovernedApplication> enabled = tenants.inTenant(ORG, () -> applications.enabledApplications(ORG));

        assertThat(enabled).extracting(GovernedApplication::code).containsExactly("corporate-directory");
    }

    @Test
    void registers_a_permission_the_target_system_has_and_the_catalogue_did_not() {
        UUID intranet = idOfApplication("intranet");

        Map<String, UUID> ids = tenants.inTenant(ORG, () -> registry.register(
                ORG, intranet, List.of(new Declaration(DISCOVERED, "Newly discovered", "A group nobody registered"))));

        assertThat(ids).containsOnlyKeys(DISCOVERED);
        assertThat(storedRow(DISCOVERED))
                .contains(new StoredEntitlement(
                        ids.get(DISCOVERED), intranet, "Newly discovered", "A group nobody registered"));
    }

    /**
     * The property that protects every grant already written. {@code
     * access_assignment.entitlement_id} is a foreign key, so a registry that
     * reinserted a permission it had seen before would either fail on the unique
     * key or, worse, leave the grants pointing at a row nobody reads any more.
     */
    @Test
    void keeps_the_id_of_a_permission_it_has_seen_before() {
        UUID intranet = idOfApplication("intranet");
        UUID seeded = storedRow("/employee").orElseThrow().id();
        int before = countEntitlementsOf(intranet);

        Map<String, UUID> ids = tenants.inTenant(
                ORG, () -> registry.register(ORG, intranet, List.of(Declaration.of("/employee"))));

        assertThat(ids).containsEntry("/employee", seeded);
        assertThat(countEntitlementsOf(intranet))
                .as("registering a permission that is already there must not add a row")
                .isEqualTo(before);
    }

    /**
     * What a target system calls a group is the key, and what the catalogue says
     * it is for is governance. Keycloak serves no description at all for most
     * groups, so a registry that overwrote this field would quietly delete the
     * only documentation the platform has, on the first successful run.
     */
    @Test
    void does_not_replace_what_the_catalogue_says_with_what_the_system_says() {
        UUID intranet = idOfApplication("intranet");

        tenants.runInTenant(ORG, () -> registry.register(
                ORG, intranet, List.of(new Declaration("/employee", "employee", "whatever Keycloak returns"))));

        assertThat(storedRow("/employee").orElseThrow())
                .extracting(StoredEntitlement::displayName, StoredEntitlement::description)
                .containsExactly("Employee", "Sign-in to the intranet. The second half of the base package.");
    }

    /**
     * The other direction, which is the one that makes the catalogue better over
     * time. A permission first seen on somebody's account arrives with nothing
     * but its name; when the read of the catalogue later says what it is called,
     * that is new information and not a contradiction.
     */
    @Test
    void fills_in_documentation_the_catalogue_was_missing() {
        UUID intranet = idOfApplication("intranet");
        tenants.runInTenant(ORG, () -> registry.register(ORG, intranet, List.of(Declaration.of(UNDOCUMENTED))));
        assertThat(storedRow(UNDOCUMENTED).orElseThrow().displayName()).isNull();

        tenants.runInTenant(ORG, () -> registry.register(
                ORG, intranet, List.of(new Declaration(UNDOCUMENTED, "Undocumented", "Said on the second read"))));

        assertThat(storedRow(UNDOCUMENTED).orElseThrow())
                .extracting(StoredEntitlement::displayName, StoredEntitlement::description)
                .containsExactly("Undocumented", "Said on the second read");
    }

    /**
     * A read that found nothing is a legitimate answer, and an empty list is
     * also what the last page of a paged read looks like. {@code IN ()} is not
     * valid SQL, so a registry that did not check would fail on the one input it
     * is guaranteed to receive.
     */
    @Test
    void writes_nothing_when_a_read_found_no_permissions() {
        UUID intranet = idOfApplication("intranet");
        int before = countEntitlementsOf(intranet);

        Map<String, UUID> ids = tenants.inTenant(ORG, () -> registry.register(ORG, intranet, List.of()));

        assertThat(ids).isEmpty();
        assertThat(countEntitlementsOf(intranet)).isEqualTo(before);
    }

    /** The row as the table holds it, so that an assertion reads as a row and not as four queries. */
    private record StoredEntitlement(UUID id, UUID applicationId, String displayName, String description) {}

    private Optional<StoredEntitlement> storedRow(String nativeValue) {
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                        SELECT id, application_id, display_name, description
                          FROM entitlement
                         WHERE org_id = :orgId AND native_value = :nativeValue
                        """)
                .param("orgId", ORG)
                .param("nativeValue", nativeValue)
                .query((rs, row) -> new StoredEntitlement(
                        rs.getObject("id", UUID.class),
                        rs.getObject("application_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("description")))
                .optional());
    }

    private int countEntitlementsOf(UUID applicationId) {
        return tenants.inTenant(ORG, () -> jdbc.sql(
                        "SELECT count(*) FROM entitlement WHERE org_id = :orgId AND application_id = :applicationId")
                .param("orgId", ORG)
                .param("applicationId", applicationId)
                .query(Integer.class)
                .single());
    }

    private UUID idOfApplication(String code) {
        return tenants.inTenant(ORG, () -> jdbc.sql(
                        "SELECT id FROM application WHERE org_id = :orgId AND code = :code")
                .param("orgId", ORG)
                .param("code", code)
                .query(UUID.class)
                .single());
    }
}
