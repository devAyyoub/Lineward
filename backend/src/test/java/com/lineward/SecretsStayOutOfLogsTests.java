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
package com.lineward;

import static org.assertj.core.api.Assertions.assertThat;

import com.lineward.aggregation.AggregationReport;
import com.lineward.aggregation.TargetSystemAggregation;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * No connector credential reaches the log, at the most verbose level anybody
 * would turn on to find out why a target system is failing.
 *
 * <p>The application runs whole, with a secret per connector that appears
 * nowhere else, and both targets fail on purpose, because failure is when
 * credentials leak: that is when a message carries what was sent, and when
 * somebody turns the logging up to read it. The directory refuses the
 * connection. The identity provider is a stand-in that refuses the token and,
 * like a careless or hostile server, echoes back the request it got, form
 * body and headers included.
 *
 * <p>Seen failing on 2026-11-04 before the Keycloak connector changed, twice
 * over: the DEBUG log of Spring's {@code DefaultRestClient} printed the token
 * request's form with {@code client_secret} in it, and the WARN line of the
 * aggregation quoted the echoing server's body, secret included.
 *
 * <p>Asserted on the whole captured output and not on one logger, because the
 * leak this guards against is the one nobody thought of: a framework logger at
 * DEBUG, not a line of this project's own.
 */
@SpringBootTest(properties = {
    "logging.level.com.lineward=DEBUG",
    "logging.level.org.springframework.web=DEBUG",
    "logging.level.org.springframework.http=DEBUG",
    "lineward.connector.targets.intranet.keycloak.client-secret=" + SecretsStayOutOfLogsTests.KEYCLOAK_SECRET,
    "lineward.connector.targets.corporate-directory.ldap.bind-password=" + SecretsStayOutOfLogsTests.LDAP_PASSWORD
})
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SecretsStayOutOfLogsTests {

    static final String KEYCLOAK_SECRET = "kc-sentinel-7f3a9c";
    static final String LDAP_PASSWORD = "ldap-sentinel-2b8e41";

    /**
     * The same secret as the Authorization header carries it. Encoded is not
     * hidden, so finding this in a log is the same leak as finding the secret.
     */
    private static final String BASIC_CREDENTIAL = Base64.getEncoder()
            .encodeToString(("lineward-aggregator:" + KEYCLOAK_SECRET).getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6")
            .withInitScript("db/test-roles.sql");

    private static final HttpServer ECHOING_KEYCLOAK = echoingKeycloak();

    @DynamicPropertySource
    static void pointAtTheStandIns(DynamicPropertyRegistry registry) throws IOException {
        int closed = closedPort();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("lineward.connector.targets.intranet.keycloak.base-url",
                () -> "http://127.0.0.1:" + ECHOING_KEYCLOAK.getAddress().getPort());
        registry.add("lineward.connector.targets.corporate-directory.ldap.url",
                () -> "ldap://127.0.0.1:" + closed);
    }

    @AfterAll
    static void stop() {
        ECHOING_KEYCLOAK.stop(0);
    }

    @Autowired
    private TargetSystemAggregation aggregation;

    @Test
    void a_failing_aggregation_logs_why_and_never_with_what_credential(CapturedOutput output) {
        List<AggregationReport> reports = aggregation.aggregateEverything();

        assertThat(output.getAll())
                .as("the failures were logged, so the absence below is a measurement and not a silence")
                .contains("did not start")
                .doesNotContain(KEYCLOAK_SECRET)
                .doesNotContain(BASIC_CREDENTIAL)
                .doesNotContain(LDAP_PASSWORD);
        assertThat(reports.toString())
                .as("nor in the reports, which a caller is free to print")
                .doesNotContain(KEYCLOAK_SECRET)
                .doesNotContain(LDAP_PASSWORD);
    }

    /** Answers every request 401, with the request it received as the body. */
    private static HttpServer echoingKeycloak() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                StringBuilder echo = new StringBuilder("{\"error\":\"unauthorized_client\",\"you_sent\":\"");
                exchange.getRequestHeaders().forEach((name, values) -> echo.append(name).append('=').append(values));
                try (InputStream body = exchange.getRequestBody()) {
                    echo.append(' ').append(new String(body.readAllBytes()));
                }
                byte[] bytes = echo.append("\"}").toString().getBytes();
                exchange.sendResponseHeaders(401, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException cannotStart) {
            throw new IllegalStateException(cannotStart);
        }
    }

    /** A port nothing listens on: taken from the system and released again. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
