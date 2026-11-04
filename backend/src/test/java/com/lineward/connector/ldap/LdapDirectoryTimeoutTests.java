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
package com.lineward.connector.ldap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.lineward.connector.ConnectorException;
import com.lineward.shared.SilentServer;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * A directory that accepts the connection and never answers the bind.
 *
 * <p>Seen failing before the fix, with the two timeout properties taken out of
 * the environment: the bind waited for an answer that never came, and the
 * preemptive limit stopped the test.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LdapDirectoryTimeoutTests {

    private static final Duration SHORT = Duration.ofMillis(500);

    @Test
    void a_directory_that_never_answers_is_a_connector_failure_and_not_a_hang() throws IOException {
        try (SilentServer silent = new SilentServer()) {
            LdapCredentials credentials = new LdapCredentials(
                    "ldap://127.0.0.1:" + silent.port(),
                    "ou=users,dc=lineward,dc=example",
                    "ou=groups,dc=lineward,dc=example",
                    "cn=lineward-aggregator,ou=services,dc=lineward,dc=example",
                    "reader-password",
                    null);

            try (LdapDirectory directory = new LdapDirectory(credentials, SHORT, SHORT)) {
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(
                                () -> directory.accounts(10, null))
                        .isInstanceOf(ConnectorException.class)
                        .hasMessageNotContaining("reader-password"));
            }
            assertThat(silent.connections())
                    .as("the connection opened, so what stopped the wait was the read limit and not a refusal")
                    .isPositive();
        }
    }
}
