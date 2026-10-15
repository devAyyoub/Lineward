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
package dev.ayyoub.lineward.hrsim;

import dev.ayyoub.lineward.datagen.SeededAnomaly;

/**
 * One anomaly planted in the generated population.
 *
 * <p>Published by this service for a reason worth stating: it is the answer key.
 * An integration test can assert that Lineward found these and only these, and a
 * demo can show the finding it promised instead of hoping one turns up.
 *
 * <p>A real HR system would obviously not expose its own defects. This endpoint
 * exists because this one is a test fixture pretending to be an HR system, and
 * being explicit about which half is which is cheaper than finding out later.
 */
record SeededAnomalyView(String kind, String employeeNumber, String detail) {

    static SeededAnomalyView of(SeededAnomaly anomaly) {
        return new SeededAnomalyView(anomaly.kind().name(), anomaly.employeeNumber(), anomaly.detail());
    }
}
