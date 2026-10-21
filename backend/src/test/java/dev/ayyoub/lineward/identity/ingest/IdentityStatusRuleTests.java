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
package dev.ayyoub.lineward.identity.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ayyoub.lineward.identity.IdentityStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class IdentityStatusRuleTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);

    @Test
    void somebodyWorkingHereIsActive() {
        assertThat(IdentityStatusRule.statusFor(LocalDate.of(2020, 1, 1), null, TODAY))
                .isEqualTo(IdentityStatus.ACTIVE);
    }

    /**
     * The future dated joiner of M1. The record has to exist, because HR already
     * asserts the person, and it has to hold no access until the date arrives.
     */
    @Test
    void somebodyStartingNextMonthIsPending() {
        assertThat(IdentityStatusRule.statusFor(LocalDate.of(2026, 11, 14), null, TODAY))
                .isEqualTo(IdentityStatus.PENDING);
    }

    @Test
    void somebodyStartingTodayIsAlreadyActive() {
        assertThat(IdentityStatusRule.statusFor(TODAY, null, TODAY)).isEqualTo(IdentityStatus.ACTIVE);
    }

    /** The retroactive termination: HR says today that somebody left in January. */
    @Test
    void somebodyWhoAlreadyLeftIsTerminated() {
        assertThat(IdentityStatusRule.statusFor(
                        LocalDate.of(2025, 12, 16), LocalDate.of(2026, 1, 15), TODAY))
                .isEqualTo(IdentityStatus.TERMINATED);
    }

    @Test
    void theLastDayCountsAsLeft() {
        assertThat(IdentityStatusRule.statusFor(LocalDate.of(2020, 1, 1), TODAY, TODAY))
                .isEqualTo(IdentityStatus.TERMINATED);
    }

    /** A notice period: the leaving date is known and has not arrived. */
    @Test
    void somebodyLeavingNextWeekIsStillActive() {
        assertThat(IdentityStatusRule.statusFor(
                        LocalDate.of(2020, 1, 1), LocalDate.of(2026, 10, 28), TODAY))
                .isEqualTo(IdentityStatus.ACTIVE);
    }

    /**
     * The contradictory record that a real extract contains and the generated
     * population plants on purpose. The safe reading of nonsense is the one that
     * grants nothing; the contradiction itself is a finding for M2, not a reason
     * to hand out access while somebody works out what the source meant.
     */
    @Test
    void aTerminationBeforeTheHireDateStillCountsAsTerminated() {
        assertThat(IdentityStatusRule.statusFor(
                        LocalDate.of(2026, 7, 27), LocalDate.of(2026, 7, 7), TODAY))
                .isEqualTo(IdentityStatus.TERMINATED);
    }

    /** An extract is allowed to be incomplete, and no dates is not a reason to refuse it. */
    @Test
    void aRecordWithNoDatesIsActive() {
        assertThat(IdentityStatusRule.statusFor(null, null, TODAY)).isEqualTo(IdentityStatus.ACTIVE);
    }
}
