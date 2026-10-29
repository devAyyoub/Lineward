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
package com.lineward.connector;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One account exactly as a target system reports it.
 *
 * <p>The mirror image of {@code AssertedIdentity}, and worth stating as such:
 * that one is what the authoritative source claims about who exists, this one is
 * what a system claims about who can get in. Comparing the two is the whole of
 * M2. Nothing here is a Lineward concept, so no organization, no identity, no
 * correlation: an account arrives not knowing whose it is, and most of the value
 * of aggregation is in the ones that turn out to be nobody's.
 *
 * <p>Almost everything is nullable because almost everything is optional in
 * practice. Verified against Keycloak 26.7.5 on 2026-10-23 by reading a real
 * user back from its admin API: the response carried an id, a username, an
 * {@code enabled} flag and a created timestamp, and the email field was not
 * null but absent. An account with nothing but an identifier is still an
 * account, and refusing it would drop exactly the neglected rows that a first
 * aggregation is run to find.
 *
 * @param nativeId          the target system's own identifier, and the only
 *                          required field. Often not the username: Keycloak
 *                          returns a UUID and lets the username be renamed,
 *                          so matching on the username would lose the account's
 *                          history on a rename
 * @param createdInTargetAt when the target system says the account was created,
 *                          which is not when Lineward first saw it. The
 *                          difference is the evidence in an orphan finding: an
 *                          account nobody owns that has existed for six years
 *                          is a different conversation from one created last
 *                          week
 * @param lastActivityAt    the last use the system reports, for the dormant
 *                          account detection of KAN-12. Null means the system
 *                          does not say, which is the Keycloak case, and that
 *                          has to read as "cannot tell" and never as "never
 *                          used": the second would report the whole directory
 *                          as dormant
 * @param entitlementValues the {@code nativeValue} of each
 *                          {@link DiscoveredEntitlement} this account holds.
 *                          Stated on the account because that is what
 *                          reconciliation compares against expected access, not
 *                          because it is how the system serves it. Keycloak
 *                          gives memberships per user, a directory gives them
 *                          per group, Okta gives them per application: each
 *                          connector reads whichever direction is cheap and
 *                          inverts it internally, so that the cost of the
 *                          choice stays inside the connector that knows it
 * @param attributes        everything else the system exposes, for the
 *                          correlation rules of M2 to match on. Multi valued,
 *                          and that is not generality for its own sake: real
 *                          Keycloak returned {@code is_temporary_admin} as a
 *                          one element array, and LDAP attributes are
 *                          multi valued by definition. A map of single strings
 *                          would have silently kept the first value of each.
 *                          There is nowhere to persist these yet, which is a
 *                          decision for KAN-11
 */
public record DiscoveredAccount(
        String nativeId,
        String username,
        String email,
        String displayName,
        AccountStatus status,
        Instant createdInTargetAt,
        Instant lastActivityAt,
        List<String> entitlementValues,
        Map<String, List<String>> attributes) {

    public DiscoveredAccount {
        if (nativeId == null || nativeId.isBlank()) {
            throw new IllegalArgumentException(
                    "an account with no native identifier cannot be correlated, reconciled or found again");
        }
        status = status == null ? AccountStatus.UNKNOWN : status;
        entitlementValues = entitlementValues == null ? List.of() : List.copyOf(entitlementValues);
        attributes = copyAttributes(attributes);
    }

    /**
     * Copies the values as well as the map.
     *
     * <p>{@code Map.copyOf} copies one level, so the lists inside would stay
     * the caller's and keep changing after the record was built. A connector
     * reusing a mutable list while paging is ordinary, and the result would be
     * accounts whose attributes changed after they were read.
     */
    private static Map<String, List<String>> copyAttributes(Map<String, List<String>> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        attributes.forEach((name, values) -> copy.put(name, values == null ? List.of() : List.copyOf(values)));
        return Map.copyOf(copy);
    }
}
