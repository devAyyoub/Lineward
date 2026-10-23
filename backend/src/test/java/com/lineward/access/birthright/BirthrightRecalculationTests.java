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

import com.lineward.identity.IdentityIngestion;
import com.lineward.identity.IdentityLifecycleChanged;
import com.lineward.identity.IdentityStatus;
import com.lineward.identity.LifecycleEvent;
import com.lineward.identity.ingest.HrIngestionRunner;
import com.lineward.identity.query.JdbcIdentityLookup;
import com.lineward.identity.source.LiteralJsonSourceReader;
import com.lineward.shared.TenantTransactions;
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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * From an HR extract to the access somebody is due, through the rules this build
 * ships.
 *
 * <p>The whole chain, with no piece replaced: the real reader parsing text the
 * test wrote, the real ingestion, the real event published inside the
 * transaction that stores the identity, the real rule file, and the real schema.
 * The other tests in this package each prove one piece; what only this one can
 * prove is that they meet, and in particular that the listener sees an identity
 * the same transaction inserted a moment earlier. A listener running after the
 * commit would read nothing and grant nothing, and every unit test would still
 * pass.
 *
 * <p>Access is asserted as entitlement names rather than ids, because a test
 * that asserted uuids would be unreadable and would not notice a rule pointing
 * at the wrong group.
 */
@Testcontainers
class BirthrightRecalculationTests {

    private static final String OWNER_USER = "lineward_owner";
    private static final String OWNER_PASSWORD = "lineward_owner_local";
    private static final String APP_USER = "lineward_app";
    private static final String APP_PASSWORD = "lineward_app_local";

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    /** Fixed, because every lifecycle decision is a comparison against today. */
    private static final Instant TODAY = Instant.parse("2026-10-23T09:00:00Z");

    private static final String ALL_STAFF = "cn=all-staff,ou=groups,dc=lineward,dc=example";
    private static final String DEPT_TREASURY = "cn=dept-treasury,ou=groups,dc=lineward,dc=example";
    private static final String DEPT_RISK = "cn=dept-risk,ou=groups,dc=lineward,dc=example";
    private static final String MANAGERS = "cn=managers,ou=groups,dc=lineward,dc=example";
    private static final String REMOTE_VPN = "cn=remote-vpn,ou=groups,dc=lineward,dc=example";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6")
            .withInitScript("db/test-roles.sql");

    private static JdbcClient jdbc;
    private static TenantTransactions tenants;

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
    void emptyEverythingTheRunWrites() {
        tenants.runInTenant(ORG, () -> {
            jdbc.sql("DELETE FROM access_assignment").update();
            jdbc.sql("DELETE FROM identity_responsibility").update();
            jdbc.sql("DELETE FROM source_record").update();
            jdbc.sql("DELETE FROM identity").update();
        });
    }

    @Test
    void grantsAJoinerWhatTheRulesSayTheirJobIsDue() {
        source.serving(employee("E-0001", "Treasury", "Treasury Analyst", "Madrid", "EMPLOYEE",
                "2020-01-01", null));

        ingestion(TODAY).ingestEverything();

        assertThat(liveAccess()).containsExactlyInAnyOrder(
                "base-package/" + ALL_STAFF,
                "base-package/employee",
                "department-treasury/" + DEPT_TREASURY,
                "treasury-read/treasury-read");
    }

    /**
     * Two attributes deciding one grant, through the file. A remote intern is the
     * case the rule is written for: the population whose access most often
     * outlives them.
     */
    @Test
    void appliesARuleThatNeedsTwoAttributesToMatch() {
        source.serving(
                employee("E-0001", "Engineering", "Software Engineer", "Remote", "EMPLOYEE",
                        "2020-01-01", null),
                employee("E-0002", "Engineering", "Software Engineer", "Remote", "INTERN",
                        "2020-01-01", null));

        ingestion(TODAY).ingestEverything();

        assertThat(accessOf("E-0001")).contains("remote-workers/" + REMOTE_VPN);
        assertThat(accessOf("E-0002")).doesNotContain("remote-workers/" + REMOTE_VPN);
    }

    /**
     * The mover, end to end, and the half that gets forgotten. The source says
     * one thing about Lucia and then another; what she is due changes in both
     * directions, and the base package is not disturbed because it never stopped
     * being due.
     */
    @Test
    void swapsAccessWhenTheSourceMovesSomebody() {
        source.serving(employee("E-0001", "Treasury", "Treasury Analyst", "Madrid", "EMPLOYEE",
                "2020-01-01", null));
        ingestion(TODAY).ingestEverything();

        source.serving(employee("E-0001", "Risk", "Risk Analyst", "Madrid", "EMPLOYEE",
                "2020-01-01", null));
        ingestion(TODAY).ingestEverything();

        assertThat(liveAccess()).containsExactlyInAnyOrder(
                "base-package/" + ALL_STAFF,
                "base-package/employee",
                "department-risk/" + DEPT_RISK);
        assertThat(revokedAccess()).containsExactlyInAnyOrder(
                "department-treasury/" + DEPT_TREASURY,
                "treasury-read/treasury-read");
    }

