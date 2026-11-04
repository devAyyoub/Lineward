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
 * @param correlation       what the run decided about who the accounts belong
 *                          to
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
        Correlation correlation,
        Instant startedAt,
        Instant finishedAt) {

    /**
     * What a run changed about the owners of the accounts it read.
     *
     * <p><b>The first two are changes, the last two are not</b>, and that is
     * worth stating because the first version of this javadoc claimed all four
     * were changes. An end to end run against a real directory proved it wrong
     * on 2026-11-04: a second read of an unchanged system links and unlinks
     * nothing, which is what makes it idempotent, and still reports the two
     * accounts it cannot place, because they are still there. A run that
     * stopped mentioning them would be hiding the finding rather than being
     * quiet.
     *
     * <p>What is deliberately absent is how many accounts have an owner. That
     * is a question for a query, not for a run: a run that counted them would
     * report five thousand correlations every night and the numbers worth
     * seeing would be lost in it.
     *
     * @param linked      accounts a rule gave an owner this run, including ones
     *                    whose owner changed
     * @param unlinked    accounts a rule had correlated and no longer matches.
     *                    Worth its own number: it is somebody losing their
     *                    claim on an account, and the usual cause is that the
     *                    person left and their identity stopped matching
     * @param ambiguous   accounts read this run whose identifier matched more
     *                    than one person, and which were therefore left without
     *                    an owner. A data problem somebody has to fix, which is
     *                    why it is not folded into the one below. Reported
     *                    every run and not only the first, so it does not stop
     *                    being visible by being old
     * @param unmatched   accounts read this run that no rule could match at
     *                    all. Not an error: an account that belongs to nobody
     *                    is what aggregation is run to find. Reported every
     *                    run, same reason
     * @param machines    accounts the target system itself called service
     *                    accounts, which no rule was run against. Counted apart
     *                    from {@code unmatched} because the two mean opposite
     *                    things to whoever reads the report: unmatched is a
     *                    question the rules could not answer, and this is a
     *                    question they were never asked. Folding them together
     *                    would put every service account on a list of orphans
     *                    to chase, and the list people stop reading is the one
     *                    that cried wolf. Reported every run, same reason as
     *                    the two above
     */
    public record Correlation(int linked, int unlinked, int ambiguous, int unmatched, int machines) {

        public static final Correlation NOTHING = new Correlation(0, 0, 0, 0, 0);

        /**
         * The four outcomes, named, because five positional integers at the
         * call site read as a checksum rather than as a decision. Each one
         * also carries how many claims it released, which is not an extra
         * detail: three of the four are reached by accounts that a rule used
         * to own, and the release is the only part of them that changes
         * anything.
         */
        public static Correlation linked(int accounts) {
            return new Correlation(accounts, 0, 0, 0, 0);
        }

        public static Correlation ambiguous(int released) {
            return new Correlation(0, released, 1, 0, 0);
        }

        public static Correlation unmatched(int released) {
            return new Correlation(0, released, 0, 1, 0);
        }

        public static Correlation machine(int released) {
            return new Correlation(0, released, 0, 0, 1);
        }

        public Correlation plus(Correlation other) {
            return new Correlation(
                    linked + other.linked,
                    unlinked + other.unlinked,
                    ambiguous + other.ambiguous,
                    unmatched + other.unmatched,
                    machines + other.machines);
        }
    }

    /**
     * A run that never started, because the system would not answer.
     *
     * <p>Every count is zero and that is the truth: nothing was read. What
     * stops it being read as an empty system is {@code reachable}.
     */
    public static AggregationReport unreachable(
            String applicationCode, String detail, Instant startedAt, Instant finishedAt) {
        return new AggregationReport(
                applicationCode, false, detail, 0, 0, 0, 0, 0, 0, Correlation.NOTHING, startedAt, finishedAt);
    }
}
