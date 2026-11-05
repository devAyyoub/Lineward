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
package com.lineward.connector.okta;

import static org.assertj.core.api.Assertions.assertThat;

import com.lineward.connector.ConnectionCheck;
import com.lineward.connector.ConnectorPage;
import com.lineward.connector.DiscoveredAccount;
import com.lineward.connector.DiscoveredEntitlement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The connector against a real Okta organization.
 *
 * <p>Runs where the credential is configured and skips everywhere else, for
 * the reason {@code OktaAdminApiLiveTests} gives: there is no Okta to start.
 * What only these can show is the walk, because a catalogue in two populations
 * behind one cursor is exactly the kind of thing that works in reasoning and
 * loops forever against the real thing.
 */
@EnabledIfEnvironmentVariable(named = "OKTA_ORG_URL", matches = ".+")
class OktaConnectorLiveTests {

    /** Enough to stop a broken walk before it becomes a rate limit incident. */
    private static final int WAY_MORE_PAGES_THAN_THIS_ORGANIZATION_NEEDS = 40;

    private final OktaConnector connector = new OktaConnector(fromEnvironment());

    @Test
    void reaches_the_organization_and_says_as_whom() {
        ConnectionCheck check = connector.testConnection();

        assertThat(check.reachable()).isTrue();
        assertThat(check.detail()).contains("read as application");
    }

    @Test
    void reads_accounts_with_what_each_one_holds() {
        ConnectorPage<DiscoveredAccount> page = connector.readAccounts(null, 50);

        assertThat(page.items()).isNotEmpty();
        DiscoveredAccount account = page.items().get(0);
        assertThat(account.nativeId()).isNotBlank();
        assertThat(account.username()).isNotBlank();
        assertThat(account.entitlementValues())
                .as("everybody is in the built in Everyone group, so nobody holds nothing")
                .isNotEmpty();
    }

    @Test
    void walks_the_whole_catalogue_once_and_stops() {
        List<String> seen = new ArrayList<>();
        ConnectorPage<DiscoveredEntitlement> page = connector.readEntitlements(null, 1);
        int pages = 1;
        while (!page.exhausted() && pages < WAY_MORE_PAGES_THAN_THIS_ORGANIZATION_NEEDS) {
            page.items().forEach(entitlement -> seen.add(entitlement.nativeValue()));
            page = connector.readEntitlements(page.next(), 1);
            pages++;
        }
        page.items().forEach(entitlement -> seen.add(entitlement.nativeValue()));

        assertThat(page.exhausted())
                .as("a walk that never ends is worse than one that ends early")
                .isTrue();
        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).anyMatch(value -> value.startsWith("group:"));
        assertThat(seen)
                .as("an assignment to an application is a grant of its own, and the walk has to reach them")
                .anyMatch(value -> value.startsWith("app:"));
    }

    @Test
    void covers_the_groups_before_the_applications() {
        // Not an arbitrary order: it is what lets the cursor say where the walk
        // is without carrying two of them.
        List<String> seen = new ArrayList<>();
        ConnectorPage<DiscoveredEntitlement> page = connector.readEntitlements(null, 200);
        int pages = 1;
        while (!page.exhausted() && pages < WAY_MORE_PAGES_THAN_THIS_ORGANIZATION_NEEDS) {
            page.items().forEach(entitlement -> seen.add(entitlement.nativeValue()));
            page = connector.readEntitlements(page.next(), 200);
            pages++;
        }
        page.items().forEach(entitlement -> seen.add(entitlement.nativeValue()));

        int lastGroup = lastIndexMatching(seen, "group:");
        int firstApplication = firstIndexMatching(seen, "app:");
        // Both checked before comparing them, because a walk that never reached
        // the applications leaves firstApplication at MAX_VALUE and makes the
        // comparison below true without having measured anything. Seen happen
        // on 2026-11-05: ending the walk at the groups on purpose left this
        // test green and only the one above failed.
        assertThat(lastGroup).as("no group came back, so the order of the two populations was not measured").isNotNegative();
        assertThat(firstApplication)
                .as("no application came back, so the order of the two populations was not measured")
                .isNotEqualTo(Integer.MAX_VALUE);
        assertThat(lastGroup).isLessThan(firstApplication);
    }

    private static int lastIndexMatching(List<String> values, String prefix) {
        int last = -1;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).startsWith(prefix)) {
                last = i;
            }
        }
        return last;
    }

    private static int firstIndexMatching(List<String> values, String prefix) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static OktaCredentials fromEnvironment() {
        try {
            return OktaCredentials.fromPem(
                    System.getenv("OKTA_ORG_URL"),
                    System.getenv("OKTA_CLIENT_ID"),
                    System.getenv("OKTA_KEY_ID"),
                    Files.readString(Path.of(System.getenv("OKTA_PRIVATE_KEY_FILE"))));
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException(
                    "OKTA_PRIVATE_KEY_FILE does not name a readable key, so these tests cannot run", unreadable);
        }
    }
}
