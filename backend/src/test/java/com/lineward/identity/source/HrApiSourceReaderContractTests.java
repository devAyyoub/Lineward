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
package com.lineward.identity.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the reader does with the bytes the HR system really sends.
 *
 * <p>The payloads below are not constructed: they are the response of
 * {@code GET /api/v1/employees} from the running simulator on 2026-10-21, pasted
 * in. A test that builds the response from this project's own classes proves
 * that the project agrees with itself, which is what let a broken contract pass
 * sixteen times on 2026-10-16.
 *
 * <p>Over a real socket, with the JDK's own HTTP server, so the query string the
 * reader sends is observed rather than assumed. The incremental read is a
 * question asked in a query parameter, and getting that parameter wrong is
 * invisible to any test that stubs the call.
 */
class HrApiSourceReaderContractTests {

    private static final String FIRST_PAGE = """
            {"content":[{"employeeNumber":"E-0001","firstName":"Carmen","lastName":"Munoz",\
            "email":"carmen.munoz@lineward.example","department":"Executive Office",\
            "jobTitle":"Chief Executive Officer","location":"Madrid","costCenter":"CC-EXE-001",\
            "employmentType":"EMPLOYEE","hireDate":"2015-09-10","terminationDate":null,\
            "managerEmployeeNumber":null,"lastModifiedAt":"2015-09-10T00:00:00Z"},\
            {"employeeNumber":"E-0002","firstName":"Brais","lastName":"Castro",\
            "email":"brais.castro@lineward.example","department":"Engineering",\
            "jobTitle":"Head of Engineering","location":"Remote","costCenter":"CC-ENG-100",\
            "employmentType":"EMPLOYEE","hireDate":"2018-04-27","terminationDate":null,\
            "managerEmployeeNumber":"E-0001","lastModifiedAt":"2018-04-27T00:00:00Z"}],\
            "page":0,"size":2,"totalElements":5000,"totalPages":2500}""";

    /** E-3894, the retroactive termination planted in the generated population. */
    private static final String RETROACTIVE_TERMINATION = """
            {"content":[{"employeeNumber":"E-3894","firstName":"Jimena","lastName":"Gonzalez",\
            "email":"jimena.gonzalez2@lineward.example","department":"Procurement",\
            "jobTitle":"Buyer","location":"Barcelona","costCenter":"CC-PRC-300",\
            "employmentType":"EMPLOYEE","hireDate":"2025-12-16","terminationDate":"2026-01-15",\
            "managerEmployeeNumber":"E-3571","lastModifiedAt":"2026-10-15T00:00:00Z"}],\
            "page":0,"size":100,"totalElements":1,"totalPages":1}""";

    /** E-2028, the record with no email: correlation by email will not resolve it. */
    private static final String MISSING_EMAIL = """
            {"content":[{"employeeNumber":"E-2028","firstName":"Nerea","lastName":"Santos",\
            "email":null,"department":"Operations","jobTitle":"Operations Analyst",\
            "location":"Bilbao","costCenter":"CC-OPS-300","employmentType":"EMPLOYEE",\
            "hireDate":"2024-07-22","terminationDate":null,"managerEmployeeNumber":"E-1328",\
            "lastModifiedAt":"2026-10-15T00:00:00Z"}],\
            "page":0,"size":100,"totalElements":1,"totalPages":1}""";

    private static HttpServer server;
    private static final List<String> requestedQueries = new ArrayList<>();
    private static volatile String response = FIRST_PAGE;
    private static HrApiSourceReader reader;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/employees", HrApiSourceReaderContractTests::serve);
        server.start();
        reader = new HrApiSourceReader("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void resetServer() {
        requestedQueries.clear();
        response = FIRST_PAGE;
    }

