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
import com.lineward.connector.ConnectorException;
import com.lineward.connector.ConnectorPage;
import com.lineward.connector.DiscoveredAccount;
import com.lineward.connector.DiscoveredEntitlement;
import com.lineward.connector.ReadCursor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The edges of the Keycloak connector's paging, against {@link StandInKeycloak}.
 *
 * <p>Seven of them were seen failing on 2026-11-04 by breaking, one at a time
 * in one run, the lines they cover: the extra row asked for to detect the end,
 * the hand over from people to machines, the child walk past its first
 * hundred, the depth guard and the check on a negative offset. The other five
 * guard behaviour those breakages did not touch and have not been seen
 * failing, which is said here rather than implied. A paging bug in a connector does not crash anything, it makes accounts
 * disappear, and to a reconciliation a disappeared account reads as deleted.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class KeycloakConnectorPagingTests {

    private StandInKeycloak keycloak;

    @BeforeEach
    void start() throws IOException {
        keycloak = new StandInKeycloak();
    }

    @AfterEach
    void stop() {
        keycloak.close();
    }

    @Nested
    class Reading_accounts {

        @Test
        void asks_for_one_row_more_than_the_page_to_know_whether_there_is_more() {
            keycloak.user("u1", "a").user("u2", "b");

            ConnectorPage<DiscoveredAccount> page = keycloak.connector().readAccounts(null, 2);

            assertThat(keycloak.requests()).first().asString().endsWith("/users?first=0&max=3");
            assertThat(page.items()).extracting(DiscoveredAccount::username).containsExactly("a", "b");
            assertThat(page.next())
                    .as("a full page of people that turns out to be all of them still hands over to the machines")
                    .isEqualTo(new ReadCursor("machines:0"));
        }

        @Test
        void hands_over_from_people_to_machines_and_ends_after_the_machines() {
            keycloak.user("u1", "a").user("u2", "b").user("u3", "c")
                    .client("c1", true).client("c2", false).client("c3", true);

            List<DiscoveredAccount> all = readAllAccounts(2);

            assertThat(all).extracting(DiscoveredAccount::username)
                    .containsExactly("a", "b", "c", "service-account-c1", "service-account-c3");
            assertThat(all).extracting(DiscoveredAccount::kind)
                    .containsExactly(AccountKind.UNKNOWN, AccountKind.UNKNOWN, AccountKind.UNKNOWN,
                            AccountKind.SERVICE, AccountKind.SERVICE);
        }

        /**
         * The page size bounds the walk over clients, not the result, so a page
         * of clients with no service account among them is empty and is not
         * the end (error diary of 2026-11-04).
         */
        @Test
        void an_empty_page_of_machines_with_more_clients_behind_it_is_not_the_end() {
            keycloak.client("c1", false).client("c2", false).client("c3", true);

            ConnectorPage<DiscoveredAccount> people = keycloak.connector().readAccounts(null, 2);
            ConnectorPage<DiscoveredAccount> firstMachines = keycloak.connector().readAccounts(people.next(), 2);

            assertThat(firstMachines.items()).isEmpty();
            assertThat(firstMachines.exhausted()).isFalse();
            assertThat(keycloak.connector().readAccounts(firstMachines.next(), 2).items())
                    .extracting(DiscoveredAccount::username)
                    .containsExactly("service-account-c3");
        }

        @Test
        void an_empty_realm_still_reads_both_populations_and_ends() {
            List<DiscoveredAccount> all = readAllAccounts(5);

            assertThat(all).isEmpty();
            assertThat(keycloak.requests())
                    .as("the machines are asked for even when there are no people")
                    .anyMatch(request -> request.contains("/clients?"));
        }

        @Test
        void carries_the_memberships_of_each_account_by_path() {
            keycloak.user("u1", "a", "/finance", "/finance/treasury");

            assertThat(keycloak.connector().readAccounts(null, 10).items().getFirst().entitlementValues())
                    .containsExactly("/finance", "/finance/treasury");
        }

        private List<DiscoveredAccount> readAllAccounts(int size) {
            KeycloakConnector connector = keycloak.connector();
            List<DiscoveredAccount> all = new ArrayList<>();
            ReadCursor cursor = null;
            for (int guard = 0; guard < 100; guard++) {
                ConnectorPage<DiscoveredAccount> page = connector.readAccounts(cursor, size);
                all.addAll(page.items());
                if (page.exhausted()) {
                    return all;
                }
                cursor = page.next();
            }
            throw new AssertionError("the read did not end in a hundred pages");
        }
    }

    @Nested
    class Reading_the_catalogue {

        /**
         * The child walk pages in hundreds, so the hundred and first child is
         * exactly where a walk that stops after one request loses permissions.
         */
        @Test
        void walks_past_the_first_hundred_children_of_a_group() {
            keycloak.group("root", "/apps", null);
            for (int i = 0; i < 150; i++) {
                keycloak.group("child-" + i, "/apps/app-" + i, "root");
            }

            ConnectorPage<DiscoveredEntitlement> page = keycloak.connector().readEntitlements(null, 10);

            assertThat(page.exhausted()).isTrue();
            assertThat(page.items()).hasSize(151);
            assertThat(page.items()).extracting(DiscoveredEntitlement::nativeValue)
                    .contains("/apps", "/apps/app-0", "/apps/app-99", "/apps/app-100", "/apps/app-149");
        }

        @Test
        void pages_over_the_top_level_groups_with_an_offset_cursor() {
            keycloak.group("g1", "/a", null).group("g2", "/b", null).group("g3", "/c", null);

            ConnectorPage<DiscoveredEntitlement> first = keycloak.connector().readEntitlements(null, 2);
            ConnectorPage<DiscoveredEntitlement> second = keycloak.connector().readEntitlements(first.next(), 2);

            assertThat(first.items()).extracting(DiscoveredEntitlement::nativeValue).containsExactly("/a", "/b");
            assertThat(first.next()).isEqualTo(new ReadCursor("2"));
            assertThat(second.items()).extracting(DiscoveredEntitlement::nativeValue).containsExactly("/c");
            assertThat(second.exhausted()).isTrue();
        }

        /**
         * A tree deeper than the guard is refused whole, because half a
         * catalogue reads as permissions that were deleted.
         */
        @Test
        void refuses_a_tree_deeper_than_it_will_walk_rather_than_returning_part_of_it() {
            keycloak.group("level-1", "/l1", null);
            String path = "/l1";
            for (int level = 2; level <= 65; level++) {
                path = path + "/l" + level;
                keycloak.group("level-" + level, path, "level-" + (level - 1));
            }

            assertThatThrownBy(() -> keycloak.connector().readEntitlements(null, 10))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessageContaining("deeper than 64");
        }

        @Test
        void walks_a_tree_exactly_as_deep_as_the_guard_allows() {
            keycloak.group("level-1", "/l1", null);
            String path = "/l1";
            for (int level = 2; level <= 64; level++) {
                path = path + "/l" + level;
                keycloak.group("level-" + level, path, "level-" + (level - 1));
            }

            assertThat(keycloak.connector().readEntitlements(null, 10).items()).hasSize(64);
        }
    }

    /**
     * A cursor is the connector's own, and a cursor it did not issue is
     * refused rather than read as a guess about where to carry on.
     */
    @Nested
    class Refusing_what_it_did_not_issue {

        @Test
        void an_account_cursor_from_somewhere_else() {
            for (String foreign : List.of("12", "robots:0", "people:-1", "people:x", ":3", "people")) {
                assertThatThrownBy(() -> keycloak.connector().readAccounts(new ReadCursor(foreign), 10))
                        .as(foreign)
                        .isInstanceOf(ConnectorException.class)
                        .hasMessageContaining("not issued by the Keycloak connector");
            }
        }

        @Test
        void a_catalogue_cursor_from_somewhere_else() {
            for (String foreign : List.of("people:0", "-1", "two")) {
                assertThatThrownBy(() -> keycloak.connector().readEntitlements(new ReadCursor(foreign), 10))
                        .as(foreign)
                        .isInstanceOf(ConnectorException.class)
                        .hasMessageContaining("not issued by the Keycloak connector");
            }
        }

        @Test
        void a_page_that_cannot_make_progress() {
            assertThatThrownBy(() -> keycloak.connector().readAccounts(null, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> keycloak.connector().readEntitlements(null, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
