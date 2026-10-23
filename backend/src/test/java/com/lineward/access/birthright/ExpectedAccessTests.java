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

import com.lineward.access.birthright.BirthrightRule.Grant;
import com.lineward.access.birthright.BirthrightRules.DueAccess;
import com.lineward.access.birthright.ExpectedAccessWriter.Outcome;
import com.lineward.shared.TenantTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Expected access against the real schema, the real indexes and the real
 * application role.
 *
 * <p>What needs a database here is everything the rules cannot tell you: that
 * the second run of an unchanged identity writes nothing, that a move back after
 * a revocation is allowed by the partial unique index rather than rejected by
 * it, that two reasons for one entitlement are two rows, and that a recalculation
 * does not touch an assignment some other origin put there. The last one is the
 * failure that would be invisible in a unit test and expensive in production.
 *
 * <p>The rules used are written here rather than loaded from the shipped file,
 * so that a change to the file cannot make these assertions mean something else.
 * The one test that does read the file is the one about the file.
 */
@Testcontainers
class ExpectedAccessTests {

    private static final String OWNER_USER = "lineward_owner";
    private static final String OWNER_PASSWORD = "lineward_owner_local";
    private static final String APP_USER = "lineward_app";
    private static final String APP_PASSWORD = "lineward_app_local";

    /** Inserted by V002, so the test uses the organization the installation has. */
    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private static final Instant NOW = Instant.parse("2026-10-23T09:00:00Z");

    private static final String ALL_STAFF = "cn=all-staff,ou=groups,dc=lineward,dc=example";
    private static final String DEPT_TREASURY = "cn=dept-treasury,ou=groups,dc=lineward,dc=example";
    private static final String DEPT_RISK = "cn=dept-risk,ou=groups,dc=lineward,dc=example";

    private static final DueAccess BASE_PACKAGE =
            new DueAccess("corporate-directory", ALL_STAFF, "base-package");
    private static final DueAccess TREASURY_GROUP =
            new DueAccess("corporate-directory", DEPT_TREASURY, "department-treasury");
    private static final DueAccess RISK_GROUP =
            new DueAccess("corporate-directory", DEPT_RISK, "department-risk");
    private static final DueAccess TREASURY_READ =
            new DueAccess("intranet", "treasury-read", "treasury-read");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6")
            .withInitScript("db/test-roles.sql");

    private static JdbcClient jdbc;
    private static TenantTransactions tenants;
    private static UUID hrSourceSystemId;

    private final ExpectedAccessWriter writer = new ExpectedAccessWriter(
            jdbc, new EntitlementCatalog(jdbc), Clock.fixed(NOW, ZoneOffset.UTC));

    private UUID identityId;

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
    void oneTreasuryAnalyst() {
        tenants.runInTenant(ORG, () -> {
            jdbc.sql("DELETE FROM access_assignment").update();
            jdbc.sql("DELETE FROM identity").update();
        });
        identityId = tenants.inTenant(ORG, () -> jdbc.sql("""
                INSERT INTO identity (
                    org_id, type, status, source_system_id, display_name,
                    department, job_title, location, cost_center, employment_type)
                VALUES (
                    :orgId, 'HUMAN', 'ACTIVE', :sourceSystemId, 'Lucia Garcia',
                    'Treasury', 'Treasury Analyst', 'Madrid', 'TRE-001', 'EMPLOYEE')
                RETURNING id
                """)
                .param("orgId", ORG)
                .param("sourceSystemId", hrSourceSystemId)
                .query(UUID.class)
                .single());
    }

    @Test
    void writesWhatAJoinerIsDueAsAccessNothingHasProvisionedYet() {
        Outcome outcome = reconcile(BASE_PACKAGE, TREASURY_GROUP, TREASURY_READ);

        assertThat(outcome).isEqualTo(new Outcome(3, 0));
        assertThat(liveAssignments()).containsExactlyInAnyOrder(
                "base-package/" + ALL_STAFF + "/PENDING",
                "department-treasury/" + DEPT_TREASURY + "/PENDING",
                "treasury-read/treasury-read/PENDING");
        // Nothing has given the access, so no row may claim a time it was given.
        assertThat(timestampsSet("granted_at"))
                .as("granted_at says when the access was really given, and nothing gave it")
                .isEmpty();
    }

