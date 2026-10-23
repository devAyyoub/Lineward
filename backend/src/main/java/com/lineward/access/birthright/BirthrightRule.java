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
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One rule: who it applies to, and what they therefore hold.
 *
 * <p>A rule lives in a versioned file and is loaded as this type, which is
 * decision 10 of ADR-013. The three reasons given there are worth repeating
 * because they are the reasons this class exists instead of a table: a rule is
 * adjusted when a system is onboarded and then almost never, rules have to be
 * tested and a file is tested in the build while production rows are tested by
 * nobody, and a change in git has a reviewer, a diff and a way back where a row
 * has an audit entry and no reviewer. A rule editable while the system runs also
 * invites editing it so that a demonstration goes well.
 *
 * <p><b>How a condition reads.</b> Every attribute named in {@code when} has to
 * match, and a value matches when it is one of the listed ones: and across
 * attributes, or within one. Treasury plus two job titles means somebody in
 * Treasury holding either of those jobs, which is the shape almost every real
 * birthright rule has.
 *
 * <p><b>An absent attribute matches nothing.</b> M1 requires ingesting
 * incomplete HR data, so a record with no department arrives and has to be
 * governed. Granting it whatever the empty case happens to fall into is the kind
 * of default nobody notices until an audit, so a null fails the condition and
 * such a record holds only what unconditional rules give it.
 *
 * <p><b>Matching is exact and case sensitive.</b> The values come from an
 * authoritative source that asserts the same spelling every day, and loosening
 * the comparison would mean "Treasury" and "treasury" are the same department
 * here and two different ones in every target system, which is a correlation
 * problem bought in exchange for nothing.
 *
 * @param id names this rule in the access trail: it is written to
 *     {@code access_assignment.source_detail}, which is what lets the "why does
 *     this identity have this" screen answer with a rule instead of a shrug. It
 *     is therefore a stable identifier, and renaming one retires the access it
 *     granted and grants it again under the new name
 * @param description what the rule is for, in a sentence, and required rather
 *     than optional. The file is the reviewable artefact that ADR-013 decision
 *     10 is paying for, and a rule nobody can read is a rule nobody reviews
 * @param appliesToEveryone the base package: what somebody holds for working
 *     here at all. Explicit, and not an empty condition, because a rule that
 *     grants to everybody is the one worth being unable to write by accident
 * @param when the attributes that have to match, empty when
 *     {@code appliesToEveryone}
 * @param grants what the identity is due when this rule matches, each named by
 *     the application code and the native value of the entitlement
 */
record BirthrightRule(
        String id,
        String description,
        boolean appliesToEveryone,
        Map<BirthrightAttribute, List<String>> when,
        List<Grant> grants) {

    /**
     * An entitlement named the way a human writes it, by application and native
     * value, rather than by the database id it has in this installation.
     *
     * <p>A rule file full of uuids could not be reviewed, and the same file has
     * to mean the same thing in a test schema, in the demo and anywhere else it
     * is ever applied. The translation to ids is {@link EntitlementCatalog}'s
     * job, and failing it is how a rule pointing at nothing gets found.
     */
    record Grant(String application, String entitlement) {

        Grant {
            if (application == null || application.isBlank()) {
                throw new IllegalArgumentException("a grant names the application it is in");
            }
            if (entitlement == null || entitlement.isBlank()) {
                throw new IllegalArgumentException(
                        "a grant in application '" + application + "' names no entitlement");
            }
        }

        @Override
        public String toString() {
            return application + "/" + entitlement;
        }
    }

    BirthrightRule {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("every birthright rule needs an id, it is its access trail");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("birthright rule '" + id + "' has no description");
        }

        // An EnumMap, so that iterating the conditions follows the declaration
        // order of the attributes and a failure message reads the same twice.
        when = when == null || when.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new EnumMap<>(when));
        grants = grants == null ? List.of() : List.copyOf(grants);

        if (grants.isEmpty()) {
            throw new IllegalArgumentException("birthright rule '" + id + "' grants nothing");
        }
        // Exactly one of the two ways of saying who a rule is for. Neither is a
        // rule that matches nobody, and both is a condition that reads as a
        // restriction while granting to everybody, which is worse than either.
        boolean conditional = !when.isEmpty();
        if (appliesToEveryone == conditional) {
            throw new IllegalArgumentException(
                    "birthright rule '" + id + "' must either set applies-to-everyone or name "
                            + "conditions under 'when', and not both: " + when);
        }
        for (Map.Entry<BirthrightAttribute, List<String>> condition : when.entrySet()) {
            if (condition.getValue() == null || condition.getValue().isEmpty()) {
                throw new IllegalArgumentException(
                        "birthright rule '" + id + "' accepts no value for " + condition.getKey()
                                + ", so it can never match");
            }
            if (condition.getValue().stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException(
                        "birthright rule '" + id + "' accepts a blank value for " + condition.getKey());
            }
        }
    }

    /** Whether this rule is about this identity. */
    boolean matches(GovernedAttributes attributes) {
        if (appliesToEveryone) {
            return true;
        }
        for (Map.Entry<BirthrightAttribute, List<String>> condition : when.entrySet()) {
            String asserted = condition.getKey().of(attributes);
            if (asserted == null || !condition.getValue().contains(asserted)) {
                return false;
            }
        }
        return true;
    }
}
