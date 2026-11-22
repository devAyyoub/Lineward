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
package com.lineward.aggregation.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.lineward.access.GovernedApplication;
import com.lineward.access.catalogue.JdbcEntitlementRegistry;
import com.lineward.aggregation.AggregationReport;
import com.lineward.connector.okta.OktaConnector;
import com.lineward.connector.okta.OktaCredentials;
import com.lineward.connector.okta.OktaDemoSlice;
import com.lineward.connector.okta.OktaDemoSlice.SeedAccount;
import com.lineward.identity.ingest.HrIngestionRunner;
import com.lineward.identity.query.JdbcIdentityLookup;
import com.lineward.identity.query.JdbcIdentityResolution;
import com.lineward.identity.source.LiteralJsonSourceReader;
import com.lineward.shared.TenantTransactions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole chain against the seeded Okta organization: read it, write down
 * what it said, and say whose each account is.
 *
 * <p>This is the sibling of {@code DirectoryAggregationTests} and differs in
 * one way that decides its shape: there is no container to start. The other two
 * target systems are images this repository brings up, so their aggregation
 * tests are hermetic and run in continuous integration. An Okta organization is
 * somebody's account, so this one runs against the real thing where the
 * credential is configured and is skipped everywhere else. That makes it worth
 * little as a regression net and everything as the proof that the chain
 * actually closed, which is why its counts belong in the delivery.
 *
 * <p>It reads with the <b>read only</b> credential, the same one the platform
 * uses, and never with the seeder's: what is being tested is what Lineward can
 * see, and a test that read with wider rights than the product would prove the
 * wrong thing.
 *
 * <p>PostgreSQL is still a container, because the database half has to be
 * clean and nobody's.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "OKTA_ORG_URL", matches = ".+")
class OktaAggregationLiveTests {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private static final Instant NOW = Instant.parse("2026-11-22T09:00:00Z");

    private static final String OWNER_USER = "lineward_owner";
    private static final String OWNER_PASSWORD = "lineward_owner_local";
    private static final String APP_USER = "lineward_app";
    private static final String APP_PASSWORD = "lineward_app_local";

    /**
     * What the seeded organization holds, counted from it on 2026-11-22.
     *
     * <p>The slice is seven accounts and the organization's own administrator
     * makes eight. The catalogue is eight, and the arithmetic is worth writing
     * down because one of them does not come from where the others do: five
     * groups, two of which are Okta's built in {@code Everyone} and {@code
     * Okta Administrators}; the two applications registered in the
     * organization, which in Okta are entitlements of their own and not a
     * consequence of a group; and <b>one more that the catalogue endpoint
     * never returned</b>. {@code GET /api/v1/apps} omits the applications the
     * credential cannot see, so {@code Okta Workflows} is invisible there and
     * appears only as an assignment on the administrator's account. It is in
     * the catalogue because aggregation registers a permission it meets on an
     * account and had not been told about, the net KAN-11 put in. This is the
     * second time that path has caught a real omission against this
     * organization, and it is the reason the count is eight and not seven.
     *
     * <p>Hardcoded rather than counted at runtime on purpose: an account added
     * by hand should break this test, because it means the organization is no
     * longer the fixture the demo describes.
     */
    private static final int ACCOUNTS_IN_THE_ORGANIZATION = OktaDemoSlice.accounts().size() + 1;

    private static final int ENTITLEMENTS_IN_THE_ORGANIZATION = 8;

    /** What walking the catalogue returns: the five groups and the two visible applications. */
    private static final int ENTITLEMENTS_THE_CATALOGUE_SERVES = 7;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6")
            .withInitScript("db/test-roles.sql");

    private static JdbcClient jdbc;
    private static TenantTransactions tenants;
    private static GovernedApplication okta;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .placeholders(Map.of("app_role", APP_USER))
                .load()
                .migrate();

