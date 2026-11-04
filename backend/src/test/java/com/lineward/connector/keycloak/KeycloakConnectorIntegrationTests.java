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
package com.lineward.connector.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lineward.connector.AccountKind;
import com.lineward.connector.ConnectionCheck;
import com.lineward.connector.ConnectorException;
import com.lineward.connector.ConnectorPage;
import com.lineward.connector.DiscoveredAccount;
import com.lineward.connector.DiscoveredEntitlement;
import com.lineward.connector.ReadCursor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The connector against a real Keycloak, which is the only place the two
 * decisions of this task can actually be proved.
 *
 * <p>The realm under test is the one this repository ships,
 * {@code infra/keycloak/import/lineward-realm.json}, mounted into the container
 * and imported at startup. That is deliberate: the realm file is not scaffolding
 * for the test, it is part of the delivery, and the attribute that correlation
 * will match on only survives because that file declares it. A test that built
 * its own realm would pass while the shipped one was broken.
 *
 * <p>Local only in the sense that it needs a Docker daemon, like every other
 * integration test here. It costs one Keycloak start for the whole class.
 */
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class KeycloakConnectorIntegrationTests {

    /** ADR-006's version, and the one every document in {@link KeycloakDocumentsTests} came from. */
    private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.7.5";

    private static final String REALM = "lineward";
    private static final String AGGREGATOR = "lineward-aggregator";

    /**
     * Test only values. They are not secrets being committed: they exist for the
     * life of one container and authorise nothing outside it. The shipped realm
     * file carries no secret at all, only the name of the variable Keycloak
     * substitutes, which is the control this test also exercises.
     */
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASSWORD = "integration-test-admin";
    private static final String AGGREGATOR_SECRET = "integration-test-aggregator";

    /** Relative to the backend module, which is where Maven puts the working directory. */
    private static final Path REALM_FILE = Path.of("../infra/keycloak/import/lineward-realm.json");

    /** The people created below. Deliberately an even number, so that a page of four divides evenly. */
    private static final int PEOPLE_IN_REALM = 8;

    /**
     * One, and it is the connector's own: {@code lineward-aggregator} is the
     * only client in the shipped realm with {@code serviceAccountsEnabled}.
     * Counted against a running Keycloak 26.7.5 on 2026-11-04 rather than
     * assumed: the realm also carries the six clients Keycloak creates itself
     * ({@code account}, {@code account-console}, {@code admin-cli},
     * {@code broker}, {@code realm-management},
     * {@code security-admin-console}), and none of those has one.
     *
     * <p>That Lineward's own reader turns up in its own read is not a flaw to
     * filter out. It is the first useful finding of the machine pass: the
     * platform that governs non-human identities holds one, and an aggregation
     * that hid it would be making an exception for the account most worth
     * seeing.
     */
    private static final int SERVICE_ACCOUNTS_IN_REALM = 1;

    /** What a complete read returns, which is both passes and not just the user list. */
    private static final int ACCOUNTS_IN_REALM = PEOPLE_IN_REALM + SERVICE_ACCOUNTS_IN_REALM;

    @Container
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(KEYCLOAK_IMAGE)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN_USER)
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withEnv("LINEWARD_AGGREGATOR_SECRET", AGGREGATOR_SECRET)
            .withCopyFileToContainer(theShippedRealmFile(), "/opt/keycloak/data/import/lineward-realm.json")
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(8080)
            // The realm's own discovery document and not the master realm's: it
            // is only served once the import has finished, so waiting on it is
            // what makes the import a precondition rather than a race.
            .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                    .forPort(8080)
                    .forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3));

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final RestClient HTTP = RestClient.create();

    private static String baseUrl;
    private static String adminToken;

    private KeycloakConnector connector() {
        return new KeycloakConnector(credentials(AGGREGATOR_SECRET));
    }

    private static KeycloakCredentials credentials(String secret) {
        return new KeycloakCredentials(baseUrl, REALM, AGGREGATOR, secret);
    }

    /**
     * Guards the guard, the way {@code PackageReadingOrderTests} does, and
     * deliberately not from a {@code @BeforeAll}.
     *
     * <p>Found by breaking it: pointing this at a file that does not exist made
     * Testcontainers copy nothing, Keycloak start with no realm, and the wait
     * strategy time out after three minutes with "container startup failed",
     * which is exactly the misleading failure the check exists to prevent. A
     * {@code @BeforeAll} cannot prevent it, because the container field is
     * initialised and started before any such method runs. Called from the field
     * initialiser instead, so a missing or secret bearing realm file fails here,
     * immediately, saying which.
     */
    private static MountableFile theShippedRealmFile() {
        if (!Files.isRegularFile(REALM_FILE)) {
            throw new IllegalStateException("no realm file at " + REALM_FILE.toAbsolutePath()
                    + ". This test mounts the file this repository ships; run it from the backend module"
                    + " directory, which is where Maven puts the working directory");
        }
        String content;
        try {
            content = Files.readString(REALM_FILE);
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException("could not read " + REALM_FILE.toAbsolutePath(), unreadable);
        }
        if (!content.contains("${LINEWARD_AGGREGATOR_SECRET}")) {
            throw new IllegalStateException("the shipped realm file must name the variable Keycloak substitutes"
                    + " rather than carry a client secret, and " + REALM_FILE + " no longer does");
        }
        return MountableFile.forHostPath(REALM_FILE);
    }

    @BeforeAll
    static void populateTheRealm() {
        baseUrl = "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
        adminToken = adminToken();

        for (int number = 1; number <= PEOPLE_IN_REALM; number++) {
            admin("POST", "/admin/realms/" + REALM + "/users", """
                    {"username":"probe.user%d","enabled":%s,"firstName":"Probe%d","lastName":"User",
                     "email":"probe.user%d@lineward.example",
                     "attributes":{"employeeNumber":["E-%04d"],"costCenter":["CC-PRB-1","CC-PRB-2"]}}"""
                    .formatted(number, number == 7 ? "false" : "true", number, number, number));
        }

        // A top-level group whose leaf name is also the leaf name of a nested
        // one, which is what makes the path rule testable rather than asserted.
        admin("POST", "/admin/realms/" + REALM + "/groups", "{\"name\":\"payment-approve\"}");

        // One user in both of the colliding groups, so that an account holding
        // them is not ambiguous either.
        String userId = idOfUser("probe.user1");
        joinGroup(userId, groupIdAtPath("/payment-approve"));
        joinGroup(userId, groupIdAtPath("/intranet/payment-approve"));
    }

    @Nested
    class Reading_the_accounts {

        /**
         * Six requests for nine accounts, and the arithmetic is the point.
         *
         * <p>Three pages walk the eight people, and three more walk the seven
         * clients to find the one service account among them. A read that
         * costs a request per three clients to return a single account looks
         * wasteful until the alternative is written down: the connector would
         * have to keep asking for clients inside one call until the page
         * filled, which in a realm with thousands of clients and no service
         * accounts is an unbounded loop the caller cannot interrupt and whose
         * progress it cannot save. A short page with a cursor is the honest
         * answer, because it says both how far the read got and that there is
         * more.
         */
        @Test
        void walks_the_whole_realm_once_when_paging() {
            List<String> seen = new ArrayList<>();
            int pages = 0;
            ReadCursor cursor = null;
            do {
                ConnectorPage<DiscoveredAccount> page = connector().readAccounts(cursor, 3);
                page.items().forEach(account -> seen.add(account.nativeId()));
                cursor = page.next();
                pages++;
            } while (cursor != null);

            assertThat(seen).hasSize(ACCOUNTS_IN_REALM).doesNotHaveDuplicates();
            assertThat(pages).isEqualTo(6);
        }

        /**
         * The page that proves emptiness is not an ending here.
         *
         * <p>The machine pass pages over clients and most clients have no
         * service account, so its first page of three returns nothing at all.
         * A caller that read the end from an empty list would stop there and
         * report a realm with no machine accounts as complete, which is the
         * worst available outcome: every service account would look deleted,
         * and a reconciliation acting on that would be acting on an absence
         * that was never real. The ending lives in the cursor and nowhere
         * else.
         */
        @Test
        void does_not_mean_the_end_when_a_machine_page_comes_back_empty() {
            ReadCursor intoTheMachines = handoverCursor();

            ConnectorPage<DiscoveredAccount> firstMachinePage = connector().readAccounts(intoTheMachines, 3);

            assertThat(firstMachinePage.items()).isEmpty();
            assertThat(firstMachinePage.exhausted()).isFalse();
            assertThat(firstMachinePage.next()).isNotNull();
        }

        /**
         * Walks the people pass to its end and returns the cursor it hands
         * over, rather than writing {@code machines:0} into the test. The token
         * is the connector's private business, and a test that spelled it would
         * pass after a change that broke every caller holding a stored cursor.
         */
        private ReadCursor handoverCursor() {
            ReadCursor cursor = null;
            int people = 0;
            while (people < PEOPLE_IN_REALM) {
                ConnectorPage<DiscoveredAccount> read = connector().readAccounts(cursor, PEOPLE_IN_REALM);
                people += read.items().size();
                cursor = read.next();
                assertThat(cursor).as("the people pass ended before all the people were read").isNotNull();
            }
            return cursor;
        }

        /**
         * The reason this connector asks Keycloak for one row more than it
         * needs. Eight people in pages of four divide evenly, so a connector
         * that only learned the end by receiving nothing would ask for a third
         * page of users and get an empty one back.
         *
         * <p>What that extra row buys is visible in the cursor rather than in
         * the page: the second page of people hands over to the machine pass,
         * which is the connector saying it already knows the user list is
         * finished. Before the machine pass existed this test asserted the read
         * itself was over here, and that assertion would now be wrong in the
         * expensive direction, because one service account would go unread and
         * the read would still call itself complete.
         */
        @Test
        void knows_the_user_list_is_finished_when_the_count_divides_evenly() {
            ReadCursor afterFirst = connector().readAccounts(null, 4).next();
            assertThat(afterFirst).isNotNull();

            ConnectorPage<DiscoveredAccount> second = connector().readAccounts(afterFirst, 4);

            assertThat(second.items()).hasSize(4);
            // Not the end of the read, and the difference matters: there is no
            // third request for users, and there is a request for clients.
            assertThat(second.exhausted()).isFalse();
            assertThat(second.next()).isEqualTo(handoverCursor());
        }

        @Test
        void resumes_exactly_where_the_cursor_left_off() {
            ConnectorPage<DiscoveredAccount> first = connector().readAccounts(null, 2);
            ConnectorPage<DiscoveredAccount> second = connector().readAccounts(first.next(), 2);

            assertThat(second.items()).extracting(DiscoveredAccount::nativeId)
                    .doesNotContainAnyElementsOf(first.items().stream().map(DiscoveredAccount::nativeId).toList());
            // Reading the same cursor twice gives the same page, which is what
            // makes a resume after a crash safe rather than a gamble.
            assertThat(connector().readAccounts(first.next(), 2).items())
                    .extracting(DiscoveredAccount::nativeId)
                    .isEqualTo(second.items().stream().map(DiscoveredAccount::nativeId).toList());
        }

        /**
         * The whole reason the realm file exists. Keycloak drops any attribute
         * the realm's user profile does not declare, and it drops it when the
         * account is written, so a realm configured afterwards does not recover
         * them. Without the shipped profile this assertion fails and
         * correlation by employee number in KAN-11 would have nothing to match
         * on.
         */
        @Test
        void reads_the_attributes_the_shipped_realm_profile_declares() {
            DiscoveredAccount user = accountNamed("probe.user3");

            assertThat(user.attributes().get("employeeNumber")).containsExactly("E-0003");
            assertThat(user.attributes().get("costCenter")).containsExactly("CC-PRB-1", "CC-PRB-2");
        }

        @Test
        void reads_a_disabled_account_as_disabled() {
            assertThat(accountNamed("probe.user7").status().name()).isEqualTo("DISABLED");
            assertThat(accountNamed("probe.user1").status().name()).isEqualTo("ACTIVE");
        }

        @Test
        void leaves_the_last_activity_unset_because_keycloak_does_not_report_it() {
            assertThat(accountNamed("probe.user1").lastActivityAt()).isNull();
        }

        @Test
        void reports_when_the_target_system_says_the_account_was_created() {
            assertThat(accountNamed("probe.user1").createdInTargetAt()).isNotNull();
        }

        /**
         * The blind spot KAN-7 pinned down with a test asserting its absence,
         * now closed, which is why that test is gone and these are here.
         *
         * <p>Keycloak's user list does not return client service accounts. It is
         * a filter on the endpoint and not a missing right: KAN-7 verified that
         * the full administrator credential gets the same empty answer. So they
         * are reached the only way they can be, through the clients endpoint,
         * which needs {@code view-clients} on top of {@code view-users}.
         *
         * <p>It matters because a service account is exactly the non-human
         * identity this platform exists to govern, so leaving them out was a gap
         * in the product's own claim and not a quirk of a target system.
         */
        @Test
        void reads_the_service_account_of_a_client_that_has_one() {
            assertThat(allAccounts()).extracting(DiscoveredAccount::username)
                    .contains("service-account-" + AGGREGATOR);
        }

        /**
         * The reason the kind is on the account and not derived later: only the
         * connector knows where it read this from.
         *
         * <p>A reader downstream could guess from the {@code service-account-}
         * prefix, and that would be putting a Keycloak naming convention into a
         * module that must not know what Keycloak is. Worse, it would be a guess
         * about a name an administrator can change.
         */
        @Test
        void says_a_service_account_is_not_a_person() {
            assertThat(accountNamed("service-account-" + AGGREGATOR).kind())
                    .isEqualTo(AccountKind.SERVICE);
        }

        /**
         * And the other half, which is the one worth arguing about: an ordinary
         * user comes back {@code UNKNOWN} and never {@code PERSON}.
         *
         * <p>Keycloak asserts nothing about whether a user is a human. Somebody
         * can create a robot as an ordinary user, and plenty of organizations
         * do. {@code UNKNOWN} is the truth, and a {@code PERSON} value would be
         * a claim no target system supports, which is why the enum does not have
         * one.
         */
        @Test
        void does_not_claim_an_ordinary_user_is_a_person() {
            assertThat(accountNamed("probe.user1").kind()).isEqualTo(AccountKind.UNKNOWN);
        }

        @Test
        void names_the_memberships_of_an_account_by_path() {
            assertThat(accountNamed("probe.user1").entitlementValues())
                    .containsExactlyInAnyOrder("/payment-approve", "/intranet/payment-approve");
        }
    }

    @Nested
    class Reading_the_catalogue {

        /**
         * The finding that shaped this connector.
         * {@code GET /admin/realms/{realm}/groups} serves only top level groups
         * and reports {@code subGroups} as empty even when there are children,
         * so a flat read of this realm returns four of its seven groups. The
         * three it would miss are the ones under {@code /intranet}, and missing
         * them is not a smaller catalogue: it is three permissions that
         * reconciliation would later decide had been deleted.
         */
        @Test
        void finds_every_group_including_the_nested_ones() {
            assertThat(allEntitlements()).extracting(DiscoveredEntitlement::nativeValue)
                    .containsExactlyInAnyOrder(
                            "/employee",
                            "/contractor",
                            "/intranet",
                            "/intranet/treasury-read",
                            "/intranet/supplier-create",
                            "/intranet/payment-approve",
                            "/payment-approve");
        }

        @Test
        void names_a_permission_by_its_path_so_that_a_repeated_leaf_name_is_two_permissions() {
            List<String> values = allEntitlements().stream()
                    .map(DiscoveredEntitlement::nativeValue)
                    .filter(value -> value.endsWith("payment-approve"))
                    .toList();

            assertThat(values).containsExactlyInAnyOrder("/payment-approve", "/intranet/payment-approve");
            assertThat(allEntitlements()).extracting(DiscoveredEntitlement::nativeValue).doesNotHaveDuplicates();
        }

        @Test
        void shows_the_leaf_name_as_the_display_name() {
            assertThat(allEntitlements())
                    .filteredOn(entitlement -> entitlement.nativeValue().equals("/intranet/treasury-read"))
                    .extracting(DiscoveredEntitlement::displayName)
                    .containsExactly("treasury-read");
        }

        /**
         * A page of this read is a page of top level groups with each subtree
         * expanded into it, so asking for one returns more than one. The SPI
         * allows that on purpose: size is a request and not a promise.
         */
        @Test
        void expands_a_whole_subtree_into_the_page_that_carries_its_root() {
            ConnectorPage<DiscoveredEntitlement> page = connector().readEntitlements(null, 1);

            assertThat(page.items()).hasSize(1);
            assertThat(page.next()).isNotNull();

            List<String> everything = new ArrayList<>();
            ReadCursor cursor = null;
            do {
                ConnectorPage<DiscoveredEntitlement> each = connector().readEntitlements(cursor, 1);
                each.items().forEach(entitlement -> everything.add(entitlement.nativeValue()));
                cursor = each.next();
            } while (cursor != null);

            assertThat(everything).hasSize(7).doesNotHaveDuplicates();
        }

        @Test
        void reads_the_catalogue_whether_or_not_anybody_holds_it() {
            // Nobody is in /contractor, and it is still a permission a request
            // can ask for in M5.
            assertThat(allEntitlements()).extracting(DiscoveredEntitlement::nativeValue).contains("/contractor");
        }
    }

    @Nested
    class Reaching_the_realm {

        @Test
        void describes_what_it_reached_and_as_whom() {
            ConnectionCheck check = connector().testConnection();

            assertThat(check.reachable()).isTrue();
            assertThat(check.detail()).contains(REALM).contains(AGGREGATOR);
        }

        @Test
        void never_puts_the_secret_in_the_detail_it_shows_a_person() {
            assertThat(connector().testConnection().detail()).doesNotContain(AGGREGATOR_SECRET);
        }

        /** An unreachable system is an answer, so this must not throw. */
        @Test
        void reports_a_refused_credential_without_throwing() {
            ConnectionCheck check =
                    new KeycloakConnector(credentials("not-the-secret")).testConnection();

            assertThat(check.reachable()).isFalse();
            assertThat(check.detail()).doesNotContain("not-the-secret");
        }

        @Test
        void reports_an_unreachable_host_without_throwing() {
            KeycloakCredentials nowhere =
                    new KeycloakCredentials("http://127.0.0.1:1", REALM, AGGREGATOR, AGGREGATOR_SECRET);

            assertThat(new KeycloakConnector(nowhere).testConnection().reachable()).isFalse();
        }

        /**
         * Why the detail carries no server version, written as the fact rather
         * than as the intention.
         *
         * <p>The status code is the trap here: {@code /admin/serverinfo} answers
         * this read only credential with 200, so a check that only looked at the
         * code would conclude the version was available. What comes back omits
         * {@code systemInfo} entirely, which is where the version lives, and
         * only a full administrator sees it.
         */
        @Test
        void cannot_read_the_server_version_even_though_the_endpoint_answers_it() {
            assertThat(statusOfAggregatorCall("GET", "/admin/serverinfo", null))
                    .as("it answers, which is what makes the omission easy to miss")
                    .isEqualTo(200);
            assertThat(bodyOfAggregatorCall("GET", "/admin/serverinfo"))
                    .as("and withholds the block the version lives in")
                    .doesNotContain("\"systemInfo\"");
            assertThat(connector().testConnection().detail()).doesNotContain("26.7.5");
        }
    }

    @Nested
    class The_rights_the_realm_grants {

        /**
         * Minimum privilege as a fact about the shipped realm and not as an
         * intention in a comment. The connector exposes no write operation by
         * construction, and this says the credential behind it would be refused
         * even if one appeared.
         */
        @Test
        void refuses_to_create_an_account_with_the_aggregators_credential() {
            assertThat(statusOfAggregatorCall(
                            "POST", "/admin/realms/" + REALM + "/users", "{\"username\":\"should-not-exist\"}"))
                    .isEqualTo(403);
        }

        @Test
        void refuses_to_delete_an_account_with_the_aggregators_credential() {
            String victim = idOfUser("probe.user2");

            assertThat(statusOfAggregatorCall("DELETE", "/admin/realms/" + REALM + "/users/" + victim, null))
                    .isEqualTo(403);
            assertThat(allAccounts()).extracting(DiscoveredAccount::username).contains("probe.user2");
        }

        @Test
        void allows_the_reads_the_connector_actually_makes() {
            assertThat(statusOfAggregatorCall("GET", "/admin/realms/" + REALM + "/users?max=1", null))
                    .isEqualTo(200);
            assertThat(statusOfAggregatorCall("GET", "/admin/realms/" + REALM + "/groups?max=1", null))
                    .isEqualTo(200);
        }
    }

    @Nested
    class Refusing_what_it_cannot_honour {

        /**
         * A size of zero would make the connector ask for one row, keep none of
         * it, and report that there is more behind, for ever. An aggregation
         * would neither finish nor fail, which is the worst of the three
         * outcomes.
         */
        @Test
        void refuses_a_page_size_that_cannot_make_progress() {
            assertThatThrownBy(() -> connector().readAccounts(null, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot make progress");
            assertThatThrownBy(() -> connector().readEntitlements(null, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot make progress");
        }

        /**
         * Restarting from zero and calling it a resume would read part of the
         * population twice and report the result as a complete picture, which
         * reconciliation would act on.
         */
        @Test
        void refuses_a_cursor_it_did_not_issue() {
            assertThatThrownBy(() -> connector().readAccounts(new ReadCursor("Mg==cookie"), 10))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessageContaining("not issued by the Keycloak connector");
        }

        @Test
        void refuses_to_be_built_without_a_credential() {
            assertThatThrownBy(() -> new KeycloakCredentials(baseUrl, REALM, AGGREGATOR, "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("clientSecret");
        }

        /** A record's generated toString would have published the secret. */
        @Test
        void keeps_the_secret_out_of_its_own_string_form() {
            assertThat(credentials(AGGREGATOR_SECRET).toString())
                    .doesNotContain(AGGREGATOR_SECRET)
                    .contains("<redacted>")
                    .contains(AGGREGATOR);
        }

        /**
         * The control this whole half turns on, and the one that decides whether
         * a wider credential is safe to ask for.
         *
         * <p>Reaching service accounts needs {@code view-clients}. A realm that
         * grants only {@code view-users} answers the clients endpoint with 403,
         * and the tempting thing to do is to carry on and return the users: the
         * read succeeds, the counts look plausible, nobody sees an error.
         *
         * <p>That is the worst available behaviour. The SPI's own rule is that a
         * finished read is a complete picture of the target system, so a read
         * that silently dropped every service account would make each one look
         * deleted, and the reconciliation of KAN-12 would report the whole set
         * as access to revoke. A loud failure costs an aggregation. A quiet one
         * costs the credentials of every machine in the organization.
         */
        @Test
        void refuses_to_report_a_read_that_could_not_see_the_clients() {
            String narrowRealm = "lineward-narrow-probe";
            try {
                admin("POST", "/admin/realms", "{\"realm\":\"%s\",\"enabled\":true}".formatted(narrowRealm));
                admin("POST", "/admin/realms/" + narrowRealm + "/clients", """
                        {"clientId":"%s","enabled":true,"protocol":"openid-connect","publicClient":false,
                         "serviceAccountsEnabled":true,"standardFlowEnabled":false,
                         "directAccessGrantsEnabled":false,"secret":"%s"}"""
                        .formatted(AGGREGATOR, AGGREGATOR_SECRET));
                grantRealmManagementRoles(narrowRealm, "view-users");

                KeycloakConnector narrow = new KeycloakConnector(
                        new KeycloakCredentials(baseUrl, narrowRealm, AGGREGATOR, AGGREGATOR_SECRET));

                assertThatThrownBy(() -> readEverything(narrow))
                        .as("a read that cannot see the clients is incomplete, and saying otherwise"
                                + " would make every service account look deleted")
                        .isInstanceOf(ConnectorException.class)
                        .hasMessageContaining("view-clients");
            } finally {
                admin("DELETE", "/admin/realms/" + narrowRealm, null);
            }
        }

        /**
         * A realm nested deeper than the walk allows ends the read with a
         * {@link ConnectorException} and never with a {@link StackOverflowError},
         * which would kill the thread with no usable message and look like a
         * defect in Lineward rather than a property of the target system.
         *
         * <p>Built in a realm of its own so that the counts the rest of this
         * class asserts stay true.
         */
        @Test
        void refuses_a_group_tree_deeper_than_it_can_walk() {
            String deepRealm = "lineward-deep-probe";
            try {
                admin("POST", "/admin/realms", "{\"realm\":\"%s\",\"enabled\":true}".formatted(deepRealm));
                admin("POST", "/admin/realms/" + deepRealm + "/clients", """
                        {"clientId":"%s","enabled":true,"protocol":"openid-connect","publicClient":false,
                         "serviceAccountsEnabled":true,"standardFlowEnabled":false,
                         "directAccessGrantsEnabled":false,"secret":"%s"}"""
                        .formatted(AGGREGATOR, AGGREGATOR_SECRET));
                grantRealmManagementRoles(deepRealm, "view-users");

                String parent = createGroup(deepRealm, null, "level-0");
                for (int depth = 1; depth <= 70; depth++) {
                    parent = createGroup(deepRealm, parent, "level-" + depth);
                }

                KeycloakConnector deep =
                        new KeycloakConnector(new KeycloakCredentials(baseUrl, deepRealm, AGGREGATOR, AGGREGATOR_SECRET));

                assertThatThrownBy(() -> deep.readEntitlements(null, 10))
                        .isInstanceOf(ConnectorException.class)
                        .hasMessageContaining("deeper than 64 levels");
            } finally {
                admin("DELETE", "/admin/realms/" + deepRealm, null);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Reading the realm through the connector, for the assertions above.
    // ---------------------------------------------------------------------

    private List<DiscoveredAccount> allAccounts() {
        return readEverything(connector());
    }

    /** Every page of accounts from one connector, which the narrow realm test needs too. */
    private static List<DiscoveredAccount> readEverything(KeycloakConnector connector) {
        List<DiscoveredAccount> found = new ArrayList<>();
        ReadCursor cursor = null;
        do {
            ConnectorPage<DiscoveredAccount> page = connector.readAccounts(cursor, 50);
            found.addAll(page.items());
            cursor = page.next();
        } while (cursor != null);
        return found;
    }

    private DiscoveredAccount accountNamed(String username) {
        return allAccounts().stream()
                .filter(account -> username.equals(account.username()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no account named " + username + " in the realm"));
    }

    private List<DiscoveredEntitlement> allEntitlements() {
        List<DiscoveredEntitlement> found = new ArrayList<>();
        ReadCursor cursor = null;
        do {
            ConnectorPage<DiscoveredEntitlement> page = connector().readEntitlements(cursor, 50);
            found.addAll(page.items());
            cursor = page.next();
        } while (cursor != null);
        return found;
    }

    // ---------------------------------------------------------------------
    // Driving Keycloak as the administrator, which is the test's own job and
    // deliberately not something the connector can do.
    // ---------------------------------------------------------------------

    private static String adminToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", ADMIN_USER);
        form.add("password", ADMIN_PASSWORD);

        String body = HTTP.post()
                .uri(baseUrl + "/realms/master/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        return JSON.readTree(body).path("access_token").asString();
    }

    private static String admin(String method, String path, String body) {
        RestClient.RequestBodySpec request = HTTP.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(baseUrl + path)
                .header("Authorization", "Bearer " + adminToken);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON);
            return request.body(body).retrieve().body(String.class);
        }
        return request.retrieve().body(String.class);
    }

    /** What the aggregator's own credential is served, for the assertions about what it can and cannot see. */
    private static String bodyOfAggregatorCall(String method, String path) {
        return HTTP.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(baseUrl + path)
                .header("Authorization", "Bearer " + aggregatorToken())
                .retrieve()
                .body(String.class);
    }

    private static String aggregatorToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", AGGREGATOR);
        form.add("client_secret", AGGREGATOR_SECRET);
        return JSON.readTree(HTTP.post()
                        .uri(baseUrl + "/realms/" + REALM + "/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(String.class))
                .path("access_token")
                .asString();
    }

    /** The status code the aggregator's own credential gets, which is how least privilege is asserted. */
    private static int statusOfAggregatorCall(String method, String path, String body) {
        RestClient.RequestBodySpec request = HTTP.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(baseUrl + path)
                .header("Authorization", "Bearer " + aggregatorToken());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON);
            return request.body(body)
                    .exchange((ignored, response) -> response.getStatusCode())
                    .value();
        }
        return request.exchange((ignored, response) -> (HttpStatusCode) response.getStatusCode())
                .value();
    }

    private static String idOfUser(String username) {
        JsonNode found = JSON.readTree(
                admin("GET", "/admin/realms/" + REALM + "/users?username=" + username + "&exact=true", null));
        return found.get(0).path("id").asString();
    }

    private static String groupIdAtPath(String path) {
        String leaf = path.substring(path.lastIndexOf('/') + 1);
        JsonNode found = JSON.readTree(admin("GET", "/admin/realms/" + REALM + "/groups?search=" + leaf, null));
        String id = findByPath(found, path);
        if (id == null) {
            throw new AssertionError("no group at path " + path);
        }
        return id;
    }

    /** The search endpoint answers with an ancestor rooted subtree, so the match can be nested. */
    private static String findByPath(JsonNode nodes, String path) {
        for (JsonNode node : nodes) {
            if (path.equals(node.path("path").asString(null))) {
                return node.path("id").asString();
            }
            String nested = findByPath(node.path("subGroups"), path);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static void joinGroup(String userId, String groupId) {
        admin("PUT", "/admin/realms/" + REALM + "/users/" + userId + "/groups/" + groupId, null);
    }

    private static String createGroup(String realm, String parentId, String name) {
        String path = parentId == null
                ? "/admin/realms/" + realm + "/groups"
                : "/admin/realms/" + realm + "/groups/" + parentId + "/children";
        String created = admin("POST", path, "{\"name\":\"" + name + "\"}");
        if (created != null && !created.isBlank()) {
            return JSON.readTree(created).path("id").asString();
        }
        JsonNode found = JSON.readTree(admin("GET", "/admin/realms/" + realm + "/groups?search=" + name, null));
        return found.get(0).path("id").asString();
    }

    /**
     * Grants the named {@code realm-management} roles to the aggregator's own
     * service account.
     *
     * <p>Takes the names rather than hardcoding one, because the point of the
     * narrow realm test is a realm with {@code view-users} and without
     * {@code view-clients}, which is the credential this connector had before
     * the second half of KAN-11 and the one every deployment will start from.
     */
    private static void grantRealmManagementRoles(String realm, String... roleNames) {
        String clientUuid = JSON.readTree(
                        admin("GET", "/admin/realms/" + realm + "/clients?clientId=" + AGGREGATOR, null))
                .get(0)
                .path("id")
                .asString();
        String realmManagement = JSON.readTree(
                        admin("GET", "/admin/realms/" + realm + "/clients?clientId=realm-management", null))
                .get(0)
                .path("id")
                .asString();
        String serviceAccount = JSON.readTree(
                        admin("GET", "/admin/realms/" + realm + "/clients/" + clientUuid + "/service-account-user", null))
                .path("id")
                .asString();

        StringBuilder payload = new StringBuilder("[");
        for (int index = 0; index < roleNames.length; index++) {
            JsonNode role = JSON.readTree(admin(
                    "GET",
                    "/admin/realms/" + realm + "/clients/" + realmManagement + "/roles/" + roleNames[index],
                    null));
            payload.append(index == 0 ? "" : ",")
                    .append("{\"id\":\"%s\",\"name\":\"%s\"}"
                            .formatted(role.path("id").asString(), roleNames[index]));
        }
        admin(
                "POST",
                "/admin/realms/" + realm + "/users/" + serviceAccount + "/role-mappings/clients/" + realmManagement,
                payload.append("]").toString());
    }
}
