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

import com.lineward.connector.ConnectorException;
import com.lineward.connector.ConnectorType;
import com.lineward.shared.OutboundHttp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The four calls this connector makes against Keycloak's admin API, and the
 * token that authorises them.
 *
 * <p>Returns response text rather than parsed documents, so that reading JSON
 * stays in {@link KeycloakDocuments} and this class has exactly one job. The
 * pair mirrors {@code HrApiSourceReader} and {@code HrRecordParser}, for the
 * reason given there: a parser reachable without a server is a parser a test can
 * feed bytes copied from the wire.
 *
 * <p>Builds its own {@link RestClient}, because Spring Boot 4 split
 * autoconfiguration per technology and there is no {@code RestClient.Builder}
 * bean on this classpath unless {@code spring-boot-restclient} is added.
 *
 * <p>Every failure here leaves as a {@link ConnectorException}, which is what
 * tells the owner of an aggregation run that the run is interrupted and
 * resumable rather than defective. The one exception is
 * {@link #describeReachability()}, which answers instead of throwing because an
 * unreachable system is an answer.
 */
class KeycloakAdminApi {

    /** Refreshed this long before it actually expires, so a read never starts with a token about to die. */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final KeycloakCredentials credentials;
    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    private String token;
    private Instant tokenExpiresAt = Instant.MIN;

    KeycloakAdminApi(KeycloakCredentials credentials) {
        this(credentials, OutboundHttp.restClient(null));
    }

    /** With a client of the caller's choosing, which is how a test gets limits it can wait for. */
    KeycloakAdminApi(KeycloakCredentials credentials, RestClient http) {
        this.credentials = credentials;
        this.http = http;
    }

    /**
     * One page of the realm's users, by offset.
     *
     * <p><strong>It does not return service accounts.</strong> Verified on
     * 26.7.5: a realm holding one client service account and no people serves an
     * empty list here, while
     * {@code /users?username=service-account-...&exact=true} finds it. So the
     * omission is a filter on this endpoint and not a missing right, confirmed
     * with the full administrator credential as well as this connector's.
     *
     * <p>That matters more to Lineward than to most callers, because a client
     * service account is exactly the kind of non-human identity this platform
     * exists to govern, and an aggregation built on this endpoint alone cannot
     * see one. Which is why {@link #clients} and {@link #serviceAccountOf}
     * exist: a read covers this endpoint and then those, and the cost is
     * {@code view-clients} on top of {@code view-users}.
     */
    String users(int first, int max) {
        return get(credentials.adminUrl() + "/users?first=" + first + "&max=" + max, "the realm's users");
    }

    /**
     * The groups one user belongs to.
     *
     * <p>One call per user, which is the shape Keycloak offers: there is no
     * endpoint that serves users with their memberships, and reading the other
     * way round, members per group, cannot be paged consistently alongside a
     * page of users. The SPI anticipated this and says each connector inverts
     * whichever direction is cheap, so the cost of the choice stays here.
     */
    String groupsOfUser(String userId) {
        return get(credentials.adminUrl() + "/users/" + userId + "/groups", "the groups of user " + userId);
    }

    /**
     * One page of the realm's top level groups.
     *
     * <p>Top level only, verified on 26.7.5: this endpoint returns
     * {@code subGroups} empty even for a group that has children. The children
     * come from {@link #childGroups}, which is why reading the catalogue is a
     * walk.
     */
    String topLevelGroups(int first, int max) {
        return get(credentials.adminUrl() + "/groups?first=" + first + "&max=" + max, "the realm's top level groups");
    }

    /** One page of the children of a group. */
    String childGroups(String groupId, int first, int max) {
        return get(
                credentials.adminUrl() + "/groups/" + groupId + "/children?first=" + first + "&max=" + max,
                "the children of group " + groupId);
    }

    /**
     * One page of the realm's clients, by offset.
     *
     * <p>Here only because a client is where a service account lives, and a
     * service account is the half of the population {@link #users} cannot see.
     * Nothing else in this connector cares about clients: the catalogue is
     * groups, and client roles are a different kind of permission that nothing
     * reads yet.
     *
     * <p><strong>This is the call that needs {@code view-clients}.</strong> A
     * realm that grants only {@code view-users} answers it with 403, and the
     * message says which role is missing, because the alternative is somebody
     * reading "Keycloak would not serve the realm's clients" and checking the
     * network.
     */
    String clients(int first, int max) {
        return get(credentials.adminUrl() + "/clients?first=" + first + "&max=" + max, "the realm's clients");
    }

    /**
     * The service account of one client, as a user representation.
     *
     * <p>The same shape {@link #users} returns, which is what lets one parser
     * handle both, and it is also why the account's kind cannot be read from the
     * document: the document does not say. What says it is having come from
     * here.
     *
     * <p>Only call it for a client whose {@code serviceAccountsEnabled} is true.
     * For any other, 26.7.5 answers 404, and a 404 here would be reported as a
     * target system that would not serve something it was asked for, which it
     * is, except that asking was the mistake.
     */
    String serviceAccountOf(String clientUuid) {
        return get(
                credentials.adminUrl() + "/clients/" + clientUuid + "/service-account-user",
                "the service account of client " + clientUuid);
    }

    /**
     * Reads one row to prove the realm is reachable and readable, and describes
     * what was reached.
     *
     * <p>Proves both halves on purpose: a token says the credential is valid,
     * and a one row read says the service account is actually allowed to list
     * users. A check that only fetched a token would pass for a client whose
     * {@code view-users} role had been removed, and the first real aggregation
     * would be where that turned up instead.
     *
     * <p>Throws rather than returning a verdict, so that the caller decides what
     * an unreachable system means. Turning it into an answer is the connector's
     * job, because {@code ConnectionCheck} is the SPI's vocabulary and not this
     * class's.
     *
     * @return what was reached and as whom, never the secret
     * @throws ConnectorException if the realm could not be reached or read
     */
    String verifyReadable() {
        users(0, 1);
        return "realm '" + credentials.realm() + "' at " + credentials.baseUrl() + ", read as client '"
                + credentials.clientId() + "'";
    }

    private String get(String url, String what) {
        try {
            String body = http.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + token())
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                throw new ConnectorException(ConnectorType.KEYCLOAK, "Keycloak returned an empty body for " + what);
            }
            return body;
        } catch (HttpClientErrorException.Forbidden denied) {
            // Named, because this one has exactly one cause and the generic
            // message sends the reader to look at the network. A realm that
            // grants view-users and not view-clients is the credential every
            // deployment starts from, including this repository's own until
            // 2026-11-04, so it is the expected failure and not an exotic one.
            throw new ConnectorException(
                    ConnectorType.KEYCLOAK,
                    "Keycloak refused " + what + ". Reading a client's service accounts needs the"
                            + " 'view-clients' role on this connector's service account, on top of"
                            + " 'view-users'. Without it a read cannot see the realm's machine accounts,"
                            + " and reporting it as complete would make every one of them look deleted",
                    denied);
        } catch (RestClientException problem) {
            // The message of the cause describes the response, not the request,
            // so it cannot contain the client secret. The URL can contain a user
            // identifier and no credential.
            throw new ConnectorException(
                    ConnectorType.KEYCLOAK, "Keycloak would not serve " + what + ": " + problem.getMessage(), problem);
        }
    }

    /**
     * A valid access token, fetched on first use and reused until it is nearly
     * expired.
     *
     * <p>Cached because an aggregation is thousands of calls and a token request
     * per call would multiply the load on the identity provider by two for no
     * gain. Refreshed early rather than on a 401, because recovering from a 401
     * means deciding whether the failure was the token or the authorisation, and
     * those have different answers.
     */
    private String token() {
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
            return token;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", credentials.clientId());
        form.add("client_secret", credentials.clientSecret());

        try {
            String body = http.post()
                    .uri(credentials.realmUrl() + "/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                throw new ConnectorException(
                        ConnectorType.KEYCLOAK, "Keycloak returned no token document for client "
                                + credentials.clientId());
            }
            var document = json.readTree(body);
            String fetched = document.path("access_token").asString(null);
            if (fetched == null || fetched.isBlank()) {
                throw new ConnectorException(
                        ConnectorType.KEYCLOAK,
                        "Keycloak's token response for client " + credentials.clientId() + " carried no access_token");
            }
            long lifetime = document.path("expires_in").asLong(60L);
            token = fetched;
            tokenExpiresAt = Instant.now().plusSeconds(lifetime).minus(EXPIRY_MARGIN);
            return token;
        } catch (RestClientException refused) {
            // Deliberately does not echo the form: that is the one place the
            // secret exists in this class, and a 401 here is almost always the
            // secret being wrong, which is exactly when somebody is tempted to
            // print it.
            throw new ConnectorException(
                    ConnectorType.KEYCLOAK,
                    "Keycloak refused a token for client " + credentials.clientId() + " on realm "
                            + credentials.realm() + ": " + refused.getMessage(),
                    refused);
        }
    }
}
