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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The writing half of the Okta demo organization, and it lives in test scope on
 * purpose.
 *
 * <p><b>Why this is not in {@code src/main}.</b> The Okta connector is read
 * only and stays read only, decided on 2026-11-04: a target system connector
 * that can also write means one misconfiguration away from an aggregation run
 * mutating the system it was meant to observe. Seeding a demo organization is
 * not something the platform does, it is something a developer does once, so
 * the code that can write lives where the platform cannot reach it and runs
 * with a credential of its own.
 *
 * <p><b>Why it does not reuse {@link OktaTokens}.</b> That class hardcodes
 * three read scopes, which is minimum privilege expressed in code rather than
 * in configuration: there is no setting that makes the connector ask for
 * {@code okta.users.manage}. Making the scope list injectable so this class
 * could borrow it would throw that property away to save forty lines. What is
 * reused instead is {@link OktaProofs}, which holds the signing and the proof
 * of possession, because those are the parts that must never be written twice.
 *
 * <p>Everything else about the exchange is the same as the connector's, and for
 * the same reasons, which the 2026-11-04 findings explain: the first request is
 * refused so that Okta can hand back a nonce, the retry signs a fresh assertion
 * rather than resending the one that failed, and every call to the API carries
 * its own proof bound to the method, the URL and the token.
 */
final class OktaWriteApi {

    /**
     * What the seeder is allowed to do, and nothing more.
     *
     * <p>Four scopes, paired with the {@code lineward-seeder} custom role: the
     * scopes decide which endpoints may be called and the role decides which
     * resources are visible behind them, which is the finding of 2026-11-04 and
     * the reason a token alone is never enough.
     *
     * <p>{@code okta.schemas.read} is here and {@code okta.schemas.manage} is
     * not: reading the profile schema answers whether an attribute exists,
     * changing it is a different decision and gets asked for when it is needed.
     */
    private static final String SCOPES =
            "okta.users.manage okta.groups.manage okta.apps.manage okta.schemas.read";

    /** Renew before Okta would refuse, so a slow seeding run does not fail halfway. */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private static final String NONCE_HEADER = "DPoP-Nonce";

    private final OktaCredentials credentials;
    private final Clock clock;
    private final OktaProofs proofs;
    private final RestClient http = RestClient.create();
    private final JsonMapper json = JsonMapper.builder().build();

    private String token;
    private Instant expiresAt = Instant.MIN;
    private String nonce;

    OktaWriteApi(OktaCredentials credentials, Clock clock) {
        this.credentials = credentials;
        this.clock = clock;
        this.proofs = new OktaProofs(credentials, clock);
    }

    /**
     * The organization's user profile schema.
     *
     * <p>The cheapest call the seeder can make that proves the whole chain
     * works: it needs a signed assertion, a proof of possession, a nonce, a
     * granted scope and the administrator role, and it changes nothing. Run it
     * before anything that writes, so that a credential set up wrong is found
     * with zero accounts created rather than with half the slice in place.
     */
    JsonNode userSchema() {
        return parse(send("GET", credentials.apiUrl("/api/v1/meta/schemas/user/default"), null, "the user schema"));
    }

    /** Every user, one page at a time, so the seeder can tell a rerun from a first run. */
    Answer users(int limit, String after) {
        String url = credentials.apiUrl("/api/v1/users") + "?limit=" + limit + (after == null ? "" : "&after=" + encode(after));
        return send("GET", url, null, "the organization's users");
    }

    /** Every group, for the same reason. */
    Answer groups(int limit, String after) {
        String url = credentials.apiUrl("/api/v1/groups") + "?limit=" + limit + (after == null ? "" : "&after=" + encode(after));
        return send("GET", url, null, "the organization's groups");
    }

    /** Every application the credential can see, which is not necessarily every one that exists. */
    Answer apps(int limit, String after) {
        String url = credentials.apiUrl("/api/v1/apps") + "?limit=" + limit + (after == null ? "" : "&after=" + encode(after));
        return send("GET", url, null, "the organization's applications");
    }

    /**
     * Creates a user, activated and without sending anybody an email.
     *
     * <p>{@code activate=true} because an account that is not active is not the
     * account the demo is about, and {@code sendEmail=false} because these are
     * synthetic people at addresses nobody reads.
     */
    Answer createUser(String body) {
        return send(
                "POST",
                credentials.apiUrl("/api/v1/users") + "?activate=true&sendEmail=false",
                body,
                "a user");
    }

    /** Replaces a user's profile, which is how a rerun converges instead of duplicating. */
    Answer updateUser(String userId, String body) {
        return send("POST", credentials.apiUrl("/api/v1/users/" + encode(userId)), body, "a user's profile");
    }

    /** Creates a group. */
    Answer createGroup(String body) {
        return send("POST", credentials.apiUrl("/api/v1/groups"), body, "a group");
    }

    /**
     * Puts a user in a group.
     *
     * <p>Needs two permissions and not one, which the console says in both
     * directions: {@code Edit users' group membership} on the user and {@code
     * Manage group membership} on the group. The same shape as the scope and
     * the role, and the third time this organization has asked for two keys to
     * one door.
     */
    Answer addToGroup(String groupId, String userId) {
        return send(
                "PUT",
                credentials.apiUrl("/api/v1/groups/" + encode(groupId) + "/users/" + encode(userId)),
                null,
                "a group membership");
    }

