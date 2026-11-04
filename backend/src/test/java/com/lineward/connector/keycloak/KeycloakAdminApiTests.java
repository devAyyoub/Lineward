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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.lineward.connector.ConnectorException;
import com.lineward.shared.OutboundHttp;
import com.lineward.shared.SilentServer;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * How the admin API client behaves on the wire, against stand-in servers
 * rather than against Keycloak.
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
