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

import com.lineward.access.birthright.BirthrightRules.DueAccess;
import com.lineward.identity.GovernedAttributes;
import com.lineward.identity.IdentityStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The rules this build actually ships, loaded the way the application loads
 * them.
 *
 * <p>The other tests feed the engine rules written in Java, which proves the
 * engine and nothing about the file. This one reads
 * {@code birthright-rules.yaml} off the classpath and binds it, so a condition
 * naming an attribute that does not exist, a rule that lost its last condition
 * and silently became universal, or a duplicated id fails here rather than at
 * somebody's first day. It is the half of ADR-013 decision 10 that says rules in
 * the repository get tested by the build.
 *
 * <p>It asserts the shape of the file and one worked case, not every rule. A
 * test that restated all fourteen would have to be edited with every rule
 * change, which is how a test stops being a check and becomes a copy.
 */
class BirthrightRulesFileTests {

    private static BirthrightRules rules;

    @BeforeAll
    static void loadTheShippedRules() {
        rules = ShippedBirthrightRules.load();
    }

    @Test
    void bindsEveryRuleInTheFile() {
        assertThat(rules.declared()).hasSize(14);
        assertThat(rules.declared()).extracting(BirthrightRule::id).contains("base-package", "managers");
    }

    /**
     * One rule is universal and the rest are conditional. If a second rule ever
     * becomes universal it is a decision, not a typo, and this is where the
     * decision gets noticed.
     */
    @Test
    void grantsToEverybodyFromExactlyOneRule() {
        assertThat(rules.declared())
                .filteredOn(BirthrightRule::appliesToEveryone)
                .extracting(BirthrightRule::id)
                .containsExactly("base-package");
    }

    /**
     * A worked case through the real file: the treasury analyst of section 4.12
     * of the working document, working remotely. Five grants from four
     * different rules, which is the smallest example that shows the base
     * package, a department, a location and an entitlement that carries
     * authority all landing on one person.
     */
    @Test
    void worksOutWhatARemoteTreasuryAnalystIsDue() {
        GovernedAttributes lucia = new GovernedAttributes(
                UUID.randomUUID(), IdentityStatus.ACTIVE,
                "Treasury", "Treasury Analyst", "Remote", "TRE-001", "EMPLOYEE");

        assertThat(rules.dueFor(lucia)).containsExactlyInAnyOrder(
                new DueAccess("corporate-directory", "cn=all-staff,ou=groups,dc=lineward,dc=example",
                        "base-package"),
                new DueAccess("intranet", "employee", "base-package"),
                new DueAccess("corporate-directory", "cn=dept-treasury,ou=groups,dc=lineward,dc=example",
                        "department-treasury"),
                new DueAccess("corporate-directory", "cn=remote-vpn,ou=groups,dc=lineward,dc=example",
                        "remote-workers"),
                new DueAccess("intranet", "treasury-read", "treasury-read"));
    }

    /**
     * The department every synthetic population has and no rule names: the one
     * person in the executive office. Worth asserting because the alternative
     * design, deriving a group name from the department, would have asked a
     * target system for a group that does not exist instead of granting nothing.
     */
    @Test
    void grantsOnlyTheBasePackageForADepartmentNoRuleNames() {
        GovernedAttributes chiefExecutive = new GovernedAttributes(
                UUID.randomUUID(), IdentityStatus.ACTIVE,
                "Executive Office", "Chief Executive Officer", "Madrid", "CC-EXE-001", "EMPLOYEE");

        assertThat(rules.dueFor(chiefExecutive))
                .extracting(DueAccess::ruleId)
                .containsExactlyInAnyOrder("base-package", "base-package", "managers");
    }

    /** Every rule the file declares points at something, and says what it is for. */
    @Test
    void declaresADescriptionAndAtLeastOneGrantEverywhere() {
        List<BirthrightRule> declared = rules.declared();

        assertThat(declared).allSatisfy(rule -> {
            assertThat(rule.description()).isNotBlank();
            assertThat(rule.grants()).isNotEmpty();
        });
    }
}
