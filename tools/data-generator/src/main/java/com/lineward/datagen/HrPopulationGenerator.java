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

import com.lineward.datagen.model.HrPopulationSpec;
import com.lineward.datagen.model.SeededAnomaly;
import com.lineward.datagen.model.SyntheticEmployee;
import com.lineward.datagen.model.SyntheticHrPopulation;

import com.lineward.datagen.HrCatalog.Department;
import com.lineward.datagen.HrCatalog.Level;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Builds a synthetic HR population from a seed.
 *
 * <p>Reproducibility is the whole point (section 10.2), and it rests on two
 * rules that are easy to break by accident:
 *
 * <ol>
 *   <li>{@link Random} is seeded explicitly and every draw happens in a fixed
 *       order, on one thread. {@code java.util.Random} specifies its algorithm
 *       in its own contract, so the same seed yields the same sequence on any
 *       JDK. A parallel stream anywhere in here, or a {@code HashMap} iteration
 *       feeding a draw, would quietly end that.
 *   <li>Nothing reads the clock. The date comes in through
 *       {@link HrPopulationSpec#asOf()}. A generator that calls
 *       {@code LocalDate.now()} produces a different population every day, and
 *       then no test can assert on an employee number and no demo can promise
 *       what it will show.
 * </ol>
 *
 * <p>The shape it produces is a four-level org chart: one chief executive, one
 * head per department, a manager for roughly every twelve people, and
 * individual contributors below them. Governance needs only one property from
 * that shape, and it is that somebody answers for everybody, because that is who
 * a certification campaign asks and who a leaver event cascades from.
 */
public final class HrPopulationGenerator {

    private static final String CHIEF_EXECUTIVE_NUMBER = "E-0001";
    private static final int REPORTS_PER_MANAGER = 12;

    /** Fraction of leaf employees the source already reports as gone. */
    private static final double HISTORICAL_LEAVER_RATE = 0.03;

    private HrPopulationGenerator() {}

    public static SyntheticHrPopulation generate(HrPopulationSpec spec) {
        // One head per department plus a chief executive is the smallest
        // population that still has a hierarchy. Below that this generator
        // would have to silently drop departments, and a generator that
        // quietly produces something other than what was asked for is worse
        // than one that refuses. The check lives here and not in the spec
        // because it depends on this catalogue, not on the request.
        int minimum = HrCatalog.DEPARTMENTS.size() + 1;
        if (spec.employeeCount() < minimum) {
            throw new IllegalArgumentException(
                    "employeeCount must be at least " + minimum
                            + " (one head per department plus a chief executive), got " + spec.employeeCount());
        }

        Random rnd = new Random(spec.seed());
        List<SyntheticEmployee> employees = new ArrayList<>(spec.employeeCount());
        Set<String> takenEmails = new HashSet<>();
        // Employee numbers of people nobody reports to. Anomalies and historical
        // terminations are confined to these, so that the manager chain stays
        // intact: a seeded anomaly that also orphans twenty reports produces
        // twenty findings that are not in the catalogue, and then the catalogue
        // is a lie.
        List<String> leaves = new ArrayList<>();

        int nextNumber = 1;

        // The chief executive is outside the department catalogue on purpose:
        // they are the one identity with no manager that is not a finding, which
        // is what makes MISSING_MANAGER meaningful for everybody else.
        employees.add(employee(
                CHIEF_EXECUTIVE_NUMBER, rnd, takenEmails,
                "Executive Office", "Chief Executive Officer", "CC-EXE-001",
                Level.HEAD, spec.asOf(), null));
        nextNumber++;

        for (Map.Entry<Department, Integer> entry : headcountPerDepartment(spec).entrySet()) {
            Department department = entry.getKey();
            int size = entry.getValue();

            String headNumber = numberOf(nextNumber++);
            employees.add(employee(
                    headNumber, rnd, takenEmails, department.name(),
                    department.titleFor(Level.HEAD), costCenter(department, 1),
                    Level.HEAD, spec.asOf(), CHIEF_EXECUTIVE_NUMBER));

            int managerCount = Math.max(1, (size - 1) / REPORTS_PER_MANAGER);
            managerCount = Math.min(managerCount, Math.max(0, size - 1));
            List<String> managerNumbers = new ArrayList<>(managerCount);
            for (int i = 0; i < managerCount; i++) {
                String managerNumber = numberOf(nextNumber++);
                managerNumbers.add(managerNumber);
                employees.add(employee(
                        managerNumber, rnd, takenEmails, department.name(),
                        department.titleFor(Level.MANAGER), costCenter(department, 2),
                        Level.MANAGER, spec.asOf(), headNumber));
            }

            int contributors = size - 1 - managerCount;
            for (int i = 0; i < contributors; i++) {
                // Roughly a third of contributors are senior, which keeps the
                // pyramid from being uniformly junior without pretending to
                // model a real career distribution.
                Level level = rnd.nextInt(3) == 0 ? Level.SENIOR : Level.JUNIOR;
                String reportsTo = managerNumbers.isEmpty()
                        ? headNumber
                        : managerNumbers.get(rnd.nextInt(managerNumbers.size()));
                String number = numberOf(nextNumber++);
                leaves.add(number);
                employees.add(employee(
                        number, rnd, takenEmails, department.name(),
                        department.titleFor(level), costCenter(department, 3),
                        level, spec.asOf(), reportsTo));
            }
        }

        Map<String, Integer> indexByNumber = new HashMap<>();
        for (int i = 0; i < employees.size(); i++) {
            indexByNumber.put(employees.get(i).employeeNumber(), i);
        }

        // People the source already reports as gone. Not anomalies: a real
        // extract contains recent leavers, and they are what makes an orphan
        // account possible in the first place.
        Set<String> alreadyTerminated = new HashSet<>();
        int historicalLeavers = (int) Math.round(leaves.size() * HISTORICAL_LEAVER_RATE);
        for (int i = 0; i < historicalLeavers; i++) {
            String number = leaves.get(rnd.nextInt(leaves.size()));
            if (!alreadyTerminated.add(number)) {
                continue;
            }
            int index = indexByNumber.get(number);
            SyntheticEmployee person = employees.get(index);
            LocalDate left = spec.asOf().minusDays(1 + rnd.nextInt(180));
            LocalDate effective = left.isBefore(person.hireDate()) ? person.hireDate().plusDays(1) : left;
            // Stamped on the day they left, which is when the source last
            // touched the record, not the day they were hired.
            employees.set(index, person.withTerminationDate(effective).withLastModifiedAt(
                    SyntheticEmployee.derivedLastModifiedAt(person.hireDate(), effective, spec.asOf())));
        }

        List<SeededAnomaly> anomalies =
                new AnomalySeeder(rnd, spec, employees, indexByNumber, leaves, alreadyTerminated).seed();

        return new SyntheticHrPopulation(employees, anomalies);
    }

    /**
     * Splits the headcount across departments by weight, giving every department
     * at least its head. Remainders go to the heaviest departments first, so the
     * total is exactly what was asked for rather than approximately.
     */
    private static Map<Department, Integer> headcountPerDepartment(HrPopulationSpec spec) {
        int assignable = spec.employeeCount() - 1;
        int totalWeight = HrCatalog.DEPARTMENTS.stream().mapToInt(Department::weight).sum();

        Map<Department, Integer> result = new java.util.LinkedHashMap<>();
        int assigned = 0;
        for (Department department : HrCatalog.DEPARTMENTS) {
            int share = Math.max(1, assignable * department.weight() / totalWeight);
            result.put(department, share);
            assigned += share;
        }

        // Correct the rounding drift, in catalogue order so the result stays
        // deterministic.
        int drift = assignable - assigned;
        while (drift != 0) {
            for (Department department : HrCatalog.DEPARTMENTS) {
                if (drift == 0) {
                    break;
                }
                int current = result.get(department);
                if (drift > 0) {
                    result.put(department, current + 1);
                    drift--;
                } else if (current > 1) {
                    result.put(department, current - 1);
                    drift++;
                }
            }
        }
        return result;
    }

    private static SyntheticEmployee employee(
            String employeeNumber, Random rnd, Set<String> takenEmails,
            String department, String jobTitle, String costCenter,
            Level level, LocalDate asOf, String managerEmployeeNumber) {

        String givenName = HrCatalog.GIVEN_NAMES.get(rnd.nextInt(HrCatalog.GIVEN_NAMES.size()));
        String surname = HrCatalog.SURNAMES.get(rnd.nextInt(HrCatalog.SURNAMES.size()));
        String email = uniqueEmail(givenName, surname, takenEmails);
        String location = HrCatalog.LOCATIONS.get(rnd.nextInt(HrCatalog.LOCATIONS.size()));
        String employmentType = level == Level.HEAD || level == Level.MANAGER
                ? "EMPLOYEE"
                : HrCatalog.EMPLOYMENT_TYPES.get(rnd.nextInt(HrCatalog.EMPLOYMENT_TYPES.size()));
        LocalDate hireDate = hireDateFor(level, asOf, rnd);

        return new SyntheticEmployee(
                employeeNumber, givenName, surname, email, department, jobTitle, location,
                costCenter, employmentType, hireDate, null, managerEmployeeNumber,
                SyntheticEmployee.derivedLastModifiedAt(hireDate, null, asOf));
    }

    /**
     * Two people called the same thing get different addresses, the way a
     * corporate convention disambiguates them. Accidental collisions are removed
     * so that every duplicate email in the output is one
     * {@link AnomalySeeder} planted and recorded.
     */
    private static String uniqueEmail(String givenName, String surname, Set<String> takenEmails) {
        String localPart = HrCatalog.emailLocalPart(givenName, surname);
        String candidate = localPart + "@" + HrCatalog.EMAIL_DOMAIN;
        int suffix = 2;
        while (!takenEmails.add(candidate)) {
            candidate = localPart + suffix + "@" + HrCatalog.EMAIL_DOMAIN;
            suffix++;
        }
        return candidate;
    }

    /** Seniority shows up as time served, which is also what makes a head's access the oldest. */
    private static LocalDate hireDateFor(Level level, LocalDate asOf, Random rnd) {
        int minYears;
        int maxYears;
        switch (level) {
            case HEAD -> { minYears = 8; maxYears = 15; }
            case MANAGER -> { minYears = 4; maxYears = 12; }
            case SENIOR -> { minYears = 2; maxYears = 8; }
            case JUNIOR -> { minYears = 0; maxYears = 3; }
            default -> throw new IllegalStateException("unhandled level " + level);
        }
        int spanDays = (maxYears - minYears) * 365;
        return asOf.minusDays((long) minYears * 365 + rnd.nextInt(Math.max(1, spanDays)) + 1);
    }

    private static String costCenter(Department department, int tier) {
        return "CC-" + department.costCenterPrefix() + "-" + String.format("%03d", tier * 100);
    }

    private static String numberOf(int sequence) {
        return String.format("E-%04d", sequence);
    }
}
