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

import com.lineward.connector.AccountKind;
import com.lineward.connector.AccountStatus;
import com.lineward.connector.DiscoveredAccount;
import com.lineward.connector.DiscoveredEntitlement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What Okta's administration API says, turned into what the SPI holds.
 *
 * <p><b>The shape of every document below was captured from Okta on
 * 2026-11-04, and the values in them are invented.</b> That is a deliberate
 * difference from {@code KeycloakDocumentsTests}, where the documents are
 * pasted in exactly as a throwaway realm served them. A Keycloak realm is a
 * container this repository starts and deletes; the Okta organization is a
 * real account whose only user is its owner, so pasting its responses in would
 * publish a person's name, login and email in a public repository. The field
 * names, the nesting, the timestamp format and which fields arrive null are
 * the organization's; the people are not.
 *
 * <p>One field is marked where it appears: {@code employeeNumber} has not been
 * seen on a real user yet, because the organization has no custom profile
 * attribute defined. What it pins down is this connector's mapping of custom
 * attributes, not Okta's behaviour, and the difference matters enough to say.
 */
class OktaDocumentsTests {

    /** A user with everything populated, including the last login no other connector can report. */
    private static final String ACTIVE_USER =
            """
            {"id":"00u1g3xk2mZQ8N4Rx697","status":"ACTIVE","created":"2026-10-12T08:14:02.000Z",\
            "activated":"2026-10-12T08:15:40.000Z","statusChanged":"2026-10-12T08:15:40.000Z",\
            "lastLogin":"2026-11-03T21:55:11.000Z","lastUpdated":"2026-10-12T08:15:40.000Z",\
            "passwordChanged":"2026-10-12T08:15:40.000Z","type":{"id":"oty1g3xk1vJK2P9R0697"},\
            "profile":{"firstName":"Lucia","lastName":"Perez","mobilePhone":null,"secondEmail":null,\
            "login":"lperez@lineward.test","email":"lperez@lineward.test","employeeNumber":"E-1042"},\
            "credentials":{"password":{},"provider":{"type":"OKTA","name":"OKTA"}},\
            "_links":{"self":{"href":"https://example.okta.com/api/v1/users/00u1g3xk2mZQ8N4Rx697"}}}""";

    /** Never signed in, which is what an account created and never used looks like. */
    private static final String NEVER_USED_USER =
            """
            {"id":"00u1g3xk7pWW5S3Tb697","status":"PROVISIONED","created":"2026-11-01T06:00:00.000Z",\
            "activated":null,"statusChanged":"2026-11-01T06:00:00.000Z","lastLogin":null,\
            "lastUpdated":"2026-11-01T06:00:00.000Z","passwordChanged":null,\
            "type":{"id":"oty1g3xk1vJK2P9R0697"},\
            "profile":{"firstName":"Nuevo","lastName":"Ingreso","mobilePhone":null,"secondEmail":null,\
            "login":"ningreso@lineward.test","email":"ningreso@lineward.test"},\
            "credentials":{"provider":{"type":"OKTA","name":"OKTA"}},"_links":{}}""";

    private static final String BUILT_IN_GROUP =
            """
            {"id":"00g1g3xk4nQQ1P7Rd697","created":"2026-10-12T08:00:00.000Z",\
            "lastUpdated":"2026-10-12T08:00:00.000Z","lastMembershipUpdated":"2026-11-03T10:00:00.000Z",\
            "objectClass":["okta:user_group"],"type":"BUILT_IN",\
            "profile":{"name":"Everyone","description":"All users in your organization"},"_links":{}}""";

    private static final String APPLICATION =
            """
            {"id":"0oa1g3xk9mTT2Q1Rf697","orn":"orn:okta:idp:00o1:apps:oidc_client:0oa1g3xk9mTT2Q1Rf697",\
            "name":"oidc_client","label":"Payments console","status":"ACTIVE",\
            "lastUpdated":"2026-10-12T09:00:00.000Z","created":"2026-10-12T09:00:00.000Z",\
            "signOnMode":"OPENID_CONNECT","features":[],"_links":{}}""";

    /**
     * One element of {@code /users/{id}/appLinks}, which carries <b>three</b> identifiers and only one of
     * them is the application's.
     *
     * <p>The prefixes are Okta's and are what makes them tellable apart: {@code auc} for the link itself,
     * {@code 0oa} for the application, {@code 0ua} for the assignment. Verified against a real appLink on
     * 2026-11-05, which is how this fixture was corrected: it had been written with {@code id} equal to
     * {@code appInstanceId}, and a test cannot catch a mapping that reads the wrong field when both fields
     * hold the same value.
     */
    private static final String APP_LINK =
            """
            {"id":"auc1g3xkcoVV4S3Th697","label":"Payments console","linkUrl":"https://example.okta.com/home/x",\
            "logoUrl":"https://example.okta.com/logo.png","appName":"oidc_client",\
            "appInstanceId":"0oa1g3xk9mTT2Q1Rf697","appAssignmentId":"0ua1g3xkbnUU3R2Sg697",\
            "credentialsSetup":false,"hidden":false,"sortOrder":0}""";

    private final OktaDocuments documents = new OktaDocuments();

