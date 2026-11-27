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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What one reconciliation of one target system concluded.
 *
 * <p>The counterpart of {@link AggregationReport} on the other half of M2, and
 * the difference between the two says what the two halves are: that one counts
 * what a target system said, this one counts what Lineward decided about it.
 * Nothing here talks to anything outside the database.
 *
 * <p><b>Three numbers per kind of finding and not one</b>, for the reason
 * {@code AggregationReport} gives about its own: a pass where everything is
 * confirmed is the normal case and the one that proves the pass is idempotent,
 * and a pass where everything looks newly opened means the findings are not
 * matching the ones already stored and the table is filling with duplicates.
 * Neither failure is visible if the only number reported is how many problems
 * exist.
 *
 * @param applicationCode  which system this was
 * @param dormancyAssessed whether this system reports activity at all. False
 *                         means its dormancy tally is zero because nothing
 *                         could be measured, which is a different sentence from
 *                         a system where every account is in use, and the one
 *                         number here that would otherwise be read as the wrong
 *                         good news
 * @param tallies          one entry per kind of finding this pass evaluated, in
 *                         the order of {@link FindingType}
 * @param startedAt        when the pass began
 * @param finishedAt       when it ended
 */
public record ReconciliationReport(
        String applicationCode,
        boolean dormancyAssessed,
        List<Tally> tallies,
        Instant startedAt,
        Instant finishedAt) {

    public ReconciliationReport {
        tallies = List.copyOf(tallies);
    }

    /**
     * What happened to one kind of finding.
     *
     * @param type      which kind
     * @param opened    conditions that were not already written down as open.
     *                  The number somebody acts on
     * @param confirmed conditions that were already open and still hold. Not a
     *                  change: the finding's heartbeat moved and nothing else
     * @param resolved  findings that were open and whose condition has stopped
     *                  holding. Closed as resolved externally, because Lineward
     *                  did not do it and has no way to know who did
     */
    public record Tally(FindingType type, int opened, int confirmed, int resolved) {

        public static Tally nothing(FindingType type) {
            return new Tally(type, 0, 0, 0);
        }

        public Tally plus(Tally other) {
            return new Tally(type, opened + other.opened, confirmed + other.confirmed, resolved + other.resolved);
        }

        /** True when this kind of finding changed nothing, which is what a quiet night looks like. */
        public boolean quiet() {
            return opened == 0 && resolved == 0;
        }
    }

    /** The tally of one kind, or an empty one when this pass did not evaluate it. */
    public Tally of(FindingType type) {
        return tallies.stream()
                .filter(tally -> tally.type() == type)
                .findFirst()
                .orElseGet(() -> Tally.nothing(type));
    }

    public int openedTotal() {
        return tallies.stream().mapToInt(Tally::opened).sum();
    }

    public int confirmedTotal() {
        return tallies.stream().mapToInt(Tally::confirmed).sum();
    }

    public int resolvedTotal() {
        return tallies.stream().mapToInt(Tally::resolved).sum();
    }

    /**
     * Builds a report from tallies gathered in any order, putting them in the
     * order of the enum.
     *
     * <p>The order matters for the same reason {@code JdbcApplicationLookup}
     * orders its applications: two passes that report the same findings in a
     * different sequence are two passes nobody can diff.
     */
    public static ReconciliationReport of(
            String applicationCode,
            boolean dormancyAssessed,
            Map<FindingType, Tally> tallies,
            Instant startedAt,
            Instant finishedAt) {
        // Constructed empty and filled, not copied: EnumMap's copy
        // constructor refuses an empty map it cannot take the type from, and an
        // application with nothing to evaluate is a legitimate pass.
        Map<FindingType, Tally> ordered = new EnumMap<>(FindingType.class);
        ordered.putAll(tallies);
        return new ReconciliationReport(
                applicationCode, dormancyAssessed, List.copyOf(ordered.values()), startedAt, finishedAt);
    }
}
