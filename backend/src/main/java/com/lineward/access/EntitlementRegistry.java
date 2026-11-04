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

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Registers the permissions a target system turns out to have.
 *
 * <p>The one write M4 exposes to another module, and it exists because the
 * catalogue stopped being something only a migration writes. Before the read
 * connectors there were seventeen entitlements, all seeded, all guesses in the
 * sense that {@code V003} says so itself: a stand-in that a connector turns
 * into confirmed or into drift. Aggregation is what confirms them, and
 * confirming them means writing rows in a table M4 owns.
 *
 * <p>Why a registry and not a {@code SELECT} from M2. The alternative was
 * letting the aggregation insert into {@code entitlement} directly, which
 * ADR-001 rules out and for a reason that shows up here: this table has a
 * unique key, a risk, a {@code requestable} flag and a curated description, and
 * every one of those is a governance decision. A module that could insert the
 * row could also decide them. Through this door it cannot: it says what the
 * target system has, and M4 decides what that becomes.
 *
 * <p>What a caller gets back is the id of every value it declared, whether the
 * row already existed or was created here, because an account's memberships are
 * stored by id and the caller cannot write them without one.
 */
public interface EntitlementRegistry {

    /**
     * One permission as a target system reports it.
     *
     * <p>The mirror of {@code DiscoveredEntitlement} on this side of the
     * boundary, and not that type itself: M4 does not depend on M3, so the
     * connector's vocabulary stops at the module that reads it and is
     * translated here. Three fields in both, which is not a coincidence but is
     * also not a contract.
     *
     * @param nativeValue the target system's own name for the thing, which is
     *     the key: a group path in Keycloak, a distinguished name in a
     *     directory
     * @param displayName what the system calls it for a human, or null when it
     *     offers nothing
     * @param description what the system says it is for, which is usually
     *     nothing
     */
    record Declaration(String nativeValue, String displayName, String description) {

        public Declaration {
            if (nativeValue == null || nativeValue.isBlank()) {
                throw new IllegalArgumentException("an entitlement with no native value cannot be referred to");
            }
        }

        /** A permission a system named and said nothing else about, which is the common case. */
        public static Declaration of(String nativeValue) {
            return new Declaration(nativeValue, null, null);
        }
    }

    /**
     * Makes sure every declared permission is in the catalogue, and says which
     * id each one has.
     *
     * <p>Idempotent by the shape of the table rather than by a check that
     * remembers: the unique key is {@code (org_id, application_id,
     * native_value)}, so registering the same page twice can only find what the
     * first pass wrote. That is what makes an aggregation safe to run again
     * after it broke halfway.
     *
     * <p>It never deletes. A permission that has stopped being reported is not
     * proof that it is gone, it is proof that this read did not see it, and
     * those two are the same sentence only when the read was complete. Removing
     * the row would also take away the thing a grant points at, which turns a
     * missing permission into a missing answer.
     *
     * @param orgId         the tenant
     * @param applicationId which application these permissions belong to, as
     *     {@code ApplicationLookup} reported it
     * @param declared      what the target system says it has. An empty
     *     collection is allowed and writes nothing
     * @return the native value of each declaration mapped to its entitlement
     *     id, with one entry per distinct declared value
     */
    Map<String, UUID> register(UUID orgId, UUID applicationId, Collection<Declaration> declared);
}
