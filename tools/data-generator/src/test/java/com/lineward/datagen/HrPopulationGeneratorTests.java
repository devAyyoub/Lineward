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
import com.lineward.datagen.model.SyntheticHrPopulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The generator's contract is reproducibility and an honest anomaly catalogue.
 * Everything else about it is taste.
 */
class HrPopulationGeneratorTests {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 16);
    private static final long SEED = 20261016L;

    private static HrPopulationSpec spec(long seed, int headcount) {
        return HrPopulationSpec.of(seed, headcount, AS_OF);
    }

    @Test
    @DisplayName("the same seed produces the same population, field by field")
    void sameSeedIsReproducible() {
        SyntheticHrPopulation first = HrPopulationGenerator.generate(spec(SEED, 500));
        SyntheticHrPopulation second = HrPopulationGenerator.generate(spec(SEED, 500));

        // Records compare by value, so this covers every field of every
        // employee, not just the count. If a draw ever depends on hash
        // iteration order or on a parallel stream, this is what catches it.
        assertThat(first.employees()).isEqualTo(second.employees());
        assertThat(first.anomalies()).isEqualTo(second.anomalies());
    }

    @Test
    @DisplayName("a different seed produces a different population")
    void differentSeedDiffers() {
        SyntheticHrPopulation first = HrPopulationGenerator.generate(spec(SEED, 500));
        SyntheticHrPopulation other = HrPopulationGenerator.generate(spec(SEED + 1, 500));

        assertThat(first.employees()).isNotEqualTo(other.employees());
    }

    @Test
    @DisplayName("nothing reads the clock: the date comes from the spec")
    void populationFollowsAsOf() {
        LocalDate earlierAsOf = LocalDate.of(2020, 1, 1);
        SyntheticHrPopulation earlier = HrPopulationGenerator.generate(
                new HrPopulationSpec(SEED, 500, earlierAsOf, 0.03));
        SyntheticHrPopulation later = HrPopulationGenerator.generate(spec(SEED, 500));

        Set<String> futureJoiners = earlier.anomaliesOf(AnomalyKind.FUTURE_HIRE_DATE).stream()
                .map(SeededAnomaly::employeeNumber).collect(java.util.stream.Collectors.toSet());

        // Every hire date sits before asOf, except the future-dated joiners that
        // are seeded on purpose and named in the catalogue. So moving asOf back
        // six years has to move every one of them with it: a generator calling
        // LocalDate.now() would produce today's dates for both specs and fail
        // here.
        List<String> afterAsOf = earlier.employees().stream()
                .filter(e -> e.hireDate().isAfter(earlierAsOf))
                .map(SyntheticEmployee::employeeNumber)
                .filter(number -> !futureJoiners.contains(number))
                .toList();

        assertThat(afterAsOf)
                .as("hire dates after asOf that are not seeded future joiners")
                .isEmpty();
        assertThat(later.employees()).isNotEqualTo(earlier.employees());
    }

    @Test
    @DisplayName("no record claims to have been modified in the future")
    void noModificationTimestampIsAfterAsOf() {
        SyntheticHrPopulation population = HrPopulationGenerator.generate(spec(SEED, 1_000));
        java.time.Instant snapshot = AS_OF.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();

        // The failure this exists for: lastModifiedAt used to be derived from
        // the hire date, so a future-dated joiner got a future timestamp. Anything
        // asking "what changed since X" then returned those employees on every
        // single run, because their timestamp was permanently ahead of the
        // question. Found on 2026-10-16 by the simulator's incremental read test.
        List<String> fromTheFuture = population.employees().stream()
                .filter(e -> e.lastModifiedAt().isAfter(snapshot))
                .map(SyntheticEmployee::employeeNumber)
                .toList();

        assertThat(fromTheFuture)
                .as("records modified after the snapshot: an incremental read would never converge")
                .isEmpty();
    }

    @Test
    @DisplayName("the headcount is exactly what was asked for")
    void headcountIsExact() {
        for (int headcount : new int[] {11, 100, 501, 2_000}) {
            SyntheticHrPopulation population = HrPopulationGenerator.generate(spec(SEED, headcount));
            assertThat(population.employees()).hasSize(headcount);
            assertThat(population.employees().stream().map(SyntheticEmployee::employeeNumber).distinct().count())
                    .isEqualTo(headcount);
        }
    }

    @Test
    @DisplayName("everybody answers to somebody, except the chief executive and the seeded orphans")
    void everybodyHasAManagerExceptByDesign() {
        SyntheticHrPopulation population = HrPopulationGenerator.generate(spec(SEED, 1_000));
        Set<String> numbers = population.byEmployeeNumber().keySet();

        Set<String> seededOrphans = population.anomaliesOf(AnomalyKind.MISSING_MANAGER).stream()
                .map(SeededAnomaly::employeeNumber).collect(java.util.stream.Collectors.toSet());

        List<String> unexplainedOrphans = population.employees().stream()
                .filter(e -> e.managerEmployeeNumber() == null)
                .map(SyntheticEmployee::employeeNumber)
                .filter(number -> !number.equals("E-0001"))
                .filter(number -> !seededOrphans.contains(number))
                .toList();

        assertThat(unexplainedOrphans)
                .as("an orphan that is not in the catalogue is a finding nobody planted")
                .isEmpty();

        // Every manager reference resolves. Without this, a dangling reference
        // would surface much later as a correlation bug in the platform.
        assertThat(population.employees().stream()
                .map(SyntheticEmployee::managerEmployeeNumber)
                .filter(java.util.Objects::nonNull)
                .filter(manager -> !numbers.contains(manager))
                .toList()).isEmpty();
    }

    @Test
    @DisplayName("the manager chain has no cycles and reaches the chief executive")
    void managerChainTerminates() {
        SyntheticHrPopulation population = HrPopulationGenerator.generate(spec(SEED, 1_000));
        Map<String, SyntheticEmployee> byNumber = population.byEmployeeNumber();

        for (SyntheticEmployee employee : population.employees()) {
            Set<String> visited = new HashSet<>();
            String current = employee.employeeNumber();
            while (current != null) {
                assertThat(visited.add(current))
                        .as("cycle in the manager chain at %s", current)
                        .isTrue();
                current = byNumber.get(current).managerEmployeeNumber();
            }
        }
    }

    @Test
    @DisplayName("every kind of anomaly is present, and the catalogue says where")
    void everyAnomalyKindIsSeeded() {
        SyntheticHrPopulation population = HrPopulationGenerator.generate(spec(SEED, 1_000));

        for (AnomalyKind kind : AnomalyKind.values()) {
            assertThat(population.anomaliesOf(kind))
                    .as("no %s seeded, so nothing exercises that branch", kind)
                    .isNotEmpty();
        }

        assertThat(population.anomalies())
                .as("3 % of 1.000 people, give or take the rounding")
                .hasSizeBetween(25, 40);
    }

    @Test
    @DisplayName("the catalogue is exactly true: every planted anomaly is real, and nothing else is")
    void catalogueMatchesTheData() {
        SyntheticHrPopulation population = HrPopulationGenerator.generate(spec(SEED, 1_000));
        Map<String, SyntheticEmployee> byNumber = population.byEmployeeNumber();

        // Direction one: everything the catalogue claims is in the data.
        for (SeededAnomaly anomaly : population.anomalies()) {
            SyntheticEmployee person = byNumber.get(anomaly.employeeNumber());
            assertThat(person).as("catalogue points at %s, who does not exist", anomaly.employeeNumber()).isNotNull();

            switch (anomaly.kind()) {
                case MISSING_MANAGER -> assertThat(person.managerEmployeeNumber()).isNull();
                case FUTURE_HIRE_DATE -> assertThat(person.hireDate()).isAfter(AS_OF);
                case RETROACTIVE_TERMINATION -> assertThat(person.terminationDate()).isBefore(AS_OF.minusYears(1).plusDays(1));
                case MISSING_EMAIL -> assertThat(person.email()).isNull();
                case TERMINATION_BEFORE_HIRE -> assertThat(person.terminationDate()).isBefore(person.hireDate());
                case DUPLICATE_EMAIL -> assertThat(population.employees().stream()
                        .filter(other -> person.email().equals(other.email()))
                        .count()).isGreaterThan(1);
            }
        }

        // Direction two, the one that matters: nothing in the data is an anomaly
        // the catalogue does not mention. This is the assertion that would catch
        // an accidental duplicate email, and an accidental duplicate is
        // indistinguishable from a seeded one once it is in the dataset.
        Map<String, List<String>> byEmail = new HashMap<>();
        for (SyntheticEmployee employee : population.employees()) {
            if (employee.email() != null) {
                byEmail.computeIfAbsent(employee.email(), key -> new ArrayList<>())
                        .add(employee.employeeNumber());
            }
        }
        Set<String> catalogued = population.anomaliesOf(AnomalyKind.DUPLICATE_EMAIL).stream()
                .map(SeededAnomaly::employeeNumber).collect(java.util.stream.Collectors.toSet());

        List<String> uncatalogued = byEmail.values().stream()
                .filter(sharers -> sharers.size() > 1)
                .flatMap(List::stream)
                .filter(number -> !catalogued.contains(number))
                .filter(number -> byEmail.get(byNumber.get(number).email()).stream().noneMatch(catalogued::contains))
                .toList();

        assertThat(uncatalogued)
                .as("duplicate emails nobody planted: the catalogue would be lying")
                .isEmpty();

        // And at most one anomaly per person, so a test asserting on one is not
        // really asserting on the order two were applied in.
        assertThat(population.anomalies().stream().map(SeededAnomaly::employeeNumber).distinct().count())
                .isEqualTo(population.anomalies().size());
    }

    @Test
    @DisplayName("historical leavers exist, because an orphan account needs somebody to have left")
    void historicalLeaversArePresent() {
        SyntheticHrPopulation population = HrPopulationGenerator.generate(spec(SEED, 1_000));

        Set<String> seeded = population.anomalies().stream()
                .map(SeededAnomaly::employeeNumber).collect(java.util.stream.Collectors.toSet());

        long ordinaryLeavers = population.employees().stream()
                .filter(e -> !e.active())
                .filter(e -> !seeded.contains(e.employeeNumber()))
                .count();

        assertThat(ordinaryLeavers).isPositive();
    }

    @Test
    @DisplayName("a population too small to hold a hierarchy is refused, not silently reshaped")
    void refusesImpossiblePopulations() {
        // Whether a headcount can staff a hierarchy depends on the catalogue,
        // so the generator refuses it rather than the spec: a request cannot
        // judge what it will be generated against.
        assertThatThrownBy(() -> HrPopulationGenerator.generate(spec(SEED, 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("employeeCount must be at least");

        // What the spec can judge on its own, it still refuses on construction.
        assertThatThrownBy(() -> new HrPopulationSpec(SEED, 500, AS_OF, 0.9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anomalyRate");

        assertThatThrownBy(() -> new HrPopulationSpec(SEED, 0, AS_OF, 0.03))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("employeeCount must be at least 1");
    }

    @Test
    @DisplayName("the demo scale of section 10.2 generates")
    void demoScaleGenerates() {
        SyntheticHrPopulation population = HrPopulationGenerator.generate(
                spec(SEED, HrPopulationSpec.DEMO_HEADCOUNT));

        assertThat(population.employees()).hasSize(HrPopulationSpec.DEMO_HEADCOUNT);
        assertThat(population.anomalies()).hasSizeGreaterThan(100);
    }
}
