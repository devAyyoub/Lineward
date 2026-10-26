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
package com.lineward.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.lineward.connector.LiteralTargetSystemConnector.account;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What every connector has to do, whatever it talks to.
 *
 * <p>The documents below are not constructed: they are responses of the
 * Keycloak 26.7.5 admin API, read from the container of
 * {@code infra/docker-compose.yml} on 2026-10-23 and pasted in. The user and the
 * group come from two throwaway realms that were deleted afterwards, and they
 * are paired here because what this test needs from each is its shape. A test
 * that built the documents from this project's own classes would prove that the
 * project agrees with itself, which is what let a broken contract pass sixteen
 * times on 2026-10-16.
 *
 * <p>These assertions belong to the SPI and not to any one connector. KAN-7,
 * KAN-8 and KAN-9 each get their own contract test against the real bytes of
 * their own system, and should keep these.
 */
class TargetSystemConnectorContractTests {

    /**
     * A real user, with a name, an email, a multi-valued attribute and a
     * created timestamp in epoch milliseconds.
     *
     * <p>{@code secondaryEmail} carries two values, and that is the shape that
     * decided {@code attributes} is a map of lists: this is what Keycloak
     * returned, not a possibility allowed for.
     */
    private static final String MARTA = """
            {"id":"295cb9e0-8844-4ff0-a93d-aadf392a0020","username":"m.ferreira",\
            "firstName":"Marta","lastName":"Ferreira","email":"m.ferreira@lineward.example",\
            "emailVerified":false,"attributes":{"secondaryEmail":["marta.ferreira@lineward.example",\
            "m.ferreira.external@lineward.example"],"costCenter":["CC-FIN-200"],\
            "employeeNumber":["E-0417"]},"enabled":true,"createdTimestamp":1792758433868,\
            "totp":false,"disableableCredentialTypes":[],"requiredActions":[],"notBefore":0,\
            "access":{"manage":true}}""";

    /**
     * A disabled service account: no email, no first or last name, no groups.
     *
     * <p>The kind of row an aggregation is run to find, and the reason nothing
     * but the identifier is required. Note what is not in the document: there is
     * no {@code email} key at all, rather than an {@code email} of null.
     */
    private static final String SERVICE_ACCOUNT = """
            {"id":"7c1d7afe-33af-4ea0-b687-5211bdb9fcc0","username":"svc-nightly-batch",\
            "emailVerified":false,"enabled":false,"createdTimestamp":1792758269956,"totp":false,\
            "disableableCredentialTypes":[],"requiredActions":[],"notBefore":0,\
            "access":{"manage":true}}""";

    /** One element of {@code GET /admin/realms/{realm}/groups}: the permission catalogue. */
    private static final String FINANCE_APPROVERS = """
            {"id":"dec32c4a-ab4e-4d7d-8c93-210c6cb6a8d3","name":"finance-approvers",\
            "path":"/finance-approvers","subGroupCount":0,"subGroups":[],\
            "access":{"view":true,"viewMembers":true,"manageMembers":true,"manage":true,\
            "manageMembership":true}}""";

    /** The same group as {@code GET /admin/realms/{realm}/users/{id}/groups} returns it, which is a shorter document. */
    private static final String MEMBER_OF_FINANCE_APPROVERS = """
            {"id":"dec32c4a-ab4e-4d7d-8c93-210c6cb6a8d3","name":"finance-approvers",\
            "path":"/finance-approvers","subGroups":[]}""";

    private final LiteralTargetSystemConnector connector = new LiteralTargetSystemConnector();

    /** The same real shape with a different identifier, for the paging tests. */
    private static String userNumbered(int n) {
        return MARTA.replace("295cb9e0-8844-4ff0-a93d-aadf392a0020", "account-" + n)
                .replace("m.ferreira", "user" + n);
    }

