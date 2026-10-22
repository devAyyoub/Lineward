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
package com.lineward.identity.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lineward.identity.IngestionReport;
import com.lineward.identity.source.LiteralJsonSourceReader;
import com.lineward.shared.TenantTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * The ingestion against the real schema, the real policies and the real
 * application role.
 *
 * <p>Not an in-memory database and not the owner role, for the reason ADR-011
 * gives: with the owner, every policy is silently ignored and the isolation
 * looks installed while doing nothing. These tests connect as
 * {@code lineward_app}, which is what production does, so a query that forgot
 * its tenant fails here rather than in front of the second customer.
 *
 * <p>The source is fed records written as text. What it exercises is the half of
 * phase 1 that is verifiable on its own: whether what Lineward stored is what
 * the source asserted. Classifying the difference as a joiner, a mover or a
 * leaver is the next task and has its own tests.
 */
@Testcontainers
class IdentityIngestionTests {

    private static final String OWNER_USER = "lineward_owner";
    private static final String OWNER_PASSWORD = "lineward_owner_local";
    private static final String APP_USER = "lineward_app";
    private static final String APP_PASSWORD = "lineward_app_local";

    /** Inserted by V002, so the test uses the organization the installation has. */
    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    /** Fixed, because half of what the status rule decides is a comparison against today. */
    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6")
            .withInitScript("db/test-roles.sql");

