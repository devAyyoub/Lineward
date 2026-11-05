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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The calls this connector makes against Okta's administration API.
 *
 * <p>Returns response text rather than parsed documents, so that reading JSON
 * stays in {@link OktaDocuments} and this class has one job, which is the split
 * {@code KeycloakAdminApi} and {@code KeycloakDocuments} make for the same
 * reason: a parser reachable without a server is a parser a test can feed bytes
 * copied from the wire, and an Okta organization is the one target system this
 * project cannot start a copy of.
 *
 * <p><b>The continuation value is taken apart rather than followed.</b> Okta
 * pages by handing back the URL of the next page in a {@code Link} header, and
 * the obvious implementation requests that URL. This one reads the {@code
 * after} parameter out of it and rebuilds its own URL. The difference only
 * matters on the day a response is not what it should be, and then it matters a
 * lot: following a URL a response hands you is letting the far end choose where
 * the next authenticated request goes, with this connector's token attached.
 *
 * <p>Every failure leaves as a {@link ConnectorException}, which is what tells
 * the owner of a run that it is interrupted and resumable rather than
 * defective. The one exception is {@link #verifyReadable()}, which the
 * connector turns into an answer because an unreachable system is an answer.
 */
final class OktaAdminApi {

    /** {@code <https://org/api/v1/users?after=00u1&limit=50>; rel="next"}, and only the after is kept. */
    private static final Pattern NEXT_PAGE =
            Pattern.compile("<[^>]*[?&]after=([^&>]+)[^>]*>\\s*;\\s*rel=\"next\"");

    private final OktaCredentials credentials;
    private final OktaTokens tokens;
    private final RestClient http;

    OktaAdminApi(OktaCredentials credentials, Clock clock) {
        this.credentials = credentials;
        this.tokens = new OktaTokens(credentials, clock);
        this.http = RestClient.create();
    }

    /** One page of the organization's users. */
    Page users(int limit, String after) {
        return get(path("/api/v1/users", limit, after), "the organization's users");
    }

    /**
     * One page of the organization's groups, which are half of its catalogue.
     *
     * <p>Always at least one, and that is load bearing elsewhere: an Okta
     * organization is created with the built in {@code Everyone} group and
     * cannot be left without it. {@link #verifyReadable()} leans on that to
     * tell an empty organization from a blind credential.
     */
    Page groups(int limit, String after) {
        return get(path("/api/v1/groups", limit, after), "the organization's groups");
    }

    /**
     * One page of the organization's applications, the other half of the
     * catalogue.
     *
     * <p>In Okta an assignment to an application is a grant in its own right
     * and not a consequence of a group, so a catalogue of groups alone would
     * miss the access most organizations actually hand out.
     */
    Page apps(int limit, String after) {
        return get(path("/api/v1/apps", limit, after), "the organization's applications");
    }

    /**
     * The groups one user belongs to.
     *
     * <p>One call per user, which is the shape Okta offers, and the same cost
     * the Keycloak connector pays for the same reason: there is no endpoint
     * serving users with their memberships, and reading members per group
     * cannot be paged consistently alongside a page of users.
     */
    String groupsOfUser(String userId) {
        return get(credentials.apiUrl("/api/v1/users/" + encode(userId) + "/groups"), "the groups of a user").body();
    }

    /**
     * The applications one user is assigned to.
     *
     * <p>Separate from the groups because Okta assigns both directly, so a
     * user can hold an application without belonging to any group that grants
     * it. Verified on 2026-11-04 against {@code integrator-3500333}.
     */
    String appLinksOfUser(String userId) {
        return get(credentials.apiUrl("/api/v1/users/" + encode(userId) + "/appLinks"), "the applications of a user")
                .body();
    }

    /**
     * Whether the organization can be reached and genuinely read, and as whom.
     *
     * <p>Proves three things on purpose, and the third is the one this project
     * learned the hard way. A token says the key is registered and the client
     * is known. A read of one user says the scopes were granted. <b>And a read
     * of one group says the credential can actually see the directory</b>,
     * because an application holding the scopes and no administrator role is
     * served HTTP 200 and an empty list by the users endpoint, measured on
     * 2026-11-04. A check that stopped at the first two would pass for a
     * credential that reports every account in the organization as gone.
     *
     * @throws ConnectorException if any of the three cannot be shown
     */
    String verifyReadable() {
        users(1, null);
        if (groups(1, null).isEmpty()) {
            throw new ConnectorException(
                    ConnectorType.OKTA,
                    "Okta served no groups at all for " + credentials.orgUrl() + ", and every organization has the"
                            + " built in 'Everyone' group, so this credential is not seeing the directory."
                            + " A service application needs an administrator role on top of its scopes:"
                            + " with the scopes alone Okta answers reads with an empty list instead of a"
                            + " refusal, and a read reported as complete would make every account look deleted");
        }
        return "organization at " + credentials.orgUrl() + ", read as application '" + credentials.clientId() + "'";
    }

    private String path(String endpoint, int limit, String after) {
        String url = credentials.apiUrl(endpoint) + "?limit=" + limit;
        return after == null ? url : url + "&after=" + encode(after);
    }

    /**
     * One GET, with the two things Okta wants on every call and one retry.
     *
     * <p>The retry is for the nonce and nothing else. Okta may hand back a new
     * one at any point, including from the API and not only from the token
     * endpoint, and the request that gets told so is refused. Retrying anything
     * else here would hide a real failure behind a second attempt.
     */
    private Page get(String url, String what) {
        Answer answer = call(url);
        if (answer.asksForNonce()) {
            tokens.rememberNonce(answer.nonce());
            answer = call(url);
        }
        if (answer.status().value() == 403) {
            throw new ConnectorException(
                    ConnectorType.OKTA,
                    "Okta refused " + what + ". A service application reads with two things and not one:"
                            + " the read scopes, and an administrator role that says which resources it may"
                            + " see. This one is missing the role, or the role does not cover them");
        }
        if (!answer.status().is2xxSuccessful()) {
            throw new ConnectorException(
                    ConnectorType.OKTA, "Okta would not serve " + what + ": " + answer.describe());
        }
        if (answer.body() == null) {
            throw new ConnectorException(ConnectorType.OKTA, "Okta returned an empty body for " + what);
        }
        return new Page(answer.body(), nextAfter(answer.link()));
    }

    private Answer call(String url) {
        try {
            Answer answer = http.get()
                    .uri(url)
                    .header("Authorization", OktaTokens.scheme() + " " + tokens.accessToken())
                    .header("DPoP", tokens.proofFor("GET", url))
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .exchange(
                            (request, response) -> new Answer(
                                    response.getStatusCode(),
                                    response.getHeaders().getFirst("DPoP-Nonce"),
                                    response.getHeaders().get("Link"),
                                    response.bodyTo(String.class)),
                            false);
            tokens.rememberNonce(answer.nonce());
            return answer;
        } catch (RestClientException unreachable) {
            throw new ConnectorException(
                    ConnectorType.OKTA,
                    "Okta could not be reached at " + credentials.orgUrl() + ": " + unreachable.getMessage(),
                    unreachable);
        }
    }

    /**
     * The {@code after} of the next page, or {@code null} when Okta says there
     * is none.
     *
     * <p>Okta sends a {@code self} link on every response and a {@code next}
     * link only while there is more, which is exactly the shape the SPI wants:
     * the end of a read is the absence of a continuation and never an empty
     * page.
     */
    private static String nextAfter(List<String> linkHeaders) {
        if (linkHeaders == null) {
            return null;
        }
        for (String header : linkHeaders) {
            Matcher next = NEXT_PAGE.matcher(header);
            if (next.find()) {
                return decode(next.group(1));
            }
        }
        return null;
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * One page of a read: the documents, and where to carry on.
     *
     * @param body what Okta served, as text
     * @param after the continuation value, or {@code null} when this was the
     *              last page
     */
    record Page(String body, String after) {

        boolean isEmpty() {
            return body == null || body.isBlank() || body.strip().equals("[]");
        }
    }

    /** One answer, status and headers included, read without letting a 4xx throw. */
    private record Answer(HttpStatusCode status, String nonce, List<String> link, String body) {

        boolean asksForNonce() {
            return !status.is2xxSuccessful() && body != null && body.contains("use_dpop_nonce");
        }

        String describe() {
            return status + (body == null || body.isBlank() ? "" : " " + body);
        }
    }
}