    /**
     * The property that makes a failed run safe to repeat, and the one that stops
     * a nightly recalculation from granting the same access every night. Asserted
     * on the rows and not only on the counters: the updated_at trigger moves on
     * any write, so a row that was touched cannot hide.
     */
    @Test
    void writesNothingWhenNothingChanged() {
        reconcile(BASE_PACKAGE, TREASURY_GROUP);
        List<String> after = rowFingerprints();

        Outcome again = reconcile(BASE_PACKAGE, TREASURY_GROUP);

        assertThat(again).isEqualTo(Outcome.NOTHING);
        assertThat(again.changedAnything()).isFalse();
        assertThat(rowFingerprints()).isEqualTo(after);
    }

    /**
     * The mover, and the half of it that gets forgotten. The new department's
     * group arrives, the old one is retired, and the base package is not touched
     * because it never stopped being due.
     */
    @Test
    void swapsWhatAMoverGainsForWhatTheyLose() {
        reconcile(BASE_PACKAGE, TREASURY_GROUP, TREASURY_READ);

        Outcome outcome = reconcile(BASE_PACKAGE, RISK_GROUP);

        assertThat(outcome).isEqualTo(new Outcome(1, 2));
        assertThat(liveAssignments()).containsExactlyInAnyOrder(
                "base-package/" + ALL_STAFF + "/PENDING",
                "department-risk/" + DEPT_RISK + "/PENDING");
        assertThat(revokedAssignments()).containsExactlyInAnyOrder(
                "department-treasury/" + DEPT_TREASURY,
                "treasury-read/treasury-read");
        assertThat(timestampsSet("revoked_at")).containsOnly(NOW);
    }

    /**
     * The leaver, which is the same subtraction with an empty left hand side.
     * There is no leaver branch in the writer and this is the test that says so.
     */
    @Test
    void retiresEverythingWhenNothingIsDue() {
        reconcile(BASE_PACKAGE, TREASURY_GROUP, TREASURY_READ);

        Outcome outcome = reconcile();

        assertThat(outcome).isEqualTo(new Outcome(0, 3));
        assertThat(liveAssignments()).isEmpty();
        assertThat(revokedAssignments()).hasSize(3);
    }

    /**
     * Somebody who moves to Risk and comes back. The partial unique index covers
     * live states only, which is what lets the second grant exist alongside the
     * revoked first one. If it covered every state, this would fail on the insert
     * and a returning employee would be an error in the middle of an ingestion.
     */
    @Test
    void grantsAnAccessAgainAfterItWasRetired() {
        reconcile(TREASURY_GROUP);
        reconcile(RISK_GROUP);

        Outcome outcome = reconcile(TREASURY_GROUP);

        assertThat(outcome).isEqualTo(new Outcome(1, 1));
        assertThat(liveAssignments()).containsExactly("department-treasury/" + DEPT_TREASURY + "/PENDING");
        assertThat(assignmentCount()).isEqualTo(3);
    }

    /**
     * Two rules granting the same entitlement are two reasons for it, so two
     * rows, and retiring one leaves the access in place. It is the shape the
     * schema already describes for a birthright grant and an approved request
     * coexisting, one level down.
     */
    @Test
    void keepsTwoReasonsForOneEntitlementApart() {
        DueAccess sameGroupAnotherRule =
                new DueAccess("corporate-directory", DEPT_TREASURY, "treasury-leavers-watch");

        reconcile(TREASURY_GROUP, sameGroupAnotherRule);
        assertThat(liveAssignments()).hasSize(2);

        Outcome outcome = reconcile(TREASURY_GROUP);

        assertThat(outcome).isEqualTo(new Outcome(0, 1));
        assertThat(liveAssignments()).containsExactly("department-treasury/" + DEPT_TREASURY + "/PENDING");
    }

    /**
     * The negative control that matters most, because the failure it catches is
     * invisible until somebody loses access they were granted on purpose. Phase 3
     * writes REQUEST and ROLE rows into this same table; a recalculation that
     * read them as its own would revoke an approved request for the sole reason
     * that no birthright rule happens to grant it.
     */
    @Test
    void leavesAnAssignmentFromAnotherOriginAlone() {
        UUID requested = insertAssignment("REQUEST", "REQ-2026-0001", DEPT_RISK);

        Outcome outcome = reconcile(BASE_PACKAGE);

        assertThat(outcome).isEqualTo(new Outcome(1, 0));
        assertThat(stateOf(requested)).isEqualTo("PENDING");
    }

