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
package dev.ayyoub.lineward.hrsim.config;

import dev.ayyoub.lineward.datagen.model.HrPopulationSpec;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

/**
 * How big the simulated company is and which seed it grows from.
 *
 * <p>{@code asOf} is a configured date and not today's date on purpose. With a
 * fixed seed and a fixed {@code asOf}, this service serves the same company
 * every time it starts, which is what lets an integration test assert on
 * employee {@code E-0423} and a demo promise what it will show. Setting it to
 * the current date would quietly make the population drift day by day.
 *
 * @param seed         the fixed seed of section 10.2
 * @param headcount    how many employees to generate
 * @param asOf         the date the population is generated as of
 * @param anomalyRate  fraction carrying a seeded anomaly
 */
@ConfigurationProperties(prefix = "hr-simulator")
public record HrSimulatorProperties(
        long seed,
        int headcount,
        LocalDate asOf,
        double anomalyRate) {

    public HrPopulationSpec toSpec() {
        return new HrPopulationSpec(seed, headcount, asOf, anomalyRate);
    }
}
