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
package com.lineward.aggregation.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lineward.access.catalogue.JdbcAccessExpectation;
import com.lineward.access.catalogue.JdbcApplicationLookup;
import com.lineward.aggregation.FindingType;
import com.lineward.aggregation.Reconciliation;
import com.lineward.aggregation.ReconciliationReport;
import com.lineward.identity.ingest.HrIngestionRunner;
import com.lineward.identity.source.LiteralJsonSourceReader;
import com.lineward.shared.TenantTransactions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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

/**
 * A condition becoming a row, staying one row while it lasts, and closing when
 * it stops.
 *
 * <p>{@code ReconciliationRulesTests} covers what makes a row a finding.
 * Everything here is about what happens to the finding afterwards, which is
 * where this operation can do real damage: a pass that opened a duplicate every
 * night would turn the list into a log, a pass that closed findings because it
 * had nothing to compare would quietly empty it, and a pass that overruled a
 * person's decision would teach people not to make any.
 *
 * <p><b>Accounts and expectations are written as rows here, not produced by a
 * run.</b> How an account gets written is {@code AccountAggregationTests} and
 * how an expectation gets written is {@code ExpectedAccessTests}; both already
 * run against this schema. What this needs is a controlled starting state, and
 * generating one through fourteen birthright rules and a connector double would
 * make these tests assert those instead of the comparison. The identities are
 * ingested by the real HR run even so, because an identity id has to exist for
 * the soft references to point at something.
 */
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ReconciliationTests {

    private static final String OWNER_USER = "lineward_owner";
    private static final String OWNER_PASSWORD = "lineward_owner_local";
    private static final String APP_USER = "lineward_app";
    private static final String APP_PASSWORD = "lineward_app_local";

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private static final Instant NOW = Instant.parse("2026-11-27T09:00:00Z");

    private static final Duration NINETY_DAYS = Duration.ofDays(90);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6")
            .withInitScript("db/test-roles.sql");

    private static JdbcClient jdbc;
    private static TenantTransactions tenants;
    private static UUID intranet;
    private static UUID directory;
    private static UUID employeeGroup;
    private static UUID treasuryGroup;

    private final LiteralJsonSourceReader hr = new LiteralJsonSourceReader();

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

        tenants.runInTenant(ORG, () -> {
            intranet = applicationId("intranet");
            directory = applicationId("corporate-directory");
            employeeGroup = entitlementId("/employee");
            treasuryGroup = entitlementId("/intranet/treasury-read");
        });
    }

    @BeforeEach
    void emptyEverythingAPassReads() {
        tenants.runInTenant(ORG, () -> {
            jdbc.sql("DELETE FROM reconciliation_finding").update();
            jdbc.sql("DELETE FROM account_entitlement").update();
            jdbc.sql("DELETE FROM account").update();
            jdbc.sql("DELETE FROM access_assignment").update();
            jdbc.sql("DELETE FROM identity_responsibility").update();
            jdbc.sql("DELETE FROM source_record").update();
            jdbc.sql("DELETE FROM identity").update();
        });
    }

    // --- what a pass writes ------------------------------------------------

    @Test
    void writes_one_finding_for_the_account_nobody_could_place() {
        UUID lost = account(intranet, "lperez", null, "ACTIVE", "UNKNOWN", null);

        ReconciliationReport report = reconcile().getFirst();

        assertThat(report.of(FindingType.ORPHAN_ACCOUNT).opened()).isEqualTo(1);
        assertThat(findingsAbout(lost)).containsExactly(FindingType.ORPHAN_ACCOUNT.name());
        assertThat(stateOf(lost, FindingType.ORPHAN_ACCOUNT)).isEqualTo("OPEN");
    }

    /**
     * The report says which systems it could not answer for, and this is the
     * number that would otherwise be read as good news: a directory reports
     * zero dormant accounts because it has no idea, not because everybody signs
     * in.
     */
    @Test
    void says_that_a_system_which_measures_no_activity_was_not_assessed_for_dormancy() {
        account(intranet, "lperez", identity("E-0001"), "ACTIVE", "UNKNOWN", null);

        ReconciliationReport report = reconcile().getFirst();

        assertThat(report.dormancyAssessed()).isFalse();
        assertThat(report.of(FindingType.DORMANT_ACCOUNT).opened()).isZero();
    }

    /**
     * The activity column is set here rather than read from a connector,
     * because the only connector that populates it is Okta's and an Okta
     * organization is somebody's account rather than a container. What is under
     * test is what a pass does with the value.
     */
    @Test
    void writes_a_finding_for_the_account_the_system_has_not_seen_used() {
        UUID stale = account(intranet, "old", identity("E-0001"), "ACTIVE", "UNKNOWN", NOW.minus(Duration.ofDays(200)));
        account(intranet, "fresh", identity("E-0002"), "ACTIVE", "UNKNOWN", NOW.minus(Duration.ofDays(1)));

        ReconciliationReport report = reconcile().getFirst();

        assertThat(report.dormancyAssessed()).isTrue();
        assertThat(report.of(FindingType.DORMANT_ACCOUNT).opened()).isEqualTo(1);
        assertThat(findingsAbout(stale)).containsExactly(FindingType.DORMANT_ACCOUNT.name());
    }

    @Test
    void writes_a_finding_for_an_access_the_system_grants_and_nothing_says_is_due() {
        UUID lucia = identity("E-0001");
        UUID account = account(intranet, "lperez", lucia, "ACTIVE", "UNKNOWN", null);
        grant(account, treasuryGroup);

        ReconciliationReport report = reconcile().getFirst();

        assertThat(report.of(FindingType.DRIFT_UNEXPECTED_GRANT).opened()).isEqualTo(1);
        assertThat(driftState(lucia, treasuryGroup, FindingType.DRIFT_UNEXPECTED_GRANT)).contains("OPEN");
    }

    /**
     * The asymmetry against the real schema. The same identity, the same two
     * entitlements and the same empty target system: the pending expectation
     * produces nothing and the active one produces a finding.
     */
    @Test
    void writes_a_finding_only_for_the_access_it_records_as_provisioned() {
        UUID lucia = identity("E-0001");
        account(intranet, "lperez", lucia, "ACTIVE", "UNKNOWN", null);
        expect(lucia, employeeGroup, "PENDING");
        expect(lucia, treasuryGroup, "ACTIVE");

        ReconciliationReport report = reconcile().getFirst();

        assertThat(report.of(FindingType.DRIFT_MISSING_GRANT).opened()).isEqualTo(1);
        assertThat(driftState(lucia, treasuryGroup, FindingType.DRIFT_MISSING_GRANT)).contains("OPEN");
        assertThat(driftState(lucia, employeeGroup, FindingType.DRIFT_MISSING_GRANT)).isEmpty();
    }

    /**
     * An orphan's memberships are not drift, and the reason is in the schema:
     * a drift finding names an identity and an orphan has none. It is not
     * dropped silently either, because the account itself is already a finding
     * and the access it holds is what makes that finding matter.
     */
    @Test
    void does_not_report_the_access_of_an_account_nobody_could_place_as_drift() {
        UUID lost = account(intranet, "lperez", null, "ACTIVE", "UNKNOWN", null);
        grant(lost, treasuryGroup);

        ReconciliationReport report = reconcile().getFirst();

        assertThat(report.of(FindingType.DRIFT_UNEXPECTED_GRANT).opened()).isZero();
        assertThat(findingsAbout(lost)).containsExactly(FindingType.ORPHAN_ACCOUNT.name());
    }

    // --- what a second pass does -------------------------------------------

    /**
     * The property the whole ledger exists for. A finding is one row for as
     * long as the problem lasts, so a second pass over an unchanged
     * installation opens nothing, closes nothing and leaves one row.
     */
    @Test
    void a_second_pass_over_an_unchanged_installation_opens_and_closes_nothing() {
        UUID lost = account(intranet, "lperez", null, "ACTIVE", "UNKNOWN", null);
        reconcile();

        ReconciliationReport again = reconcile(NOW.plus(Duration.ofDays(1))).getFirst();

        assertThat(again.openedTotal()).isZero();
        assertThat(again.resolvedTotal()).isZero();
        assertThat(again.of(FindingType.ORPHAN_ACCOUNT).confirmed()).isEqualTo(1);
        assertThat(rowsAbout(lost)).isEqualTo(1);
    }

    /**
     * How long this has been wrong is the question that orders the list by how
     * badly somebody has been ignoring it, so a confirming pass moves the
     * heartbeat and nothing else.
     */
    @Test
    void keeps_the_first_detection_and_moves_the_heartbeat() {
        UUID lost = account(intranet, "lperez", null, "ACTIVE", "UNKNOWN", null);
        reconcile();

        Instant later = NOW.plus(Duration.ofDays(5));
        reconcile(later);

        assertThat(firstDetected(lost)).isEqualTo(NOW);
        assertThat(lastSeen(lost)).isEqualTo(later);
    }

    @Test
    void closes_a_finding_whose_condition_has_stopped_holding() {
        UUID lost = account(intranet, "lperez", null, "ACTIVE", "UNKNOWN", null);
        reconcile();

        correlate(lost, identity("E-0001"));
        ReconciliationReport after = reconcile(NOW.plus(Duration.ofDays(1))).getFirst();

        assertThat(after.of(FindingType.ORPHAN_ACCOUNT).resolved()).isEqualTo(1);
        assertThat(stateOf(lost, FindingType.ORPHAN_ACCOUNT)).isEqualTo("RESOLVED_EXTERNALLY");
    }

    // --- what a pass must not do -------------------------------------------

    /**
     * The control. Somebody looked at this orphan and decided it is allowed to
     * go on existing, which is the opposite instruction from the other three
     * closed states: a pass that opened a fresh finding for it every night
     * would overrule that decision one row at a time, and the list would fill
     * with the problems somebody has already answered for.
     */
    @Test
    void does_not_reopen_a_condition_a_person_has_accepted_as_an_exception() {
        UUID lost = account(intranet, "lperez", null, "ACTIVE", "UNKNOWN", null);
        reconcile();
        accept(lost, FindingType.ORPHAN_ACCOUNT);

        ReconciliationReport again = reconcile(NOW.plus(Duration.ofDays(1))).getFirst();

        assertThat(again.openedTotal()).as("the condition still holds and is not news").isZero();
        assertThat(again.of(FindingType.ORPHAN_ACCOUNT).confirmed())
                .as("not a finding any more, so not counted as one either")
                .isZero();
        assertThat(rowsAbout(lost)).as("one row, not a second one beside the accepted one").isEqualTo(1);
        assertThat(stateOf(lost, FindingType.ORPHAN_ACCOUNT)).isEqualTo("ACCEPTED_EXCEPTION");
        assertThat(lastSeen(lost)).as("still exercised, which the row has to show").isEqualTo(NOW.plus(Duration.ofDays(1)));
    }

    /**
     * An accepted exception is not closed either, and the date of the decision
     * is not overwritten with the date of a pass.
     */
    @Test
    void does_not_close_an_accepted_exception_when_its_condition_stops_holding() {
        UUID lost = account(intranet, "lperez", null, "ACTIVE", "UNKNOWN", null);
        reconcile();
        accept(lost, FindingType.ORPHAN_ACCOUNT);
        Instant decided = resolvedAt(lost);

        correlate(lost, identity("E-0001"));
        ReconciliationReport again = reconcile(NOW.plus(Duration.ofDays(1))).getFirst();

        assertThat(again.resolvedTotal()).isZero();
        assertThat(stateOf(lost, FindingType.ORPHAN_ACCOUNT)).isEqualTo("ACCEPTED_EXCEPTION");
        assertThat(resolvedAt(lost)).isEqualTo(decided);
    }

    /**
     * The scope. A pass over one target system must not conclude anything
     * about another, and the only thing that tells two findings apart is which
     * application their subject belongs to.
     *
     * <p>The two orphans are fixed one at a time rather than by emptying a
     * system, because {@code reconciliation_finding.account_id} cascades on
     * delete: removing the accounts would take the findings with them and the
     * test would pass without the scope existing.
     */
    @Test
    void closes_the_finding_of_one_target_system_and_leaves_the_others_alone() {
        UUID lostInIntranet = account(intranet, "lperez", null, "ACTIVE", "UNKNOWN", null);
        UUID lostInDirectory = account(directory, "uid=mferreira", null, "ACTIVE", "UNKNOWN", null);
        reconcile();
        assertThat(stateOf(lostInIntranet, FindingType.ORPHAN_ACCOUNT)).isEqualTo("OPEN");
        assertThat(stateOf(lostInDirectory, FindingType.ORPHAN_ACCOUNT)).isEqualTo("OPEN");

        correlate(lostInIntranet, identity("E-0001"));
        List<ReconciliationReport> reports = reconcile(NOW.plus(Duration.ofDays(1)));

        assertThat(reports)
                .extracting(ReconciliationReport::applicationCode)
                .containsExactly("corporate-directory", "intranet");
        assertThat(stateOf(lostInIntranet, FindingType.ORPHAN_ACCOUNT)).isEqualTo("RESOLVED_EXTERNALLY");
        assertThat(stateOf(lostInDirectory, FindingType.ORPHAN_ACCOUNT))
                .as("the other system's finding is not this pass's to close")
                .isEqualTo("OPEN");
    }

    /**
     * The guard that stops this operation doing damage. A system nobody has
     * aggregated and a system with nothing in it are identical from here, and
     * treating the first as the second would resolve every finding it has by
     * concluding that the accounts they are about have gone away.
     */
    @Test
    void skips_a_system_no_account_of_which_has_ever_been_read() {
        account(intranet, "lperez", identity("E-0001"), "ACTIVE", "UNKNOWN", null);

        List<ReconciliationReport> reports = reconcile();

        assertThat(reports).extracting(ReconciliationReport::applicationCode).containsExactly("intranet");
    }

    // --- what the schema refuses -------------------------------------------

    /**
     * The subject check of V001, seen refusing. A finding with every reference
     * null is insertable without it and means nothing, and a finding about an
     * account stored without the account is a row nobody can act on.
     */
    @Test
    void the_table_refuses_a_finding_that_does_not_name_its_subject() {
        assertThatThrownBy(() -> tenants.runInTenant(ORG, () -> jdbc.sql("""
                        INSERT INTO reconciliation_finding (org_id, type, state)
                        VALUES (:orgId, 'ORPHAN_ACCOUNT', 'OPEN')
                        """)
                        .param("orgId", ORG)
                        .update()))
                .hasMessageContaining("reconciliation_finding_subject_check");
    }

    /**
     * The partial unique index, seen refusing. It is what makes a finding one
     * row per subject rather than one per pass, and without it a second pass
     * would be free to insert a duplicate.
     */
    @Test
    void the_table_refuses_a_second_open_finding_about_the_same_subject() {
        UUID lost = account(intranet, "lperez", null, "ACTIVE", "UNKNOWN", null);
        reconcile();

        assertThatThrownBy(() -> tenants.runInTenant(ORG, () -> jdbc.sql("""
                        INSERT INTO reconciliation_finding (org_id, type, state, account_id)
                        VALUES (:orgId, 'ORPHAN_ACCOUNT', 'OPEN', :accountId)
                        """)
                        .param("orgId", ORG)
                        .param("accountId", lost)
                        .update()))
                .hasMessageContaining("reconciliation_finding_open_key");
    }

    // --- fixtures ----------------------------------------------------------

    private List<ReconciliationReport> reconcile() {
        return reconcile(NOW);
    }

    private List<ReconciliationReport> reconcile(Instant now) {
        Reconciliation reconciliation = new ReconciliationRunner(
                new JdbcApplicationLookup(jdbc),
                new JdbcAccessExpectation(jdbc),
                new AccountConditions(jdbc),
                new GrantDrift(jdbc),
                new FindingLedger(jdbc),
                tenants,
                Clock.fixed(now, ZoneOffset.UTC),
                ORG,
                NINETY_DAYS);
        return reconciliation.reconcileEverything();
    }

    private static UUID applicationId(String code) {
        return jdbc.sql("SELECT id FROM application WHERE org_id = :orgId AND code = :code")
                .param("orgId", ORG)
                .param("code", code)
                .query(UUID.class)
                .single();
    }

    private static UUID entitlementId(String nativeValue) {
        return jdbc.sql("SELECT id FROM entitlement WHERE org_id = :orgId AND native_value = :nativeValue")
                .param("orgId", ORG)
                .param("nativeValue", nativeValue)
                .query(UUID.class)
                .single();
    }

    /** One identity, ingested by the real HR run so that the row is one M1 wrote. */
    private UUID identity(String employeeNumber) {
        Optional<UUID> existing = tenants.inTenant(ORG, () -> jdbc.sql("""
                SELECT identity_id FROM source_record WHERE org_id = :orgId AND external_id = :externalId
                """)
                .param("orgId", ORG)
                .param("externalId", employeeNumber)
                .query(UUID.class)
                .optional());
        if (existing.isPresent()) {
            return existing.get();
        }

        hr.serving("""
                {"employeeNumber":"%s","firstName":"Lucia","lastName":"Garcia",\
                "email":"%s@lineward.example","department":"Treasury","jobTitle":"Treasury Analyst",\
                "location":"Madrid","costCenter":"CC-001","employmentType":"EMPLOYEE",\
                "hireDate":"2020-01-01","terminationDate":null,"managerEmployeeNumber":null,\
                "lastModifiedAt":"2026-10-01T00:00:00Z"}"""
                .formatted(employeeNumber, employeeNumber.toLowerCase()));
        HrIngestionRunner.create(hr, jdbc, tenants, Clock.fixed(NOW, ZoneOffset.UTC), event -> {}, ORG, "hr", 100)
                .ingestEverything();
        return identity(employeeNumber);
    }

    private UUID account(
            UUID applicationId, String nativeId, UUID identityId, String status, String kind, Instant lastActivity) {
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                INSERT INTO account (
                    org_id, application_id, native_id, username, status, kind,
                    last_activity_at, identity_id, correlation_method, correlated_at)
                VALUES (
                    :orgId, :applicationId, :nativeId, :nativeId, :status, :kind,
                    CAST(:lastActivity AS timestamptz), CAST(:identityId AS uuid),
                    CASE WHEN CAST(:identityId AS uuid) IS NULL THEN NULL ELSE 'RULE' END,
                    CASE WHEN CAST(:identityId AS uuid) IS NULL THEN NULL ELSE :now END)
                RETURNING id
                """)
                .param("orgId", ORG)
                .param("applicationId", applicationId)
                .param("nativeId", nativeId)
                .param("status", status)
                .param("kind", kind)
                .param("lastActivity", lastActivity == null ? null : OffsetDateTime.ofInstant(lastActivity, ZoneOffset.UTC))
                .param("identityId", identityId)
                .param("now", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .query(UUID.class)
                .single());
    }

    private void grant(UUID accountId, UUID entitlementId) {
        tenants.runInTenant(ORG, () -> jdbc.sql("""
                INSERT INTO account_entitlement (org_id, account_id, entitlement_id)
                VALUES (:orgId, :accountId, :entitlementId)
                """)
                .param("orgId", ORG)
                .param("accountId", accountId)
                .param("entitlementId", entitlementId)
                .update());
    }

    private void expect(UUID identityId, UUID entitlementId, String state) {
        tenants.runInTenant(ORG, () -> jdbc.sql("""
                INSERT INTO access_assignment (org_id, identity_id, entitlement_id, source, state)
                VALUES (:orgId, :identityId, :entitlementId, 'BIRTHRIGHT', :state)
                """)
                .param("orgId", ORG)
                .param("identityId", identityId)
                .param("entitlementId", entitlementId)
                .param("state", state)
                .update());
    }

    private void correlate(UUID accountId, UUID identityId) {
        tenants.runInTenant(ORG, () -> jdbc.sql("""
                UPDATE account
                   SET identity_id = :identityId, correlation_method = 'RULE', correlated_at = :now
                 WHERE org_id = :orgId AND id = :accountId
                """)
                .param("orgId", ORG)
                .param("accountId", accountId)
                .param("identityId", identityId)
                .param("now", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .update());
    }

    /** What a person deciding to live with a finding does to the row. */
    private void accept(UUID accountId, FindingType type) {
        tenants.runInTenant(ORG, () -> jdbc.sql("""
                UPDATE reconciliation_finding
                   SET state = 'ACCEPTED_EXCEPTION', resolved_at = :now
                 WHERE org_id = :orgId AND account_id = :accountId AND type = :type
                """)
                .param("orgId", ORG)
                .param("accountId", accountId)
                .param("type", type.name())
                .param("now", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .update());
    }

    private List<String> findingsAbout(UUID accountId) {
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                SELECT type FROM reconciliation_finding
                 WHERE org_id = :orgId AND account_id = :accountId ORDER BY type
                """)
                .param("orgId", ORG)
                .param("accountId", accountId)
                .query(String.class)
                .list());
    }

    private int rowsAbout(UUID accountId) {
        return findingsAbout(accountId).size();
    }

    private String stateOf(UUID accountId, FindingType type) {
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                SELECT state FROM reconciliation_finding
                 WHERE org_id = :orgId AND account_id = :accountId AND type = :type
                """)
                .param("orgId", ORG)
                .param("accountId", accountId)
                .param("type", type.name())
                .query(String.class)
                .single());
    }

    private Optional<String> driftState(UUID identityId, UUID entitlementId, FindingType type) {
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                SELECT state FROM reconciliation_finding
                 WHERE org_id = :orgId AND identity_id = :identityId
                   AND entitlement_id = :entitlementId AND type = :type
                """)
                .param("orgId", ORG)
                .param("identityId", identityId)
                .param("entitlementId", entitlementId)
                .param("type", type.name())
                .query(String.class)
                .optional());
    }

    private Instant firstDetected(UUID accountId) {
        return timestamp(accountId, "first_detected_at");
    }

    private Instant lastSeen(UUID accountId) {
        return timestamp(accountId, "last_seen_at");
    }

    private Instant resolvedAt(UUID accountId) {
        return timestamp(accountId, "resolved_at");
    }

    private Instant timestamp(UUID accountId, String column) {
        return tenants.inTenant(ORG, () -> jdbc.sql(
                        "SELECT " + column + " FROM reconciliation_finding WHERE org_id = :orgId AND account_id = :accountId")
                .param("orgId", ORG)
                .param("accountId", accountId)
                .query(OffsetDateTime.class)
                .single())
                .toInstant();
    }
}
