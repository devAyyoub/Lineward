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
package com.lineward.identity.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.lineward.identity.IdentityStatus;
import com.lineward.identity.LifecycleEvent;
import com.lineward.identity.source.AssertedIdentity;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The joiner, mover and leaver classification.
 *
 * <p>Split the way the rule is: the transition cases say what each change of
 * lifecycle state means, and the comparison cases say which fields count as a
 * move. The dates themselves are not retested here, because deriving a status
 * from a hire and a termination date is {@link IdentityStatusRule} and has its
 * own tests. Only the two composed cases at the end cross both, to prove that
 * today's date reaches the derivation.
 *
 * <p>What the comparison never looks at is the governed identity, which is
 * decision 4 of ADR-013: comparing attributes against {@code identity} would
 * turn a correction made inside Lineward into news from HR and fire a mover
 * that nobody asked for. The one thing read from {@code identity} is the status,
 * and that is the point: it is the record of what Lineward last acted on.
 */
class LifecycleEventRuleTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    @Nested
    class WhatEachTransitionMeans {

        @Test
        void somebodyThePayrollHasNeverMentionedBeforeIsAJoiner() {
            assertThat(LifecycleEventRule.classify(null, IdentityStatus.ACTIVE, false))
                    .isEqualTo(LifecycleEvent.JOINER);
        }

        /**
         * The future dated joiner of M1. The identity has to exist, because HR
         * already asserts the person, and no access is due until the date
         * arrives, so there is nothing for a consumer to act on yet.
         */
        @Test
        void somebodyWhoStartsNextMonthIsNotAJoinerYet() {
            assertThat(LifecycleEventRule.classify(null, IdentityStatus.PENDING, false))
                    .isEqualTo(LifecycleEvent.NONE);
        }

        /**
         * The first full read of a real population contains people who left
         * years ago. They never held access here, so there is nothing to
         * revoke, and calling it a leaver would bury the real ones under a few
         * hundred events that mean nothing.
         */
        @Test
        void somebodyWhoAlreadyLeftBeforeLinewardSawThemIsNotALeaver() {
            assertThat(LifecycleEventRule.classify(null, IdentityStatus.TERMINATED, false))
                    .isEqualTo(LifecycleEvent.NONE);
        }

        /**
         * The day a future joiner starts. HR asserts the same record as
         * yesterday, so the payload fingerprint sees nothing: the only thing
         * that changed is which side of the hire date today falls on.
         */
        @Test
        void theDayAFutureJoinerStartsIsTheirJoiner() {
            assertThat(LifecycleEventRule.classify(IdentityStatus.PENDING, IdentityStatus.ACTIVE, false))
                    .isEqualTo(LifecycleEvent.JOINER);
        }

        @Test
        void aTerminationThatHasTakenEffectIsTheLeaver() {
            assertThat(LifecycleEventRule.classify(IdentityStatus.ACTIVE, IdentityStatus.TERMINATED, false))
                    .isEqualTo(LifecycleEvent.LEAVER);
        }

        /**
         * M1 asks for the returning employee to reactivate the identity instead
         * of duplicating it. The access work is a joiner's, and the name is its
         * own because the audit trail of somebody who came back is not the
         * audit trail of somebody new.
         */
        @Test
        void aReturningEmployeeIsARehireAndNotAJoiner() {
            assertThat(LifecycleEventRule.classify(IdentityStatus.TERMINATED, IdentityStatus.ACTIVE, false))
                    .isEqualTo(LifecycleEvent.REHIRE);
        }

        @Test
        void anActiveIdentityWhoseBasisMovedIsAMover() {
            assertThat(LifecycleEventRule.classify(IdentityStatus.ACTIVE, IdentityStatus.ACTIVE, true))
                    .isEqualTo(LifecycleEvent.MOVER);
        }

        @Test
        void anActiveIdentityThatDidNotMoveIsNotAnEvent() {
            assertThat(LifecycleEventRule.classify(IdentityStatus.ACTIVE, IdentityStatus.ACTIVE, false))
                    .isEqualTo(LifecycleEvent.NONE);
        }

        /** Revoking everything makes recalculating the new department moot. */
        @Test
        void leavingWinsOverAMove() {
            assertThat(LifecycleEventRule.classify(IdentityStatus.ACTIVE, IdentityStatus.TERMINATED, true))
                    .isEqualTo(LifecycleEvent.LEAVER);
        }

        /**
         * Somebody who starts today with a department different from the one
         * announced last month gets one event and not two: a joiner grants from
         * scratch, so there is nothing for a mover to recalculate.
         */
        @Test
        void joiningWinsOverAMove() {
            assertThat(LifecycleEventRule.classify(IdentityStatus.PENDING, IdentityStatus.ACTIVE, true))
                    .isEqualTo(LifecycleEvent.JOINER);
        }

        /**
         * Somebody who left keeps being sent in the extract, and their record
         * keeps being corrected. None of it is access work.
         */
        @Test
        void aChangeToSomebodyWhoAlreadyLeftIsNotAMover() {
            assertThat(LifecycleEventRule.classify(
                            IdentityStatus.TERMINATED, IdentityStatus.TERMINATED, true))
                    .isEqualTo(LifecycleEvent.NONE);
        }

        @Test
        void aChangeToSomebodyWhoHasNotStartedIsNotAMoverEither() {
            assertThat(LifecycleEventRule.classify(IdentityStatus.PENDING, IdentityStatus.PENDING, true))
                    .isEqualTo(LifecycleEvent.NONE);
        }

        /**
         * Contradictory data that a real extract contains: the hire date of
         * somebody already working here moves into the future. The safe reading
         * is the one that takes access away, and the contradiction itself is a
         * finding for M2 rather than a reason to leave access live while
         * somebody works out what the source meant.
         */
        @Test
        void aHireDatePushedIntoTheFutureTakesAccessAway() {
            assertThat(LifecycleEventRule.classify(IdentityStatus.ACTIVE, IdentityStatus.PENDING, false))
                    .isEqualTo(LifecycleEvent.LEAVER);
        }
    }

    @Nested
    class WhichFieldsCountAsAMove {

        @Test
        void aChangeOfDepartmentCounts() {
            Employee lucia = employee().in("Tesoreria", "Analista");
            AssertedIdentity before = lucia.build();
            AssertedIdentity after = lucia.in("Riesgos", "Analista").build();

            assertThat(LifecycleEventRule.changedAccessAttributes(before, after))
                    .containsExactly("department");
        }

        /**
         * Listed by M1 as an edge case precisely because it is the one that gets
         * missed: birthright rules do not read the manager, so a reporting line
         * change looks like nothing. It still moves who approves this person's
         * requests and who certifies their access.
         */
        @Test
        void aChangeOfManagerWithoutAChangeOfJobCounts() {
            Employee lucia = employee().reportingTo("E-00042");
            AssertedIdentity before = lucia.build();
            AssertedIdentity after = lucia.reportingTo("E-00077").build();

            assertThat(LifecycleEventRule.changedAccessAttributes(before, after))
                    .containsExactly("manager");
        }

        @Test
        void losingAManagerAltogetherCounts() {
            Employee lucia = employee().reportingTo("E-00042");
            AssertedIdentity before = lucia.build();
            AssertedIdentity after = lucia.reportingTo(null).build();

            assertThat(LifecycleEventRule.changedAccessAttributes(before, after))
                    .containsExactly("manager");
        }

        /**
         * A correction of somebody's name must not be a move: recalculating
         * access for every typo is how a mover stops meaning anything.
         */
        @Test
        void aCorrectedSpellingDoesNotCount() {
            Employee lucia = employee().named("Lucia", "Fernandez");
            AssertedIdentity before = lucia.build();
            AssertedIdentity after = lucia.named("Lucia", "Fernandez Ruiz").build();

            assertThat(LifecycleEventRule.changedAccessAttributes(before, after)).isEmpty();
        }

        /**
         * The dates decide the lifecycle state and are compared through it, not
         * here. Counting them as a move would turn every leaver into a leaver
         * and a mover at once.
         */
        @Test
        void aChangeOfDatesDoesNotCount() {
            Employee lucia = employee().hiredOn(LocalDate.of(2020, 1, 7));
            AssertedIdentity before = lucia.build();
            AssertedIdentity after = lucia.terminatedOn(LocalDate.of(2026, 11, 28)).build();

            assertThat(LifecycleEventRule.changedAccessAttributes(before, after)).isEmpty();
        }

        @Test
        void anUnchangedRecordMovedNothing() {
            AssertedIdentity sameRecord = employee().build();

            assertThat(LifecycleEventRule.changedAccessAttributes(sameRecord, sameRecord)).isEmpty();
        }

        /**
         * A record Lineward has never seen has nothing to compare against, and
         * naming every attribute as changed would make a joiner look like the
         * largest mover in the organization.
         */
        @Test
        void aFirstAssertionMovedNothing() {
            assertThat(LifecycleEventRule.changedAccessAttributes(null, employee().build()))
                    .isEmpty();
        }

        /**
         * The event says what to do; the audit trail has to say what changed,
         * and "something about this person changed" is not an answer an auditor
         * accepts. The order is the declaration order, so two runs of the same
         * move read the same.
         */
        @Test
        void everyFieldThatMovedIsNamed() {
            Employee lucia = employee()
                    .in("Tesoreria", "Analista")
                    .at("Madrid")
                    .costing("CC-100")
                    .employedAs("PERMANENT")
                    .reportingTo("E-00042");
            AssertedIdentity before = lucia.build();
            AssertedIdentity after = lucia.in("Riesgos", "Analista senior")
                    .at("Barcelona")
                    .costing("CC-200")
                    .employedAs("CONTRACTOR")
                    .reportingTo("E-00077")
                    .build();

            assertThat(LifecycleEventRule.changedAccessAttributes(before, after))
                    .containsExactly(
                            "department", "jobTitle", "location", "costCenter", "employmentType", "manager");
        }
    }

    /**
     * The two cases that need the dates and the transition at once, which is
     * what the composed entry point exists for.
     */
    @Nested
    class FromTwoAssertions {

        /** HR says today that somebody left in January, and access has been live since. */
        @Test
        void aRetroactiveTerminationIsALeaver() {
            Employee lucia = employee().hiredOn(LocalDate.of(2020, 1, 7));
            AssertedIdentity before = lucia.build();
            AssertedIdentity after = lucia.terminatedOn(LocalDate.of(2026, 1, 15)).build();

            assertThat(LifecycleEventRule.classify(IdentityStatus.ACTIVE, before, after, TODAY))
                    .isEqualTo(LifecycleEvent.LEAVER);
        }

        /**
         * A notice period. The leaving date is known and has not arrived, so
         * access is still due and the leaver fires on the day rather than when
         * it is announced.
         */
        @Test
        void aNoticePeriodIsNotALeaverYet() {
            Employee lucia = employee().hiredOn(LocalDate.of(2020, 1, 7));
            AssertedIdentity before = lucia.build();
            AssertedIdentity after = lucia.terminatedOn(LocalDate.of(2026, 11, 28)).build();

            assertThat(LifecycleEventRule.classify(IdentityStatus.ACTIVE, before, after, TODAY))
                    .isEqualTo(LifecycleEvent.NONE);
        }

        @Test
        void aMoveOfDepartmentSurvivesTheComposition() {
            Employee lucia = employee().in("Tesoreria", "Analista");
            AssertedIdentity before = lucia.build();
            AssertedIdentity after = lucia.in("Riesgos", "Analista").build();

            assertThat(LifecycleEventRule.classify(IdentityStatus.ACTIVE, before, after, TODAY))
                    .isEqualTo(LifecycleEvent.MOVER);
        }

        @Test
        void aFirstAssertionOfSomebodyWorkingHereIsAJoiner() {
            AssertedIdentity arriving = employee().hiredOn(LocalDate.of(2026, 10, 1)).build();

            assertThat(LifecycleEventRule.classify(null, null, arriving, TODAY))
                    .isEqualTo(LifecycleEvent.JOINER);
        }
    }

    private static Employee employee() {
        return new Employee();
    }

    /**
     * Builds the record the way HR asserts it, so that each test names only the
     * field it is about.
     *
     * <p>Mutable on purpose, and safe because {@link #build()} returns an
     * immutable record: a test builds the earlier assertion, changes one field
     * and builds the later one, which is the shape of every comparison here.
     */
    private static final class Employee {

        private String firstName = "Lucia";
        private String lastName = "Fernandez";
        private String department = "Tesoreria";
        private String jobTitle = "Analista";
        private String location = "Madrid";
        private String costCenter = "CC-100";
        private String employmentType = "PERMANENT";
        private String managerExternalId = "E-00042";
        private LocalDate hireDate = LocalDate.of(2020, 1, 7);
        private LocalDate terminationDate;

        Employee named(String firstName, String lastName) {
            this.firstName = firstName;
            this.lastName = lastName;
            return this;
        }

        Employee in(String department, String jobTitle) {
            this.department = department;
            this.jobTitle = jobTitle;
            return this;
        }

        Employee at(String location) {
            this.location = location;
            return this;
        }

        Employee costing(String costCenter) {
            this.costCenter = costCenter;
            return this;
        }

        Employee employedAs(String employmentType) {
            this.employmentType = employmentType;
            return this;
        }

        Employee reportingTo(String managerExternalId) {
            this.managerExternalId = managerExternalId;
            return this;
        }

        Employee hiredOn(LocalDate hireDate) {
            this.hireDate = hireDate;
            return this;
        }

        Employee terminatedOn(LocalDate terminationDate) {
            this.terminationDate = terminationDate;
            return this;
        }

        AssertedIdentity build() {
            return new AssertedIdentity(
                    "E-00123",
                    firstName,
                    lastName,
                    "lucia.fernandez@example.test",
                    department,
                    jobTitle,
                    location,
                    costCenter,
                    employmentType,
                    hireDate,
                    terminationDate,
                    managerExternalId,
                    Instant.parse("2026-10-22T06:00:00Z"),
                    "{}");
        }
    }
}