    @Test
    void carriesWhatARealIdentityProviderReturns() {
        connector.serving(account(MARTA, MEMBER_OF_FINANCE_APPROVERS));

        DiscoveredAccount marta = connector.readAccounts(null, 10).items().getFirst();

        // The identifier is the UUID and not the username. Keycloak lets the
        // username be renamed, so correlating on it would lose the account on a
        // rename and report it as one deleted and one created.
        assertThat(marta.nativeId()).isEqualTo("295cb9e0-8844-4ff0-a93d-aadf392a0020");
        assertThat(marta.username()).isEqualTo("m.ferreira");
        assertThat(marta.email()).isEqualTo("m.ferreira@lineward.example");
        assertThat(marta.displayName()).isEqualTo("Marta Ferreira");
        assertThat(marta.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(marta.createdInTargetAt()).isEqualTo(Instant.parse("2026-10-23T12:27:13.868Z"));
        assertThat(marta.entitlementValues()).containsExactly("/finance-approvers");
    }

    /**
     * The attribute the correlation rules of M2 will match on, and the reason
     * the map holds lists.
     */
    @Test
    void keepsEveryValueOfAMultiValuedAttribute() {
        connector.serving(account(MARTA));

        Map<String, List<String>> attributes =
                connector.readAccounts(null, 10).items().getFirst().attributes();

        assertThat(attributes.get("employeeNumber")).containsExactly("E-0417");
        assertThat(attributes.get("secondaryEmail"))
                .containsExactly("marta.ferreira@lineward.example", "m.ferreira.external@lineward.example");
    }

    /**
     * Keycloak reports no last login on the user representation, verified on
     * 26.7.5. Absent has to read as "cannot tell": filling it with the epoch or
     * with now would report either the whole directory as dormant or none of it.
     */
    @Test
    void leavesTheLastActivityUnsetWhenTheSystemDoesNotTrackIt() {
        connector.serving(account(MARTA));

        assertThat(connector.readAccounts(null, 10).items().getFirst().lastActivityAt()).isNull();
    }

    @Test
    void readsADisabledAccountWithNothingButAnIdentifier() {
        connector.serving(account(SERVICE_ACCOUNT));

        DiscoveredAccount batch = connector.readAccounts(null, 10).items().getFirst();

        assertThat(batch.status()).isEqualTo(AccountStatus.DISABLED);
        assertThat(batch.email()).isNull();
        assertThat(batch.displayName()).isNull();
        // Holding no permissions is not a reason to drop it. An account that
        // belongs to nobody and grants nothing is still an account somebody has
        // to answer for, and dropping it would hide the finding.
        assertThat(batch.entitlementValues()).isEmpty();
        assertThat(batch.attributes()).isEmpty();
    }

    @Test
    void treatsSilenceAboutTheStatusAsUnknownAndNotAsActive() {
        connector.serving(account(SERVICE_ACCOUNT.replace("\"enabled\":false,", "")));

        assertThat(connector.readAccounts(null, 10).items().getFirst().status())
                .isEqualTo(AccountStatus.UNKNOWN);
    }

    @Test
    void refusesAnAccountWithNoNativeIdentifier() {
        connector.serving(account(SERVICE_ACCOUNT.replace("\"id\":\"7c1d7afe-33af-4ea0-b687-5211bdb9fcc0\",", "")));

        assertThatThrownBy(() -> connector.readAccounts(null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be correlated");
    }

    @Test
    void walksTheWholePopulationOnceWhenPaging() {
        connector.serving(
                account(userNumbered(1)),
                account(userNumbered(2)),
                account(userNumbered(3)),
                account(userNumbered(4)),
                account(userNumbered(5)));

        List<String> seen = new ArrayList<>();
        int pages = 0;
        ReadCursor cursor = null;
        do {
            ConnectorPage<DiscoveredAccount> page = connector.readAccounts(cursor, 2);
            page.items().forEach(account -> seen.add(account.nativeId()));
            cursor = page.next();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen)
                .containsExactly("account-1", "account-2", "account-3", "account-4", "account-5")
                .doesNotHaveDuplicates();
    }

    /**
     * No empty round trip at the end. A connector that only signalled the end by
     * returning nothing would need one extra request per aggregation, and worse,
     * would make an interrupted read indistinguishable from a finished one.
     */
    @Test
    void saysTheLastPageIsTheLastWhenTheCountDividesEvenly() {
        connector.serving(account(userNumbered(1)), account(userNumbered(2)));

        ConnectorPage<DiscoveredAccount> only = connector.readAccounts(null, 2);

        assertThat(only.items()).hasSize(2);
        assertThat(only.exhausted()).isTrue();
        assertThat(only.next()).isNull();
    }

    @Test
    void resumesExactlyWhereTheCursorLeftOff() {
        connector.serving(account(userNumbered(1)), account(userNumbered(2)), account(userNumbered(3)));

        ReadCursor afterFirst = connector.readAccounts(null, 1).next();
        ConnectorPage<DiscoveredAccount> second = connector.readAccounts(afterFirst, 1);

        assertThat(second.items()).extracting(DiscoveredAccount::nativeId).containsExactly("account-2");
        // Reading the same cursor twice gives the same page, which is what makes
        // a resume after a crash safe rather than a gamble.
        assertThat(connector.readAccounts(afterFirst, 1).items())
                .extracting(DiscoveredAccount::nativeId)
                .containsExactly("account-2");
    }

    @Test
    void readsTheCatalogueSeparatelyFromTheAccountsThatHoldIt() {
        connector.serving(account(SERVICE_ACCOUNT)).withCatalogue(FINANCE_APPROVERS);

        DiscoveredEntitlement approvers = connector.readEntitlements(null, 10).items().getFirst();

        // Nobody holds it and it is still in the catalogue. A permission that
        // only existed while somebody held it could never be requested in M5,
        // and "nobody has this any more" would be invisible.
        assertThat(approvers.nativeValue()).isEqualTo("/finance-approvers");
        assertThat(approvers.displayName()).isEqualTo("finance-approvers");
    }

    /** The account refers to the catalogue by native value, which is the join reconciliation needs. */
    @Test
    void joinsAnAccountToTheCatalogueByNativeValue() {
        connector.serving(account(MARTA, MEMBER_OF_FINANCE_APPROVERS)).withCatalogue(FINANCE_APPROVERS);

        List<String> held = connector.readAccounts(null, 10).items().getFirst().entitlementValues();
        List<String> catalogue = connector.readEntitlements(null, 10).items().stream()
                .map(DiscoveredEntitlement::nativeValue)
                .toList();

        assertThat(catalogue).containsAll(held);
    }

    @Test
    void reportsAnUnreachableSystemWithoutThrowing() {
        connector.unreachable("401 from the admin API: the client secret expired on 2026-10-01");

        ConnectionCheck check = connector.testConnection();

        assertThat(check.reachable()).isFalse();
        assertThat(check.detail()).contains("client secret expired");
    }

    @Test
    void describesWhatItReachedWhenItCan() {
        ConnectionCheck check = connector.testConnection();

        assertThat(check.reachable()).isTrue();
        assertThat(check.detail()).contains("26.7.5");
    }

    /**
     * The difference the whole page type exists for. A read that broke has to
     * fail, because returning what arrived would report a part as the whole, and
     * then every account that did not arrive looks deleted.
     */
    @Test
    void failsLoudlyWhenTheReadBreaksPartWayThrough() {
        connector
                .serving(account(userNumbered(1)), account(userNumbered(2)), account(userNumbered(3)))
                .breakingAfter(1);

        ReadCursor afterFirst = connector.readAccounts(null, 1).next();

        assertThatThrownBy(() -> connector.readAccounts(afterFirst, 1))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("connection dropped")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ConnectorException.class))
                .extracting(ConnectorException::type)
                .isEqualTo(ConnectorType.KEYCLOAK);
    }

    @Test
    void refusesAPageThatClaimsMoreWithoutSayingWhereToCarryOn() {
        assertThatThrownBy(() -> ConnectorPage.more(List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("where to carry on");
    }

    @Test
    void refusesACursorWithNoToken() {
        assertThatThrownBy(() -> new ReadCursor("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot resume anything");
    }

    /**
     * The copy is deep, so a connector reusing a mutable list while paging
     * cannot change an account it already handed over.
     */
    @Test
    void keepsTheAttributesItWasGivenWhenTheCallerKeepsEditingTheirs() {
        List<String> theirValues = new ArrayList<>(List.of("E-0417"));
        Map<String, List<String>> theirAttributes = new java.util.HashMap<>();
        theirAttributes.put("employeeNumber", theirValues);

        DiscoveredAccount account =
                new DiscoveredAccount("a-1", null, null, null, null, null, null, List.of(), theirAttributes);
        theirValues.add("E-9999");
        theirAttributes.put("costCenter", List.of("CC-X"));

        assertThat(account.attributes()).containsOnlyKeys("employeeNumber");
        assertThat(account.attributes().get("employeeNumber")).containsExactly("E-0417");
    }
}
