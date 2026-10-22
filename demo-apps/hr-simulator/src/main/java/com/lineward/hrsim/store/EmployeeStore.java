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
package com.lineward.hrsim.store;

import com.lineward.hrsim.config.HrSimulatorProperties;
import com.lineward.hrsim.model.InvalidRequestException;
import com.lineward.hrsim.model.UnknownEmployeeException;

import com.lineward.hrsim.model.EmployeePage;
import com.lineward.hrsim.model.EmployeeUpdateRequest;
import com.lineward.hrsim.model.EmployeeView;
import com.lineward.hrsim.model.NewEmployeeRequest;

import com.lineward.datagen.HrPopulationGenerator;
import com.lineward.datagen.model.SeededAnomaly;
import com.lineward.datagen.model.SyntheticEmployee;
import com.lineward.datagen.model.SyntheticHrPopulation;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The simulated HR system's own store, in memory.
 *
 * <p>In memory and not in a database, for two reasons that are the same reason:
 * the population is reproducible from a seed, so nothing here is worth
 * surviving a restart, and a reset has to be instant for a demo. ADR-013
 * decision 1 only requires that this store is not Lineward's, which it is not.
 *
 * <p>The generated population is the starting point; every mutation after that
 * is a lifecycle event somebody provoked. That is the division of labour worth
 * remembering: the seed makes the <em>initial</em> state reproducible, and it
 * says nothing about what happens afterwards.
 */
@Component
public class EmployeeStore {

    private final HrSimulatorProperties properties;
    private final Clock clock;
    private final Map<String, SyntheticEmployee> employees = new ConcurrentHashMap<>();
    private final AtomicInteger nextSequence = new AtomicInteger(1);
    private volatile List<SeededAnomaly> anomalies = List.of();

    EmployeeStore(HrSimulatorProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        reset();
    }

    /** Back to the seeded population, discarding every provoked event. */
    public final synchronized void reset() {
        SyntheticHrPopulation population = HrPopulationGenerator.generate(properties.toSpec());
        employees.clear();
        population.employees().forEach(e -> employees.put(e.employeeNumber(), e));
        anomalies = population.anomalies();

        // New hires continue the sequence rather than reusing a number. A reused
        // employee number is the worst thing this service could hand out: it is
        // the natural key of the whole model, so two people sharing one would
        // merge two identities downstream and the bug would look like a
        // correlation fault in Lineward.
        int highest = employees.keySet().stream()
                .mapToInt(EmployeeStore::sequenceOf)
                .max()
                .orElse(0);
        nextSequence.set(highest + 1);
    }

    public List<SeededAnomaly> seededAnomalies() {
        return anomalies;
    }

    public Optional<SyntheticEmployee> find(String employeeNumber) {
        return Optional.ofNullable(employees.get(employeeNumber));
    }