    /** Assigns a user to an application, which in Okta is a grant of its own and not a consequence of a group. */
    Answer assignToApp(String appId, String body) {
        return send("POST", credentials.apiUrl("/api/v1/apps/" + encode(appId) + "/users"), body, "an application assignment");
    }

    /** Deactivates a user, which is the only way back from a slice that went in wrong. */
    Answer deactivateUser(String userId) {
        return send(
                "POST",
                credentials.apiUrl("/api/v1/users/" + encode(userId) + "/lifecycle/deactivate") + "?sendEmail=false",
                null,
                "a deactivation");
    }

    /** Deletes a deactivated user. Okta refuses this on an active one, which is a safety net and not an obstacle. */
    Answer deleteUser(String userId) {
        return send("DELETE", credentials.apiUrl("/api/v1/users/" + encode(userId)), null, "a deletion");
    }

    /**
     * One request, with the nonce dance and nothing else.
     *
     * <p>Unlike the connector this does not turn a non 2xx into an exception.
     * A seeder asks questions whose interesting answer is a refusal: a 409
     * means the account is already there and the run is a rerun, a 403 means
     * the role is too narrow. Swallowing those into one exception type would
     * make a rerun indistinguishable from a broken credential.
     */
    Answer send(String method, String url, String body, String what) {
        Answer answer = attempt(method, url, body, what);
        if (answer.asksForNonce()) {
            answer = attempt(method, url, body, what);
        }
        return answer;
    }

    private Answer attempt(String method, String url, String body, String what) {
        try {
            RestClient.RequestBodySpec request = http.method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(url)
                    .header("Authorization", "DPoP " + accessToken())
                    .header("DPoP", proofs.dpopProof(method, url, accessToken(), nonce))
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE);
            if (body != null) {
                request = request.contentType(MediaType.APPLICATION_JSON);
                request.body(body);
            }
            Answer answer = request.exchange(
                    (outgoing, response) -> new Answer(
                            response.getStatusCode(),
                            response.getHeaders().getFirst(NONCE_HEADER),
                            response.bodyTo(String.class),
                            what),
                    false);
            rememberNonce(answer.nonce());
            return answer;
        } catch (RestClientException unreachable) {
            throw new IllegalStateException(
                    "Okta could not be reached for " + what + ": " + unreachable.getMessage(), unreachable);
        }
    }

    private String accessToken() {
        if (token != null && clock.instant().isBefore(expiresAt)) {
            return token;
        }
        Attempt first = exchange();
        Attempt result = first.asksForNonce() ? exchange() : first;
        if (!result.status().is2xxSuccessful()) {
            throw new IllegalStateException(
                    "Okta would not issue a seeding token to client " + credentials.clientId() + ": "
                            + result.status() + " " + result.body());
        }
        JsonNode document = json.readTree(result.body());
        String issued = document.path("access_token").asString(null);
        if (issued == null || issued.isBlank()) {
            throw new IllegalStateException("Okta's token response carried no access_token");
        }
        token = issued;
        expiresAt = clock.instant().plusSeconds(document.path("expires_in").asLong(60L)).minus(EXPIRY_MARGIN);
        return token;
    }

    private Attempt exchange() {
        org.springframework.util.MultiValueMap<String, String> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", SCOPES);
        form.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        form.add("client_assertion", proofs.clientAssertion());
        try {
            Attempt attempt = http.post()
                    .uri(credentials.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("DPoP", proofs.dpopProof("POST", credentials.tokenUrl(), null, nonce))
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .body(form)
                    .exchange(
                            (request, response) -> new Attempt(
                                    response.getStatusCode(),
                                    response.getHeaders().getFirst(NONCE_HEADER),
                                    response.bodyTo(String.class)),
                            false);
            rememberNonce(attempt.nonce());
            return attempt;
        } catch (RestClientException unreachable) {
            // Deliberately does not echo the form: it carries a signed assertion.
            throw new IllegalStateException(
                    "Okta's token endpoint could not be reached: " + unreachable.getMessage(), unreachable);
        }
    }

    private void rememberNonce(String issued) {
        if (issued != null && !issued.isBlank()) {
            nonce = issued;
        }
    }

    private JsonNode parse(Answer answer) {
        if (!answer.status().is2xxSuccessful()) {
            throw new IllegalStateException("Okta refused " + answer.what() + ": " + answer.describe());
        }
        return json.readTree(answer.body());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** One answer from the administration API, refusals included. */
    record Answer(HttpStatusCode status, String nonce, String body, String what) {

        boolean asksForNonce() {
            return !status.is2xxSuccessful() && body != null && body.contains("use_dpop_nonce");
        }

        boolean ok() {
            return status.is2xxSuccessful();
        }

        /** Okta's way of saying the thing is already there, which a rerun treats as success. */
        boolean alreadyThere() {
            return status.value() == 409;
        }

        String describe() {
            return status + " " + (body == null ? "<no body>" : body);
        }
    }

    /** One answer from the token endpoint. */
    private record Attempt(HttpStatusCode status, String nonce, String body) {

        boolean asksForNonce() {
            return !status.is2xxSuccessful() && body != null && body.contains("use_dpop_nonce");
        }
    }
}
