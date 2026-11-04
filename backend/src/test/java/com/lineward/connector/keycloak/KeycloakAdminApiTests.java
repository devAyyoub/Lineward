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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.lineward.connector.ConnectorException;
import com.lineward.shared.OutboundHttp;
import com.lineward.shared.SilentServer;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the admin API client sends, measured on the wire against a stand-in
 * server rather than against Keycloak.
 *
 * <p>A stand-in and not the real thing on purpose: the property is what this
 * class puts in a request line, and a real Keycloak would answer 404 to a
 * hostile identifier and hide whether the request was the one intended. The
 * stand-in records the raw path, which is the only place the difference shows.
 * The contract with the real server is {@code KeycloakConnectorIntegrationTests}.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class KeycloakAdminApiTests {

    private static final Duration SHORT = Duration.ofMillis(500);

    /**
     * The identifiers in an admin path come from Keycloak's own answers, so
     * they are input from the target system. Seen failing before the fix: the
     * first test sent {@code /users/../../clients/x/groups}, which the server
     * normalises into a different endpoint, and the second threw
     * {@code IllegalArgumentException} from URI template expansion before any
     * request left.
     */
    @Nested
    class An_identifier_read_from_keycloak {

        private HttpServer server;
        private final List<String> paths = new CopyOnWriteArrayList<>();

        @BeforeEach
        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getRawPath();
                String body = path.endsWith("/protocol/openid-connect/token")
                        ? "{\"access_token\":\"stand-in\",\"expires_in\":300}"
                        : "[]";
                if (!path.endsWith("/token")) {
                    paths.add(exchange.getRequestURI().getRawPath()
                            + (exchange.getRequestURI().getRawQuery() == null
                                    ? ""
                                    : "?" + exchange.getRequestURI().getRawQuery()));
                }
                try (InputStream ignored = exchange.getRequestBody()) {
                    ignored.readAllBytes();
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        @AfterEach
        void stop() {
            server.stop(0);
        }

        private KeycloakAdminApi api() {
            return new KeycloakAdminApi(new KeycloakCredentials(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "lineward", "lineward-aggregator", "s3cret"));
        }

        @Test
        void stays_one_path_segment_even_when_it_climbs_out_of_it() {
            api().groupsOfUser("../../clients/x");

            assertThat(paths).containsExactly("/admin/realms/lineward/users/..%2F..%2Fclients%2Fx/groups");
        }

        @Test
        void cannot_add_a_query_or_break_the_template_with_a_brace() {
            api().serviceAccountOf("x?first=0&max=100000#{oops}");

            assertThat(paths)
                    .containsExactly("/admin/realms/lineward/clients/x%3Ffirst%3D0%26max%3D100000%23%7Boops%7D"
                            + "/service-account-user");
        }

        @Test
        void the_paging_values_still_arrive_as_a_query() {
            api().childGroups("parent", 200, 101);

            assertThat(paths).containsExactly("/admin/realms/lineward/groups/parent/children?first=200&max=101");
        }
    }

    /**
     * Seen failing before the fix, against {@code RestClient.create()}: the
     * call never returned and the preemptive limit stopped the test.
     */
    @Test
    void a_keycloak_that_accepts_and_never_answers_is_a_connector_failure_and_not_a_hang() throws IOException {
        try (SilentServer silent = new SilentServer()) {
            KeycloakAdminApi api = new KeycloakAdminApi(
                    new KeycloakCredentials(
                            "http://127.0.0.1:" + silent.port(), "lineward", "lineward-aggregator", "s3cret"),
                    OutboundHttp.restClient(null, SHORT, SHORT));

            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(() -> api.users(0, 1))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessageContaining("Keycloak refused a token")
                    .hasMessageNotContaining("s3cret"));
        }
    }
}
