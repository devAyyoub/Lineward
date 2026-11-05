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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The only tests in this project that need somebody's account rather than a
 * container.
 *
 * <p>Keycloak and OpenLDAP start with their integration tests, so those
 * connectors are measured against a real server on every build, by anybody.
 * There is no Okta to start: an organization belongs to a person, its
 * credential is a private key on that person's machine, and neither can go
 * into this repository. So these run where the four variables are set and
 * skip everywhere else, including in continuous integration.
 *
 * <p><b>A test that skips is not a test</b>, so the rest of the connector is
 * pinned down without an organization: {@code OktaProofsTests} checks what gets
 * signed and {@code OktaDocumentsTests} checks the parsing against documents
 * captured from a real organization. What only these can show is that the three
 * stacked mechanisms work together against the real thing, which is exactly the
 * part that no amount of local reasoning would have got right.
 *
 * <p>To run them, point the shell at the credential described in
 * {@code docs/private/PROYECTO.md} and run the build as usual:
 *
 * <pre>
 * set -a; . ~/.lineward/okta.env; set +a
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "OKTA_ORG_URL", matches = ".+")
class OktaAdminApiLiveTests {

    private final OktaAdminApi api = new OktaAdminApi(fromEnvironment(), Clock.systemUTC());

    @Test
    void authenticates_with_a_key_a_nonce_and_a_proof() {
        // Three mechanisms stacked, and the exchange is designed to fail once:
        // the first request is refused so Okta can hand back the nonce. If this
        // passes, all of it worked, including minting a second assertion for
        // the retry rather than resending the first.
        String reached = api.verifyReadable();

        assertThat(reached).contains("organization at").contains("read as application");
    }

    @Test
    void reads_the_organizations_users() {
        OktaAdminApi.Page page = api.users(5, null);

        assertThat(page.isEmpty())
                .as("an organization always has at least the administrator who created it")
                .isFalse();
        assertThat(page.body()).startsWith("[");
    }

    @Test
    void pages_with_the_continuation_okta_hands_back_and_not_with_a_number() {
        OktaAdminApi.Page first = api.groups(1, null);

        assertThat(first.after())
                .as("the organization has more than one group, so Okta sends a next link")
                .isNotNull();

        OktaAdminApi.Page second = api.groups(1, first.after());

        assertThat(second.body()).isNotEqualTo(first.body());
    }

    @Test
    void says_a_read_is_finished_by_leaving_the_continuation_out() {
        // The end of a read is the absent cursor and never an empty page, which
        // is what the SPI promises callers. Asking for more groups than exist
        // is how Okta is made to say it.
        OktaAdminApi.Page everything = api.groups(200, null);

        assertThat(everything.after()).isNull();
        assertThat(everything.isEmpty()).isFalse();
    }

    @Test
    void reads_what_one_account_holds_from_two_different_places() {
        String userId = firstUserId();

        assertThat(api.groupsOfUser(userId)).startsWith("[");
        assertThat(api.appLinksOfUser(userId))
                .as("an application assignment in Okta is a grant of its own, not a consequence of a group")
                .startsWith("[");
    }

    private String firstUserId() {
        OktaAdminApi.Page page = api.users(1, null);
        // Checked rather than assumed: without it, a credential that cannot see
        // the directory makes this helper throw StringIndexOutOfBounds, which
        // sends whoever reads the report looking at the wrong thing. Seen
        // happen on 2026-11-05, with the role removed on purpose.
        assertThat(page.isEmpty())
                .as("no users came back, so this credential cannot see the directory")
                .isFalse();
        String body = page.body();
        int id = body.indexOf("\"id\":\"");
        return body.substring(id + 6, body.indexOf('"', id + 6));
    }

    /**
     * The credential, from the environment and never from a file in this
     * repository.
     *
     * <p>The key is read from the path the environment names rather than from
     * a variable holding the key itself, so that it keeps file permissions and
     * never appears in a process listing or a shell history.
     */
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
