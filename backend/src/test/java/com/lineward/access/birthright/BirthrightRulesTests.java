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
package com.lineward.access.birthright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lineward.access.birthright.BirthrightRule.Grant;
import com.lineward.access.birthright.BirthrightRules.DueAccess;
import com.lineward.identity.GovernedAttributes;
import com.lineward.identity.IdentityStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rules without a database, because none of this needs one.
 *
 * <p>What is asserted is the one question the class asks: what is this identity
 * due right now. The cases chosen are the ones where a plausible implementation
 * differs from this one, which is why there is a test for a terminated identity
 * that still matches every rule, and one for an identity whose department the
 * source never asserted.
 */
class BirthrightRulesTests {

    private static final Grant ALL_STAFF = new Grant("corporate-directory", "cn=all-staff");
    private static final Grant EMPLOYEE = new Grant("intranet", "employee");
    private static final Grant TREASURY_READ = new Grant("intranet", "treasury-read");
    private static final Grant REMOTE_VPN = new Grant("corporate-directory", "cn=remote-vpn");

    private static final BirthrightRule BASE_PACKAGE = new BirthrightRule(
            "base-package", "Everybody.", true, Map.of(), List.of(ALL_STAFF, EMPLOYEE));

    private static final BirthrightRule TREASURY = new BirthrightRule(
            "treasury-read",
            "Treasury reads treasury positions.",
            false,
            Map.of(BirthrightAttribute.DEPARTMENT, List.of("Treasury")),
            List.of(TREASURY_READ));

    private static final BirthrightRule REMOTE = new BirthrightRule(
            "remote-workers",
            "Remote staff and contractors, not interns.",
            false,
            Map.of(
                    BirthrightAttribute.LOCATION, List.of("Remote"),
                    BirthrightAttribute.EMPLOYMENT_TYPE, List.of("EMPLOYEE", "CONTRACTOR")),
            List.of(REMOTE_VPN));

    private final BirthrightRules rules = new BirthrightRules(List.of(BASE_PACKAGE, TREASURY, REMOTE));

    @Test
    void grantsTheBasePackageToAnybodyActive() {
        assertThat(rules.dueFor(active("Engineering", "Software Engineer", "Madrid", "EMPLOYEE")))
                .containsExactly(
                        new DueAccess("corporate-directory", "cn=all-staff", "base-package"),
                        new DueAccess("intranet", "employee", "base-package"));
    }

    @Test
    void addsWhatTheDepartmentGives() {
        assertThat(rules.dueFor(active("Treasury", "Treasury Analyst", "Madrid", "EMPLOYEE")))
                .contains(new DueAccess("intranet", "treasury-read", "treasury-read"));
    }

    /**
     * The access trail, which is half of what gets stored. Without the rule id
     * on the row, the "why does this identity have this" screen can say
     * birthright and not which one, and a rule that stops applying cannot be
     * told from one that never did.
     */
    @Test
    void namesTheRuleThatGrantsEachEntitlement() {
        assertThat(rules.dueFor(active("Treasury", "Treasury Analyst", "Remote", "EMPLOYEE")))
                .extracting(DueAccess::ruleId)
                .containsExactly("base-package", "base-package", "treasury-read", "remote-workers");
    }

    @Nested
    class AccessIsDueOnlyWhileActive {

        /**
         * The reason there is no leaver branch anywhere in this package. A
         * terminated identity matching every rule in the file is due nothing, so
         * the difference against what it holds is a full revocation and the
         * leaver is the same subtraction as the mover.
         */
        @Test
        void aTerminatedIdentityIsDueNothingAlthoughEveryRuleMatches() {
            GovernedAttributes terminated = new GovernedAttributes(
                    UUID.randomUUID(), IdentityStatus.TERMINATED,
                    "Treasury", "Treasury Analyst", "Remote", "TRE-001", "EMPLOYEE");

            assertThat(rules.dueFor(terminated)).isEmpty();
        }

        /** The future dated joiner M1 lists: the record exists and holds nothing. */
        @Test
        void aJoinerWhoHasNotStartedIsDueNothing() {
            GovernedAttributes notStartedYet = new GovernedAttributes(
                    UUID.randomUUID(), IdentityStatus.PENDING,
                    "Treasury", "Treasury Analyst", "Madrid", "TRE-001", "EMPLOYEE");

            assertThat(rules.dueFor(notStartedYet)).isEmpty();
        }

