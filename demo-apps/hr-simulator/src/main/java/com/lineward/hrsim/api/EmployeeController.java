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
package com.lineward.hrsim.api;

import com.lineward.hrsim.model.InvalidRequestException;
import com.lineward.hrsim.model.UnknownEmployeeException;

import com.lineward.hrsim.model.EmployeePage;
import com.lineward.hrsim.model.EmployeeUpdateRequest;
import com.lineward.hrsim.model.EmployeeView;
import com.lineward.hrsim.model.NewEmployeeRequest;
import com.lineward.hrsim.model.RehireRequest;
import com.lineward.hrsim.model.SeededAnomalyView;
import com.lineward.hrsim.model.TerminationRequest;

import com.lineward.hrsim.store.EmployeeStore;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * The HR system's API.
 *
 * <p>Read endpoints serve the population; write endpoints provoke the three
 * lifecycle events. Nothing here knows what Lineward is: this is an external
 * system that happens to be convenient to drive, and keeping it ignorant is
 * what makes the ingestion in M1 a real integration rather than a function call
 * with extra steps.
 *
 * <p>Unauthenticated, deliberately. It holds synthetic data, it binds to
 * localhost in development, and adding authentication here would mean the first
 * connector Lineward writes is tested against a bespoke scheme instead of
 * against the real protocols of M3. The connector credential problem is a
 * phase 2 concern and section 9 already names it as the most valuable target in
 * the platform.
 */
@RestController
@RequestMapping("/api/v1")
class EmployeeController {

    /** Big enough to be worth paging, small enough not to serve 5.000 rows by accident. */
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 1_000;

    private final EmployeeStore store;

    EmployeeController(EmployeeStore store) {
        this.store = store;
    }

    /**
     * The extract.
     *
     * @param changedSince optional ISO-8601 instant; only records that changed
     *                     after it are returned, which is what an incremental
     *                     ingest uses instead of re-reading the whole company
     */
    @GetMapping("/employees")
    EmployeePage employees(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @RequestParam(required = false) Instant changedSince) {

        if (page < 0) {
            throw new InvalidRequestException("page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return store.page(page, size, changedSince);
    }

    @GetMapping("/employees/{employeeNumber}")
    EmployeeView employee(@PathVariable String employeeNumber) {
        return store.find(employeeNumber)
                .map(EmployeeView::of)
                .orElseThrow(() -> new UnknownEmployeeException(employeeNumber));
    }

    /** Joiner. */
    @PostMapping("/employees")
    ResponseEntity<EmployeeView> hire(@RequestBody NewEmployeeRequest request) {
        request.validate();
        EmployeeView hired = EmployeeView.of(store.hire(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(hired);
    }

    /** Mover. */
    @PatchMapping("/employees/{employeeNumber}")
    EmployeeView update(@PathVariable String employeeNumber, @RequestBody EmployeeUpdateRequest request) {
        return EmployeeView.of(store.update(employeeNumber, request));
    }

    /** Leaver, with a past date allowed. */
    @PostMapping("/employees/{employeeNumber}/termination")
    EmployeeView terminate(@PathVariable String employeeNumber, @RequestBody(required = false) TerminationRequest request) {
        return EmployeeView.of(store.terminate(
                employeeNumber, request == null ? null : request.terminationDate()));
    }

    /** Rehire: same employee number, so the same person and not a second one. */
    @PostMapping("/employees/{employeeNumber}/rehire")
    EmployeeView rehire(@PathVariable String employeeNumber, @RequestBody(required = false) RehireRequest request) {
        return EmployeeView.of(store.rehire(
                employeeNumber, request == null ? null : request.hireDate()));
    }

    /** The answer key: what was planted in the generated population, and where. */
    @GetMapping("/anomalies")
    List<SeededAnomalyView> anomalies() {
        return store.seededAnomalies().stream().map(SeededAnomalyView::of).toList();
    }

    /** Back to the seeded population. The demo resets between runs (section 3.2). */
    @PostMapping("/admin/reset")
    ResponseEntity<Void> reset() {
        store.reset();
        return ResponseEntity.noContent().build();
    }
}
