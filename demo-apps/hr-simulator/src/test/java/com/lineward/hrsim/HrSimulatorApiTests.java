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
package com.lineward.hrsim;

import com.lineward.hrsim.model.EmployeePage;
import com.lineward.hrsim.model.EmployeeUpdateRequest;
import com.lineward.hrsim.model.EmployeeView;
import com.lineward.hrsim.model.NewEmployeeRequest;
import com.lineward.hrsim.model.RehireRequest;
import com.lineward.hrsim.model.SeededAnomalyView;
import com.lineward.hrsim.model.TerminationRequest;
import com.lineward.hrsim.store.EmployeeStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the simulated HR system over real HTTP.
 *
 * <p>Over HTTP and not against the store directly, because what phase 1 will
 * consume is the API: the paging, the status codes and the JSON shape are the
 * contract, and a test that calls the store would pass while the contract was
 * broken.
 *
 * <p>A smaller company than the configured default keeps the run quick; the
 * seed is the same, so the population is still a fixed one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Spring Boot 4 no longer contributes a TestRestTemplate just because the web
// environment is a real one: the client moved to its own module and asking for
// it is now explicit. Verified 2026-10-16 by injecting it without this and
// getting NoSuchBeanDefinitionException.
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "hr-simulator.seed=20261016",
        "hr-simulator.headcount=300",
        "hr-simulator.as-of=2026-10-16",
        "hr-simulator.anomaly-rate=0.05"
})
class HrSimulatorApiTests {

    private static final Instant START = Instant.parse("2026-10-16T09:00:00Z");

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock testClock() {
            return new MutableClock(START);
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private Clock clock;

    @Autowired
    private EmployeeStore store;

    private MutableClock clock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void resetBetweenTests() {
        // Each test provokes lifecycle events, and they would otherwise leak
        // into the next one. This is also the reset the public demo needs
        // between runs (section 3.2).
        store.reset();
        // The clock too. It is a singleton in a cached context, so a test that
        // advances it moves the starting point of every test that runs after,
        // and then a failure depends on execution order.
        clock().set(START);
    }

    @Test
    @DisplayName("serves the generated population, paged, with an honest total")
    void servesPagedPopulation() {
        EmployeePage first = page(0, 100, null);

        assertThat(first.content()).hasSize(100);
        assertThat(first.totalElements()).isEqualTo(300);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.content().getFirst().employeeNumber()).isEqualTo("E-0001");
    }

    @Test
    @DisplayName("dates go out as ISO-8601 strings, not as epoch numbers")
    void datesAreIso8601() {
        String json = rest.getForObject("/api/v1/employees/E-0002", String.class);

        // Asserted on the raw JSON because this now rests on a Jackson 3
        // default rather than on configuration: the property that used to pin it
        // does not exist any more. A connector should not have to guess whether
        // a number is seconds or milliseconds, and a minor upgrade that flipped
        // this back would otherwise be found by whoever wrote the connector.
        assertThat(json).contains("\"hireDate\":\"20");
        assertThat(json).contains("\"lastModifiedAt\":\"20");
    }

    @Test
    @DisplayName("paging over every page returns everybody exactly once")
    void pagingCoversEverybodyOnce() {
        List<String> collected = new ArrayList<>();
        int page = 0;
        EmployeePage current;
        do {
            current = page(page, 40, null);
            current.content().forEach(e -> collected.add(e.employeeNumber()));
            page++;
        } while (page < current.totalPages());

        // The failure this catches is the classic one: paging over an unordered
        // collection, which skips some rows and repeats others. Downstream, a
        // skipped employee is indistinguishable from a termination.
        assertThat(collected).hasSize(300);
        assertThat(new HashSet<>(collected)).hasSize(300);
    }

