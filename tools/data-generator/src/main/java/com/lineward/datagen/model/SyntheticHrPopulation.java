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
package com.lineward.datagen.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A generated HR population together with the catalogue of anomalies planted in
 * it.
 *
 * <p>Both halves come from the same seed, so the same seed always yields the
 * same population and the same catalogue. That is what lets an integration test
 * assert on specific employee numbers instead of on counts.
 */
public record SyntheticHrPopulation(List<SyntheticEmployee> employees, List<SeededAnomaly> anomalies) {

    public SyntheticHrPopulation {
        employees = List.copyOf(employees);
        anomalies = List.copyOf(anomalies);
    }

    public Map<String, SyntheticEmployee> byEmployeeNumber() {
        return employees.stream()
                .collect(Collectors.toMap(SyntheticEmployee::employeeNumber, Function.identity()));
    }

    public List<SeededAnomaly> anomaliesOf(AnomalyKind kind) {
        return anomalies.stream().filter(a -> a.kind() == kind).toList();
    }

    public Optional<SyntheticEmployee> find(String employeeNumber) {
        return employees.stream().filter(e -> e.employeeNumber().equals(employeeNumber)).findFirst();
    }
}