    /**
     * A page of employees, ordered by employee number.
     *
     * <p>Paged because a real extract is, and because the aggregation being
     * built in phase 1 has to cope with 5.000 people arriving in batches rather
     * than in one response. The order is stable for the same reason: paging over
     * an unordered collection silently skips and repeats rows between pages.
     *
     * @param changedSince when given, only employees whose record changed after
     *                     this instant. This is what makes an incremental ingest
     *                     possible instead of re-reading everybody every time
     */
    public EmployeePage page(int page, int size, Instant changedSince) {
        List<SyntheticEmployee> all = employees.values().stream()
                .filter(e -> changedSince == null || e.lastModifiedAt().isAfter(changedSince))
                .sorted(Comparator.comparing(SyntheticEmployee::employeeNumber))
                .toList();

        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) all.size() / size);

        return new EmployeePage(
                all.subList(from, to).stream().map(EmployeeView::of).toList(),
                page, size, all.size(), totalPages);
    }

    /** A joiner. */
    public synchronized SyntheticEmployee hire(NewEmployeeRequest request) {
        if (request.managerEmployeeNumber() != null && !employees.containsKey(request.managerEmployeeNumber())) {
            throw new UnknownEmployeeException(request.managerEmployeeNumber());
        }
        String employeeNumber = String.format("E-%04d", nextSequence.getAndIncrement());
        LocalDate hireDate = request.hireDate() != null ? request.hireDate() : LocalDate.now(clock);

        SyntheticEmployee hired = new SyntheticEmployee(
                employeeNumber,
                request.firstName(),
                request.lastName(),
                request.email(),
                request.department(),
                request.jobTitle(),
                request.location(),
                request.costCenter(),
                request.employmentType() != null ? request.employmentType() : "EMPLOYEE",
                hireDate,
                null,
                request.managerEmployeeNumber(),
                clock.instant());

        employees.put(employeeNumber, hired);
        return hired;
    }

    /**
     * A mover: whatever the request names changes, the rest stays.
     *
     * <p>Note what this method does <em>not</em> do: it does not touch access.
     * This is the HR system, and all it can do is assert that somebody now works
     * somewhere else. Working out that the old access has to go is Lineward's
     * job, and failing to do it is privilege creep.
     */
    public synchronized SyntheticEmployee update(String employeeNumber, EmployeeUpdateRequest request) {
        SyntheticEmployee current = require(employeeNumber);
        if (request.managerEmployeeNumber() != null) {
            if (request.managerEmployeeNumber().equals(employeeNumber)) {
                throw new InvalidRequestException("an employee cannot report to themselves");
            }
            if (!employees.containsKey(request.managerEmployeeNumber())) {
                throw new UnknownEmployeeException(request.managerEmployeeNumber());
            }
        }

        SyntheticEmployee moved = new SyntheticEmployee(
                current.employeeNumber(),
                orKeep(request.firstName(), current.firstName()),
                orKeep(request.lastName(), current.lastName()),
                request.clearEmailRequested() ? null : orKeep(request.email(), current.email()),
                orKeep(request.department(), current.department()),
                orKeep(request.jobTitle(), current.jobTitle()),
                orKeep(request.location(), current.location()),
                orKeep(request.costCenter(), current.costCenter()),
                orKeep(request.employmentType(), current.employmentType()),
                current.hireDate(),
                current.terminationDate(),
                request.clearManagerRequested() ? null : orKeep(request.managerEmployeeNumber(), current.managerEmployeeNumber()),
                clock.instant());

        employees.put(employeeNumber, moved);
        return moved;
    }

    /**
     * A leaver, with the termination date the caller chooses.
     *
     * <p>A date in the past is allowed, and that is the point. HR announcing on
     * the 10th that somebody left on the 3rd is the retroactive termination M1
     * lists as an edge case, and it is only reachable if this endpoint accepts
     * it. The platform then has to separate when it happened from when it was
     * told, which is the first thing an auditor asks.
     */
    public synchronized SyntheticEmployee terminate(String employeeNumber, LocalDate terminationDate) {
        SyntheticEmployee current = require(employeeNumber);
        LocalDate effective = terminationDate != null ? terminationDate : LocalDate.now(clock);

        // Stamped now, not with the termination date: what an incremental read
        // needs to know is that we were told now, however old the date is. That
        // gap between the two is exactly what makes a retroactive leaver
        // reconstructible.
        SyntheticEmployee left = current.withTerminationDate(effective).withLastModifiedAt(clock.instant());

        employees.put(employeeNumber, left);
        return left;
    }

    /**
     * A rehire: the same person comes back.
     *
     * <p>M1 requires reactivating the existing identity rather than duplicating
     * it, which is why this reuses the employee number instead of issuing a new
     * one. Issuing a new number would hand Lineward two identities for one
     * person and lose their history, and the join date is what a certification
     * campaign later uses to ask why their old access is still there.
     */
    public synchronized SyntheticEmployee rehire(String employeeNumber, LocalDate hireDate) {
        SyntheticEmployee current = require(employeeNumber);
        if (current.active()) {
            throw new InvalidRequestException(employeeNumber + " is already active");
        }
        SyntheticEmployee back = new SyntheticEmployee(
                current.employeeNumber(), current.firstName(), current.lastName(), current.email(),
                current.department(), current.jobTitle(), current.location(), current.costCenter(),
                current.employmentType(),
                hireDate != null ? hireDate : LocalDate.now(clock),
                null,
                current.managerEmployeeNumber(),
                clock.instant());

        employees.put(employeeNumber, back);
        return back;
    }

    private SyntheticEmployee require(String employeeNumber) {
        SyntheticEmployee current = employees.get(employeeNumber);
        if (current == null) {
            throw new UnknownEmployeeException(employeeNumber);
        }
        return current;
    }

    private static String orKeep(String candidate, String current) {
        return candidate != null ? candidate : current;
    }

    private static int sequenceOf(String employeeNumber) {
        try {
            return Integer.parseInt(employeeNumber.substring(employeeNumber.indexOf('-') + 1));
        } catch (RuntimeException ex) {
            return 0;
        }
    }
}
