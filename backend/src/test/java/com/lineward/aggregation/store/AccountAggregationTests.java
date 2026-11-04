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

import static com.lineward.connector.LiteralTargetSystemConnector.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lineward.access.GovernedApplication;
import com.lineward.access.catalogue.JdbcEntitlementRegistry;
import com.lineward.aggregation.AggregationReport;
import com.lineward.connector.ConnectorException;
import com.lineward.connector.LiteralTargetSystemConnector;
import com.lineward.shared.TenantTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a connector read, written down: the first time Lineward stores anything
 * about the access that really exists.
 *
 * <p>Everything before this task was one half of the comparison M2 is for. The
 * HR source says who exists and the birthright rules say what they ought to
 * hold, and both are Lineward talking to itself. These tests are the other
 * half arriving, and most of them are about the ways a write can quietly lie
 * about it: an unreachable system that looks like a system with no accounts, a
 * read that broke halfway and looks like a population that shrank, a
 * membership that disappeared and was left in the table anyway.
 *
 * <p>The connector is the literal double of the SPI contract tests, serving
 * Keycloak 26.7.5 documents as text. What is under test here is the writing,
 * not the reading, and the two real connectors have their own integration tests
 * against running systems. Using the double means these tests can serve the one
 * thing a running system will not: a read that breaks on page two.
 */
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AccountAggregationTests {

    private static final String OWNER_USER = "lineward_owner";
    private static final String OWNER_PASSWORD = "lineward_owner_local";
    private static final String APP_USER = "lineward_app";
    private static final String APP_PASSWORD = "lineward_app_local";

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private static final Instant NOW = Instant.parse("2026-11-04T09:00:00Z");

    /** A real Keycloak user representation, and the attributes are why the map holds lists. */
    private static final String MARTA = """
            {"id":"295cb9e0-8844-4ff0-a93d-aadf392a0020","username":"m.ferreira",\
            "firstName":"Marta","lastName":"Ferreira","email":"m.ferreira@lineward.example",\
            "emailVerified":false,"attributes":{"employeeNumber":["E-0417"],\
            "secondaryEmail":["marta.ferreira@lineward.example","m.ferreira.external@lineward.example"]},\
            "enabled":true,"createdTimestamp":1792758433868,"totp":false,"notBefore":0}""";

    /** The account nobody owns, disabled, with no email and no groups. */
    private static final String BATCH_SERVICE = """
            {"id":"7c1d7afe-33af-4ea0-b687-5211bdb9fcc0","username":"svc-nightly-batch",\
            "emailVerified":false,"enabled":false,"createdTimestamp":1792758269956,"totp":false}""";

    /** Groups as the shipped realm publishes them, which is what V004 made the catalogue agree with. */
    private static final String EMPLOYEE_GROUP = """
            {"id":"b1e1c0a2-0001-4000-8000-000000000001","name":"employee","path":"/employee","subGroups":[]}""";

    private static final String TREASURY_GROUP = """
            {"id":"b1e1c0a2-0002-4000-8000-000000000002","name":"treasury-read",\
            "path":"/intranet/treasury-read","subGroups":[]}""";

    /** A group the realm has and no migration ever registered. */
    private static final String UNREGISTERED_GROUP = """
            {"id":"b1e1c0a2-0003-4000-8000-000000000003","name":"late-arrival",\
            "path":"/intranet/late-arrival","subGroups":[]}""";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6")
            .withInitScript("db/test-roles.sql");

    private static JdbcClient jdbc;
    private static TenantTransactions tenants;
    private static GovernedApplication intranet;

    private final JsonMapper json = JsonMapper.builder().build();
    private final LiteralTargetSystemConnector keycloak = new LiteralTargetSystemConnector();

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
        intranet = new TenantTransactions(new JdbcTransactionManager(appDataSource), jdbc)
                .inTenant(ORG, () -> jdbc.sql("""
                        SELECT id, code, name, connector_type
                          FROM application
                         WHERE org_id = :orgId AND code = 'intranet'
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
    void emptyWhatARunWrites() {
        tenants.runInTenant(ORG, () -> {
            jdbc.sql("DELETE FROM account_entitlement").update();
            jdbc.sql("DELETE FROM account").update();
            jdbc.sql("DELETE FROM entitlement WHERE native_value = '/intranet/late-arrival'").update();
        });
    }

    @Test
    void writes_an_account_the_target_system_has_and_lineward_had_never_seen() {
        keycloak.serving(account(MARTA, EMPLOYEE_GROUP)).withCatalogue(EMPLOYEE_GROUP, TREASURY_GROUP);

        AggregationReport report = aggregation().read(intranet, keycloak);

        assertThat(report.accountsSeen()).isEqualTo(1);
        assertThat(report.accountsCreated()).isEqualTo(1);
        assertThat(report.entitlementsSeen()).isEqualTo(2);
        StoredAccount marta = storedAccount("295cb9e0-8844-4ff0-a93d-aadf392a0020").orElseThrow();
        assertThat(marta.username()).isEqualTo("m.ferreira");
        assertThat(marta.email()).isEqualTo("m.ferreira@lineward.example");
        assertThat(marta.displayName()).isEqualTo("Marta Ferreira");
        assertThat(marta.status()).isEqualTo("ACTIVE");
        assertThat(marta.createdInTargetAt()).isEqualTo(Instant.parse("2026-10-23T12:27:13.868Z"));
        assertThat(marta.identityId()).as("nothing correlates yet, and an account arrives owned by nobody").isNull();
        assertThat(nativeValuesHeldBy(marta.id())).containsExactly("/employee");
    }

    /**
     * The attribute the correlation rules match on, and the reason the column is
     * jsonb and the values are lists.
     */
    @Test
    void keeps_every_value_of_a_multi_valued_attribute() {
        keycloak.serving(account(MARTA));

        aggregation().read(intranet, keycloak);

        JsonNode attributes = json.readTree(
                storedAccount("295cb9e0-8844-4ff0-a93d-aadf392a0020").orElseThrow().attributes());
        assertThat(attributes.path("employeeNumber").get(0).asString()).isEqualTo("E-0417");
        assertThat(attributes.path("secondaryEmail").size()).isEqualTo(2);
    }

    /**
     * The property that makes a run safe to repeat, and the one a nightly
     * aggregation depends on: reading the same system twice is not two
     * populations.
     */
    @Test
    void keeps_the_same_row_when_it_reads_the_same_account_again() {
        keycloak.serving(account(MARTA, EMPLOYEE_GROUP)).withCatalogue(EMPLOYEE_GROUP);
        aggregation().read(intranet, keycloak);
        StoredAccount first = storedAccount("295cb9e0-8844-4ff0-a93d-aadf392a0020").orElseThrow();

        AggregationReport again = aggregation(NOW.plusSeconds(3600)).read(intranet, keycloak);

        StoredAccount second = storedAccount("295cb9e0-8844-4ff0-a93d-aadf392a0020").orElseThrow();
        assertThat(again.accountsCreated()).isZero();
        assertThat(again.accountsUpdated()).isEqualTo(1);
        assertThat(again.grantsAdded()).as("the membership was already there").isZero();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.firstSeenAt()).isEqualTo(first.firstSeenAt());
        assertThat(second.lastSeenAt()).isAfterOrEqualTo(first.lastSeenAt());
        assertThat(nativeValuesHeldBy(second.id())).containsExactly("/employee");
    }

    /**
     * Real state, so a membership that is gone has to be gone. Leaving the row
     * would make Lineward report access that was taken away, which is the
     * reconciliation failure that gets a platform switched off.
     */
    @Test
    void takes_away_a_permission_the_account_no_longer_holds() {
        keycloak.serving(account(MARTA, EMPLOYEE_GROUP, TREASURY_GROUP))
                .withCatalogue(EMPLOYEE_GROUP, TREASURY_GROUP);
        aggregation().read(intranet, keycloak);

        keycloak.serving(account(MARTA, EMPLOYEE_GROUP));
        AggregationReport report = aggregation().read(intranet, keycloak);

        assertThat(report.grantsRemoved()).isEqualTo(1);
        assertThat(nativeValuesHeldBy(storedAccount("295cb9e0-8844-4ff0-a93d-aadf392a0020").orElseThrow().id()))
                .containsExactly("/employee");
    }

    /**
     * A membership is itself proof that the permission exists, so an account
     * holding a group the catalogue has never heard of registers it. Without
     * this the membership could not be stored at all: the row needs an
     * entitlement id, and dropping it would hide real access.
     */
    @Test
    void registers_a_permission_that_arrived_on_an_account_and_not_in_the_catalogue() {
        keycloak.serving(account(MARTA, UNREGISTERED_GROUP));

        aggregation().read(intranet, keycloak);

        assertThat(nativeValuesHeldBy(storedAccount("295cb9e0-8844-4ff0-a93d-aadf392a0020").orElseThrow().id()))
                .containsExactly("/intranet/late-arrival");
    }

    /**
     * Two halves of the same row, treated differently on purpose. An email that
     * has gone has really gone, so it is overwritten; when the account was
     * created in the target system is a fact about the past, and a system that
     * stops reporting it has not unmade it. Reading the same account through a
     * connector that says less would otherwise erase what the first one learned.
     */
    @Test
    void keeps_what_the_system_has_stopped_saying_and_overwrites_what_it_still_says() {
        keycloak.serving(account(MARTA));
        aggregation().read(intranet, keycloak);

        keycloak.serving(account(MARTA
                .replace(",\"createdTimestamp\":1792758433868", "")
                .replace(",\"email\":\"m.ferreira@lineward.example\"", "")));
        aggregation(NOW.plusSeconds(60)).read(intranet, keycloak);

        StoredAccount marta = storedAccount("295cb9e0-8844-4ff0-a93d-aadf392a0020").orElseThrow();
        assertThat(marta.createdInTargetAt())
                .as("the system stopped saying when it was created, which is not the same as it not having been")
                .isEqualTo(Instant.parse("2026-10-23T12:27:13.868Z"));
        assertThat(marta.email())
                .as("an email that is gone from the target system is gone")
                .isNull();
    }

    /**
     * The one a write must never do. An account that did not come back is an
     * account this read did not see, and those are the same sentence only when
     * the read was complete. Deleting here would turn a filtered search, a
     * narrowed service account or a target system under load into a wave of
     * terminations.
     */
    @Test
    void never_deletes_an_account_a_later_read_did_not_return() {
        keycloak.serving(account(MARTA, EMPLOYEE_GROUP), account(BATCH_SERVICE))
                .withCatalogue(EMPLOYEE_GROUP);
        aggregation().read(intranet, keycloak);
        Instant seenFirstTime =
                storedAccount("7c1d7afe-33af-4ea0-b687-5211bdb9fcc0").orElseThrow().lastSeenAt();

        keycloak.serving(account(MARTA, EMPLOYEE_GROUP));
        aggregation(NOW.plusSeconds(86400)).read(intranet, keycloak);

        StoredAccount missing = storedAccount("7c1d7afe-33af-4ea0-b687-5211bdb9fcc0").orElseThrow();
        assertThat(missing.lastSeenAt())
                .as("the row stays and its heartbeat does not move, which is what makes the absence visible")
                .isEqualTo(seenFirstTime);
    }

    /**
     * Unreachable is an answer and not an empty population. A run that wrote
     * nothing and said it succeeded would be read as "this system has no
     * accounts", and the next phase acts on that.
     */
    @Test
    void says_it_could_not_reach_the_system_instead_of_reporting_no_accounts() {
        keycloak.serving(account(MARTA)).unreachable("connection refused on port 8081");

        AggregationReport report = aggregation().read(intranet, keycloak);

        assertThat(report.reachable()).isFalse();
        assertThat(report.detail()).contains("connection refused");
        assertThat(report.accountsSeen()).isZero();
        assertThat(countAccounts()).isZero();
    }

    /**
     * A read that broke part way through must not produce a report at all. The
     * pages already written stay written, which is safe because a repeat finds
     * them, and nothing is deleted on the strength of a partial read.
     */
    @Test
    void refuses_to_turn_a_broken_read_into_a_report() {
        keycloak.serving(account(MARTA, EMPLOYEE_GROUP), account(BATCH_SERVICE), account(userNumbered(3)))
                .withCatalogue(EMPLOYEE_GROUP)
                .breakingAfter(1);

        assertThatThrownBy(() -> aggregation(NOW, 2).read(intranet, keycloak))
                .isInstanceOf(ConnectorException.class);

        assertThat(countAccounts())
                .as("the first page is committed, because one transaction per page is what makes a repeat cheap")
                .isEqualTo(2);
    }

    private ApplicationAggregation aggregation() {
        return aggregation(NOW);
    }

    private ApplicationAggregation aggregation(Instant now) {
        return aggregation(now, 100);
    }

    private ApplicationAggregation aggregation(Instant now, int pageSize) {
        return new ApplicationAggregation(
                new JdbcEntitlementRegistry(jdbc),
                new AccountWriter(jdbc, JsonMapper.builder().build()),
                tenants,
                Clock.fixed(now, ZoneOffset.UTC),
                ORG,
                pageSize);
    }

    /** The row as the table holds it, so an assertion reads as a row. */
    private record StoredAccount(
            UUID id,
            String username,
            String email,
            String displayName,
            String status,
            Instant createdInTargetAt,
            Instant firstSeenAt,
            Instant lastSeenAt,
            UUID identityId,
            String attributes) {}

    private Optional<StoredAccount> storedAccount(String nativeId) {
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                        SELECT id, username, email, display_name, status, created_in_target_at,
                               first_seen_at, last_seen_at, identity_id, cast(attributes as text) AS attributes
                          FROM account
                         WHERE org_id = :orgId AND native_id = :nativeId
                        """)
                .param("orgId", ORG)
                .param("nativeId", nativeId)
                .query((rs, row) -> new StoredAccount(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        instant(rs.getObject("created_in_target_at", java.time.OffsetDateTime.class)),
                        instant(rs.getObject("first_seen_at", java.time.OffsetDateTime.class)),
                        instant(rs.getObject("last_seen_at", java.time.OffsetDateTime.class)),
                        rs.getObject("identity_id", UUID.class),
                        rs.getString("attributes")))
                .optional());
    }

    private static Instant instant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private List<String> nativeValuesHeldBy(UUID accountId) {
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                        SELECT e.native_value
                          FROM account_entitlement ae
                          JOIN entitlement e ON e.org_id = ae.org_id AND e.id = ae.entitlement_id
                         WHERE ae.org_id = :orgId AND ae.account_id = :accountId
                         ORDER BY e.native_value
                        """)
                .param("orgId", ORG)
                .param("accountId", accountId)
                .query(String.class)
                .list());
    }

    private int countAccounts() {
        return tenants.inTenant(ORG, () -> jdbc.sql("SELECT count(*) FROM account WHERE org_id = :orgId")
                .param("orgId", ORG)
                .query(Integer.class)
                .single());
    }

    private static String userNumbered(int number) {
        return """
                {"id":"00000000-0000-4000-8000-00000000000%d","username":"user%d",\
                "enabled":true,"createdTimestamp":1792758269956}"""
                .formatted(number, number);
    }
}
