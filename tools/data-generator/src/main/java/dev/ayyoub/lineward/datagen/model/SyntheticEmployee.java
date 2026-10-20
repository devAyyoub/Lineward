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
package dev.ayyoub.lineward.datagen.model;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;

/**
 * One employee as an HR system would assert them.
 *
 * <p>The fields are the ones the identity table stores as typed columns plus the
 * employee number, which is the natural key of the whole model and lives on
 * {@code source_record} (ADR-013 decision 4). Nothing here is a Lineward
 * concept: this record is what the authoritative source says, not what Lineward
 * decided, and keeping the two apart is the point of that decision.
 *
 * <p>{@code displayName} is deliberately absent. A real HR system hands over the
 * parts and lets the consumer compose them, and the composition rule belongs to
 * whoever ingests, not to the source.
 *
 * <p>Several fields are nullable because an HR extract is allowed to be
 * incomplete, and M1 lists incomplete and contradictory HR data as an edge case
 * to ingest rather than to reject. {@link AnomalyKind} seeds exactly those.
 */
public record SyntheticEmployee(
        String employeeNumber,
        String firstName,
        String lastName,
        String email,
        String department,
        String jobTitle,
        String location,
        String costCenter,
        String employmentType,
        LocalDate hireDate,
        LocalDate terminationDate,
        String managerEmployeeNumber,
        Instant lastModifiedAt) {

    /**
     * When the source last changed this record, derived from the record itself
     * rather than from the wall clock, because a generator that reads the clock
     * is not reproducible. The latest lifecycle date is the only thing that has
     * happened to a freshly generated employee.
     *
     * <p>Capped at {@code asOf}, and that cap is the whole point. A future-dated
     * joiner has a hire date after the snapshot, and taking it as the moment the
     * record changed would claim the source modified a record in the future.
     * Anything reading changes since a given instant would then return those
     * employees on <em>every</em> run, for ever, because their timestamp is
     * permanently ahead of the question. An incremental read that never
     * converges looks like a connector bug and is a data bug.
     *
     * <p>Found on 2026-10-16 by the simulator's incremental read test, which
     * asked for what changed in the last five minutes and got three
     * future-dated joiners back.
     */
    public static Instant derivedLastModifiedAt(LocalDate hireDate, LocalDate terminationDate, LocalDate asOf) {
        LocalDate latest = terminationDate == null || terminationDate.isBefore(hireDate)
                ? hireDate
                : terminationDate;
        LocalDate capped = latest.isAfter(asOf) ? asOf : latest;
        return capped.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** True while the source still considers this person employed. */
    public boolean active() {
        return terminationDate == null;
    }

    // The with* methods below keep lastModifiedAt rather than recomputing it.
    // Whoever makes the change decides when it happened: the generator knows
    // asOf, the simulator knows the clock, and this record knows neither. The
    // earlier version recomputed it here, which is how a future hire date
    // silently became a future modification timestamp.

    public SyntheticEmployee withTerminationDate(LocalDate newTerminationDate) {
        return new SyntheticEmployee(
                employeeNumber, firstName, lastName, email, department, jobTitle, location,
                costCenter, employmentType, hireDate, newTerminationDate, managerEmployeeNumber,
                lastModifiedAt);
    }

    public SyntheticEmployee withHireDate(LocalDate newHireDate) {
        return new SyntheticEmployee(
                employeeNumber, firstName, lastName, email, department, jobTitle, location,
                costCenter, employmentType, newHireDate, terminationDate, managerEmployeeNumber,
                lastModifiedAt);
    }

    /** Stamp the record as changed at a given instant. */
    public SyntheticEmployee withLastModifiedAt(Instant at) {
        return new SyntheticEmployee(
                employeeNumber, firstName, lastName, email, department, jobTitle, location,
                costCenter, employmentType, hireDate, terminationDate, managerEmployeeNumber, at);
    }

    public SyntheticEmployee withEmail(String newEmail) {
        return new SyntheticEmployee(
                employeeNumber, firstName, lastName, newEmail, department, jobTitle, location,
                costCenter, employmentType, hireDate, terminationDate, managerEmployeeNumber,
                lastModifiedAt);
    }

    public SyntheticEmployee withManager(String newManagerEmployeeNumber) {
        return new SyntheticEmployee(
                employeeNumber, firstName, lastName, email, department, jobTitle, location,
                costCenter, employmentType, hireDate, terminationDate, newManagerEmployeeNumber,
                lastModifiedAt);
    }
}