    @Test
    void reads_an_account_out_of_a_user() {
        DiscoveredAccount account = account(ACTIVE_USER);

        assertThat(account.nativeId()).isEqualTo("00u1g3xk2mZQ8N4Rx697");
        assertThat(account.username()).isEqualTo("lperez@lineward.test");
        assertThat(account.email()).isEqualTo("lperez@lineward.test");
        assertThat(account.displayName()).isEqualTo("Lucia Perez");
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.createdInTargetAt()).isEqualTo(Instant.parse("2026-10-12T08:14:02Z"));
    }

    @Test
    void reports_when_an_account_was_last_used() {
        // The one thing this connector can say and the other two cannot.
        // Keycloak does not carry it on the user and a directory does not carry
        // it on the entry, so dormancy had no source in this project until now.
        assertThat(account(ACTIVE_USER).lastActivityAt()).isEqualTo(Instant.parse("2026-11-03T21:55:11Z"));
    }

    @Test
    void says_it_cannot_tell_rather_than_never_used_when_there_is_no_last_login() {
        // Null means Okta did not say. Reading it as "never used" would report
        // every account created last week as dormant.
        assertThat(account(NEVER_USED_USER).lastActivityAt()).isNull();
    }

    @Test
    void never_claims_a_user_is_a_person() {
        // Okta's machine identities are applications, not users, so there is
        // nothing in a user document that could support the claim.
        assertThat(account(ACTIVE_USER).kind()).isEqualTo(AccountKind.UNKNOWN);
    }

    @Test
    void keeps_the_attributes_correlation_will_match_on() {
        DiscoveredAccount account = account(ACTIVE_USER);

        assertThat(account.attributes()).containsEntry("employeeNumber", List.of("E-1042"));
        assertThat(account.attributes())
                .as("Okta sends the whole profile shape on every user, so the nulls are most of it")
                .doesNotContainKey("mobilePhone")
                .doesNotContainKey("secondEmail");
    }

    @Test
    void maps_the_states_okta_has_onto_the_five_the_schema_allows() {
        assertThat(statusOf("ACTIVE")).isEqualTo(AccountStatus.ACTIVE);
        assertThat(statusOf("PASSWORD_EXPIRED"))
                .as("a live account in the middle of a password dance still has standing access")
                .isEqualTo(AccountStatus.ACTIVE);
        assertThat(statusOf("SUSPENDED")).isEqualTo(AccountStatus.DISABLED);
        assertThat(statusOf("PROVISIONED"))
                .as("created and not activated cannot be used, which is the question being asked")
                .isEqualTo(AccountStatus.DISABLED);
        assertThat(statusOf("LOCKED_OUT")).isEqualTo(AccountStatus.LOCKED);
        assertThat(statusOf("DEPROVISIONED")).isEqualTo(AccountStatus.DELETED);
    }

    @Test
    void never_reports_a_state_it_does_not_know_as_working_access() {
        // A state Okta adds after this was written must not arrive as access
        // nobody granted.
        assertThat(statusOf("SOMETHING_OKTA_ADDED_LATER")).isEqualTo(AccountStatus.UNKNOWN);
    }

    @Test
    void reads_a_permission_out_of_a_group() {
        DiscoveredEntitlement entitlement = documents.group(documents.list("[" + BUILT_IN_GROUP + "]").get(0));

        assertThat(entitlement.nativeValue()).isEqualTo("group:00g1g3xk4nQQ1P7Rd697");
        assertThat(entitlement.displayName()).isEqualTo("Everyone");
        assertThat(entitlement.description()).isEqualTo("All users in your organization");
    }

    @Test
    void reads_a_permission_out_of_an_application() {
        DiscoveredEntitlement entitlement =
                documents.application(documents.list("[" + APPLICATION + "]").get(0));

        assertThat(entitlement.nativeValue()).isEqualTo("app:0oa1g3xk9mTT2Q1Rf697");
        assertThat(entitlement.displayName())
                .as("the label is what an administrator typed; the name is Okta's word for the kind of integration")
                .isEqualTo("Payments console");
    }

    @Test
    void cannot_confuse_a_group_with_an_application() {
        // Okta prefixes its own identifiers by type, which is a convention it
        // owns rather than a promise to anybody. The catalogue says which it is.
        String group = documents.group(documents.list("[" + BUILT_IN_GROUP + "]").get(0))
                .nativeValue();
        String application = documents.application(documents.list("[" + APPLICATION + "]").get(0))
                .nativeValue();

        assertThat(group).startsWith("group:");
        assertThat(application).startsWith("app:");
    }

    @Test
    void matches_an_assignment_to_the_application_and_not_to_the_assignment() {
        // An app link carries three identifiers. Only appInstanceId is the
        // application's: matching on id or appAssignmentId would leave every
        // assignment pointing at a catalogue entry that does not exist.
        assertThat(documents.applicationValues("[" + APP_LINK + "]")).containsExactly("app:0oa1g3xk9mTT2Q1Rf697");
    }

    @Test
    void reads_what_an_account_holds_from_the_two_places_okta_keeps_it() {
        List<String> held = documents.groupValues("[" + BUILT_IN_GROUP + "]");

        assertThat(held).containsExactly("group:00g1g3xk4nQQ1P7Rd697");
    }

    private DiscoveredAccount account(String document) {
        return documents.account(documents.list("[" + document + "]").get(0), List.of());
    }

    private AccountStatus statusOf(String oktaStatus) {
        return account(ACTIVE_USER.replace("\"status\":\"ACTIVE\"", "\"status\":\"" + oktaStatus + "\""))
                .status();
    }
}
