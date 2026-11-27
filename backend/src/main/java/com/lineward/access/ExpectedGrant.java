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

import java.util.UUID;

/**
 * One access Lineward says an identity is due, as the module that compares it
 * against reality needs it.
 *
 * <p>Two ids and one flag, and not the {@code access_assignment} row. Why the
 * access is due, who approved it and when it expires are M4's business and
 * belong to the access lineage of phase 3; a reconciliation only has to know
 * which pairs to expect and which of them are supposed to be in place already.
 *
 * <p><b>{@code provisioned} is the whole reason this is not just two ids</b>,
 * and the distinction decides which of the two drift findings a difference
 * becomes. {@code PENDING} is "Lineward says this is due and nothing has
 * provisioned it yet" and {@code ACTIVE} is "this was put in place"
 * ({@code ExpectedAccessWriter} says so on the writing side). An access that is
 * merely due and is absent from the target system is not a failure, it is phase
 * 1 without a provisioner; an access recorded as in place and absent from the
 * target system is something that broke or was undone, and only that one is a
 * finding.
 *
 * @param identityId    who is due it
 * @param entitlementId what they are due
 * @param provisioned   whether Lineward records the access as in place
 *                      ({@code ACTIVE}) rather than merely due
 *                      ({@code PENDING})
 */
public record ExpectedGrant(UUID identityId, UUID entitlementId, boolean provisioned) {

    public ExpectedGrant {
        if (identityId == null || entitlementId == null) {
            throw new IllegalArgumentException("an expected grant names an identity and an entitlement");
        }
    }

    /** The pair, without the state, which is the key the comparison matches on. */
    public Pair pair() {
        return new Pair(identityId, entitlementId);
    }

    /**
     * An expectation and a reality compared without their states.
     *
     * <p>Its own type rather than a two element list or a string key, because
     * both sides of the comparison build sets of these and a mistake in the
     * order of the two ids would silently never match anything.
     */
    public record Pair(UUID identityId, UUID entitlementId) {}
}
