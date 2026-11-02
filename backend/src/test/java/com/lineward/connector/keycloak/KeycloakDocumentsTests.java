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

import com.lineward.connector.AccountStatus;
import com.lineward.connector.DiscoveredAccount;
import com.lineward.connector.DiscoveredEntitlement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What Keycloak's admin API says, turned into what the SPI holds.
 *
 * <p>Every document below is a response of the Keycloak 26.7.5 admin API, read
 * from the container of {@code infra/docker-compose.yml} on 2026-11-01 and
 * pasted in unchanged. They come from a throwaway realm that was deleted
 * afterwards. Building them from this project's own classes instead would prove
 * that the project agrees with itself, which is what let a broken contract pass
 * sixteen times on 2026-10-16.
 *
 * <p>The mapping lives apart from the HTTP so that this test needs no container:
 * understanding a payload and fetching it are different jobs, and only the
 * second one needs Keycloak running. The reads themselves are pinned down by
 * {@code KeycloakConnectorIntegrationTests} against a real server.
 */
class KeycloakDocumentsTests {

    /**
     * A user with the two attributes the realm's user profile declares, both
     * arriving as arrays.
     *
     * <p>{@code costCenter} carries two values and is the shape that decided the
     * SPI holds a map of lists: this is what Keycloak returned, not a
     * possibility allowed for.
     */
    private static final String DECLARED_ATTRIBUTES = """
            {"id":"2b5786fa-5970-4b5a-82ec-11f19be31bec","username":"probe.declared",\
            "emailVerified":false,"attributes":{"costCenter":["CC-PRB-9","CC-PRB-8"],\
            "employeeNumber":["E-9999"]},"enabled":true,"createdTimestamp":1793492578357,\
            "totp":false,"disableableCredentialTypes":[],"requiredActions":[],\
            "notBefore":0,"access":{"manage":true}}""";

    /** A full user: names, email, enabled, and a created timestamp in epoch milliseconds. */
    private static final String FULL_USER = """
            {"id":"0d06397c-618d-40e2-940c-f5a425a37de0","username":"probe.user1",\
            "firstName":"Probe1","lastName":"User","email":"probe.user1@lineward.example",\
            "emailVerified":false,"enabled":true,"createdTimestamp":1793492541567,\
            "totp":false,"disableableCredentialTypes":[],"requiredActions":[],\
            "notBefore":0,"access":{"manage":true}}""";

    /**
     * The memberships of one user, as
     * {@code GET /admin/realms/{realm}/users/{id}/groups} returns them.
     *
     * <p>Note the first one: a nested group, whose leaf name {@code probe-beta}
     * is also the name of a different top-level group. That collision is why the
     * entitlement's native value is the path.
     */
    private static final String NESTED_MEMBERSHIP = """
            {"id":"f7a2f11e-c64a-4b74-9558-f17ccd74fcf0","name":"probe-beta",\
            "path":"/probe-alpha/probe-beta","parentId":"c1c09cd2-5f2f-4b97-9def-ad36a100e831",\
            "subGroups":[]}""";

    private static final String TOP_LEVEL_MEMBERSHIP = """
            {"id":"8111b689-2ede-4e8a-b860-e0ce11c0dea2","name":"probe-gamma",\
            "path":"/probe-gamma","subGroups":[]}""";

    /**
     * One element of {@code GET /admin/realms/{realm}/groups}: the catalogue.
     *
     * <p>{@code subGroups} is empty and {@code subGroupCount} is four. That pair
     * is the whole reason the catalogue read is a tree walk: the children are
     * not in this document, and only the count admits they exist.
     */
    private static final String PARENT_GROUP = """
            {"id":"c1c09cd2-5f2f-4b97-9def-ad36a100e831","name":"probe-alpha",\
            "path":"/probe-alpha","subGroupCount":4,"subGroups":[],\
            "access":{"view":true,"viewMembers":true,"manageMembers":true,\
            "manage":true,"manageMembership":true}}""";

    private static final String LEAF_GROUP = """
            {"id":"8111b689-2ede-4e8a-b860-e0ce11c0dea2","name":"probe-gamma",\
            "path":"/probe-gamma","subGroupCount":0,"subGroups":[],\
            "access":{"view":true,"viewMembers":true,"manageMembers":true,\
            "manage":true,"manageMembership":true}}""";