    /** A promotion that adds access without taking any away. */
    @Test
    void addsWhatAPromotionIsDue() {
        source.serving(employee("E-0001", "Treasury", "Treasury Analyst", "Madrid", "EMPLOYEE",
                "2020-01-01", null));
        ingestion(TODAY).ingestEverything();

        source.serving(employee("E-0001", "Treasury", "Treasury Manager", "Madrid", "EMPLOYEE",
                "2020-01-01", null));
        ingestion(TODAY).ingestEverything();

        assertThat(liveAccess()).contains("managers/" + MANAGERS);
        assertThat(revokedAccess()).isEmpty();
    }

    @Test
    void takesEverythingAwayWhenSomebodyLeaves() {
        source.serving(employee("E-0001", "Treasury", "Treasury Analyst", "Madrid", "EMPLOYEE",
                "2020-01-01", null));
        ingestion(TODAY).ingestEverything();

        source.serving(employee("E-0001", "Treasury", "Treasury Analyst", "Madrid", "EMPLOYEE",
                "2020-01-01", "2026-10-20"));
        ingestion(TODAY).ingestEverything();

        assertThat(liveAccess()).isEmpty();
        assertThat(revokedAccess()).hasSize(4);
    }

    /**
     * The future dated joiner, and then the day their date arrives. The source
     * asserts exactly the same record on both runs, so nothing in the payload
     * says anything happened: the only thing that moved is which side of the hire
     * date today falls on. Access appearing here is the whole chain working on a
     * day nobody told it about.
     */
    @Test
    void grantsNothingUntilAFutureJoinerStartsAndEverythingOnTheDay() {
        source.serving(employee("E-0001", "Treasury", "Treasury Analyst", "Madrid", "EMPLOYEE",
                "2026-11-01", null));

        ingestion(TODAY).ingestEverything();
        assertThat(liveAccess()).isEmpty();
        assertThat(statusOf("E-0001")).isEqualTo(IdentityStatus.PENDING);

        ingestion(Instant.parse("2026-11-01T09:00:00Z")).ingestEverything();

        assertThat(statusOf("E-0001")).isEqualTo(IdentityStatus.ACTIVE);
        assertThat(liveAccess()).containsExactlyInAnyOrder(
                "base-package/" + ALL_STAFF,
                "base-package/employee",
                "department-treasury/" + DEPT_TREASURY,
                "treasury-read/treasury-read");
    }

    /**
     * The property that makes a nightly run safe. A consumer that granted on
     * every read would grant the same four accesses every night, and a table of
     * expected access would stop being a statement about anything.
     */
    @Test
    void writesNoAccessWhenTheSourceSaysNothingNew() {
        source.serving(employee("E-0001", "Treasury", "Treasury Analyst", "Madrid", "EMPLOYEE",
                "2020-01-01", null));
        ingestion(TODAY).ingestEverything();
        List<String> after = assignmentIds();

        ingestion(TODAY).ingestEverything();

        assertThat(assignmentIds()).isEqualTo(after);
    }