    @Test
    @DisplayName("changedSince returns only what moved, which is what an incremental ingest reads")
    void changedSinceIsIncremental() {
        Instant beforeChange = clock().instant();
        clock().advance(Duration.ofMinutes(5));

        EmployeeView moved = patch("E-0200", new EmployeeUpdateRequest(
                null, null, null, "Risk", "Risk Analyst", null, null, null, null, false, false));
        assertThat(moved.department()).isEqualTo("Risk");

        EmployeePage delta = page(0, 100, beforeChange);

        assertThat(delta.content())
                .as("only the moved employee changed after %s", beforeChange)
                .extracting(EmployeeView::employeeNumber).containsExactly("E-0200");
        assertThat(delta.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("a joiner gets a fresh employee number, never a reused one")
    void hireIssuesFreshNumber() {
        Set<String> before = new HashSet<>(allNumbers());

        ResponseEntity<EmployeeView> response = rest.postForEntity("/api/v1/employees",
                new NewEmployeeRequest("Nadia", "Lopez", "nadia.lopez@lineward.example",
                        "Treasury", "Treasury Analyst", "Madrid", "CC-TRE-300", "EMPLOYEE",
                        LocalDate.of(2026, 11, 2), "E-0002"),
                EmployeeView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        EmployeeView hired = response.getBody();
        assertThat(hired).isNotNull();
        assertThat(before).doesNotContain(hired.employeeNumber());
        assertThat(hired.terminationDate()).isNull();
        // A future hire date is allowed: the identity exists and holds no access
        // until the date arrives.
        assertThat(hired.hireDate()).isAfter(LocalDate.of(2026, 10, 16));
    }

    @Test
    @DisplayName("a mover changes what was sent and leaves the rest alone")
    void moverIsPartial() {
        EmployeeView before = get("E-0150");

        EmployeeView after = patch("E-0150", new EmployeeUpdateRequest(
                null, null, null, "Internal Audit", "Auditor", null, null, null, null, false, false));

        assertThat(after.department()).isEqualTo("Internal Audit");
        assertThat(after.jobTitle()).isEqualTo("Auditor");
        assertThat(after.firstName()).isEqualTo(before.firstName());
        assertThat(after.email()).isEqualTo(before.email());
        assertThat(after.hireDate()).isEqualTo(before.hireDate());
        assertThat(after.lastModifiedAt()).isAfterOrEqualTo(before.lastModifiedAt());
    }

    @Test
    @DisplayName("a partial PATCH with only the fields that change is accepted")
    void partialJsonIsAccepted() {
        EmployeeView before = get("E-0151");

        // Raw JSON, naming two fields out of eleven, because that is what a
        // caller actually sends. Every other test here builds the record in
        // Java and therefore serialises all eleven, which is how a 400 on every
        // real partial request went unnoticed until curl found it.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<EmployeeView> response = rest.exchange(
                "/api/v1/employees/E-0151", HttpMethod.PATCH,
                new HttpEntity<>("{\"department\":\"Risk\",\"jobTitle\":\"Risk Analyst\"}", headers),
                EmployeeView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        EmployeeView after = response.getBody();
        assertThat(after).isNotNull();
        assertThat(after.department()).isEqualTo("Risk");
        // The omitted fields survived, including the two flags that are absent
        // from the body entirely.
        assertThat(after.email()).isEqualTo(before.email());
        assertThat(after.managerEmployeeNumber()).isEqualTo(before.managerEmployeeNumber());
    }

    @Test
    @DisplayName("a manager can be removed, which is the identity-with-no-manager case")
    void managerCanBeCleared() {
        EmployeeView cleared = patch("E-0160", new EmployeeUpdateRequest(
                null, null, null, null, null, null, null, null, null, false, true));

        assertThat(cleared.managerEmployeeNumber()).isNull();
    }

    @Test
    @DisplayName("an email can be removed, which is the correlation-cannot-resolve case")
    void emailCanBeCleared() {
        EmployeeView cleared = patch("E-0161", new EmployeeUpdateRequest(
                null, null, null, null, null, null, null, null, null, true, false));

        assertThat(cleared.email()).isNull();
    }

    @Test
    @DisplayName("a termination dated in the past is accepted, because that case is real")
    void retroactiveTerminationIsAccepted() {
        LocalDate threeWeeksAgo = LocalDate.of(2026, 9, 25);

        EmployeeView left = rest.postForObject("/api/v1/employees/E-0170/termination",
                new TerminationRequest(threeWeeksAgo), EmployeeView.class);

        assertThat(left.terminationDate()).isEqualTo(threeWeeksAgo);
        // Told now, effective then. Those two being different is what makes a
        // retroactive leaver reconstructible, and the gap between them is the
        // window where their access was still live.
        assertThat(left.lastModifiedAt())
                .as("stamped with the test clock, not the wall clock")
                .isEqualTo(START);
    }

    @Test
    @DisplayName("a rehire reuses the employee number, so it is the same person")
    void rehireReusesTheNumber() {
        rest.postForObject("/api/v1/employees/E-0180/termination",
                new TerminationRequest(LocalDate.of(2026, 10, 1)), EmployeeView.class);

        EmployeeView back = rest.postForObject("/api/v1/employees/E-0180/rehire",
                new RehireRequest(LocalDate.of(2026, 10, 20)), EmployeeView.class);

        assertThat(back.employeeNumber()).isEqualTo("E-0180");
        assertThat(back.terminationDate()).isNull();
        assertThat(back.hireDate()).isEqualTo(LocalDate.of(2026, 10, 20));
    }

    @Test
    @DisplayName("rehiring somebody who never left is refused")
    void rehireOfActiveIsRefused() {
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/employees/E-0190/rehire", new RehireRequest(null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("an unknown employee is a 404, not an empty success")
    void unknownEmployeeIsNotFound() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/employees/E-9999", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("naming a manager who does not exist is refused, so the chain stays intact")
    void unknownManagerIsRefused() {
        ResponseEntity<String> response = rest.exchange("/api/v1/employees/E-0191", HttpMethod.PATCH,
                new HttpEntity<>(new EmployeeUpdateRequest(
                        null, null, null, null, null, null, null, null, "E-8888", false, false)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the seeded anomaly catalogue is published and points at real employees")
    void anomalyCatalogueIsPublished() {
        List<SeededAnomalyView> anomalies = rest.exchange("/api/v1/anomalies", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<SeededAnomalyView>>() {}).getBody();

        assertThat(anomalies).isNotNull().isNotEmpty();
        assertThat(anomalies).extracting(SeededAnomalyView::kind).doesNotContainNull();

        // Every employee the answer key names has to exist, or the key is useless.
        for (SeededAnomalyView anomaly : anomalies) {
            assertThat(rest.getForEntity("/api/v1/employees/" + anomaly.employeeNumber(), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("reset undoes every provoked event")
    void resetRestoresTheSeededPopulation() {
        EmployeeView before = get("E-0120");
        patch("E-0120", new EmployeeUpdateRequest(
                null, null, null, "Procurement", "Buyer", null, null, null, null, false, false));
        rest.postForEntity("/api/v1/employees", new NewEmployeeRequest(
                "Temp", "Person", null, "Operations", "Operations Analyst", null, null, null, null, null),
                EmployeeView.class);

        rest.postForEntity("/api/v1/admin/reset", null, Void.class);

        assertThat(get("E-0120")).isEqualTo(before);
        assertThat(page(0, 1, null).totalElements()).isEqualTo(300);
    }

    @Test
    @DisplayName("an absurd page size is refused rather than silently clamped")
    void pageSizeIsBounded() {
        assertThat(rest.getForEntity("/api/v1/employees?size=50000", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/v1/employees?page=-1", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private List<String> allNumbers() {
        return page(0, 1_000, null).content().stream().map(EmployeeView::employeeNumber).toList();
    }

    private EmployeePage page(int page, int size, Instant changedSince) {
        String url = "/api/v1/employees?page=" + page + "&size=" + size
                + (changedSince == null ? "" : "&changedSince=" + changedSince);
        return rest.getForObject(url, EmployeePage.class);
    }

    private EmployeeView get(String employeeNumber) {
        return rest.getForObject("/api/v1/employees/" + employeeNumber, EmployeeView.class);
    }

    private EmployeeView patch(String employeeNumber, EmployeeUpdateRequest request) {
        return rest.exchange("/api/v1/employees/" + employeeNumber, HttpMethod.PATCH,
                new HttpEntity<>(request), EmployeeView.class).getBody();
    }
}
