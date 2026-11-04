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
package com.lineward.aggregation;

import java.time.Instant;

/**
 * What one run against one target system did.
 *
 * <p>The counts are separate for the reason {@code IngestionReport} gives: a
 * run where everything is an update is the normal case and the one that proves
 * the run is idempotent, and a run where everything looks created means the
 * upsert is matching on the wrong thing. Neither failure is visible if the only
 * number reported is how many accounts came back.
 *
 * <p>{@code reachable} is first among them because it changes what every other
 * number means. A run against a system that would not answer has read zero
 * accounts, and zero accounts read is indistinguishable from a system with none
 * unless the report says which it was.
 *
 * @param applicationCode   which system this was
 * @param reachable         whether the system answered at all
 * @param detail            what it said, or why it did not. Never a credential:
 *                          the connectors redact theirs at the type
 * @param entitlementsSeen  permissions the system reported it can grant,
 *                          whether or not they were already in the catalogue
 * @param accountsSeen      accounts the system returned
 * @param accountsCreated   accounts Lineward had never stored before
 * @param accountsUpdated   accounts that were already stored. Not "that
 *                          changed": an account read again moves its heartbeat
 *                          whether or not anything about it is different
 * @param grantsAdded       memberships written for the first time
 * @param grantsRemoved     memberships the account no longer holds. Real
 *                          access taken away in the target system, which is
 *                          the one number here that somebody may have to
 *                          explain
 * @param startedAt         when the run began
 * @param finishedAt        when it ended
 */
public record AggregationReport(
        String applicationCode,
        boolean reachable,
        String detail,
        int entitlementsSeen,
        int accountsSeen,
        int accountsCreated,
        int accountsUpdated,
        int grantsAdded,
        int grantsRemoved,
        Instant startedAt,
        Instant finishedAt) {

    /**
     * A run that never started, because the system would not answer.
     *
     * <p>Every count is zero and that is the truth: nothing was read. What
     * stops it being read as an empty system is {@code reachable}.
     */
    public static AggregationReport unreachable(
            String applicationCode, String detail, Instant startedAt, Instant finishedAt) {
        return new AggregationReport(applicationCode, false, detail, 0, 0, 0, 0, 0, 0, startedAt, finishedAt);
    }
}
