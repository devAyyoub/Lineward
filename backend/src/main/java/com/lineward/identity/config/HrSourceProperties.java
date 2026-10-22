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
package com.lineward.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the HR system is and how much of it to read at a time.
 *
 * @param baseUrl          where the HR system answers. The simulated one of
 *                         phase 1 runs on port 8090
 * @param sourceSystemCode the {@code source_system.code} this reader ingests
 *                         into. A row, not a constant in the code, because
 *                         ADR-013 decision 3 models the authoritative source as
 *                         data
 * @param pageSize         how many records per request, and also how many share
 *                         a transaction
 * @param ingestOnStartup  runs a full ingestion when the application starts.
 *                         Off by default. It exists so that the run can be
 *                         driven end to end before there is an API or a
 *                         schedule to drive it, which is how this gets verified
 *                         against the real HR system rather than against a test
 *                         double
 */
@ConfigurationProperties(prefix = "lineward.identity.hr")
record HrSourceProperties(String baseUrl, String sourceSystemCode, int pageSize, boolean ingestOnStartup) {

    HrSourceProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("lineward.identity.hr.base-url is required");
        }
        if (sourceSystemCode == null || sourceSystemCode.isBlank()) {
            throw new IllegalArgumentException("lineward.identity.hr.source-system-code is required");
        }
        // Zero would turn the paging loop into one that never advances, and the
        // default of an unset int is zero.
        if (pageSize < 1) {
            throw new IllegalArgumentException(
                    "lineward.identity.hr.page-size must be at least 1, got " + pageSize);
        }
    }
}
