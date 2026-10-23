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

import com.lineward.identity.GovernedAttributes;
import com.lineward.identity.IdentityStatus;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What access an identity is due, by the rules as written.
 *
 * <p>One question, asked the way {@code LifecycleEventRule} asks its own:
 * <b>what is this identity due right now?</b> Not what changed, not what it had.
 * The answer is a set, it is computed from the stored attributes every time, and
 * that is what makes the awkward cases need no code of their own:
 *
 * <ul>
 *   <li>A leaver is due nothing, so the difference against what it holds is a
 *       full revocation. There is no leaver branch here.
 *   <li>A joiner hired next month is {@code PENDING} and due nothing, so the
 *       record can exist without holding anything, which is the edge case M1
 *       lists.
 *   <li>A mover is due what the new department gives and not what the old one
 *       did, so the surplus falls out of the same subtraction. Privilege creep
 *       is what happens when the surplus is computed by hand.
 *   <li>A suspended agent whose sponsor left is due nothing, in phase 6, without
 *       this class learning what a sponsor is.
 * </ul>
 *
 * <p>Access is due only while the identity is {@code ACTIVE}, and that single
 * check is doing the work of four. It is section 1.6 point 5 of the working
 * document in practice: a design where the failure cannot happen beats a
 * validation somebody has to remember to call.
 */
final class BirthrightRules {

    private final List<BirthrightRule> rules;

    BirthrightRules(List<BirthrightRule> rules) {
        this.rules = List.copyOf(rules);

        // Two rules with one id would collide in the database rather than here:
        // source_detail is part of the unique index on a live assignment, so the
        // second grant of the same entitlement would be rejected by PostgreSQL
        // halfway through an ingestion run. A duplicate id is also a trail that
        // cannot answer which rule granted the access, which is the one thing
        // the id is for.
        Set<String> seen = new HashSet<>();
        for (BirthrightRule rule : this.rules) {
            if (!seen.add(rule.id())) {
                throw new IllegalArgumentException(
                        "two birthright rules share the id '" + rule.id()
                                + "', so neither can be the reason an identity holds something");
            }
        }
    }

    /**
     * An entitlement somebody is due, and the rule that says so.
     *
     * <p>The rule travels with the entitlement rather than being looked up
     * afterwards, because it is half of what gets stored: principle 2 of section
     * 3.3 asks the platform to answer why an identity has an access, and the
     * answer is a row that names the rule. Two rules granting the same
     * entitlement are two reasons for it and therefore two of these, which is
     * the same shape as a birthright grant and an approved request coexisting.
     */
    record DueAccess(String application, String entitlement, String ruleId) {}

    /**
     * @return the entitlements due, each paired with the rule that grants it, in
     *     the order the rules are declared. Empty for any identity that is not
     *     active, whatever the rules say about it
     */
    Set<DueAccess> dueFor(GovernedAttributes attributes) {
        if (attributes.status() != IdentityStatus.ACTIVE) {
            return Set.of();
        }

        // Insertion ordered, so that the inserts of one recalculation happen in
        // the order the file reads. Nothing depends on it, and it means a log or
        // a failure points at the same rule twice.
        Set<DueAccess> due = new LinkedHashSet<>();
        for (BirthrightRule rule : rules) {
            if (rule.matches(attributes)) {
                for (BirthrightRule.Grant grant : rule.grants()) {
                    due.add(new DueAccess(grant.application(), grant.entitlement(), rule.id()));
                }
            }
        }
        return due;
    }

    /** Every entitlement any rule can grant, which is what the catalogue has to contain. */
    Set<BirthrightRule.Grant> everyGrant() {
        Set<BirthrightRule.Grant> grants = new LinkedHashSet<>();
        rules.forEach(rule -> grants.addAll(rule.grants()));
        return grants;
    }

    List<BirthrightRule> declared() {
        return rules;
    }
}
