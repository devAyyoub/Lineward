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
package com.lineward.identity;

import java.util.UUID;

/**
 * The fields of a governed identity that decide what access it is due.
 *
 * <p>A projection and not the whole row, and the omissions are the point. There
 * is no display name and no email here, because a rule that granted access on
 * somebody's name would be a rule nobody could defend, and leaving them out
 * means the question never comes up. There is no manager either, for the reason
 * {@code LifecycleEventRule} gives: birthright does not read the reporting line,
 * which is exactly why a reporting line change is an edge case M1 calls out.
 *
 * <p>Why this exists instead of letting another module read the table. ADR-001
 * forbids reaching into a module's internals and its own stated blind spot is
 * that the test enforcing it reads Java types and not SQL, so two modules can
 * look decoupled and be fused by a {@code SELECT}. A typed read declared here is
 * the difference between {@code access} depending on {@code identity} and
 * {@code access} depending on the shape of {@code identity}'s table.
 *
 * <p>Every attribute is nullable, because M1 requires ingesting incomplete HR
 * data rather than rejecting it. What a null means for access is decided by the
 * rules and not here: no rule can match on a value that is absent, so an
 * incomplete record grants nothing rather than granting a default.
 *
 * @param id the governed identity
 * @param status what Lineward last acted on, which is what says whether any
 *     access is due at all
 * @param department the organizational unit, the coarsest and most used basis
 *     for a birthright grant
 * @param jobTitle the job, which is what separates two people in the same
 *     department who must not hold the same thing
 * @param location where the person works, which is how access to a network or a
 *     building gets decided
 * @param costCenter who pays for the person, used by rules that follow the
 *     money rather than the org chart
 * @param employmentType employee, contractor or intern. The population whose
 *     access most often outlives them, because their end date is known in
 *     advance and nobody acts on it
 */
public record GovernedAttributes(
        UUID id,
        IdentityStatus status,
        String department,
        String jobTitle,
        String location,
        String costCenter,
        String employmentType) {

    public GovernedAttributes {
        if (id == null) {
            throw new IllegalArgumentException("a governed identity always has an id");
        }
        if (status == null) {
            throw new IllegalArgumentException(
                    "identity " + id + ": status is NOT NULL in the schema, and a projection that "
                            + "lost it would let a consumer decide access without knowing whether "
                            + "any is due");
        }
    }
}
