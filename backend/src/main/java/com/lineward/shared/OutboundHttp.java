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

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client every outbound call to a system Lineward reads is made with.
 *
 * <p>It exists because the default one waits forever. {@code RestClient.create()}
 * picks the JDK client with no connect timeout and no read timeout, verified on
 * 2026-11-04 by reading {@code DefaultRestClientBuilder} in spring-web 7.0.10,
 * and a server that accepts the connection and then says nothing holds the
 * caller until somebody restarts the application. For Lineward that is not a
 * slow read, it is a control that stops: an HR ingestion stuck on one request
 * processes no leaver after it, and a stuck aggregation keeps the one live run
 * its application is allowed, so the next one cannot start either.
 *
 * <p>Redirects are refused, and said here because the JDK client already
 * refuses them by default: a request that carries a bearer token or a client
 * secret goes to the address that was configured and nowhere else, whatever the
 * server answers. Stating it keeps the property from depending on a default
 * nobody chose.
 *
 * <p>The two numbers are constants and not configuration on purpose. Nobody
 * has measured a reason to tune them, and a knob that exists before its reason
 * mostly gets set to zero.
 */
public final class OutboundHttp {

    /** How long a connection may take to open. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * How long a response may take to arrive once asked for. Generous, because
     * one page of a large realm or a large HR extract is a real query on the
     * other side; the point is that it ends, not that it is fast.
     */
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private OutboundHttp() {}

    /** A client with the default limits, rooted at {@code baseUrl}, or at nothing when it is null. */
    public static RestClient restClient(String baseUrl) {
        return restClient(baseUrl, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    /** The same with limits of the caller's choosing, which is what lets a test wait seconds and not minutes. */
    public static RestClient restClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(readTimeout);

        RestClient.Builder builder = RestClient.builder().requestFactory(requests);
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }
}
