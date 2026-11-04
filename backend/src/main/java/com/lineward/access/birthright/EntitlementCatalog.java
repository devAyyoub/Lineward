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

import com.lineward.access.birthright.BirthrightRule.Grant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Translates the names a rule uses into the ids a row needs.
 *
 * <p>A rule says {@code intranet/treasury-read} because that is what a reviewer
 * can read and what a connector will send, and
 * {@code access_assignment.entitlement_id} is a foreign key to a uuid. Something
 * has to be in the middle, and this is the smallest thing that can be: a lookup
 * that fails loudly.
 *
 * <p><b>Cached per organization, and the cache is allowed to be behind but never
 * ahead.</b> A lookup that finds nothing throws the cache away and asks the
 * database before it believes the answer, which is what makes the cache safe
 * now that the catalogue changes while the platform runs. The previous version
 * of this class loaded once and kept it, and said so with an expiry date:
 * KAN-11 is that date. An aggregation registers a permission at ten past
 * midnight, and the identity recalculated at eleven minutes past would have been
 * told the permission does not exist.
 *
 * <p>Invalidation is what this deliberately does not have, and that is the
 * point of the shape rather than an omission. Something has to tell a cache when
 * it is stale, and nothing can be relied on to: the registry lives in another
 * package, a future one will live in another process, and a hook that somebody
 * has to remember to call is the control that rule 10 of the project document
 * tells us not to build. Refreshing on a miss needs nobody to remember
 * anything, because a stale entry can only ever be missing, and missing is the
 * case that re-reads.
 *
 * <p>What it costs is one extra read per genuine miss, which is a rule naming a
 * permission nobody has: a path that aborts the recalculation anyway. What it
 * buys is the steady state unchanged, one query per organization for a run of
 * thousands of identities.
 *
 * <p>The one thing a stale entry can still be wrong about is a permission whose
 * row was deleted, which this would keep serving. Nothing deletes an
 * entitlement: {@code EntitlementRegistry} says why, and a grant pointing at a
 * row that is gone fails on the foreign key rather than quietly granting
 * nothing.
 */
final class EntitlementCatalog {

    private final JdbcClient jdbc;
    private final Map<UUID, Map<Grant, UUID>> byOrganization = new ConcurrentHashMap<>();

    EntitlementCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param ruleId named in the failure rather than looked up afterwards,
     *     because "entitlement intranet/treasury-read does not exist" sends
     *     somebody to the catalogue and "rule treasury-read points at
     *     intranet/treasury-read, which does not exist" sends them to the line
     *     of the file that is wrong
     * @throws IllegalStateException when the catalogue has no such entitlement.
     *     Loudly and not silently skipped: a rule that grants nothing because
     *     its target is missing is a person without their access, and the
     *     quietest possible failure
     */
    UUID idOf(UUID orgId, Grant grant, String ruleId) {
        UUID id = resolve(orgId, grant);
        if (id == null) {
            throw new IllegalStateException(
                    "birthright rule '" + ruleId + "' grants " + grant
                            + ", which is not in the entitlement catalogue of organization " + orgId
                            + ". Either the rule names it wrongly or the migration that registers it"
                            + " has not run");
        }
        return id;
    }

    /** Whether this grant resolves at all, for the test that checks the shipped rules. */
    boolean contains(UUID orgId, Grant grant) {
        return resolve(orgId, grant) != null;
    }

    /**
     * The cached answer, and a second look before an absence is believed.
     *
     * <p>Two threads missing at once both re-read, which is a read each and
     * nothing worse: the query is idempotent and they arrive at the same map.
     * Locking to prevent it would serialise every miss to save a query that
     * costs less than the lock.
     */
    private UUID resolve(UUID orgId, Grant grant) {
        UUID cached = catalogueOf(orgId).get(grant);
        if (cached != null) {
            return cached;
        }
        byOrganization.remove(orgId);
        return catalogueOf(orgId).get(grant);
    }

    /**
     * Reads the whole catalogue in one query rather than one per grant.
     *
     * <p>Seventeen rows today, and a few hundred once connectors discover them.
     * Either way it is one round trip against thousands of identities, which is
     * the trade this cache exists to make.
     *
     * <p>No transaction of its own, like everything else in this package, so
     * the read sees whatever the caller's transaction can see. That matters for
     * the refresh above: a permission registered in the same transaction that
     * is now calculating access is visible, and one registered by a transaction
     * that has not committed is not, which is the correct answer in both cases.
     */
    private Map<Grant, UUID> catalogueOf(UUID orgId) {
        return byOrganization.computeIfAbsent(orgId, org -> {
            Map<Grant, UUID> catalogue = new HashMap<>();
            jdbc.sql("""
                    SELECT a.code AS application, e.native_value, e.id
                      FROM entitlement e
                      JOIN application a ON a.org_id = e.org_id AND a.id = e.application_id
                     WHERE e.org_id = :orgId
                    """)
                    .param("orgId", org)
                    .query((rs, row) -> catalogue.put(
                            new Grant(rs.getString("application"), rs.getString("native_value")),
                            rs.getObject("id", UUID.class)))
                    .list();
            return Map.copyOf(catalogue);
        });
    }
}