    private static void serve(HttpExchange exchange) throws IOException {
        requestedQueries.add(exchange.getRequestURI().getQuery());
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void readsTheFieldsAndTheTotalsTheSourceReports() {
        AssertedPage page = reader.readPage(0, 2, null);

        assertThat(page.totalElements()).isEqualTo(5000);
        assertThat(page.totalPages()).isEqualTo(2500);
        assertThat(page.records()).hasSize(2);

        AssertedIdentity chief = page.records().getFirst();
        assertThat(chief.externalId()).isEqualTo("E-0001");
        assertThat(chief.displayName()).isEqualTo("Carmen Munoz");
        assertThat(chief.department()).isEqualTo("Executive Office");
        assertThat(chief.hireDate()).isEqualTo(LocalDate.of(2015, 9, 10));
        assertThat(chief.terminationDate()).isNull();
        assertThat(chief.assertedAt()).isEqualTo(Instant.parse("2015-09-10T00:00:00Z"));

        // Whoever runs the company reports to nobody, which is the one case of a
        // missing manager that is not a finding.
        assertThat(chief.managerExternalId()).isNull();
        assertThat(page.records().get(1).managerExternalId()).isEqualTo("E-0001");
    }

    /** The payload is stored verbatim, so a field this code ignores is not lost. */
    @Test
    void keepsTheRecordAsItArrived() {
        AssertedIdentity chief = reader.readPage(0, 2, null).records().getFirst();

        assertThat(chief.rawPayload())
                .startsWith("{\"employeeNumber\":\"E-0001\"")
                .contains("\"costCenter\":\"CC-EXE-001\"")
                .contains("\"lastModifiedAt\":\"2015-09-10T00:00:00Z\"");
    }

    @Test
    void asksForTheWholePopulationWhenNoInstantIsGiven() {
        reader.readPage(3, 100, null);

        assertThat(requestedQueries).containsExactly("page=3&size=100");
    }

    /**
     * The incremental read is this query parameter and nothing else, so it is
     * worth asserting on the string that leaves the process.
     */
    @Test
    void asksOnlyForWhatChangedWhenGivenAnInstant() {
        reader.readPage(0, 100, Instant.parse("2026-10-20T09:00:00Z"));

        assertThat(requestedQueries)
                .containsExactly("page=0&size=100&changedSince=2026-10-20T09:00:00Z");
    }

    @Test
    void readsARetroactiveTerminationWithBothDates() {
        response = RETROACTIVE_TERMINATION;

        AssertedIdentity left = reader.readPage(0, 100, null).records().getFirst();

        assertThat(left.hireDate()).isEqualTo(LocalDate.of(2025, 12, 16));
        assertThat(left.terminationDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        // The source said on the fifteenth of October that she left in January.
        // Those are two different instants and collapsing them loses the window
        // in which her access was still live.
        assertThat(left.assertedAt()).isEqualTo(Instant.parse("2026-10-15T00:00:00Z"));
    }

    @Test
    void acceptsARecordWithFieldsMissing() {
        response = MISSING_EMAIL;

        AssertedIdentity incomplete = reader.readPage(0, 100, null).records().getFirst();

        assertThat(incomplete.email()).isNull();
        assertThat(incomplete.displayName()).isEqualTo("Nerea Santos");
    }

    /**
     * A source that will not say when it changed a record cannot support an
     * incremental read, and defaulting to now would make every record look fresh
     * for ever. Failing names the problem instead.
     */
    @Test
    void refusesARecordWithNoModificationTimestamp() {
        response = """
                {"content":[{"employeeNumber":"E-0001","firstName":"Carmen","lastName":"Munoz",\
                "department":"Executive Office"}],"page":0,"size":100,"totalElements":1,"totalPages":1}""";

        assertThatThrownBy(() -> reader.readPage(0, 100, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("E-0001")
                .hasMessageContaining("lastModifiedAt");
    }

    @Test
    void refusesAResponseThatIsNotAPage() {
        response = "{\"error\":\"nope\"}";

        assertThatThrownBy(() -> reader.readPage(0, 100, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("content array");
    }
}