    /**
     * A rule pointing at an entitlement nobody registered. It fails naming the
     * rule, and it fails before writing anything, because the half of a
     * recalculation that happened to resolve is a worse outcome than none of it.
     */
    @Test
    void failsNamingTheRuleWhenAGrantIsNotInTheCatalogue() {
        DueAccess missing = new DueAccess("intranet", "treasury-write", "treasury-write");

        assertThatThrownBy(() -> reconcile(BASE_PACKAGE, missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("treasury-write")
                .hasMessageContaining("not in the entitlement catalogue");

        assertThat(liveAssignments()).isEmpty();
    }

    /**
     * Every grant in the shipped rule file exists in the catalogue the migrations
     * create. This is the check ADR-013 decision 10 is counting on: rules in the
     * repository are tested by the build, so a rule that points at nothing is
     * found here rather than on somebody's first day.
     */
    @Test
    void everyShippedRuleGrantsSomethingThatExists() {
        EntitlementCatalog catalogue = new EntitlementCatalog(jdbc);
        Set<Grant> shipped = ShippedBirthrightRules.load().everyGrant();

        assertThat(shipped).isNotEmpty();
        tenants.runInTenant(ORG, () -> assertThat(shipped)
                .allSatisfy(grant -> assertThat(catalogue.contains(ORG, grant))
                        .as("birthright-rules.yaml grants %s, which V003 does not register", grant)
                        .isTrue()));
    }

    private Outcome reconcile(DueAccess... due) {
        return tenants.inTenant(ORG, () -> writer.reconcile(ORG, identityId, Set.of(due)));
    }

    /** Rule, entitlement and state of every live assignment, as one readable string. */
    private List<String> liveAssignments() {
        return query("""
                SELECT a.source_detail || '/' || e.native_value || '/' || a.state
                  FROM access_assignment a
                  JOIN entitlement e ON e.org_id = a.org_id AND e.id = a.entitlement_id
                 WHERE a.org_id = :orgId
                   AND a.identity_id = :identityId
                   AND a.source = 'BIRTHRIGHT'
                   AND a.state IN ('PENDING', 'ACTIVE')
                """);
    }

    private List<String> revokedAssignments() {
        return query("""
                SELECT a.source_detail || '/' || e.native_value
                  FROM access_assignment a
                  JOIN entitlement e ON e.org_id = a.org_id AND e.id = a.entitlement_id
                 WHERE a.org_id = :orgId
                   AND a.identity_id = :identityId
                   AND a.state = 'REVOKED'
                """);
    }

    /** Everything that a write would move, so that an untouched row can be proven untouched. */
    private List<String> rowFingerprints() {
        return query("""
                SELECT id || '/' || state || '/' || updated_at
                  FROM access_assignment
                 WHERE org_id = :orgId AND identity_id = :identityId
                 ORDER BY id
                """);
    }

    private List<String> query(String sql) {
        return tenants.inTenant(ORG, () -> jdbc.sql(sql)
                .param("orgId", ORG)
                .param("identityId", identityId)
                .query(String.class)
                .list());
    }

    /**
     * The values this column actually holds, nulls left out, so that an empty
     * result means no row filled it in. The column name is this project's own
     * literal and never comes from anywhere a caller could influence.
     */
    private List<Instant> timestampsSet(String column) {
        return tenants.inTenant(ORG, () -> jdbc
                .sql("SELECT " + column + " FROM access_assignment"
                        + " WHERE org_id = :orgId AND identity_id = :identityId"
                        + " AND " + column + " IS NOT NULL")
                .param("orgId", ORG)
                .param("identityId", identityId)
                .query(Instant.class)
                .list());
    }

    private int assignmentCount() {
        return tenants.inTenant(ORG, () -> jdbc
                .sql("SELECT count(*) FROM access_assignment"
                        + " WHERE org_id = :orgId AND identity_id = :identityId")
                .param("orgId", ORG)
                .param("identityId", identityId)
                .query(Integer.class)
                .single());
    }

    private String stateOf(UUID assignmentId) {
        return tenants.inTenant(ORG, () -> jdbc
                .sql("SELECT state FROM access_assignment WHERE org_id = :orgId AND id = :id")
                .param("orgId", ORG)
                .param("id", assignmentId)
                .query(String.class)
                .single());
    }

    private UUID insertAssignment(String source, String sourceDetail, String nativeValue) {
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                INSERT INTO access_assignment (
                    org_id, identity_id, entitlement_id, source, source_detail, state)
                SELECT :orgId, :identityId, e.id, :source, :sourceDetail, 'PENDING'
                  FROM entitlement e
                 WHERE e.org_id = :orgId AND e.native_value = :nativeValue
                RETURNING id
                """)
                .param("orgId", ORG)
                .param("identityId", identityId)
                .param("source", source)
                .param("sourceDetail", sourceDetail)
                .param("nativeValue", nativeValue)
                .query(UUID.class)
                .single());
    }
}
