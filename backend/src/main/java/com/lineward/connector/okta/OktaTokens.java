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

import com.lineward.connector.ConnectorException;
import com.lineward.connector.ConnectorType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gets an access token out of Okta and keeps it usable.
 *
 * <p><b>The first request is supposed to fail.</b> Okta answers a token
 * request that carries no {@code nonce} with HTTP 400 and
 * {@code use_dpop_nonce}, handing back the value to use in a header, and the
 * exchange only succeeds on the second attempt. That is the protocol working,
 * not an error, and it is why this class retries once by design rather than
 * through a generic retry policy that would treat a 400 as fatal.
 *
 * <p><b>The retry mints new credentials rather than resending the old ones.</b>
 * The client assertion's {@code jti} is single use: resending the one that was
 * refused is answered with {@code The client_assertion token has already been
 * used}, measured on 2026-11-04. Both facts were found by asking the
 * organization, and neither appears in the endpoint documentation this
 * connector was designed from.
 *
 * <p>The nonce is remembered afterwards, so the cost is one extra round trip
 * per process and not per token. Okta rotates it and says so by refusing again
 * with a new one, which is the same path.
 *
 * <p>The token is cached until it is nearly expired, for the reason
 * {@code KeycloakAdminApi} gives: an aggregation is thousands of calls and a
 * token request per call doubles the load on the identity provider for nothing.
 * Refreshed early rather than on a 401, because recovering from a 401 means
 * deciding whether the failure was the token or the authorisation, and those
 * have different answers.
 */
final class OktaTokens {

    /**
     * What this connector asks for, and the whole of it.
     *
     * <p>Four read scopes, verified against {@code integrator-3500333} on
     * 2026-11-04 by asking for {@code okta.users.manage} as well and being
     * refused with {@code consent_required}. Asking for more than is granted
     * does not degrade gracefully here: Okta refuses the whole request if it
     * can grant none of what was asked, so this list is also a statement that
     * the connector will stop working the day somebody revokes one of them,
     * instead of quietly reading less.
     */
    private static final String SCOPES = "okta.users.read okta.groups.read okta.apps.read okta.logs.read";

    /** Refreshed this long before it actually expires, so a read never starts with a token about to die. */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private static final String NONCE_HEADER = "DPoP-Nonce";

    private final OktaCredentials credentials;
    private final OktaProofs proofs;
    private final RestClient http;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    private String token;
    private Instant expiresAt = Instant.MIN;
    private String nonce;

    OktaTokens(OktaCredentials credentials, Clock clock) {
        this.credentials = credentials;
        this.clock = clock;
        this.proofs = new OktaProofs(credentials, clock);
        this.http = RestClient.create();
    }

    /** A token that is valid now, fetched on first use. */
    String accessToken() {
        if (token != null && clock.instant().isBefore(expiresAt)) {
            return token;
        }
        Attempt first = exchange();
        Attempt result = first.asksForNonce() ? exchange() : first;
        if (!result.status().is2xxSuccessful()) {
            throw new ConnectorException(
                    ConnectorType.OKTA,
                    "Okta would not issue a token to client " + credentials.clientId() + ": " + result.describe());
        }
        JsonNode document = json.readTree(result.body());
        String issued = document.path("access_token").asString(null);
        if (issued == null || issued.isBlank()) {
            throw new ConnectorException(
                    ConnectorType.OKTA,
                    "Okta's token response for client " + credentials.clientId() + " carried no access_token");
        }
        token = issued;
        expiresAt = clock.instant().plusSeconds(document.path("expires_in").asLong(60L)).minus(EXPIRY_MARGIN);
        return token;
    }

    /**
     * A proof for one request against the administration API.
     *
     * <p>Bound to the token being presented and to the method and URL of the
     * request, which is what makes a captured proof useless anywhere else.
     */
    String proofFor(String method, String url) {
        return proofs.dpopProof(method, url, accessToken(), nonce);
    }

    /**
     * Keeps the nonce Okta handed back from a resource request.
     *
     * <p>The API issues them too, not only the token endpoint, and the caller
     * is the only one that sees those responses.
     */
    void rememberNonce(String issued) {
        if (issued != null && !issued.isBlank()) {
            nonce = issued;
        }
    }

    /** The token scheme the API expects in the Authorization header, which is not Bearer. */
    static String scheme() {
        return "DPoP";
    }

    private Attempt exchange() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
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
            // Deliberately does not echo the form. It carries a signed
            // assertion, which is short lived but is still a credential.
            throw new ConnectorException(
                    ConnectorType.OKTA,
                    "Okta could not be reached at " + credentials.tokenUrl() + ": " + unreachable.getMessage(),
                    unreachable);
        }
    }

    /**
     * One answer from the token endpoint, status and all.
     *
     * <p>Read without letting a 4xx throw, because the 400 that asks for a
     * nonce is part of the protocol and its headers carry the value needed to
     * continue. A client that threw on it would have to recover a nonce from an
     * exception.
     */
    private record Attempt(HttpStatusCode status, String nonce, String body) {

        boolean asksForNonce() {
            return !status.is2xxSuccessful() && body != null && body.contains("use_dpop_nonce");
        }

        /** The error as Okta described it, which is safe to log: it describes the response. */
        String describe() {
            return status + (body == null || body.isBlank() ? "" : " " + body);
        }
    }
}
