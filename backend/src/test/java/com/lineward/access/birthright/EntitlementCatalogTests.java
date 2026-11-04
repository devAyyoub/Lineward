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
package com.lineward.access.birthright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lineward.access.EntitlementRegistry.Declaration;
import com.lineward.access.birthright.BirthrightRule.Grant;
import com.lineward.access.catalogue.JdbcEntitlementRegistry;
import com.lineward.shared.TenantTransactions;
import java.util.List;
import java.util.Map;
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
 * What happens to the catalogue a running platform keeps in memory when a
 * connector discovers a permission nobody had registered.
 *
 * <p>This test exists because the cache had an expiry date written into its own
 * comment, and KAN-11 is the date. Until the read connectors landed, the
 * catalogue was seventeen rows a migration wrote and nothing changed them while
 * the application was up, so reading them once was free. An aggregation changes
 * them while the application is up, and an identity whose rule grants a
 * permission discovered an hour ago would have been told it does not exist.
 *
 * <p>Against the real schema and the real registry, because what is under test
 * is precisely that one component sees what another one wrote. Two doubles
 * agreeing with each other would prove nothing about that.
 */
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EntitlementCatalogTests {

    private static final String OWNER_USER = "lineward_owner";
    private static final String OWNER_PASSWORD = "lineward_owner_local";
    private static final String APP_USER = "lineward_app";
    private static final String APP_PASSWORD = "lineward_app_local";

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    /** A permission no migration seeds, so that the first lookup of it is genuinely a miss. */
    private static final String DISCOVERED_LATER = "/intranet/discovered-later";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6")
            .withInitScript("db/test-roles.sql");

    private static JdbcClient jdbc;
    private static TenantTransactions tenants;
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
        registry = new JdbcEntitlementRegistry(jdbc);
    }

    @BeforeEach
    void undoWhatTheTestsWrite() {
        tenants.runInTenant(ORG, () -> jdbc.sql(
                        "DELETE FROM entitlement WHERE org_id = :orgId AND native_value = :nativeValue")
                .param("orgId", ORG)
                .param("nativeValue", DISCOVERED_LATER)
                .update());
    }

    /**
     * The sequence an aggregation produces, in order: the catalogue is read for
     * somebody, a connector finds a new permission, and the next person's rules
     * are calculated. A cache that only ever loads once answers the third step
     * with the state of the first.
     */
    @Test
    void resolves_a_permission_registered_after_it_had_already_read_the_catalogue() {
        EntitlementCatalog catalogue = new EntitlementCatalog(jdbc);
        Grant discovered = new Grant("intranet", DISCOVERED_LATER);
        assertThat(tenants.inTenant(ORG, () -> catalogue.contains(ORG, discovered)))
                .as("nothing has registered it yet, and asking is what fills the cache")
                .isFalse();

        UUID registered = tenants.inTenant(ORG, () -> registry
                .register(ORG, idOfApplication("intranet"), List.of(Declaration.of(DISCOVERED_LATER)))
                .get(DISCOVERED_LATER));

        assertThat(tenants.inTenant(ORG, () -> catalogue.idOf(ORG, discovered, "a-rule-that-grants-it")))
                .isEqualTo(registered);
    }

    /**
     * And the failure is still loud, which is the half a re-read could have
     * quietly destroyed. A rule naming a permission that does not exist is a
     * person without their access, and the only thing worse than failing there
     * is not failing.
     */
    @Test
    void still_refuses_loudly_when_a_rule_names_a_permission_nobody_has() {
        EntitlementCatalog catalogue = new EntitlementCatalog(jdbc);

        assertThatThrownBy(() -> tenants.inTenant(
                        ORG, () -> catalogue.idOf(ORG, new Grant("intranet", "/invented"), "a-misspelled-rule")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a-misspelled-rule")
                .hasMessageContaining("/invented");
    }

    private static UUID idOfApplication(String code) {
        return jdbc.sql("SELECT id FROM application WHERE org_id = :orgId AND code = :code")
                .param("orgId", ORG)
                .param("code", code)
                .query(UUID.class)
                .single();
    }
}