    /**
     * An event about an identity that is not there. Impossible while the event is
     * published inside the transaction that writes the identity, which is why
     * what is asserted is the message: the next person to see this needs to be
     * told that the two have come apart, not handed a missing value.
     */
    @Test
    void failsLoudlyIfAnEventArrivesForAnIdentityThatDoesNotExist() {
        UUID missing = UUID.randomUUID();
        IdentityLifecycleChanged event = new IdentityLifecycleChanged(
                ORG, missing, "E-9999", LifecycleEvent.JOINER, IdentityStatus.ACTIVE, List.of(), TODAY);

        assertThatThrownBy(() -> tenants.runInTenant(ORG, () -> recalculation().recalculateOn(event)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("E-9999")
                .hasMessageContaining("does not exist");
    }

    /**
     * The transaction boundary, which is the claim this whole design rests on and
     * the one a passing suite would otherwise not check.
     *
     * <p>The rule below points at an entitlement nobody registered, so the
     * recalculation throws while the identity is half written. Nothing is stored:
     * not the identity, not the source record, not the access. Were the listener
     * running after the commit, the identity would be sitting there marked as
     * handled while nobody had worked out its access, which is the state this
     * project describes as indistinguishable from a person nobody granted
     * anything to.
     */
    @Test
    void storesNoIdentityWhenWorkingOutItsAccessFails() {
        source.serving(employee("E-0001", "Treasury", "Treasury Analyst", "Madrid", "EMPLOYEE",
                "2020-01-01", null));
        BirthrightRules pointsNowhere = new BirthrightRules(List.of(new BirthrightRule(
                "points-nowhere",
                "Grants an entitlement nobody registered.",
                true,
                Map.of(),
                List.of(new BirthrightRule.Grant("intranet", "treasury-write")))));

        assertThatThrownBy(() -> ingestionWith(pointsNowhere, TODAY).ingestEverything())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("points-nowhere");

        assertThat(identityCount()).isZero();
        assertThat(liveAccess()).isEmpty();
    }

    /**
     * The ingestion with the recalculation hooked to its event publisher, which
     * is what Spring does with {@code @EventListener} and what makes the listener
     * part of the ingestion's transaction.
     */
    private IdentityIngestion ingestion(Instant now) {
        return ingestionWith(ShippedBirthrightRules.load(), now);
    }

    private IdentityIngestion ingestionWith(BirthrightRules rules, Instant now) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        BirthrightRecalculation recalculation = recalculation(rules, clock);
        return HrIngestionRunner.create(
                source,
                jdbc,
                tenants,
                clock,
                event -> recalculation.recalculateOn((IdentityLifecycleChanged) event),
                ORG,
                "hr",
                100);
    }

    private BirthrightRecalculation recalculation() {
        return recalculation(ShippedBirthrightRules.load(), Clock.fixed(TODAY, ZoneOffset.UTC));
    }

    private BirthrightRecalculation recalculation(BirthrightRules rules, Clock clock) {
        return new BirthrightRecalculation(
                new JdbcIdentityLookup(jdbc),
                rules,
                new ExpectedAccessWriter(jdbc, new EntitlementCatalog(jdbc), clock));
    }

    private List<String> liveAccess() {
        return access("a.state IN ('PENDING', 'ACTIVE')");
    }

    private List<String> revokedAccess() {
        return access("a.state = 'REVOKED'");
    }

    private List<String> accessOf(String employeeNumber) {
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                SELECT a.source_detail || '/' || e.native_value
                  FROM access_assignment a
                  JOIN entitlement e ON e.org_id = a.org_id AND e.id = a.entitlement_id
                  JOIN source_record sr ON sr.org_id = a.org_id AND sr.identity_id = a.identity_id
                 WHERE a.org_id = :orgId
                   AND sr.external_id = :employeeNumber
                   AND a.state IN ('PENDING', 'ACTIVE')
                """)
                .param("orgId", ORG)
                .param("employeeNumber", employeeNumber)
                .query(String.class)
                .list());
    }

    private List<String> access(String stateCondition) {
        return tenants.inTenant(ORG, () -> jdbc
                .sql("SELECT a.source_detail || '/' || e.native_value"
                        + " FROM access_assignment a"
                        + " JOIN entitlement e ON e.org_id = a.org_id AND e.id = a.entitlement_id"
                        + " WHERE a.org_id = :orgId AND " + stateCondition)
                .param("orgId", ORG)
                .query(String.class)
                .list());
    }

    private List<String> assignmentIds() {
        return tenants.inTenant(ORG, () -> jdbc
                .sql("SELECT id || '/' || state || '/' || updated_at FROM access_assignment"
                        + " WHERE org_id = :orgId ORDER BY id")
                .param("orgId", ORG)
                .query(String.class)
                .list());
    }

    private int identityCount() {
        return tenants.inTenant(ORG, () -> jdbc
                .sql("SELECT count(*) FROM identity WHERE org_id = :orgId")
                .param("orgId", ORG)
                .query(Integer.class)
                .single());
    }

    private IdentityStatus statusOf(String employeeNumber) {
        return tenants.inTenant(ORG, () -> IdentityStatus.valueOf(jdbc.sql("""
                SELECT i.status
                  FROM identity i
                  JOIN source_record sr ON sr.org_id = i.org_id AND sr.identity_id = i.id
                 WHERE i.org_id = :orgId AND sr.external_id = :employeeNumber
                """)
                .param("orgId", ORG)
                .param("employeeNumber", employeeNumber)
                .query(String.class)
                .single()));
    }

    /**
     * Written as text rather than serialised from a record, for the reason
     * {@code LiteralJsonSourceReader} gives: a payload built from this project's
     * own classes can only be the payload this code already knows how to
     * produce.
     */
    private static String employee(
            String number,
            String department,
            String jobTitle,
            String location,
            String employmentType,
            String hireDate,
            String terminationDate) {
        return """
                {"employeeNumber":"%s","firstName":"Lucia","lastName":"Garcia",\
                "email":"%s@lineward.example","department":"%s","jobTitle":"%s",\
                "location":"%s","costCenter":"CC-001","employmentType":"%s",\
                "hireDate":"%s","terminationDate":%s,"managerEmployeeNumber":null,\
                "lastModifiedAt":"2026-10-01T00:00:00Z"}"""
                .formatted(
                        number,
                        number.toLowerCase(),
                        department,
                        jobTitle,
                        location,
                        employmentType,
                        hireDate,
                        terminationDate == null ? "null" : "\"" + terminationDate + "\"");
    }
}