        DataSource appDataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
        jdbc = JdbcClient.create(appDataSource);
        tenants = new TenantTransactions(new JdbcTransactionManager(appDataSource), jdbc);
        okta = tenants.inTenant(ORG, () -> jdbc.sql("""
                SELECT id, code, name, connector_type
                  FROM application
                 WHERE org_id = :orgId AND code = 'okta-directory'
                """)
                .param("orgId", ORG)
                .query((rs, row) -> new GovernedApplication(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("connector_type")))
                .single());
    }

    @BeforeEach
    void emptyBothSidesAndIngestTheSlicesPeople() {
        tenants.runInTenant(ORG, () -> {
            jdbc.sql("DELETE FROM account_entitlement").update();
            jdbc.sql("DELETE FROM account").update();
            jdbc.sql("DELETE FROM access_assignment").update();
            jdbc.sql("DELETE FROM identity_responsibility").update();
            jdbc.sql("DELETE FROM source_record").update();
            jdbc.sql("DELETE FROM identity").update();
        });

        // Only the people the slice put in Okta, because this test is about
        // whether their accounts find them and not about the other 4,994.
        //
        // Their addresses here are deliberately not the ones Okta holds. The
        // employee number is the attribute KAN-10 exists to seed, and while
        // both sides carry the same address the email rule matches first and
        // the number is never exercised: the test then passes whatever the
        // number says, which it did until this comment was written.
        LiteralJsonSourceReader hr = new LiteralJsonSourceReader();
        hr.serving(OktaDemoSlice.accounts().stream()
                .filter(SeedAccount::shouldMatch)
                .map(OktaAggregationLiveTests::asHrRecord)
                .toArray(String[]::new));
        HrIngestionRunner.create(hr, jdbc, tenants, Clock.fixed(NOW, ZoneOffset.UTC), event -> {}, ORG, "hr", 100)
                .ingestEverything();
    }

    @Test
    @DisplayName("every person the slice seeded gets the Okta account that is theirs")
    void matches_the_slice_to_its_people() {
        AggregationReport report = aggregate();

        assertThat(report.reachable()).isTrue();
        assertThat(report.accountsSeen()).isEqualTo(ACCOUNTS_IN_THE_ORGANIZATION);
        assertThat(report.correlation().linked()).isEqualTo(expectedMatches());
        assertThat(report.correlation().ambiguous()).isZero();
        assertThat(ruleCodesUsed())
                .as("the employee number did the work, because no address in this extract is in Okta."
                        + " Without this the test passes on the email rule and proves nothing about the"
                        + " attribute KAN-10 seeded, which is how it passed a deliberate break on"
                        + " 2026-11-22 before the extract's addresses were moved apart")
                .containsExactly("employee-number");
    }

    private List<String> ruleCodesUsed() {
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                SELECT DISTINCT correlation_rule_code
                  FROM account
                 WHERE org_id = :orgId AND application_id = :app AND correlation_rule_code IS NOT NULL
                """)
                .param("orgId", ORG)
                .param("app", okta.id())
                .query(String.class)
                .list());
    }

    @Test
    @DisplayName("the catalogue is discovered, because no migration could have named it")
    void registers_the_entitlements_okta_generated() {
        AggregationReport report = aggregate();

        assertThat(report.entitlementsSeen())
                .as("groups and applications both, which is the shape Okta's access actually has")
                .isEqualTo(ENTITLEMENTS_THE_CATALOGUE_SERVES);
        assertThat(entitlementsOfOkta())
                .as("V009 seeds the system and none of its permissions, so all of these arrived by being read")
                .isEqualTo(ENTITLEMENTS_IN_THE_ORGANIZATION);
        assertThat(entitlementsOfOkta() - report.entitlementsSeen())
                .as("the one the catalogue endpoint never served and an account gave away."
                        + " If this ever reaches zero, either the credential was widened or the net"
                        + " KAN-11 put in stopped working, and the two are worth telling apart")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the account with no employee number is left unclaimed, which is the finding")
    void leaves_the_service_account_to_nobody() {
        AggregationReport report = aggregate();

        // Two: the service account the slice plants, and the organization's own
        // administrator, who is a real person and not in this HR extract.
        assertThat(report.correlation().unmatched())
                .isEqualTo(ACCOUNTS_IN_THE_ORGANIZATION - expectedMatches());
        assertThat(unclaimedLogins()).contains("svc-reporting@lineward.example");
    }

    @Test
    @DisplayName("Okta is the only system that says when an account was last used")
    void brings_the_activity_the_other_two_cannot() {
        aggregate();

        assertThat(accountsWithActivity())
                .as("the administrator has signed in and the seeded accounts never have,"
                        + " which is dormancy as this organization can express it")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("reading the same organization twice links nothing new and revokes nothing")
    void a_second_read_changes_nothing() {
        AggregationReport first = aggregate();
        AggregationReport second = aggregate();

        assertThat(second.accountsCreated()).isZero();
        assertThat(second.accountsSeen()).isEqualTo(first.accountsSeen());

        // Linked counts the links the run made, not the links that exist, so
        // the right answer on a second read is zero: everybody was claimed the
        // first time and nobody needed claiming again. The property worth
        // asserting is that the database still holds them, because the failure
        // this guards against is a rerun that unlinks and relinks, which would
        // rewrite the correlation timestamp of every account on every read.
        assertThat(first.correlation().linked()).isEqualTo(expectedMatches());
        assertThat(second.correlation().linked()).isZero();
        assertThat(correlatedAccounts()).isEqualTo(expectedMatches());
    }

    private int correlatedAccounts() {
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                SELECT count(*)
                  FROM account
                 WHERE org_id = :orgId AND application_id = :app AND identity_id IS NOT NULL
                """)
                .param("orgId", ORG)
                .param("app", okta.id())
                .query(Integer.class)
                .single());
    }

    private static int expectedMatches() {
        return (int) OktaDemoSlice.accounts().stream().filter(SeedAccount::shouldMatch).count();
    }

    private AggregationReport aggregate() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        OktaConnector connector = new OktaConnector(readOnlyCredential());
        return new ApplicationAggregation(
                        new JdbcEntitlementRegistry(jdbc),
                        new AccountWriter(jdbc, JsonMapper.builder().build()),
                        new AccountCorrelation(
                                jdbc,
                                new JdbcIdentityResolution(jdbc),
                                new JdbcIdentityLookup(jdbc),
                                clock,
                                "employeeNumber"),
                        tenants,
                        clock,
                        ORG,
                        100)
                .read(okta, connector);
    }

    /** The platform's own credential, which can read and cannot write. */
    private static OktaCredentials readOnlyCredential() {
        try {
            return OktaCredentials.fromPem(
                    System.getenv("OKTA_ORG_URL"),
                    System.getenv("OKTA_CLIENT_ID"),
                    System.getenv("OKTA_KEY_ID"),
                    Files.readString(Path.of(System.getenv("OKTA_PRIVATE_KEY_FILE"))));
        } catch (IOException unreadable) {
            throw new IllegalStateException("OKTA_PRIVATE_KEY_FILE does not name a readable key", unreadable);
        }
    }

    private int entitlementsOfOkta() {
        return tenants.inTenant(ORG, () -> jdbc.sql(
                        "SELECT count(*) FROM entitlement WHERE org_id = :orgId AND application_id = :app")
                .param("orgId", ORG)
                .param("app", okta.id())
                .query(Integer.class)
                .single());
    }

    private List<String> unclaimedLogins() {
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                SELECT username
                  FROM account
                 WHERE org_id = :orgId AND application_id = :app AND identity_id IS NULL
                """)
                .param("orgId", ORG)
                .param("app", okta.id())
                .query(String.class)
                .list());
    }

    private int accountsWithActivity() {
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                SELECT count(*)
                  FROM account
                 WHERE org_id = :orgId AND application_id = :app AND last_activity_at IS NOT NULL
                """)
                .param("orgId", ORG)
                .param("app", okta.id())
                .query(Integer.class)
                .single());
    }

    private static String asHrRecord(SeedAccount account) {
        return """
                {"employeeNumber":"%s","firstName":"%s","lastName":"%s",\
                "email":"%s","department":"%s","jobTitle":"Analyst",\
                "location":"Madrid","costCenter":"CC-001","employmentType":"EMPLOYEE",\
                "hireDate":"2020-01-01","terminationDate":null,"managerEmployeeNumber":null,\
                "lastModifiedAt":"2026-10-01T00:00:00Z"}"""
                .formatted(
                        account.employeeNumber(),
                        account.firstName(),
                        account.lastName(),
                        account.employeeNumber().toLowerCase() + "@hr.lineward.example",
                        account.department());
    }
}
