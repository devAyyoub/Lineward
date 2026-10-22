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

import java.time.LocalDate;

/**
 * What to generate.
 *
 * @param seed          the fixed seed section 10.2 requires. Same seed, same
 *                      population, down to every employee number
 * @param employeeCount how many employees, headcount of the whole company
 * @param asOf          the date the population is generated as of. Hire dates
 *                      land before it and the future-dated joiner anomaly lands
 *                      after it. Passed in rather than read from the clock,
 *                      because a generator that reads the clock produces a
 *                      different population every day and nothing downstream can
 *                      assert on it
 * @param anomalyRate   fraction of the population carrying a seeded anomaly.
 *                      Section 10.2 asks for 2 to 5 %
 */
public record HrPopulationSpec(long seed, int employeeCount, LocalDate asOf, double anomalyRate) {

    /** The scale section 10.2 targets for the public demo. */
    public static final int DEMO_HEADCOUNT = 5_000;

    public static final double DEFAULT_ANOMALY_RATE = 0.03;

    public HrPopulationSpec {
        // A spec is a request, so it only validates what it can judge on its
        // own. Whether the headcount is enough to staff a hierarchy depends on
        // the catalogue being generated against, which this record has no
        // business knowing, so HrPopulationGenerator checks that.
        if (employeeCount < 1) {
            throw new IllegalArgumentException("employeeCount must be at least 1, got " + employeeCount);
        }
        if (anomalyRate < 0.0 || anomalyRate > 0.5) {
            throw new IllegalArgumentException("anomalyRate must be between 0.0 and 0.5, got " + anomalyRate);
        }
        if (asOf == null) {
            throw new IllegalArgumentException("asOf is required");
        }
    }

    public static HrPopulationSpec of(long seed, int employeeCount, LocalDate asOf) {
        return new HrPopulationSpec(seed, employeeCount, asOf, DEFAULT_ANOMALY_RATE);
    }
}
