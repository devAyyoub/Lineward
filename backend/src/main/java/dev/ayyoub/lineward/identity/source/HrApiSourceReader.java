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
package dev.ayyoub.lineward.identity.source;

import java.time.Instant;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the HR system over HTTP.
 *
 * <p>Builds its own {@link RestClient} and its own {@link JsonMapper} instead of
 * taking autoconfigured ones, and both halves of that are deliberate. Spring
 * Boot 4 split autoconfiguration into one module per technology, so there is no
 * {@code RestClient.Builder} bean on this classpath unless
 * {@code spring-boot-restclient} is added, and {@code RestClient.create} needs
 * no builder. The mapper is private so that a change to the platform's own
 * serialization settings cannot quietly change how an external contract is
 * read.
 *
 * <p>Reads the response as text rather than binding it, because the raw record
 * is what {@code source_record.payload} stores.
 */
public class HrApiSourceReader implements AuthoritativeSourceReader {

    private static final String EMPLOYEES_PATH = "/api/v1/employees";

    private final RestClient http;
    private final HrRecordParser parser;

    public HrApiSourceReader(String baseUrl) {
        this.http = RestClient.create(baseUrl);
        this.parser = new HrRecordParser(JsonMapper.builder().build());
    }

    @Override
    public AssertedPage readPage(int page, int size, Instant changedSince) {
        String body = http.get()
                .uri(uri -> {
                    uri.path(EMPLOYEES_PATH).queryParam("page", page).queryParam("size", size);
                    if (changedSince != null) {
                        uri.queryParam("changedSince", changedSince.toString());
                    }
                    return uri.build();
                })
                .retrieve()
                .body(String.class);

        if (body == null) {
            throw new IllegalStateException("the HR system returned an empty body for page " + page);
        }
        return parser.parsePage(body);
    }
}
