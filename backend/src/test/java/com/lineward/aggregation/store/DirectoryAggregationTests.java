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
import com.lineward.connector.ldap.LdapConnector;
import com.lineward.connector.ldap.LdapCredentials;
import com.lineward.identity.ingest.HrIngestionRunner;
import com.lineward.identity.query.JdbcIdentityLookup;
import com.lineward.identity.query.JdbcIdentityResolution;
import com.lineward.identity.source.LiteralJsonSourceReader;
import com.lineward.shared.TenantTransactions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole chain, against the directory this repository ships: a real
 * directory read by the real connector into the real schema, and every employee
 * given their account.
 *
 * <p>This is the done criterion of KAN-11 and the only test that can show it.
 * The others each prove one piece against documents a test wrote; what nothing
 * proved is that the pieces meet reality: that the employee numbers the shipped
 * tree carries are the ones the HR source asserts, that the thirteen group
 * names the connector reads are the thirteen V003 seeded, and that the two
 * accounts the tree deliberately plants without an owner come out of it as
 * findings rather than as errors.
 *
 * <p>It reaches for {@code LdapConnector} by name, which production code may
 * not do, and the reason is the mapped port: the address of a container is not
 * knowable from the configuration this repository ships, so there is nothing
 * for {@code TargetSystemConnectors} to answer with. What the shipped
 * configuration builds is covered where it belongs, in
 * {@code ConfiguredConnectorsTests}.
 *
 * <p>Two containers and one start each for the class, which makes it the most
 * expensive test here. It earns it: this is the one that would notice that the
 * platform does not actually work.
 */
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DirectoryAggregationTests {

    private static final String OPENLDAP_IMAGE = "bitnamilegacy/openldap:2.6.10-debian-12-r4";

    private static final String ROOT = "dc=lineward,dc=example";
    private static final String USERS = "ou=users," + ROOT;
    private static final String GROUPS = "ou=groups," + ROOT;
    private static final String AGGREGATOR = "cn=lineward-aggregator,ou=services," + ROOT;
    private static final String READER_PASSWORD = "integration-test-reader";
    private static final String LOCK_MARKER = "pwdAccountLockedTime";

    private static final String OWNER_USER = "lineward_owner";
    private static final String OWNER_PASSWORD = "lineward_owner_local";
    private static final String APP_USER = "lineward_app";
    private static final String APP_PASSWORD = "lineward_app_local";

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private static final Instant NOW = Instant.parse("2026-11-04T09:00:00Z");

    /** Relative to the backend module, which is where Maven puts the working directory. */
    private static final Path TREE_DIR = Path.of("../infra/ldap/import");

    private static final Path INIT_DIR = Path.of("../infra/ldap/init");

    /**
     * What the shipped tree holds, counted from the file. Twelve accounts, of
     * which ten carry an employee number from E-0001 to E-0010; thirteen groups,
     * which are the thirteen V003 seeded; and twenty seven memberships between
     * them.
     */
    private static final int ACCOUNTS_IN_DIRECTORY = 12;

    private static final int GROUPS_IN_DIRECTORY = 13;

    private static final int EMPLOYEES_WITH_A_NUMBER = 10;

    /**
     * The two the tree plants without an owner, and it says why: a contractor
     * nobody put in HR and a service account whose name the 2011 payroll
     * migration truncated. They are the orphans a first aggregation is run to
     * find.
     *
     * <p>Named by what the directory calls them, not by their distinguished
     * name, because the connector stores {@code entryUUID} as the native id and
     * the directory generates that: it is the one thing a test cannot know in
     * advance. The connector is right to do it, since a distinguished name can
     * be renamed and an account keyed on its name would come back as one
     * deleted and one created.
     */
    private static final String CONTRACTOR = "K. O'Hara (contractor)";

    private static final String PAYROLL_SERVICE = "svc-payroll (2011";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6")
            .withInitScript("db/test-roles.sql");

    @Container
    static final GenericContainer<?> OPENLDAP = new GenericContainer<>(OPENLDAP_IMAGE)
            .withEnv("LDAP_ROOT", ROOT)
            .withEnv("LDAP_ADMIN_USERNAME", "admin")
            .withEnv("LDAP_ADMIN_PASSWORD", "integration-test-admin")
            .withEnv("LDAP_SKIP_DEFAULT_TREE", "yes")
            .withEnv("LDAP_CONFIGURE_PPOLICY", "yes")
            .withEnv("LINEWARD_LDAP_READER_PASSWORD", READER_PASSWORD)
            .withCopyFileToContainer(theShippedTree(), "/ldifs/")
            .withCopyFileToContainer(theShippedAccessRules(), "/docker-entrypoint-initdb.d/")
            .withExposedPorts(1389)
            .waitingFor(Wait.forLogMessage(".*slapd starting.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    private static JdbcClient jdbc;
    private static TenantTransactions tenants;
    private static GovernedApplication directory;
    private static String url;

    @BeforeAll
    static void migrateAndReadTheAddress() {
        url = "ldap://" + OPENLDAP.getHost() + ":" + OPENLDAP.getMappedPort(1389);

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
        directory = tenants.inTenant(ORG, () -> jdbc.sql("""
                SELECT id, code, name, connector_type
                  FROM application
                 WHERE org_id = :orgId AND code = 'corporate-directory'
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
    void emptyBothSidesAndIngestThePopulation() {
        tenants.runInTenant(ORG, () -> {
            jdbc.sql("DELETE FROM account_entitlement").update();
            jdbc.sql("DELETE FROM account").update();
            jdbc.sql("DELETE FROM access_assignment").update();
            jdbc.sql("DELETE FROM identity_responsibility").update();
            jdbc.sql("DELETE FROM source_record").update();
            jdbc.sql("DELETE FROM identity").update();
        });

        // The emails deliberately do not match the directory's. Every
        // correlation here therefore has to come from the employee number, which
        // is the rule this tree exists to exercise: the addresses in a directory
        // and the addresses in an HR extract agree until the day somebody
        // marries, and a platform that depends on them agreeing is a platform
        // that loses an account per wedding.
        LiteralJsonSourceReader hr = new LiteralJsonSourceReader();
        hr.serving(IntStream.rangeClosed(1, EMPLOYEES_WITH_A_NUMBER)
                .mapToObj(number -> employee("E-%04d".formatted(number)))
                .toArray(String[]::new));
        HrIngestionRunner.create(
                        hr, jdbc, tenants, Clock.fixed(NOW, ZoneOffset.UTC), event -> {}, ORG, "hr", 100)
                .ingestEverything();
    }

    @Test
    void gives_every_employee_of_the_shipped_directory_the_account_that_is_theirs() {
        AggregationReport report = aggregate(NOW);

        assertThat(report.reachable()).isTrue();
        assertThat(report.accountsSeen()).isEqualTo(ACCOUNTS_IN_DIRECTORY);
        assertThat(report.accountsCreated()).isEqualTo(ACCOUNTS_IN_DIRECTORY);
        assertThat(report.entitlementsSeen()).isEqualTo(GROUPS_IN_DIRECTORY);
        assertThat(report.correlation().linked()).isEqualTo(EMPLOYEES_WITH_A_NUMBER);
        assertThat(report.correlation().ambiguous()).isZero();
        assertThat(correlatedAccounts()).isEqualTo(EMPLOYEES_WITH_A_NUMBER);
        assertThat(ruleCodesUsed())
                .as("the number did the work, because no address in this tree is in the extract")
                .containsExactly("employee-number");
    }

    /**
     * The thirteen group names the connector reads are the thirteen V003 seeded,
     * and nothing had to be registered. {@code ShippedCatalogueTests} asserts
     * the same agreement against the file; this asserts it against a running
     * directory, which is the one that cannot be fooled by a copy.
     */
    @Test
    void registers_nothing_new_because_the_catalogue_already_names_what_the_directory_holds() {
        int before = entitlementsOfTheDirectory();

        aggregate(NOW);

        assertThat(entitlementsOfTheDirectory()).isEqualTo(before).isEqualTo(GROUPS_IN_DIRECTORY);
    }

    /**
     * The two the tree plants on purpose. Neither carries a number or an
     * address, so neither can be claimed, and that is the finding: a contractor
     * nobody registered and a service account that has outlived whoever created
     * it in 2011.
     */
    @Test
    void leaves_the_accounts_nobody_can_claim_as_the_finding_they_are() {
        AggregationReport report = aggregate(NOW);

        assertThat(report.correlation().unmatched())
                .isEqualTo(ACCOUNTS_IN_DIRECTORY - EMPLOYEES_WITH_A_NUMBER);
        assertThat(unclaimedAccounts()).containsExactlyInAnyOrder(CONTRACTOR, PAYROLL_SERVICE);
    }

    /**
     * The property a nightly run depends on, through every piece at once: the
     * connector, the catalogue, the writer and the correlation. A second read
     * of an unchanged directory creates nothing, grants nothing, takes nothing
     * away and changes nobody's owner.
     *
     * <p>It does still report the two accounts it cannot place, and the first
     * version of this test expected otherwise. That expectation came from the
     * report's own javadoc, which claimed all four correlation counts were
     * changes; two of them are not. An account that belongs to nobody is still
     * there in the morning, and a run that stopped mentioning it would be
     * hiding the finding rather than being idempotent.
     */
    @Test
    void reads_the_same_directory_again_and_changes_nothing() {
        AggregationReport first = aggregate(NOW);

        AggregationReport second = aggregate(NOW.plusSeconds(86400));

        assertThat(first.grantsAdded()).as("the memberships the tree holds").isPositive();
        assertThat(second.accountsCreated()).isZero();
        assertThat(second.accountsUpdated()).isEqualTo(ACCOUNTS_IN_DIRECTORY);
        assertThat(second.grantsAdded()).isZero();
        assertThat(second.grantsRemoved()).isZero();
        assertThat(second.correlation().linked()).as("nobody gains an owner twice").isZero();
        assertThat(second.correlation().unlinked()).as("and nobody loses one").isZero();
        assertThat(second.correlation().ambiguous()).isZero();
        assertThat(second.correlation().unmatched())
                .as("the orphans do not go away by being read again")
                .isEqualTo(first.correlation().unmatched());
    }

    private AggregationReport aggregate(Instant now) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        try (LdapConnector connector = new LdapConnector(
                new LdapCredentials(url, USERS, GROUPS, AGGREGATOR, READER_PASSWORD, LOCK_MARKER))) {
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
                    .read(directory, connector);
        }
    }

    private int correlatedAccounts() {
        return tenants.inTenant(ORG, () -> jdbc.sql(
                        "SELECT count(*) FROM account WHERE org_id = :orgId AND identity_id IS NOT NULL")
                .param("orgId", ORG)
                .query(Integer.class)
                .single());
    }

    private List<String> ruleCodesUsed() {
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                SELECT DISTINCT correlation_rule_code
                  FROM account
                 WHERE org_id = :orgId AND correlation_rule_code IS NOT NULL
                """)
                .param("orgId", ORG)
                .query(String.class)
                .list());
    }

    /** The accounts nobody owns, by the name a person reading the finding would recognise. */
    private List<String> unclaimedAccounts() {
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                SELECT display_name
                  FROM account
                 WHERE org_id = :orgId AND identity_id IS NULL
                """)
                .param("orgId", ORG)
                .query(String.class)
                .list());
    }

    private int entitlementsOfTheDirectory() {
        return tenants.inTenant(ORG, () -> jdbc.sql("""
                SELECT count(*)
                  FROM entitlement
                 WHERE org_id = :orgId AND application_id = :applicationId
                """)
                .param("orgId", ORG)
                .param("applicationId", directory.id())
                .query(Integer.class)
                .single());
    }

    /** One HR record, with an address that is deliberately not the directory's. */
    private static String employee(String number) {
        return """
                {"employeeNumber":"%s","firstName":"Given","lastName":"Family",\
                "email":"%s@hr.lineward.example","department":"Treasury","jobTitle":"Treasury Analyst",\
                "location":"Madrid","costCenter":"CC-001","employmentType":"EMPLOYEE",\
                "hireDate":"2020-01-01","terminationDate":null,"managerEmployeeNumber":null,\
                "lastModifiedAt":"2026-10-01T00:00:00Z"}"""
                .formatted(number, number.toLowerCase());
    }

    private static MountableFile theShippedTree() {
        Path tree = TREE_DIR.resolve("01-lineward-tree.ldif");
        if (!Files.isRegularFile(tree)) {
            throw new IllegalStateException("no directory tree at " + tree.toAbsolutePath()
                    + ". This test mounts the files this repository ships; run it from the backend module"
                    + " directory, which is where Maven puts the working directory");
        }
        if (!read(tree).contains("employeeNumber: E-0001")) {
            throw new IllegalStateException("the shipped tree must carry the employee numbers the HR extract"
                    + " asserts, and " + tree + " no longer does: every correlation here would be measuring"
                    + " nothing");
        }
        return MountableFile.forHostPath(tree, 0444);
    }

    private static MountableFile theShippedAccessRules() {
        Path rules = INIT_DIR.resolve("01-access-rules.sh");
        if (!Files.isRegularFile(rules)) {
            throw new IllegalStateException("no access rules at " + rules.toAbsolutePath()
                    + ". Without them the directory under test would not have the reader this"
                    + " aggregation binds as");
        }
        // Executable on purpose: the image sources a hook it cannot execute.
        return MountableFile.forHostPath(rules, 0555);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new IllegalStateException("could not read " + file.toAbsolutePath(), unreadable);
        }
    }
}