    private final KeycloakDocuments documents = new KeycloakDocuments();

    @Test
    void readsEverythingARealUserCarries() {
        DiscoveredAccount account = documents.account(FULL_USER, List.of());

        // The UUID and not the username: Keycloak lets a username be renamed,
        // so correlating on it would lose the account's history on a rename and
        // report it as one deleted and one created.
        assertThat(account.nativeId()).isEqualTo("0d06397c-618d-40e2-940c-f5a425a37de0");
        assertThat(account.username()).isEqualTo("probe.user1");
        assertThat(account.email()).isEqualTo("probe.user1@lineward.example");
        assertThat(account.displayName()).isEqualTo("Probe1 User");
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.createdInTargetAt()).isEqualTo(Instant.parse("2026-11-01T00:22:21.567Z"));
    }

    @Test
    void keepsEveryValueOfAMultiValuedAttribute() {
        DiscoveredAccount account = documents.account(DECLARED_ATTRIBUTES, List.of());

        assertThat(account.attributes().get("employeeNumber")).containsExactly("E-9999");
        assertThat(account.attributes().get("costCenter")).containsExactly("CC-PRB-9", "CC-PRB-8");
    }

    /**
     * Keycloak reports no last login on the user representation, verified on
     * 26.7.5. Absent has to read as "cannot tell": filling it in would report
     * either the whole realm as dormant or none of it.
     */
    @Test
    void leavesTheLastActivityUnsetBecauseKeycloakDoesNotReportIt() {
        assertThat(documents.account(FULL_USER, List.of()).lastActivityAt()).isNull();
    }

    @Test
    void readsAUserWithNoNameAndNoEmail() {
        DiscoveredAccount account = documents.account(DECLARED_ATTRIBUTES, List.of());

        assertThat(account.email()).isNull();
        assertThat(account.displayName()).isNull();
    }

    @Test
    void readsDisabledAsDisabledAndNotAsAbsent() {
        DiscoveredAccount account = documents.account(FULL_USER.replace("\"enabled\":true", "\"enabled\":false"), List.of());

        assertThat(account.status()).isEqualTo(AccountStatus.DISABLED);
    }

    /**
     * Keycloak always sends {@code enabled}, so this is the defence against a
     * future version that stops. Reading silence as active would invent live
     * access nobody granted.
     */
    @Test
    void treatsSilenceAboutTheStatusAsUnknownAndNotAsActive() {
        DiscoveredAccount account = documents.account(FULL_USER.replace("\"enabled\":true,", ""), List.of());

        assertThat(account.status()).isEqualTo(AccountStatus.UNKNOWN);
    }

    @Test
    void namesTheMembershipsByPathSoThatANestedGroupIsNotConfusedWithATopLevelOne() {
        DiscoveredAccount account =
                documents.account(FULL_USER, List.of(NESTED_MEMBERSHIP, TOP_LEVEL_MEMBERSHIP));

        assertThat(account.entitlementValues())
                .containsExactly("/probe-alpha/probe-beta", "/probe-gamma");
    }

    @Test
    void refusesAUserWithNoIdentifier() {
        String withoutId = FULL_USER.replace("\"id\":\"0d06397c-618d-40e2-940c-f5a425a37de0\",", "");

        assertThatThrownBy(() -> documents.account(withoutId, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be correlated");
    }

    @Test
    void namesAnEntitlementByItsPathAndShowsItsLeafName() {
        DiscoveredEntitlement entitlement = documents.entitlement(LEAF_GROUP);

        assertThat(entitlement.nativeValue()).isEqualTo("/probe-gamma");
        assertThat(entitlement.displayName()).isEqualTo("probe-gamma");
    }

    /**
     * The count, not the array. {@code GET /groups} returns {@code subGroups}
     * empty whatever the truth is, so a walk that trusted the array would stop
     * at the top level and report a part of the catalogue as the whole.
     */
    @Test
    void believesTheChildCountAndNotTheEmptySubGroupsArray() {
        assertThat(documents.hasChildren(PARENT_GROUP)).isTrue();
        assertThat(documents.hasChildren(LEAF_GROUP)).isFalse();
    }

    @Test
    void readsTheGroupIdentifierTheChildWalkNeeds() {
        assertThat(documents.groupId(PARENT_GROUP)).isEqualTo("c1c09cd2-5f2f-4b97-9def-ad36a100e831");
    }
}
