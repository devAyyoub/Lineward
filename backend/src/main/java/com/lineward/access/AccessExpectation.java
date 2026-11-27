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
package com.lineward.access;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What Lineward says should be true of one target system, for the module that
 * knows what is really true of it.
 *
 * <p>The read counterpart of {@link EntitlementRegistry}: that one is how a
 * target system tells the catalogue what it holds, this one is how the
 * catalogue tells M2 what it expects. Together they are the two halves of the
 * pair the working document calls the engine of phase 1, {@code
 * access_assignment} against {@code account_entitlement}.
 *
 * <p><b>Why this exists instead of a join.</b> The comparison needs rows from a
 * table M4 owns and rows from a table M2 owns, and one of the two modules has
 * to ask the other rather than read its table. It is M2 that asks, because the
 * comparison is M2's: {@code reconciliation_finding} is M2's table and the
 * finding is M2's output. ADR-001 forbids the foreign key across the boundary
 * and the working document forbids the {@code SELECT}; this is the door that
 * replaces both.
 *
 * <p>Everything here is scoped to one application, and that is not a
 * convenience. A reconciliation processes one target system at a time because a
 * finding is about one target system, and a method that returned every
 * expectation of the installation would hand a caller fifteen thousand rows to
 * filter down to the few hundred of the system in front of it.
 */
public interface AccessExpectation {

    /**
     * Every access that is still due on this application's entitlements,
     * whether or not anything has put it in place.
     *
     * <p>Live means {@code PENDING} or {@code ACTIVE}, which is what the
     * partial unique index of {@code access_assignment} treats as live and what
     * a recalculation treats as held. A revoked or expired row is not an
     * expectation: it is the record that one used to exist, which is what keeps
     * it answerable later and out of this list now.
     *
     * @param orgId         the tenant, passed explicitly for the reason
     *     {@link ApplicationLookup} gives: a caller running inside somebody
     *     else's transaction cannot accidentally ask about another organization
     * @param applicationId the target system, as {@link ApplicationLookup}
     *     reported it
     * @return one entry per live assignment. An identity due the same
     *     entitlement from two sources appears once per source, because the
     *     unique index allows exactly that and collapsing them here would
     *     invent a count M4 does not make
     */
    List<ExpectedGrant> liveGrants(UUID orgId, UUID applicationId);

    /**
     * Every entitlement this application's catalogue holds.
     *
     * <p>Needed because a reconciliation has to know the whole scope it is
     * answering for, and not only the part of it something is due. A finding
     * about an entitlement nobody is due any more is still a finding of this
     * application, and without the scope there is no way to tell one that
     * stopped applying from one belonging to a system this pass did not look
     * at.
     *
     * @return the ids, which may be empty for an application nothing has read
     *     yet
     */
    Set<UUID> catalogueOf(UUID orgId, UUID applicationId);
}
