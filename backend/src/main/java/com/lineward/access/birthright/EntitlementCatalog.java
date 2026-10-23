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
 * <p><b>Cached per organization, and that is a decision with an expiry date.</b>
 * The catalogue is seventeen rows a migration writes, read once per identity
 * during a run of five thousand, and nothing in phase 1 changes it while the
 * application is up. So an entitlement added to the database by hand is not seen
 * until a restart. That stops being acceptable when the read connectors of
 * KAN-6 to KAN-8 start discovering entitlements from the target systems, because
 * then the catalogue changes while the platform runs, and the cache has to go or
 * learn to be invalidated.
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
        UUID id = catalogueOf(orgId).get(grant);
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
        return catalogueOf(orgId).containsKey(grant);
    }

    /**
     * Reads the whole catalogue in one query rather than one per grant.
     *
     * <p>Seventeen rows today, and a few hundred once connectors discover them.
     * Either way it is one round trip against thousands of identities, which is
     * the trade this cache exists to make.
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
