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
package com.lineward.datagen;

import com.lineward.datagen.model.AnomalyKind;
import com.lineward.datagen.model.HrPopulationSpec;
import com.lineward.datagen.model.SeededAnomaly;
import com.lineward.datagen.model.SyntheticEmployee;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plants the anomalies of {@link AnomalyKind} and records where each one went.
 *
 * <p>Two constraints shape this class, and both exist so the catalogue it
 * returns is exactly true:
 *
 * <ul>
 *   <li><b>Victims are leaves only</b>, that is people nobody reports to. An
 *       anomaly on a manager would cascade into their reports and produce
 *       findings that are real but absent from the catalogue, which is worse
 *       than no catalogue at all.
 *   <li><b>One anomaly per person.</b> Two stacked on the same employee make the
 *       outcome depend on the order they were applied, and then a test asserting
 *       on either one is asserting on an implementation detail.
 * </ul>
 */
final class AnomalySeeder {

    private final java.util.Random rnd;
    private final HrPopulationSpec spec;
    private final List<SyntheticEmployee> employees;
    private final Map<String, Integer> indexByNumber;
    private final List<String> leaves;
    private final Set<String> alreadyTerminated;

    AnomalySeeder(java.util.Random rnd,
                  HrPopulationSpec spec,
                  List<SyntheticEmployee> employees,
                  Map<String, Integer> indexByNumber,
                  List<String> leaves,
                  Set<String> alreadyTerminated) {
        this.rnd = rnd;
        this.spec = spec;
        this.employees = employees;
        this.indexByNumber = indexByNumber;
        this.leaves = leaves;
        this.alreadyTerminated = alreadyTerminated;
    }

    List<SeededAnomaly> seed() {
        AnomalyKind[] kinds = AnomalyKind.values();
        List<SeededAnomaly> planted = new ArrayList<>();

        // Candidates are leaves that are not already terminated, in a fixed
        // order, so the draw stays reproducible.
        List<String> candidates = new ArrayList<>(leaves.size());
        for (String number : leaves) {
            if (!alreadyTerminated.contains(number)) {
                candidates.add(number);
            }
        }

        int requested = (int) Math.round(spec.employeeCount() * spec.anomalyRate());
        // At least one of each kind whenever the population can carry them: a
        // dataset missing a whole category of finding silently stops testing
        // that branch, and nobody notices because the tests that exist pass.
        int total = Math.min(candidates.size(), Math.max(requested, kinds.length));

        Set<String> used = new java.util.HashSet<>();
        for (int i = 0; i < total; i++) {
            AnomalyKind kind = kinds[i % kinds.length];
            String victim = drawUnused(candidates, used);
            if (victim == null) {
                break;
            }
            planted.add(apply(kind, victim));
        }
        return List.copyOf(planted);
    }

    private String drawUnused(List<String> candidates, Set<String> used) {
        // Bounded scan rather than a retry loop: with a 3 % rate the first draw
        // almost always lands, and an unbounded loop would hang on a population
        // whose candidates are exhausted.
        for (int attempt = 0; attempt < candidates.size(); attempt++) {
            String candidate = candidates.get(rnd.nextInt(candidates.size()));
            if (used.add(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private SeededAnomaly apply(AnomalyKind kind, String employeeNumber) {
        int index = indexByNumber.get(employeeNumber);
        SyntheticEmployee person = employees.get(index);
        // Every seeded anomaly is a change the source made as of the snapshot
        // date, so that is when the record was last modified. Deriving it from
        // the dates instead would put a future-dated joiner's timestamp in the
        // future, and an incremental read would then return them for ever.
        java.time.Instant changedAt = spec.asOf().atStartOfDay(java.time.ZoneOffset.UTC).toInstant();

        switch (kind) {
            case MISSING_MANAGER -> {
                employees.set(index, person.withManager(null).withLastModifiedAt(changedAt));
                return new SeededAnomaly(kind, employeeNumber,
                        "manager removed, previously " + person.managerEmployeeNumber());
            }
            case FUTURE_HIRE_DATE -> {
                // A joiner the platform learns about before they start. The
                // identity has to exist and hold no access yet, which is what
                // the PENDING status in the schema is for.
                LocalDate future = spec.asOf().plusDays(15L + rnd.nextInt(46));
                employees.set(index, person.withHireDate(future).withLastModifiedAt(changedAt));
                return new SeededAnomaly(kind, employeeNumber, "hire date moved to " + future);
            }
            case RETROACTIVE_TERMINATION -> {
                // Still in the extract more than a year after leaving. The
                // dynamic version of this case, HR announcing today a
                // termination dated three months ago, is the simulator's job:
                // in a first extract it is indistinguishable from an ordinary
                // leaver, because there is no previous snapshot to compare with.
                LocalDate left = spec.asOf().minusDays(365L + rnd.nextInt(365));
                LocalDate safe = left.isBefore(person.hireDate()) ? person.hireDate().plusDays(30) : left;
                employees.set(index, person.withTerminationDate(safe).withLastModifiedAt(changedAt));
                return new SeededAnomaly(kind, employeeNumber,
                        "left on " + safe + " and the source still reports them");
            }
            case MISSING_EMAIL -> {
                employees.set(index, person.withEmail(null).withLastModifiedAt(changedAt));
                return new SeededAnomaly(kind, employeeNumber, "email removed, correlation by email will not resolve");
            }
            case TERMINATION_BEFORE_HIRE -> {
                LocalDate impossible = person.hireDate().minusDays(5L + rnd.nextInt(26));
                employees.set(index, person.withTerminationDate(impossible).withLastModifiedAt(changedAt));
                return new SeededAnomaly(kind, employeeNumber,
                        "termination " + impossible + " precedes hire " + person.hireDate());
            }
            case DUPLICATE_EMAIL -> {
                SyntheticEmployee twin = pickEmailDonor(employeeNumber);
                employees.set(index, person.withEmail(twin.email()).withLastModifiedAt(changedAt));
                return new SeededAnomaly(kind, employeeNumber,
                        "shares " + twin.email() + " with " + twin.employeeNumber());
            }
            default -> throw new IllegalStateException("unhandled anomaly kind " + kind);
        }
    }

    /** Somebody else with a usable email, so the duplicate has two sides. */
    private SyntheticEmployee pickEmailDonor(String excludedNumber) {
        for (int attempt = 0; attempt < employees.size(); attempt++) {
            SyntheticEmployee candidate = employees.get(rnd.nextInt(employees.size()));
            if (!candidate.employeeNumber().equals(excludedNumber) && candidate.email() != null) {
                return candidate;
            }
        }
        throw new IllegalStateException("no employee with an email to duplicate");
    }
}