    private static JdbcClient jdbc;
    private static TenantTransactions tenants;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final LiteralJsonSourceReader source = new LiteralJsonSourceReader();

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
    }

    @BeforeEach
    void emptyTheIdentityTables() {
        tenants.runInTenant(ORG, () -> {
            jdbc.sql("DELETE FROM identity_responsibility").update();
            jdbc.sql("DELETE FROM source_record").update();
            jdbc.sql("DELETE FROM identity").update();
        });
    }

    @Test
    void storesWhatTheSourceAssertedAndTheGovernedViewOfIt() {
        source.serving(employee("E-0001", "Carmen Munoz", "Executive Office", null));

        IngestionReport report = ingestion(100).ingestEverything();

        assertThat(report.recordsRead()).isEqualTo(1);
        assertThat(report.identitiesCreated()).isEqualTo(1);
        assertThat(report.identitiesUpdated()).isZero();
        assertThat(report.identitiesUnchanged()).isZero();

        assertThat(identityOf("E-0001"))
                .containsEntry("display_name", "Carmen Munoz")
                .containsEntry("department", "Executive Office")
                .containsEntry("status", "ACTIVE")
                .containsEntry("hire_date", "2020-01-01");

        // The source's own words, kept apart from the view above. Read field by
        // field rather than as text: the column is jsonb, and PostgreSQL stores
        // a parsed document, not the bytes that arrived.
        assertThat(storedField("E-0001", "employeeNumber")).isEqualTo("E-0001");
        assertThat(storedField("E-0001", "lastModifiedAt")).isEqualTo("2026-10-01T00:00:00Z");
        assertThat(storedField("E-0001", "costCenter")).isEqualTo("CC-001");
        // Rendered as text, because a generic row map hands back whatever the
        // driver chose for a timestamptz and the point of the assertion is the
        // instant, not the Java type.
        assertThat(sourceRecordOf("E-0001").get("asserted_at_utc")).isEqualTo("2026-10-01T00:00:00Z");
    }

    /**
     * The property that makes a half finished run safe to recover from by
     * running it again, and the one that would be invisible if the report only
     * counted records read.
     */
    @Test
    void readingTheSameExtractTwiceWritesNothingTheSecondTime() {
        source.serving(employee("E-0001", "Carmen Munoz", "Executive Office", null));
        ingestion(100).ingestEverything();
        Object firstIngestedAt = sourceRecordOf("E-0001").get("ingested_at");

        IngestionReport second = ingestion(100).ingestEverything();

        assertThat(second.identitiesUnchanged()).isEqualTo(1);
        assertThat(second.identitiesCreated()).isZero();
        assertThat(second.identitiesUpdated()).isZero();
        assertThat(sourceRecordOf("E-0001").get("ingested_at")).isEqualTo(firstIngestedAt);
    }

    /**
     * An HR system that re-stamps every record on a nightly batch has not
     * changed anything about anybody. Reading it as a change would mean a mover
     * per employee, with an access recalculation behind each one.
     */
    @Test
    void aRecordRestampedWithoutChangingIsNotAnUpdate() {
        source.serving(employee("E-0001", "Carmen Munoz", "Executive Office", null));
        ingestion(100).ingestEverything();
        Object firstIngestedAt = sourceRecordOf("E-0001").get("ingested_at");

        source.serving(employeeJson(
                "E-0001", "Carmen Munoz", "Executive Office", null,
                "2020-01-01", null, "2026-10-21T03:00:00Z"));
        IngestionReport second = ingestion(100).ingestEverything();

        assertThat(second.identitiesUnchanged()).isEqualTo(1);
        assertThat(sourceRecordOf("E-0001").get("ingested_at")).isEqualTo(firstIngestedAt);
    }

    @Test
    void aChangedAttributeUpdatesBothTheViewAndTheStoredAssertion() {
        source.serving(employee("E-0001", "Carmen Munoz", "Treasury", null));
        ingestion(100).ingestEverything();
        UUID identityId = (UUID) identityOf("E-0001").get("id");

        source.serving(employeeJson(
                "E-0001", "Carmen Munoz", "Risk", null, "2020-01-01", null, "2026-10-21T03:00:00Z"));
        IngestionReport second = ingestion(100).ingestEverything();

        assertThat(second.identitiesUpdated()).isEqualTo(1);
        assertThat(identityOf("E-0001"))
                .containsEntry("department", "Risk")
                // The same identity, not a second one. A duplicate here breaks
                // correlation for years.
                .containsEntry("id", identityId);
        assertThat(storedField("E-0001", "department")).isEqualTo("Risk");
    }

    /**
     * What the {@code jsonb} column does to the payload, stated rather than
     * assumed.
     *
     * <p>PostgreSQL parses a {@code jsonb} value and stores the document, not
     * the text: key order changes, whitespace is normalised and duplicate keys
     * are dropped. So "kept verbatim" is the wrong claim and this test is what
     * replaces it. What is actually guaranteed is the part that matters, and it
     * is worth pinning down: every field the source sent comes back with its
     * value, and the fingerprint recomputed from what was stored still equals
     * the one stored beside it. That second half is only true because the
     * fingerprint normalises field order itself, which is exactly why it does.
     */
    @Test
    void theStoredPayloadIsEquivalentJsonAndStillMatchesItsFingerprint() {
        String sent = employee("E-0001", "Carmen Munoz", "Executive Office", null);
        source.serving(sent);
        ingestion(100).ingestEverything();

        String stored = (String) sourceRecordOf("E-0001").get("payload");

        assertThat(stored).isNotEqualTo(sent);
        assertThat(JSON.readTree(stored)).isEqualTo(JSON.readTree(sent));

        PayloadFingerprint fingerprint = new PayloadFingerprint(JSON);
        assertThat(fingerprint.of(stored)).isEqualTo(fingerprint.of(sent));
        assertThat(storedFingerprintOf("E-0001")).isEqualTo(fingerprint.of(sent));
    }

    /** The future dated joiner and the retroactive termination, through the real path. */
    @Test
    void derivesTheStatusFromTheDatesTheSourceAsserts() {
        source.serving(
                employeeJson("E-2242", "Pablo Ramirez", "Treasury", null,
                        "2026-11-14", null, "2026-10-15T00:00:00Z"),
                employeeJson("E-3894", "Jimena Gonzalez", "Procurement", null,
                        "2025-12-16", "2026-01-15", "2026-10-15T00:00:00Z"),
                employeeJson("E-3811", "Uxia Alvarez", "Procurement", null,
                        "2026-07-27", "2026-07-07", "2026-10-15T00:00:00Z"));

        ingestion(100).ingestEverything();

        assertThat(identityOf("E-2242")).containsEntry("status", "PENDING");
        assertThat(identityOf("E-3894")).containsEntry("status", "TERMINATED");
        // Contradictory, and the safe reading of nonsense grants nothing.
        assertThat(identityOf("E-3811")).containsEntry("status", "TERMINATED");
    }

    /**
     * A returning employee keeps the same employee number, so the status is
     * derived again and the identity comes back to life instead of being
     * duplicated. M1 asks for exactly that.
     */
    @Test
    void aRehireReactivatesTheSameIdentity() {
        source.serving(employeeJson(
                "E-0007", "Nuria Garrido", "Engineering", null,
                "2019-03-09", "2026-05-31", "2026-06-01T00:00:00Z"));
        ingestion(100).ingestEverything();
        UUID identityId = (UUID) identityOf("E-0007").get("id");
        assertThat(identityOf("E-0007")).containsEntry("status", "TERMINATED");

        source.serving(employeeJson(
                "E-0007", "Nuria Garrido", "Engineering", null,
                "2026-10-01", null, "2026-10-01T00:00:00Z"));
        ingestion(100).ingestEverything();

        assertThat(identityOf("E-0007"))
                .containsEntry("status", "ACTIVE")
                .containsEntry("id", identityId);
        assertThat(countOf("identity")).isEqualTo(1);
    }

    @Test
    void readsEveryPageAndNotJustTheFirst() {
        source.serving(
                employee("E-0001", "One", "Engineering", null),
                employee("E-0002", "Two", "Engineering", null),
                employee("E-0003", "Three", "Engineering", null),
                employee("E-0004", "Four", "Engineering", null),
                employee("E-0005", "Five", "Engineering", null));

        IngestionReport report = ingestion(2).ingestEverything();

        assertThat(report.recordsRead()).isEqualTo(5);
        assertThat(report.identitiesCreated()).isEqualTo(5);
        assertThat(countOf("identity")).isEqualTo(5);
    }

    /**
     * A short read looks exactly like a complete read of a smaller company, and
     * the next task of phase 1 would turn the difference into a wave of
     * terminations. So it fails instead of being accepted.
     */
    @Test
    void refusesToTreatAnIncompleteReadAsAnExtract() {
        source.serving(employee("E-0001", "One", "Engineering", null)).claimingTotal(5);

        assertThatThrownBy(() -> ingestion(100).ingestEverything())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete read")
                .hasMessageContaining("5");
    }

    @Test
    void anIncrementalReadOnlyTouchesWhatTheSourceChanged() {
        source.serving(
                employee("E-0001", "One", "Engineering", null),
                employee("E-0002", "Two", "Engineering", null));
        ingestion(100).ingestEverything();

        source.serving(
                employee("E-0001", "One", "Engineering", null),
                employeeJson("E-0002", "Two", "Risk", null, "2020-01-01", null, "2026-10-20T12:00:00Z"));
        IngestionReport delta = ingestion(100).ingestChangesSince(Instant.parse("2026-10-10T00:00:00Z"));

        assertThat(delta.recordsRead()).isEqualTo(1);
        assertThat(delta.identitiesUpdated()).isEqualTo(1);
        assertThat(identityOf("E-0002")).containsEntry("department", "Risk");
        assertThat(identityOf("E-0001")).containsEntry("department", "Engineering");
    }

    /**
     * The newest instant the source asserted, not the clock. A record changed
     * while the run was in progress would fall into the gap between the two and
     * never be read again.
     */
    @Test
    void reportsTheNewestAssertedInstantAsTheMarkToReadFrom() {
        source.serving(
                employeeJson("E-0001", "One", "Engineering", null,
                        "2020-01-01", null, "2026-10-01T00:00:00Z"),
                employeeJson("E-0002", "Two", "Engineering", null,
                        "2020-01-01", null, "2026-10-19T08:30:00Z"));

        IngestionReport report = ingestion(100).ingestEverything();

        assertThat(report.highWaterMark()).isEqualTo(Instant.parse("2026-10-19T08:30:00Z"));
        assertThat(report.highWaterMark()).isBefore(NOW);
    }

    @Test
    void theManagerTheSourceNamesBecomesAResponsibility() {
        source.serving(
                employee("E-0001", "Carmen Munoz", "Executive Office", null),
                employee("E-0002", "Brais Castro", "Engineering", "E-0001"));

        IngestionReport report = ingestion(100).ingestEverything();

        assertThat(report.managersLinked()).isEqualTo(1);
        assertThat(currentManagerOf("E-0002"))
                .containsEntry("responsible_identity_id", identityOf("E-0001").get("id"))
                .containsEntry("role", "MANAGER")
                .containsEntry("origin", "ASSERTED");
    }

    /**
     * A manager named on a later page than the person reporting to them. If the
     * link were resolved while reading, the result would depend on the order the
     * pages happened to arrive.
     */
    @Test
    void linksAManagerWhoArrivesOnALaterPage() {
        source.serving(
                employee("E-0002", "Brais Castro", "Engineering", "E-0001"),
                employee("E-0001", "Carmen Munoz", "Executive Office", null));

        IngestionReport report = ingestion(1).ingestEverything();

        assertThat(report.managersLinked()).isEqualTo(1);
        assertThat(currentManagerOf("E-0002")).isNotNull();
    }

    /**
     * History rather than overwriting, which is what later answers "who was
     * their manager when this access was approved".
     */
    @Test
    void aChangedManagerClosesThePreviousRowInsteadOfReplacingIt() {
        source.serving(
                employee("E-0001", "Carmen Munoz", "Executive Office", null),
                employee("E-0003", "Elena Moreno", "Risk", null),
                employee("E-0002", "Brais Castro", "Engineering", "E-0001"));
        ingestion(100).ingestEverything();

        source.serving(
                employee("E-0001", "Carmen Munoz", "Executive Office", null),
                employee("E-0003", "Elena Moreno", "Risk", null),
                employeeJson("E-0002", "Brais Castro", "Engineering", "E-0003",
                        "2020-01-01", null, "2026-10-20T12:00:00Z"));
        IngestionReport second = ingestion(100).ingestEverything();

        assertThat(second.managersLinked()).isEqualTo(1);
        assertThat(currentManagerOf("E-0002"))
                .containsEntry("responsible_identity_id", identityOf("E-0003").get("id"));
        assertThat(responsibilityRowsFor("E-0002")).hasSize(2);
        assertThat(closedResponsibilityCountFor("E-0002")).isEqualTo(1);
    }

    /** An identity nobody answers for is the finding M1 lists, not a row to leave stale. */
    @Test
    void aManagerRemovedAtTheSourceClosesTheRow() {
        source.serving(
                employee("E-0001", "Carmen Munoz", "Executive Office", null),
                employee("E-0002", "Brais Castro", "Engineering", "E-0001"));
        ingestion(100).ingestEverything();

        source.serving(
                employee("E-0001", "Carmen Munoz", "Executive Office", null),
                employeeJson("E-0002", "Brais Castro", "Engineering", null,
                        "2020-01-01", null, "2026-10-20T12:00:00Z"));
        IngestionReport second = ingestion(100).ingestEverything();

        assertThat(second.managersUnlinked()).isEqualTo(1);
        assertThat(currentManagerOf("E-0002")).isNull();
        assertThat(closedResponsibilityCountFor("E-0002")).isEqualTo(1);
    }

    /**
     * The reason {@code identity_responsibility.origin} exists. A sponsor
     * assigned by a person inside Lineward is not the HR system's to overwrite,
     * and with a single manager column there would be no way to tell the two
     * apart.
     *
     * <p>Nothing in phase 1 creates an assigned row, so the branch that protects
     * them would never run. An untested branch is the one that fails the day it
     * is needed, which is why the row is planted here by hand.
     */
    @Test
    void aResponsibilityAssignedInsideLinewardIsLeftAlone() {
        source.serving(
                employee("E-0001", "Carmen Munoz", "Executive Office", null),
                employee("E-0002", "Brais Castro", "Engineering", null));
        ingestion(100).ingestEverything();

        UUID subject = (UUID) identityOf("E-0002").get("id");
        UUID assigned = (UUID) identityOf("E-0001").get("id");
        tenants.runInTenant(ORG, () -> jdbc.sql("""
                INSERT INTO identity_responsibility (
                        org_id, subject_identity_id, responsible_identity_id, role, origin)
                VALUES (:org, :subject, :responsible, 'MANAGER', 'ASSIGNED')
                """)
                .param("org", ORG)
                .param("subject", subject)
                .param("responsible", assigned)
                .update());

        source.serving(
                employee("E-0001", "Carmen Munoz", "Executive Office", null),
                employeeJson("E-0002", "Brais Castro", "Engineering", "E-0001",
                        "2020-01-01", null, "2026-10-20T12:00:00Z"));
        IngestionReport second = ingestion(100).ingestEverything();

        assertThat(second.managersPreserved()).isEqualTo(1);
        assertThat(second.managersLinked()).isZero();
        assertThat(currentManagerOf("E-0002")).containsEntry("origin", "ASSIGNED");
        assertThat(responsibilityRowsFor("E-0002")).hasSize(1);
    }

    /**
     * An incremental read routinely sees somebody before it sees their manager.
     * Counted rather than dropped, because a control that silently loses data is
     * worse than one that fails.
     */
    @Test
    void aManagerTheSourceHasNotSentYetIsCountedNotDropped() {
        source.serving(employee("E-0002", "Brais Castro", "Engineering", "E-9999"));

        IngestionReport report = ingestion(100).ingestEverything();

        assertThat(report.managersUnresolved()).isEqualTo(1);
        assertThat(report.managersLinked()).isZero();
        assertThat(currentManagerOf("E-0002")).isNull();
    }

    /**
     * The schema forbids answering for yourself, and a real extract can assert
     * it. Letting the insert fail would abort a whole batch over one
     * contradictory record.
     */
    @Test
    void aRecordThatManagesItselfIsRefusedWithoutKillingTheBatch() {
        source.serving(
                employee("E-0001", "Carmen Munoz", "Executive Office", "E-0001"),
                employee("E-0002", "Brais Castro", "Engineering", "E-0001"));

        IngestionReport report = ingestion(100).ingestEverything();

        assertThat(report.managersUnresolved()).isEqualTo(1);
        assertThat(report.managersLinked()).isEqualTo(1);
        assertThat(currentManagerOf("E-0001")).isNull();
        assertThat(currentManagerOf("E-0002")).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Fixtures and helpers
    // -----------------------------------------------------------------------

    private HrIngestionRunner ingestion(int pageSize) {
        return HrIngestionRunner.create(
                source, jdbc, tenants, Clock.fixed(NOW, ZoneOffset.UTC), ORG, "hr", pageSize);
    }

    /** Sane defaults: hired in 2020, still here, last changed on 1 October. */
    private static String employee(String number, String name, String department, String manager) {
        return employeeJson(number, name, department, manager, "2020-01-01", null, "2026-10-01T00:00:00Z");
    }

    /**
     * Written as text rather than serialised from a record, on purpose. A
     * payload built from this project's own classes can only be the payload this
     * code already knows how to produce, which is what let a broken contract
     * pass sixteen tests on 2026-10-16.
     */
    private static String employeeJson(
            String number,
            String name,
            String department,
            String manager,
            String hireDate,
            String terminationDate,
            String modifiedAt) {
        String first = name.split(" ")[0];
        String last = name.substring(first.length()).trim();
        return """
                {"employeeNumber":"%s","firstName":"%s","lastName":"%s",\
                "email":"%s@lineward.example","department":"%s","jobTitle":"Analyst",\
                "location":"Madrid","costCenter":"CC-001","employmentType":"EMPLOYEE",\
                "hireDate":%s,"terminationDate":%s,"managerEmployeeNumber":%s,\
                "lastModifiedAt":"%s"}"""
                .formatted(
                        number,
                        first,
                        last,
                        number.toLowerCase(),
                        department,
                        quoted(hireDate),
                        quoted(terminationDate),
                        quoted(manager),
                        modifiedAt);
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static <T> T inTenant(Supplier<T> work) {
        return tenants.inTenant(ORG, work);
    }

    /**
     * Two queries rather than a join, because {@code source_record} and
     * {@code identity} belong to the same module here but the habit of joining
     * across tables is the one ADR-001 asks not to form.
     */
    private static Map<String, Object> identityOf(String externalId) {
        UUID identityId = identityIdOf(externalId);
        return inTenant(() -> jdbc.sql("""
                SELECT id, status, display_name, email, department, job_title,
                       cast(hire_date as text) AS hire_date,
                       cast(termination_date as text) AS termination_date
                  FROM identity WHERE org_id = :org AND id = :id
                """)
                .param("org", ORG)
                .param("id", identityId)
                .query()
                .singleRow());
    }

    private static UUID identityIdOf(String externalId) {
        return inTenant(() -> jdbc.sql(
                "SELECT identity_id FROM source_record WHERE org_id = :org AND external_id = :externalId")
                .param("org", ORG)
                .param("externalId", externalId)
                .query(UUID.class)
                .single());
    }

    private static Map<String, Object> sourceRecordOf(String externalId) {
        return inTenant(() -> jdbc.sql("""
                SELECT external_id,
                       cast(payload as text) AS payload,
                       to_char(asserted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS asserted_at_utc,
                       ingested_at
                  FROM source_record WHERE org_id = :org AND external_id = :externalId
                """)
                .param("org", ORG)
                .param("externalId", externalId)
                .query()
                .singleRow());
    }

    private static Map<String, Object> currentManagerOf(String externalId) {
        UUID subject = identityIdOf(externalId);
        return inTenant(() -> jdbc.sql("""
                SELECT responsible_identity_id, role, origin, valid_from
                  FROM identity_responsibility
                 WHERE org_id = :org AND subject_identity_id = :subject
                   AND role = 'MANAGER' AND valid_to IS NULL
                """)
                .param("org", ORG)
                .param("subject", subject)
                .query()
                .listOfRows())
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static List<Map<String, Object>> responsibilityRowsFor(String externalId) {
        UUID subject = identityIdOf(externalId);
        return inTenant(() -> jdbc.sql("""
                SELECT id, responsible_identity_id, origin, valid_from, valid_to
                  FROM identity_responsibility
                 WHERE org_id = :org AND subject_identity_id = :subject
                """)
                .param("org", ORG)
                .param("subject", subject)
                .query()
                .listOfRows());
    }

    private static int closedResponsibilityCountFor(String externalId) {
        UUID subject = identityIdOf(externalId);
        return inTenant(() -> jdbc.sql("""
                SELECT count(*) FROM identity_responsibility
                 WHERE org_id = :org AND subject_identity_id = :subject AND valid_to IS NOT NULL
                """)
                .param("org", ORG)
                .param("subject", subject)
                .query(Integer.class)
                .single());
    }

    /** One field of the stored document, read the way anything downstream would. */
    private static String storedField(String externalId, String field) {
        return JSON.readTree((String) sourceRecordOf(externalId).get("payload")).path(field).asString();
    }

    private static byte[] storedFingerprintOf(String externalId) {
        return inTenant(() -> jdbc.sql(
                "SELECT payload_hash FROM source_record WHERE org_id = :org AND external_id = :externalId")
                .param("org", ORG)
                .param("externalId", externalId)
                .query(byte[].class)
                .single());
    }

    private static int countOf(String table) {
        return inTenant(() -> jdbc.sql("SELECT count(*) FROM " + table + " WHERE org_id = :org")
                .param("org", ORG)
                .query(Integer.class)
                .single());
    }
}