        /**
         * Phase 6 sets this when the party responsible for an internally sourced
         * identity goes away, which is how an agent stops working when its
         * sponsor leaves. It falls out of the same check, so phase 6 does not
         * have to teach this class what a sponsor is.
         */
        @Test
        void aSuspendedIdentityIsDueNothing() {
            GovernedAttributes suspended = new GovernedAttributes(
                    UUID.randomUUID(), IdentityStatus.SUSPENDED,
                    "Treasury", "Treasury Analyst", "Madrid", "TRE-001", "EMPLOYEE");

            assertThat(rules.dueFor(suspended)).isEmpty();
        }
    }

    @Nested
    class HowAConditionMatches {

        @Test
        void everyNamedAttributeHasToMatch() {
            assertThat(rules.dueFor(active("Engineering", "Software Engineer", "Remote", "INTERN")))
                    .doesNotContain(new DueAccess("corporate-directory", "cn=remote-vpn", "remote-workers"));
            assertThat(rules.dueFor(active("Engineering", "Software Engineer", "Remote", "CONTRACTOR")))
                    .contains(new DueAccess("corporate-directory", "cn=remote-vpn", "remote-workers"));
        }

        @Test
        void anyOfTheListedValuesMatches() {
            assertThat(rules.dueFor(active("Engineering", "Software Engineer", "Remote", "EMPLOYEE")))
                    .contains(new DueAccess("corporate-directory", "cn=remote-vpn", "remote-workers"));
        }

        /**
         * M1 requires ingesting incomplete HR data, so this case arrives whether
         * it is designed for or not. A null that matched would put the record in
         * whatever bucket the empty case falls into, which is the sort of
         * default nobody notices until an audit.
         */
        @Test
        void anAttributeTheSourceNeverAssertedMatchesNothing() {
            assertThat(rules.dueFor(active(null, "Treasury Analyst", "Madrid", "EMPLOYEE")))
                    .extracting(DueAccess::ruleId)
                    .containsOnly("base-package");
        }

        /**
         * Exact and case sensitive. The source asserts the same spelling every
         * day, and a looser comparison would make two spellings one department
         * here and two in every target system.
         */
        @Test
        void aDifferentSpellingIsADifferentValue() {
            assertThat(rules.dueFor(active("treasury", "Treasury Analyst", "Madrid", "EMPLOYEE")))
                    .extracting(DueAccess::ruleId)
                    .containsOnly("base-package");
        }
    }

    @Nested
    class RejectsRulesThatCannotBeTrusted {

        /**
         * Two rules with one id would collide on the unique index of a live
         * assignment, halfway through an ingestion run, and leave a trail that
         * cannot say which rule granted what.
         */
        @Test
        void twoRulesWithTheSameId() {
            assertThatThrownBy(() -> new BirthrightRules(List.of(TREASURY, TREASURY)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("treasury-read");
        }

        /** A condition that reads as a restriction while granting to everybody. */
        @Test
        void aRuleThatIsBothUniversalAndConditional() {
            assertThatThrownBy(() -> new BirthrightRule(
                            "both", "Both.", true,
                            Map.of(BirthrightAttribute.DEPARTMENT, List.of("Treasury")),
                            List.of(ALL_STAFF)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("applies-to-everyone");
        }

        /**
         * And the direction that matters more: a rule whose last condition gets
         * deleted must not become a rule that grants to everybody.
         */
        @Test
        void aRuleWithNeitherAConditionNorTheUniversalFlag() {
            assertThatThrownBy(() -> new BirthrightRule(
                            "neither", "Neither.", false, Map.of(), List.of(ALL_STAFF)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("applies-to-everyone");
        }

        @Test
        void aRuleWithNoDescription() {
            assertThatThrownBy(() -> new BirthrightRule(
                            "quiet", "  ", true, Map.of(), List.of(ALL_STAFF)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("description");
        }

        @Test
        void aRuleThatGrantsNothing() {
            assertThatThrownBy(() -> new BirthrightRule(
                            "empty", "Grants nothing.", true, Map.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("grants nothing");
        }

        @Test
        void aConditionThatAcceptsNoValue() {
            assertThatThrownBy(() -> new BirthrightRule(
                            "unmatchable", "Can never match.", false,
                            Map.of(BirthrightAttribute.DEPARTMENT, List.of()),
                            List.of(ALL_STAFF)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DEPARTMENT");
        }

        @Test
        void aGrantWithoutAnEntitlement() {
            assertThatThrownBy(() -> new Grant("intranet", " "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("intranet");
        }
    }

    private static GovernedAttributes active(
            String department, String jobTitle, String location, String employmentType) {
        return new GovernedAttributes(
                UUID.randomUUID(), IdentityStatus.ACTIVE,
                department, jobTitle, location, "CC-001", employmentType);
    }
}
