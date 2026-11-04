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
import static com.lineward.connector.LiteralTargetSystemConnector.serviceAccount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lineward.access.GovernedApplication;
import com.lineward.access.catalogue.JdbcEntitlementRegistry;
import com.lineward.aggregation.AggregationReport;
import com.lineward.connector.LiteralTargetSystemConnector;
import com.lineward.identity.ingest.HrIngestionRunner;
import com.lineward.identity.query.JdbcIdentityLookup;
import com.lineward.identity.query.JdbcIdentityResolution;
import com.lineward.identity.source.LiteralJsonSourceReader;
import com.lineward.shared.TenantTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Which person an account belongs to, decided by rule and occasionally by a
 * human.
 *
 * <p>This is what the whole of M2 was for. An account with an owner is access
 * somebody is accountable for; an account without one is the finding that gets
 * a platform bought. Everything here is about the second case being reported
 * honestly rather than guessed at: an identifier that matches two people, an
 * account that matched yesterday and does not today, an account that carries
 * nothing to match on.
 *
 * <p>Real identities, ingested by the real HR run from text this test wrote, so
 * that the employee number lives where the schema puts it and the emails are
 * the ones a source asserted. A test that inserted identity rows by hand would
 * have been free to put the employee number somewhere the correlation can find
 * it, which is the one thing worth proving.
 */
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CorrelationTests {

    private static final String OWNER_USER = "lineward_owner";
    private static final String OWNER_PASSWORD = "lineward_owner_local";
    private static final String APP_USER = "lineward_app";
    private static final String APP_PASSWORD = "lineward_app_local";

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private static final Instant NOW = Instant.parse("2026-11-04T09:00:00Z");

    /** Carries an employee number, and an email that belongs to nobody. */
    private static final String MARTA = """
            {"id":"295cb9e0-8844-4ff0-a93d-aadf392a0020","username":"m.ferreira",\
            "firstName":"Marta","lastName":"Ferreira","email":"not.in.hr@lineward.example",\
            "attributes":{"employeeNumber":["E-0417"]},"enabled":true,\
            "createdTimestamp":1792758433868}""";

    /** Carries no number at all, which is the ordinary state of a directory entry. */
    private static final String BY_EMAIL_ONLY = """
            {"id":"a1b2c3d4-0000-4000-8000-000000000001","username":"l.garcia",\
            "email":"L.Garcia@lineward.example","enabled":true,"createdTimestamp":1792758433868}""";

    /** Nothing to match on: no email, no number, nobody's. */
    private static final String BATCH_SERVICE = """
            {"id":"7c1d7afe-33af-4ea0-b687-5211bdb9fcc0","username":"svc-nightly-batch",\
            "enabled":false,"createdTimestamp":1792758269956}""";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6")
            .withInitScript("db/test-roles.sql");

    private static JdbcClient jdbc;
    private static TenantTransactions tenants;
    private static GovernedApplication intranet;

    private final LiteralTargetSystemConnector keycloak = new LiteralTargetSystemConnector();
    private final LiteralJsonSourceReader hr = new LiteralJsonSourceReader();

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
        intranet = tenants.inTenant(ORG, () -> jdbc.sql("""
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
    void emptyBothSides() {
        tenants.runInTenant(ORG, () -> {
            jdbc.sql("DELETE FROM account_entitlement").update();
            jdbc.sql("DELETE FROM account").update();
            jdbc.sql("DELETE FROM access_assignment").update();
            jdbc.sql("DELETE FROM identity_responsibility").update();
            jdbc.sql("DELETE FROM source_record").update();
            jdbc.sql("DELETE FROM identity").update();
        });
    }

    /**
     * The first rule, and the reason it is first: an employee number is a key
     * the organization issued, and an email is a string somebody typed. The
     * account here carries an address that belongs to nobody, so a platform
     * that preferred the email would have reported this account as an orphan.
     */
    @Test
    void correlates_an_account_to_the_person_whose_employee_number_it_carries() {
        UUID marta = ingest(employee("E-0417", "marta.ferreira@lineward.example"));
        keycloak.serving(account(MARTA));

        AggregationReport report = aggregate();

        assertThat(report.correlation().linked()).isEqualTo(1);
        StoredCorrelation stored = correlationOf("295cb9e0-8844-4ff0-a93d-aadf392a0020").orElseThrow();
        assertThat(stored.identityId()).isEqualTo(marta);
        assertThat(stored.method()).isEqualTo("RULE");
        assertThat(stored.ruleCode()).isEqualTo("employee-number");
        assertThat(stored.correlatedAt()).isEqualTo(NOW);
    }

    /**
     * The second rule, for the accounts a directory holds without a number,
     * which is most of them. Case folded on purpose: no mail system has treated
     * the local part as case sensitive in decades, and refusing to match
     * {@code L.Garcia@} with {@code l.garcia@} would leave a real account
     * looking like nobody's.
     */
    @Test
    void falls_back_to_the_email_when_the_account_carries_no_number() {
        UUID lucia = ingest(employee("E-0001", "l.garcia@lineward.example"));
        keycloak.serving(account(BY_EMAIL_ONLY));

        AggregationReport report = aggregate();

        assertThat(report.correlation().linked()).isEqualTo(1);
        StoredCorrelation stored = correlationOf("a1b2c3d4-0000-4000-8000-000000000001").orElseThrow();
        assertThat(stored.identityId()).isEqualTo(lucia);
        assertThat(stored.ruleCode()).isEqualTo("email");
    }

    /**
     * The order of the rules, proven where it can be seen: the number says one
     * person and the email says another. Which one wins is the whole of what
     * "ordered by priority" means, and the test above cannot show it, because
     * there the email matched nobody and either order would have ended in the
     * same place.
     */
    @Test
    void prefers_the_number_over_the_email_when_they_point_at_different_people() {
        UUID byNumber = ingest(employee("E-0417", "somebody.else@lineward.example"));
        UUID byEmail = ingest(employee("E-0001", "l.garcia@lineward.example"));
        keycloak.serving(account(MARTA.replace(
                "\"email\":\"not.in.hr@lineward.example\"", "\"email\":\"l.garcia@lineward.example\"")));

        aggregate();

        StoredCorrelation stored = correlationOf("295cb9e0-8844-4ff0-a93d-aadf392a0020").orElseThrow();
        assertThat(stored.identityId()).isEqualTo(byNumber).isNotEqualTo(byEmail);
        assertThat(stored.ruleCode()).isEqualTo("employee-number");
    }

    /**
     * An ambiguous match on the stronger rule stops the chain instead of
     * falling through to the weaker one. The account carries two employee
     * numbers belonging to two people, which the user profile of a real realm
     * allows because the attribute is multi valued, and it also carries an
     * email that matches exactly one person. Taking that email would be picking
     * one of two possible owners on a weaker signal, which is the silent wrong
     * answer the whole class exists to avoid.
     */
    @Test
    void does_not_fall_back_to_the_email_when_the_number_matched_more_than_one_person() {
        ingest(employee("E-0417", "first@lineward.example"));
        ingest(employee("E-0418", "second@lineward.example"));
        UUID byEmail = ingest(employee("E-0001", "l.garcia@lineward.example"));
        keycloak.serving(account(MARTA
                .replace("\"employeeNumber\":[\"E-0417\"]", "\"employeeNumber\":[\"E-0417\",\"E-0418\"]")
                .replace("\"email\":\"not.in.hr@lineward.example\"", "\"email\":\"l.garcia@lineward.example\"")));

        AggregationReport report = aggregate();

        assertThat(report.correlation().ambiguous()).isEqualTo(1);
        assertThat(report.correlation().linked()).isZero();
        StoredCorrelation stored = correlationOf("295cb9e0-8844-4ff0-a93d-aadf392a0020").orElseThrow();
        assertThat(stored.identityId()).as("not %s, and not either of the two numbers", byEmail).isNull();
    }

    /**
     * An account that belongs to nobody is not a failure of the rules, it is
     * the finding aggregation is run to produce. What matters is that it is
     * reported as unmatched and left alone, rather than attached to whoever
     * came closest.
     */
    @Test
    void leaves_an_account_with_nothing_to_match_on_to_nobody() {
        ingest(employee("E-0001", "l.garcia@lineward.example"));
        keycloak.serving(account(BATCH_SERVICE));

        AggregationReport report = aggregate();

        assertThat(report.correlation().unmatched()).isEqualTo(1);
        assertThat(report.correlation().linked()).isZero();
        assertThat(correlationOf("7c1d7afe-33af-4ea0-b687-5211bdb9fcc0").orElseThrow().identityId())
                .isNull();
    }

    /**
     * The edge case the synthetic population plants on purpose, because real
     * populations have it. Two people share an address and the account carries
     * it: handing the account to whichever row came back first would make one
     * of them accountable for the other's access, and nobody would ever know
     * which. Refusing is the answer, and saying so in the report is what makes
     * it fixable.
     */
    @Test
    void refuses_to_choose_when_two_people_share_the_address_the_account_carries() {
        ingest(
                employee("E-0001", "l.garcia@lineward.example"),
                employee("E-0002", "l.garcia@lineward.example"));
        keycloak.serving(account(BY_EMAIL_ONLY));

        AggregationReport report = aggregate();

        assertThat(report.correlation().ambiguous()).isEqualTo(1);
        assertThat(report.correlation().linked()).isZero();
        assertThat(correlationOf("a1b2c3d4-0000-4000-8000-000000000001").orElseThrow().identityId())
                .isNull();
    }

    /**
     * An account that matched and stops matching. The usual cause is the person
     * leaving and their record changing, and the usual mistake is leaving the
     * old owner in place: an account still showing a name is an account nobody
     * reviews, because it looks answered.
     */
    @Test
    void takes_the_account_back_when_the_person_it_matched_stops_matching() {
        ingest(employee("E-0001", "l.garcia@lineward.example"));
        keycloak.serving(account(BY_EMAIL_ONLY));
        aggregate();
        assertThat(correlationOf("a1b2c3d4-0000-4000-8000-000000000001").orElseThrow().identityId())
                .isNotNull();

        ingest(employee("E-0001", "lucia.garcia@lineward.example"));
        AggregationReport report = aggregate();

        assertThat(report.correlation().unlinked()).isEqualTo(1);
        StoredCorrelation stored = correlationOf("a1b2c3d4-0000-4000-8000-000000000001").orElseThrow();
        assertThat(stored.identityId()).isNull();
        assertThat(stored.method()).as("the constraint refuses half a correlation, so all four go").isNull();
        assertThat(stored.correlatedAt()).isNull();
    }

    /**
     * A decision a person took is not a rule's to revisit. The rules here would
     * match somebody else by email, and they do not get to: an automated pass
     * that silently reverts a human correction teaches people not to correct
     * anything.
     */
    @Test
    void does_not_revisit_a_correlation_a_person_took() {
        UUID lucia = ingest(employee("E-0001", "l.garcia@lineward.example"));
        UUID somebodyElse = ingest(employee("E-0009", "nobody@lineward.example"));
        keycloak.serving(account(BY_EMAIL_ONLY));
        aggregate();
        UUID accountId = correlationOf("a1b2c3d4-0000-4000-8000-000000000001").orElseThrow().id();

        tenants.runInTenant(ORG, () -> correlation(NOW).correlate(ORG, accountId, somebodyElse));
        AggregationReport report = aggregate();

        StoredCorrelation stored = correlationOf("a1b2c3d4-0000-4000-8000-000000000001").orElseThrow();
        assertThat(stored.identityId()).isEqualTo(somebodyElse).isNotEqualTo(lucia);
        assertThat(stored.method()).isEqualTo("MANUAL");
        assertThat(stored.ruleCode()).as("no rule decided this one").isNull();
        assertThat(report.correlation()).isEqualTo(AggregationReport.Correlation.NOTHING);
    }

    /**
     * The rule would have matched, and it does not get to.
     *
     * <p>This account carries Marta's employee number, which is the ordinary
     * way a service account ends up carrying one: somebody created it from
     * their own request and the number came along. The first rule would
     * therefore hand a machine to a person who never asked for it, and the
     * consequence is not cosmetic. The owner of an account is who answers for
     * its access in a review, so a guess here ends with Marta certifying
     * permissions she does not know exist, and the certification record saying
     * a human reviewed them.
     *
     * <p>Who answers for a machine is a question only a person can settle, and
     * leaving it open is the honest state until they do.
     */
    @Test
    void does_not_guess_an_owner_for_an_account_the_system_calls_a_machine() {
        UUID marta = ingest(employee("E-0417", "marta.ferreira@lineward.example"));
        keycloak.serving(serviceAccount(MARTA));

        AggregationReport report = aggregate();

        assertThat(report.correlation().machines()).isEqualTo(1);
        assertThat(report.correlation().linked()).isZero();
        assertThat(report.correlation().unmatched())
                .as("a machine is not an orphan: nobody is meant to go looking for its owner")
                .isZero();
        StoredCorrelation stored = correlationOf("295cb9e0-8844-4ff0-a93d-aadf392a0020").orElseThrow();
        assertThat(stored.identityId()).as("a rule claimed the machine for %s", marta).isNull();
        assertThat(stored.method()).isNull();
    }

    /**
     * The one case where a machine does have an owner, and it is not the rules
     * that decided it. A person can declare themselves accountable for a
     * service account, which is exactly what a platform governing non-human
     * identities is for, and the machine pass must not undo that any more than
     * it may invent it.
     */
    @Test
    void keeps_the_owner_a_person_decided_for_a_machine_account() {
        UUID ana = ingest(employee("E-0417", "marta.ferreira@lineward.example"));
        keycloak.serving(serviceAccount(MARTA));
        aggregate();
        UUID accountId = correlationOf("295cb9e0-8844-4ff0-a93d-aadf392a0020").orElseThrow().id();

        tenants.runInTenant(ORG, () -> correlation(NOW).correlate(ORG, accountId, ana));
        AggregationReport second = aggregate();

        StoredCorrelation stored = correlationOf("295cb9e0-8844-4ff0-a93d-aadf392a0020").orElseThrow();
        assertThat(stored.identityId()).isEqualTo(ana);
        assertThat(stored.method()).isEqualTo("MANUAL");
        assertThat(second.correlation().machines())
                .as("a machine somebody answers for is not one of the machines nobody answers for")
                .isZero();
    }

    /**
     * The migration case, and the reason the machine pass releases instead of
     * only abstaining. Before a connector could say that an account is a
     * machine, a rule correlated this one by its number and the platform
     * recorded a person as its owner. The read that learns the truth has to
     * undo the claim, because leaving it would mean the only accounts carrying
     * a wrong owner are the ones correlated before the fix, which is the kind
     * of residue nobody finds until a review signs it off.
     */
    @Test
    void takes_back_a_machine_account_a_rule_had_claimed() {
        ingest(employee("E-0417", "marta.ferreira@lineward.example"));
        keycloak.serving(account(MARTA));
        assertThat(aggregate().correlation().linked()).as("the rule claimed it first").isEqualTo(1);

        keycloak.serving(serviceAccount(MARTA));
        AggregationReport second = aggregate();

        assertThat(second.correlation().unlinked()).isEqualTo(1);
        assertThat(second.correlation().machines()).isEqualTo(1);
        StoredCorrelation stored = correlationOf("295cb9e0-8844-4ff0-a93d-aadf392a0020").orElseThrow();
        assertThat(stored.identityId()).isNull();
        assertThat(stored.ruleCode()).isNull();
    }

    /**
     * {@code account.identity_id} crosses a module boundary, so it has no
     * foreign key behind it by design (ADR-001). This method is therefore the
     * only place the existence can be checked at all, and an account correlated
     * to nothing is worse than an orphan because it reads as answered.
     */
    @Test
    void refuses_a_manual_correlation_to_somebody_who_does_not_exist() {
        ingest(employee("E-0001", "l.garcia@lineward.example"));
        keycloak.serving(account(BATCH_SERVICE));
        aggregate();
        UUID accountId = correlationOf("7c1d7afe-33af-4ea0-b687-5211bdb9fcc0").orElseThrow().id();
        UUID nobody = UUID.fromString("00000000-0000-7000-8000-00000000dead");

        assertThatThrownBy(() -> tenants.runInTenant(ORG, () -> correlation(NOW).correlate(ORG, accountId, nobody)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(nobody.toString());
        assertThat(correlationOf("7c1d7afe-33af-4ea0-b687-5211bdb9fcc0").orElseThrow().identityId())
                .isNull();
    }

    @Test
    void refuses_a_manual_correlation_of_an_account_that_does_not_exist() {
        UUID lucia = ingest(employee("E-0001", "l.garcia@lineward.example"));
        UUID noSuchAccount = UUID.fromString("00000000-0000-7000-8000-0000000000ff");

        assertThatThrownBy(() ->
                        tenants.runInTenant(ORG, () -> correlation(NOW).correlate(ORG, noSuchAccount, lucia)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(noSuchAccount.toString());
    }

    /**
     * The property a nightly run depends on. Reading the same system again
     * decides the same thing, so the report is empty and
     * {@code correlated_at} does not move: a column that was redated every
     * night would stop answering the only question it is for, which is when
     * this was decided.
     */
    @Test
    void decides_nothing_the_second_time_it_reads_a_system_it_has_already_decided() {
        ingest(employee("E-0417", "marta.ferreira@lineward.example"));
        keycloak.serving(account(MARTA));
        aggregate();

        AggregationReport again = aggregate(NOW.plusSeconds(86400));

        assertThat(again.correlation()).isEqualTo(AggregationReport.Correlation.NOTHING);
        assertThat(correlationOf("295cb9e0-8844-4ff0-a93d-aadf392a0020").orElseThrow().correlatedAt())
                .isEqualTo(NOW);
    }

    private AggregationReport aggregate() {
        return aggregate(NOW);
    }

    private AggregationReport aggregate(Instant now) {
        return new ApplicationAggregation(
                        new JdbcEntitlementRegistry(jdbc),
                        new AccountWriter(jdbc, tools.jackson.databind.json.JsonMapper.builder().build()),
                        correlation(now),
                        tenants,
                        Clock.fixed(now, ZoneOffset.UTC),
                        ORG,
                        100)
                .read(intranet, keycloak);
    }

    private AccountCorrelation correlation(Instant now) {
        return new AccountCorrelation(
                jdbc,
                new JdbcIdentityResolution(jdbc),
                new JdbcIdentityLookup(jdbc),
                Clock.fixed(now, ZoneOffset.UTC),
                "employeeNumber");
    }

    /** Ingests the records and returns the identity of the first one. */
    private UUID ingest(String... records) {
        hr.serving(records);
        HrIngestionRunner.create(
                        hr,
                        jdbc,
                        tenants,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        event -> {},
                        ORG,
                        "hr",
                        100)
                .ingestEverything();
        String firstNumber = records[0].replaceAll(".*\"employeeNumber\":\"([^\"]+)\".*", "$1");
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                SELECT identity_id FROM source_record
                 WHERE org_id = :orgId AND external_id = :externalId
                """)
                .param("orgId", ORG)
                .param("externalId", firstNumber)
                .query(UUID.class)
                .single());
    }

    private record StoredCorrelation(
            UUID id, UUID identityId, String method, String ruleCode, Instant correlatedAt) {}

    private Optional<StoredCorrelation> correlationOf(String nativeId) {
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                SELECT id, identity_id, correlation_method, correlation_rule_code, correlated_at
                  FROM account
                 WHERE org_id = :orgId AND native_id = :nativeId
                """)
                .param("orgId", ORG)
                .param("nativeId", nativeId)
                .query((rs, row) -> new StoredCorrelation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("identity_id", UUID.class),
                        rs.getString("correlation_method"),
                        rs.getString("correlation_rule_code"),
                        Optional.ofNullable(rs.getObject("correlated_at", java.time.OffsetDateTime.class))
                                .map(java.time.OffsetDateTime::toInstant)
                                .orElse(null)))
                .optional());
    }

    /** One HR record, with the email under the test's control because sharing one is the point. */
    private static String employee(String number, String email) {
        return """
                {"employeeNumber":"%s","firstName":"Lucia","lastName":"Garcia","email":"%s",\
                "department":"Treasury","jobTitle":"Treasury Analyst","location":"Madrid",\
                "costCenter":"CC-001","employmentType":"EMPLOYEE","hireDate":"2020-01-01",\
                "terminationDate":null,"managerEmployeeNumber":null,\
                "lastModifiedAt":"2026-10-01T00:00:00Z"}"""
                .formatted(number, email);
    }
}
