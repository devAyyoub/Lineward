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
package com.lineward.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The two properties of the client every outbound call uses.
 *
 * <p>Both were seen failing before they were seen passing, which is rule 2 of
 * ADR-014: the timeout test against {@code RestClient.create()}, where it is
 * stopped by the preemptive limit because the call never returns, and the
 * redirect test against a client built with {@code Redirect.NORMAL}, where the
 * second server receives the request.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OutboundHttpTests {

    private static final Duration SHORT = Duration.ofMillis(500);

    @Test
    void a_server_that_accepts_and_never_answers_is_given_up_on() throws IOException {
        try (SilentServer silent = new SilentServer()) {
            RestClient http = OutboundHttp.restClient("http://127.0.0.1:" + silent.port(), SHORT, SHORT);

            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(
                            () -> http.get().uri("/anything").retrieve().body(String.class))
                    .isInstanceOf(ResourceAccessException.class));
            assertThat(silent.connections())
                    .as("the connection opened, so what stopped the wait was the read limit and not a refusal")
                    .isPositive();
        }
    }

    /**
     * A credential goes to the address that was configured. A server that
     * answers "go and ask over there" does not get to forward this request,
     * and its bearer token, to a host nobody registered.
     */
    @Test
    void a_redirect_is_not_followed_to_another_server() throws IOException {
        AtomicInteger reachedElsewhere = new AtomicInteger();
        HttpServer elsewhere = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        elsewhere.createContext("/", exchange -> {
            reachedElsewhere.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        HttpServer redirecting = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        redirecting.createContext("/", exchange -> {
            exchange.getResponseHeaders()
                    .add("Location", "http://127.0.0.1:" + elsewhere.getAddress().getPort() + "/stolen");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        elsewhere.start();
        redirecting.start();
        try {
            RestClient http = OutboundHttp.restClient("http://127.0.0.1:" + redirecting.getAddress().getPort());

            http.get().uri("/users").header("Authorization", "Bearer secret-token").retrieve().toBodilessEntity();

            assertThat(reachedElsewhere).as("requests that reached the server the redirect pointed at").hasValue(0);
        } finally {
            redirecting.stop(0);
            elsewhere.stop(0);
        }
    }
}
