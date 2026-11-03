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
package com.lineward.connector.ldap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lineward.connector.AccountStatus;
import com.lineward.connector.ConnectionCheck;
import com.lineward.connector.ConnectorException;
import com.lineward.connector.ConnectorPage;
import com.lineward.connector.ConnectorType;
import com.lineward.connector.DiscoveredAccount;
import com.lineward.connector.DiscoveredEntitlement;
import com.lineward.connector.ReadCursor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.NoPermissionException;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * The connector against a real OpenLDAP, which is the only place the decisions
 * of this task can be proved.
 *
 * <p>The directory under test is the one this repository ships: the tree in
 * {@code infra/ldap/import} and the access rules in {@code infra/ldap/init},
 * both mounted into the container. That is deliberate and it is not
 * convenience. Those two files are part of the delivery: the first one holds the
 * thirteen group names V003 already seeded, and the second one is the only
 * reason this directory does not hand its password hashes to anybody who asks.
 * A test that built its own directory would pass while the shipped one was
 * broken.
 *
 * <p>Local only in the sense that it needs a Docker daemon, like every other
 * integration test here. It costs one directory start for the whole class.
 */
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LdapConnectorIntegrationTests {

    /**
     * OpenLDAP 2.6.10, and the image is the awkward part of this task rather
     * than the version. The upstream project publishes nothing current, the
     * vendor that did moved its catalogue and left this repository frozen, and
     * the most downloaded alternative has not tagged a stable release since
     * 2021. Frozen and real beat current and a release candidate for something
     * that only ever runs on a laptop and in this test. See ADR-006.
     */
    private static final String OPENLDAP_IMAGE = "bitnamilegacy/openldap:2.6.10-debian-12-r4";

    private static final String ROOT = "dc=lineward,dc=example";
    private static final String USERS = "ou=users," + ROOT;
    private static final String GROUPS = "ou=groups," + ROOT;
    private static final String AGGREGATOR = "cn=lineward-aggregator,ou=services," + ROOT;

    /**
     * Test only values. They live for the life of one container and authorise
     * nothing outside it. Neither shipped file carries a password at all: the
     * tree has none anywhere, and the reader's password reaches the directory
     * from the environment, which is the control this test also exercises.
     */
    private static final String ADMIN_DN = "cn=admin," + ROOT;

    private static final String ADMIN_PASSWORD = "integration-test-admin";

    private static final String READER_PASSWORD = "integration-test-reader";

    /** The attribute this directory marks a locked account with. */
    private static final String LOCK_MARKER = "pwdAccountLockedTime";

    /** Relative to the backend module, which is where Maven puts the working directory. */
    private static final Path TREE_DIR = Path.of("../infra/ldap/import");

    private static final Path INIT_DIR = Path.of("../infra/ldap/init");

    private static final int ACCOUNTS_IN_DIRECTORY = 12;

    /**
     * The catalogue, character for character as V003 seeded it.
     *
     * <p>Copied from the migration on purpose rather than read from it: this is
     * the assertion that the connector reports permissions by the names the
     * database already holds, which is what keeps the first drift report from
     * being thirteen false findings.
     */
    private static final List<String> SEEDED_GROUPS = List.of(
            "cn=all-staff,ou=groups,dc=lineward,dc=example",
            "cn=managers,ou=groups,dc=lineward,dc=example",
            "cn=remote-vpn,ou=groups,dc=lineward,dc=example",
            "cn=dept-engineering,ou=groups,dc=lineward,dc=example",
            "cn=dept-operations,ou=groups,dc=lineward,dc=example",
            "cn=dept-treasury,ou=groups,dc=lineward,dc=example",
            "cn=dept-risk,ou=groups,dc=lineward,dc=example",
            "cn=dept-finance,ou=groups,dc=lineward,dc=example",
            "cn=dept-procurement,ou=groups,dc=lineward,dc=example",
            "cn=dept-information-security,ou=groups,dc=lineward,dc=example",
            "cn=dept-human-resources,ou=groups,dc=lineward,dc=example",
            "cn=dept-legal-and-compliance,ou=groups,dc=lineward,dc=example",
            "cn=dept-internal-audit,ou=groups,dc=lineward,dc=example");

    @Container
    static final GenericContainer<?> OPENLDAP = new GenericContainer<>(OPENLDAP_IMAGE)
            .withEnv("LDAP_ROOT", ROOT)
            .withEnv("LDAP_ADMIN_USERNAME", "admin")
            .withEnv("LDAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
            // Nothing but the shipped tree decides what this directory holds.
            .withEnv("LDAP_SKIP_DEFAULT_TREE", "yes")
            // Without this the attribute that marks a locked account does not
            // exist and the tree is rejected with "attribute type undefined".
            .withEnv("LDAP_CONFIGURE_PPOLICY", "yes")
            .withEnv("LINEWARD_LDAP_READER_PASSWORD", READER_PASSWORD)
            .withCopyFileToContainer(theShippedTree(), "/ldifs/")
            .withCopyFileToContainer(theShippedAccessRules(), "/docker-entrypoint-initdb.d/")
            .withExposedPorts(1389)
            // The last line of the startup, which comes after the tree has been
            // loaded and after the access rules have been applied, so waiting on
            // it makes both a precondition rather than a race.
            .waitingFor(Wait.forLogMessage(".*slapd starting.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    private static String url;

    @BeforeAll
    static void readTheAddress() {
        url = "ldap://" + OPENLDAP.getHost() + ":" + OPENLDAP.getMappedPort(1389);
    }

    /**
     * Mounts the tree this repository publishes, and refuses to run if it is not
     * there.
     *
     * <p>In the field initialiser and not in a {@code @BeforeAll}, which is the
     * mistake this project already made once: Testcontainers starts the
     * container while the field is initialised, so a guard in a lifecycle method
     * runs after the server has already failed, and what the reader gets is
     * three minutes of waiting and "Wait strategy failed".
     */
    private static MountableFile theShippedTree() {
        Path tree = TREE_DIR.resolve("01-lineward-tree.ldif");
        if (!Files.isRegularFile(tree)) {
            throw new IllegalStateException("no directory tree at " + tree.toAbsolutePath()
                    + ". This test mounts the files this repository ships; run it from the backend module"
                    + " directory, which is where Maven puts the working directory");
        }
        String content = read(tree);
        if (!content.contains("cn=dept-treasury,ou=groups,dc=lineward,dc=example")) {
            throw new IllegalStateException("the shipped tree must name the groups V003 seeded, and " + tree
                    + " no longer does: the catalogue the connector reports would not be the one in the database");
        }
        if (content.contains("userPassword")) {
            throw new IllegalStateException(
                    "the shipped tree holds a password, and it must not: " + tree.toAbsolutePath());
        }
        return MountableFile.forHostPath(tree, 0444);
    }

    private static MountableFile theShippedAccessRules() {
        Path rules = INIT_DIR.resolve("01-access-rules.sh");
        if (!Files.isRegularFile(rules)) {
            throw new IllegalStateException("no access rules at " + rules.toAbsolutePath()
                    + ". Without them the directory under test serves every password hash to anybody,"
                    + " and the security tests below would be measuring nothing");
        }
        if (!read(rules).contains("olcAccess")) {
            throw new IllegalStateException("the shipped access rules no longer declare any: " + rules);
        }
        // Executable on purpose: the image sources a hook it cannot execute,
        // which would run this script's "set -e" inside its own shell.
        return MountableFile.forHostPath(rules, 0555);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException("could not read " + file.toAbsolutePath(), unreadable);
        }
    }

    private static LdapCredentials credentials() {
        return new LdapCredentials(url, USERS, GROUPS, AGGREGATOR, READER_PASSWORD, LOCK_MARKER);
    }

    /** Reads a whole paged read into one list, the way an aggregation would. */
    private static <T> List<T> readEverything(PageReader<T> reader) {
        List<T> all = new ArrayList<>();
        ConnectorPage<T> page = reader.read(null);
        int pages = 1;
        all.addAll(page.items());
        while (!page.exhausted()) {
            page = reader.read(page.next());
            all.addAll(page.items());
            pages++;
            if (pages > 50) {
                throw new IllegalStateException("this read is not ending, which is the bug it exists to catch");
            }
        }
        return all;
    }

    private interface PageReader<T> {
        ConnectorPage<T> read(ReadCursor from);
    }

    private static DiscoveredAccount accountNamed(List<DiscoveredAccount> accounts, String username) {
        return accounts.stream()
                .filter(account -> username.equals(account.username()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no account named " + username + " in " + accounts.size()
                        + " read from the directory"));
    }

    @Nested
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class Reading_accounts {

        @Test
        void reads_every_account_the_directory_holds() {
            try (LdapConnector connector = new LdapConnector(credentials())) {
                List<DiscoveredAccount> accounts = readEverything(from -> connector.readAccounts(from, 5));

                assertThat(accounts).hasSize(ACCOUNTS_IN_DIRECTORY);
                assertThat(accounts).extracting(DiscoveredAccount::username)
                        .contains("a.ferrero", "j.adeyemi", "K. O'Hara (contractor)", "svc-payroll (2011");
            }
        }

        @Test
        void reads_everything_one_account_carries() {
            try (LdapConnector connector = new LdapConnector(credentials())) {
                DiscoveredAccount account =
                        accountNamed(readEverything(from -> connector.readAccounts(from, 5)), "a.ferrero");

                assertThat(account.nativeId()).isNotBlank();
                assertThat(account.email()).isEqualTo("alba.ferrero@lineward.example");
                assertThat(account.displayName()).isEqualTo("Alba Ferrero");
                assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
                assertThat(account.createdInTargetAt()).isNotNull();
                assertThat(account.attributes().get("employeeNumber")).containsExactly("E-0001");
            }
        }

        /**
         * The end of a read is the server saying so, and it says so by handing
         * back an empty cookie. Twelve accounts in pages of six end in two
         * pages and not in three: there is no trailing empty page to read as
         * the end, and no short page to guess from either, which is the
         * opposite of what the Keycloak connector has to do.
         */
        @Test
        void ends_when_the_directory_says_so_even_when_the_pages_divide_exactly() {
            try (LdapConnector connector = new LdapConnector(credentials())) {
                ConnectorPage<DiscoveredAccount> first = connector.readAccounts(null, 6);
                assertThat(first.items()).hasSize(6);
                assertThat(first.exhausted()).isFalse();

                ConnectorPage<DiscoveredAccount> second = connector.readAccounts(first.next(), 6);
                assertThat(second.items()).hasSize(6);
                assertThat(second.exhausted()).as("the second page is the last one").isTrue();
            }
        }

        @Test
        void reports_a_locked_account_as_locked_and_the_rest_as_active() {
            try (LdapConnector connector = new LdapConnector(credentials())) {
                List<DiscoveredAccount> accounts = readEverything(from -> connector.readAccounts(from, 12));

                assertThat(accountNamed(accounts, "i.sorensen").status()).isEqualTo(AccountStatus.LOCKED);
                assertThat(accountNamed(accounts, "a.ferrero").status()).isEqualTo(AccountStatus.ACTIVE);
            }
        }

        @Test
        void says_it_cannot_tell_a_status_when_nobody_configured_a_lock_marker() {
            LdapCredentials withoutMarker =
                    new LdapCredentials(url, USERS, GROUPS, AGGREGATOR, READER_PASSWORD, null);

            try (LdapConnector connector = new LdapConnector(withoutMarker)) {
                assertThat(readEverything(from -> connector.readAccounts(from, 12)))
                        .extracting(DiscoveredAccount::status)
                        .containsOnly(AccountStatus.UNKNOWN);
            }
        }

        /**
         * No password hash reaches an account's attributes.
         *
         * <p>Two independent things hold this up and only one of them is
         * measured here: the shipped access rules, which refuse to serve
         * {@code userPassword} to this credential at all and are proved by
         * {@code cannot_read_a_password_hash} below, and the connector's named
         * attribute list. The access rules alone are enough, so this assertion
         * does not by itself prove the list; the list has its own test in
         * {@code LdapEntriesTests}.
         */
        @Test
        void never_carries_a_password_into_the_attributes_of_an_account() {
            try (LdapConnector connector = new LdapConnector(credentials())) {
                assertThat(readEverything(from -> connector.readAccounts(from, 12)))
                        .allSatisfy(account ->
                                assertThat(account.attributes()).doesNotContainKey("userPassword"));
            }
        }

        @Test
        void refuses_a_page_size_that_is_not_a_page() {
            try (LdapConnector connector = new LdapConnector(credentials())) {
                assertThatThrownBy(() -> connector.readAccounts(null, 0))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Nested
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class Reading_who_holds_what {

        @Test
        void carries_the_groups_that_hold_each_account() {
            try (LdapConnector connector = new LdapConnector(credentials())) {
                DiscoveredAccount account =
                        accountNamed(readEverything(from -> connector.readAccounts(from, 12)), "a.ferrero");

                assertThat(account.entitlementValues())
                        .containsExactlyInAnyOrder(
                                "cn=all-staff,ou=groups,dc=lineward,dc=example",
                                "cn=remote-vpn,ou=groups,dc=lineward,dc=example",
                                "cn=dept-treasury,ou=groups,dc=lineward,dc=example");
            }
        }

        /**
         * The reason the membership filter takes its argument through a
         * placeholder instead of being pasted together.
         *
         * <p>{@code cn=svc-payroll (2011} is a name a migration truncated, and
         * its bracket never closes. That is legal in a distinguished name and
         * illegal in a search filter: pasted in, the filter cannot be parsed and
         * the server answers "Bad search filter", so this account's memberships
         * cannot be read at all. Watched failing exactly that way on
         * 2026-11-03 with the placeholder removed.
         *
         * <p>It also happens to belong to no group, which is the second half of
         * the assertion: an empty list and not a failure, because an account
         * nobody granted anything is the orphan a first aggregation looks for.
         */
        @Test
        void reads_the_memberships_of_an_account_whose_name_would_break_a_filter() {
            try (LdapConnector connector = new LdapConnector(credentials())) {
                DiscoveredAccount account =
                        accountNamed(readEverything(from -> connector.readAccounts(from, 12)), "svc-payroll (2011");

                assertThat(account.entitlementValues()).isEmpty();
            }
        }

        /** Brackets and an apostrophe in a name, which break a hand built filter outright. */
        @Test
        void reads_the_groups_of_an_account_whose_name_is_awkward() {
            try (LdapConnector connector = new LdapConnector(credentials())) {
                DiscoveredAccount account = accountNamed(
                        readEverything(from -> connector.readAccounts(from, 12)), "K. O'Hara (contractor)");

                assertThat(account.entitlementValues())
                        .containsExactlyInAnyOrder(
                                "cn=all-staff,ou=groups,dc=lineward,dc=example",
                                "cn=dept-engineering,ou=groups,dc=lineward,dc=example");
            }
        }
    }

    @Nested
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class Reading_the_catalogue {

        /** The assertion that ties this connector to the migration already in the database. */
        @Test
        void names_every_permission_the_way_V003_already_named_it() {
            try (LdapConnector connector = new LdapConnector(credentials())) {
                List<DiscoveredEntitlement> catalogue =
                        readEverything(from -> connector.readEntitlements(from, 5));

                assertThat(catalogue)
                        .extracting(DiscoveredEntitlement::nativeValue)
                        .containsExactlyInAnyOrderElementsOf(SEEDED_GROUPS);
            }
        }

        @Test
        void shows_a_readable_name_and_a_description_beside_the_distinguished_name() {
            try (LdapConnector connector = new LdapConnector(credentials())) {
                DiscoveredEntitlement treasury =
                        readEverything((PageReader<DiscoveredEntitlement>) from -> connector.readEntitlements(from, 5))
                                .stream()
                                .filter(entitlement -> entitlement
                                        .nativeValue()
                                        .equals("cn=dept-treasury,ou=groups,dc=lineward,dc=example"))
                                .findFirst()
                                .orElseThrow();

                assertThat(treasury.displayName()).isEqualTo("dept-treasury");
                assertThat(treasury.description()).isEqualTo("Department group for Treasury.");
            }
        }

        @Test
        void reads_the_catalogue_in_pages_too() {
            try (LdapConnector connector = new LdapConnector(credentials())) {
                ConnectorPage<DiscoveredEntitlement> first = connector.readEntitlements(null, 5);

                assertThat(first.items()).hasSize(5);
                assertThat(first.exhausted()).isFalse();
            }
        }
    }

    @Nested
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class The_cursor_that_cannot_outlive_its_connection {

        /**
         * The finding that shaped this connector, stated as a test so that it
         * cannot quietly stop being true: a cursor from a read that is no longer
         * open is refused, and never answered with page one.
         */
        @Test
        void refuses_a_cursor_from_a_read_that_is_no_longer_open() {
            try (LdapConnector connector = new LdapConnector(credentials())) {
                ReadCursor fromTheFirstRead = connector.readAccounts(null, 4).next();
                assertThat(fromTheFirstRead).isNotNull();

                // Starting a new read ends the first one, exactly as abandoning
                // it would.
                connector.readAccounts(null, 4);

                assertThatThrownBy(() -> connector.readAccounts(fromTheFirstRead, 4))
                        .isInstanceOf(ConnectorException.class)
                        .hasMessageContaining("no longer open");
            }
        }

        @Test
        void refuses_a_cursor_it_never_issued() {
            try (LdapConnector connector = new LdapConnector(credentials())) {
                connector.readAccounts(null, 4);

                assertThatThrownBy(() -> connector.readAccounts(new ReadCursor("page-two-please"), 4))
                        .isInstanceOf(ConnectorException.class);
            }
        }

        /** Two reads of different kinds do not borrow each other's cursors. */
        @Test
        void keeps_the_two_reads_apart() {
            try (LdapConnector connector = new LdapConnector(credentials())) {
                ReadCursor accounts = connector.readAccounts(null, 4).next();

                assertThatThrownBy(() -> connector.readEntitlements(accounts, 4))
                        .isInstanceOf(ConnectorException.class);
            }
        }
    }

    @Nested
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class Testing_the_connection {

        @Test
        void says_what_it_reached_and_as_whom() {
            try (LdapConnector connector = new LdapConnector(credentials())) {
                ConnectionCheck check = connector.testConnection();

                assertThat(check.reachable()).isTrue();
                assertThat(check.detail()).contains(USERS).contains(AGGREGATOR);
                assertThat(check.detail())
                        .as("a line a person reads must never carry the credential it used")
                        .doesNotContain(READER_PASSWORD);
            }
        }

        @Test
        void reports_a_wrong_password_as_unreachable_rather_than_failing() {
            LdapCredentials wrong =
                    new LdapCredentials(url, USERS, GROUPS, AGGREGATOR, "not-the-password", LOCK_MARKER);

            try (LdapConnector connector = new LdapConnector(wrong)) {
                assertThat(connector.testConnection().reachable()).isFalse();
            }
        }

        /**
         * Reachable means readable. A bind succeeds for an account that cannot
         * see a thing, so a check that stopped there would call a directory
         * healthy until the first aggregation came back empty.
         */
        @Test
        void reports_a_credential_that_cannot_read_as_unreachable() {
            LdapCredentials cannotRead =
                    new LdapCredentials(url, "cn=config", GROUPS, AGGREGATOR, READER_PASSWORD, LOCK_MARKER);

            try (LdapConnector connector = new LdapConnector(cannotRead)) {
                assertThat(connector.testConnection().reachable()).isFalse();
            }
        }

        @Test
        void is_an_ldap_connector_and_says_so() {
            try (LdapConnector connector = new LdapConnector(credentials())) {
                assertThat(connector.type()).isEqualTo(ConnectorType.LDAP);
            }
        }

        @Test
        void keeps_the_password_out_of_its_own_printout() {
            assertThat(credentials().toString()).doesNotContain(READER_PASSWORD).contains("<redacted>");
        }
    }

    /**
     * What the account Lineward reads with is not allowed to do.
     *
     * <p>These go through the directory API directly and not through the
     * connector, because what is under test is the shipped configuration and not
     * this project's code.
     *
     * <p>Which of them the shipped rules are actually responsible for, measured
     * on 2026-11-03 by running this class against a directory with those rules
     * removed, because three of the four turned out to pass either way:
     *
     * <ul>
     * <li><b>the password and the anonymous client: the shipped rules.</b> With
     *     the rules removed, slapd falls back to letting everybody read
     *     everything and both of these succeed
     * <li><b>writing: slapd's own default.</b> A credential that is not the
     *     database's rootdn cannot write whether the rules exist or not. Kept
     *     because the refusal is what matters, not which layer refuses
     * <li><b>the configuration database: the image.</b> It gives cn=config an
     *     access rule of its own. Kept for the same reason
     * </ul>
     *
     * <p>The password test needs an entry that has one, and the shipped tree
     * deliberately has none, so it creates one as the admin and then checks both
     * directions: hidden from the reader, and present when the rootdn asks. The
     * second half is what keeps it from passing against an attribute that was
     * never there, which is how it passed for the wrong reason at first.
     */
    @Nested
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class What_the_reader_is_not_allowed_to_do {

        @Test
        void cannot_read_a_password_hash() throws NamingException {
            String withAPassword = "uid=has-a-password," + USERS;
            givenAnEntryWithAPassword(withAPassword);

            SearchResult asTheReader =
                    oneResult(AGGREGATOR, READER_PASSWORD, USERS, "(uid=has-a-password)", "userPassword");
            SearchResult asTheAdmin =
                    oneResult(ADMIN_DN, ADMIN_PASSWORD, USERS, "(uid=has-a-password)", "userPassword");

            assertThat(asTheReader.getAttributes().get("userPassword"))
                    .as("the directory must not hand a password hash to the account that aggregates it")
                    .isNull();
            assertThat(asTheAdmin.getAttributes().get("userPassword"))
                    .as("and the hash has to be there, or the assertion above is measuring an empty attribute")
                    .isNotNull();
        }

        /** Through the admin, because the account that aggregates cannot write. */
        private void givenAnEntryWithAPassword(String dn) throws NamingException {
            DirContext directory = bind(ADMIN_DN, ADMIN_PASSWORD);
            try {
                BasicAttributes attributes = new BasicAttributes();
                BasicAttribute classes = new BasicAttribute("objectClass");
                classes.add("inetOrgPerson");
                attributes.put(classes);
                attributes.put(new BasicAttribute("uid", "has-a-password"));
                attributes.put(new BasicAttribute("cn", "Has a password"));
                attributes.put(new BasicAttribute("sn", "Password"));
                attributes.put(new BasicAttribute("userPassword", "integration-test-only"));
                directory.createSubcontext(dn, attributes);
            } catch (javax.naming.NameAlreadyBoundException alreadyThere) {
                // Another test in this class created it. Either way it is there.
            } finally {
                directory.close();
            }
        }

        @Test
        void cannot_write_anything() {
            assertThatThrownBy(() -> {
                        DirContext directory = bind(AGGREGATOR, READER_PASSWORD);
                        try {
                            directory.modifyAttributes(
                                    "uid=a.ferrero," + USERS,
                                    new ModificationItem[] {
                                        new ModificationItem(
                                                DirContext.REPLACE_ATTRIBUTE,
                                                new BasicAttribute("mail", "elsewhere@lineward.example"))
                                    });
                        } finally {
                            directory.close();
                        }
                    })
                    // The type and not the wording: the directory answers
                    // error 50, which the directory API turns into exactly this
                    // exception, and asserting its English would be asserting
                    // the JDK's phrasing rather than the refusal.
                    .isInstanceOf(NoPermissionException.class);
        }

        @Test
        void cannot_see_the_configuration_of_the_directory() {
            assertThatThrownBy(() -> oneResult(AGGREGATOR, READER_PASSWORD, "cn=config", "(objectClass=*)", "dn"))
                    .isInstanceOf(NamingException.class);
        }

        @Test
        void and_a_client_that_does_not_bind_at_all_is_served_nothing() {
            assertThatThrownBy(() -> oneResult(null, null, USERS, "(uid=a.ferrero)", "uid"))
                    .isInstanceOf(NamingException.class);
        }

        private SearchResult oneResult(
                String bindDn, String password, String base, String filter, String... attributes)
                throws NamingException {
            DirContext directory = bind(bindDn, password);
            try {
                SearchControls controls = new SearchControls();
                controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
                controls.setReturningAttributes(attributes);
                NamingEnumeration<SearchResult> found = directory.search(base, filter, controls);
                if (!found.hasMore()) {
                    return new SearchResult("", null, new BasicAttributes());
                }
                return found.next();
            } finally {
                directory.close();
            }
        }

        private DirContext bind(String bindDn, String password) throws NamingException {
            Hashtable<String, String> environment = new Hashtable<>();
            environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            environment.put(Context.PROVIDER_URL, url);
            if (bindDn == null) {
                environment.put(Context.SECURITY_AUTHENTICATION, "none");
            } else {
                environment.put(Context.SECURITY_AUTHENTICATION, "simple");
                environment.put(Context.SECURITY_PRINCIPAL, bindDn);
                environment.put(Context.SECURITY_CREDENTIALS, password);
            }
            return new InitialDirContext(environment);
        }
    }
}
