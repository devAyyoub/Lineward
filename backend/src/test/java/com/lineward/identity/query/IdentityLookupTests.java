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
package com.lineward.identity.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.lineward.identity.GovernedAttributes;
import com.lineward.identity.IdentityStatus;
import com.lineward.shared.TenantTransactions;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The read side of M1 against the real schema and the real application role.
 *
 * <p>Short, because there is one query here and most of what could go wrong is
 * not in it. What is worth a database is the part that cannot be faked: that the
 * projection really maps the columns it claims to, that an absent attribute
 * survives as a null instead of an empty string, and that asking about the wrong
 * organization returns nothing even when the row is one this connection is
 * allowed to see.
 */
@Testcontainers
class IdentityLookupTests {

    private static final String OWNER_USER = "lineward_owner";
    private static final String OWNER_PASSWORD = "lineward_owner_local";
    private static final String APP_USER = "lineward_app";
    private static final String APP_PASSWORD = "lineward_app_local";

    /** Inserted by V002, so the test uses the organization the installation has. */
    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6")
            .withInitScript("db/test-roles.sql");

    private static JdbcClient jdbc;
    private static TenantTransactions tenants;
    private static UUID hrSourceSystemId;

    private final JdbcIdentityLookup lookup = new JdbcIdentityLookup(jdbc);

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
        hrSourceSystemId = tenants.inTenant(ORG, () -> jdbc
                .sql("SELECT id FROM source_system WHERE org_id = :orgId AND code = 'hr'")
                .param("orgId", ORG)
                .query(UUID.class)
                .single());
    }

    @BeforeEach
    void emptyTheIdentityTable() {
        tenants.runInTenant(ORG, () -> jdbc.sql("DELETE FROM identity").update());
    }

    @Test
    void readsTheAttributesAccessIsDecidedOn() {
        UUID id = tenants.inTenant(ORG, () -> insertIdentity(
                IdentityStatus.ACTIVE, "Treasury", "Treasury Analyst", "Madrid", "TRE-001", "EMPLOYEE"));

        GovernedAttributes attributes =
                tenants.inTenant(ORG, () -> lookup.attributesOf(ORG, id)).orElseThrow();

        assertThat(attributes.id()).isEqualTo(id);
        assertThat(attributes.status()).isEqualTo(IdentityStatus.ACTIVE);
        assertThat(attributes.department()).isEqualTo("Treasury");
        assertThat(attributes.jobTitle()).isEqualTo("Treasury Analyst");
        assertThat(attributes.location()).isEqualTo("Madrid");
        assertThat(attributes.costCenter()).isEqualTo("TRE-001");
        assertThat(attributes.employmentType()).isEqualTo("EMPLOYEE");
    }

    /**
     * M1 requires ingesting incomplete HR data rather than rejecting it, so the
     * projection has to carry the gap through. A null that arrived as an empty
     * string would quietly become a value a rule could match on.
     */
    @Test
    void carriesAnAbsentAttributeThroughAsNull() {
        UUID id = tenants.inTenant(ORG, () -> insertIdentity(
                IdentityStatus.ACTIVE, null, null, null, null, null));

        GovernedAttributes attributes =
                tenants.inTenant(ORG, () -> lookup.attributesOf(ORG, id)).orElseThrow();

        assertThat(attributes.department()).isNull();
        assertThat(attributes.jobTitle()).isNull();
        assertThat(attributes.location()).isNull();
        assertThat(attributes.costCenter()).isNull();
        assertThat(attributes.employmentType()).isNull();
    }

    @Test
    void findsNothingForAnIdentityThatDoesNotExist() {
        Optional<GovernedAttributes> found =
                tenants.inTenant(ORG, () -> lookup.attributesOf(ORG, UUID.randomUUID()));

        assertThat(found).isEmpty();
    }

    /**
     * The negative control for the {@code org_id} predicate, and the reason it is
     * in the query although the policy already filters on the tenant. The row
     * below is one this connection is allowed to see: the policy says yes and
     * only the predicate says no. Without it, a caller that passed the wrong
     * organization would get the right row back and never find out.
     */
    @Test
    void findsNothingWhenAskedAboutADifferentOrganization() {
        UUID id = tenants.inTenant(ORG, () -> insertIdentity(
                IdentityStatus.ACTIVE, "Treasury", "Treasury Analyst", "Madrid", "TRE-001", "EMPLOYEE"));

        Optional<GovernedAttributes> found =
                tenants.inTenant(ORG, () -> lookup.attributesOf(UUID.randomUUID(), id));

        assertThat(found).isEmpty();
    }

    /** Written with SQL rather than through ingestion, so that this test fails for one reason. */
    private UUID insertIdentity(
            IdentityStatus status,
            String department,
            String jobTitle,
            String location,
            String costCenter,
            String employmentType) {
        return jdbc.sql("""
                INSERT INTO identity (
                    org_id, type, status, source_system_id, display_name,
                    department, job_title, location, cost_center, employment_type)
                VALUES (
                    :orgId, 'HUMAN', :status, :sourceSystemId, 'Lucia Garcia',
                    :department, :jobTitle, :location, :costCenter, :employmentType)
                RETURNING id
                """)
                .param("orgId", ORG)
                .param("status", status.name())
                .param("sourceSystemId", hrSourceSystemId)
                .param("department", department)
                .param("jobTitle", jobTitle)
                .param("location", location)
                .param("costCenter", costCenter)
                .param("employmentType", employmentType)
                .query(UUID.class)
                .single();
    }
}
