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
package com.lineward.access.catalogue;

import com.lineward.access.EntitlementRegistry;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes what a target system turns out to have into the catalogue M4 owns.
 *
 * <p>Three statements at most and usually one. The first read of a system
 * registers everything it has; every read after that finds the same
 * permissions already there, which is the steady state and costs a single
 * query. That is the shape to keep in mind: this runs once per page of an
 * aggregation, not once per account.
 *
 * <p>No transaction of its own. The caller decides how much of a run shares
 * one, and what it decides matters here: the ids this hands back are what the
 * account memberships are written with, so the registration and the memberships
 * belong in the same unit of work. Split them, and a failure in between leaves
 * an account whose permissions point at nothing.
 */
public final class JdbcEntitlementRegistry implements EntitlementRegistry {

    private final JdbcClient jdbc;

    public JdbcEntitlementRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What the catalogue already holds for one native value. */
    private record Known(UUID id, String displayName, String description) {}

    @Override
    public Map<String, UUID> register(UUID orgId, UUID applicationId, Collection<Declaration> declared) {
        // An empty read is not an error: it is what the last page of a paged
        // read looks like, and what a target system with no groups answers.
        // Returning here is also what keeps the query below from being handed an
        // empty IN list, which is not valid SQL.
        if (declared.isEmpty()) {
            return Map.of();
        }

        // Deduplicated by native value, because that is the key the table has
        // and a page can legitimately mention one permission twice: two accounts
        // in the same group. The first declaration of a value wins, which only
        // matters if a system described the same group two different ways in one
        // read, and in that case either answer is as good as the other.
        Map<String, Declaration> byValue = new LinkedHashMap<>();
        for (Declaration declaration : declared) {
            byValue.putIfAbsent(declaration.nativeValue(), declaration);
        }

        Map<String, Known> known = loadKnown(orgId, applicationId, byValue.keySet());

        Map<String, UUID> ids = new LinkedHashMap<>();
        byValue.forEach((nativeValue, declaration) -> {
            Known existing = known.get(nativeValue);
            if (existing == null) {
                ids.put(nativeValue, insert(orgId, applicationId, declaration));
            } else {
                ids.put(nativeValue, existing.id());
                if (missingDocumentationThatThisReadCanSupply(existing, declaration)) {
                    fillDocumentation(orgId, existing.id(), declaration);
                }
            }
        });
        return Map.copyOf(ids);
    }

    /** One query for the whole page rather than one per permission. */
    private Map<String, Known> loadKnown(UUID orgId, UUID applicationId, Collection<String> nativeValues) {
        Map<String, Known> byValue = new HashMap<>();
        jdbc.sql("""
                SELECT native_value, id, display_name, description
                  FROM entitlement
                 WHERE org_id = :orgId
                   AND application_id = :applicationId
                   AND native_value IN (:nativeValues)
                """)
                .param("orgId", orgId)
                .param("applicationId", applicationId)
                .param("nativeValues", nativeValues)
                .query((rs, row) -> byValue.put(
                        rs.getString("native_value"),
                        new Known(
                                rs.getObject("id", UUID.class),
                                rs.getString("display_name"),
                                rs.getString("description"))))
                .list();
        return byValue;
    }

    /**
     * Inserts, and survives another run having inserted the same permission a
     * moment ago.
     *
     * <p>The conflict clause is not defensive programming, it is the only way
     * this statement can be correct: the read above and the insert here are two
     * statements, so two aggregations of the same system can both find a
     * permission missing and both try to add it. {@code ON CONFLICT} makes the
     * loser of that race read the winner's row instead of failing, which is
     * what the caller needs, because it is the id and not the insert that it
     * came for.
     *
     * <p>{@code risk} and {@code requestable} are left to the column defaults,
     * LOW and false. A target system has no opinion on how dangerous its own
     * permissions are, and a connector that could set either would be a system
     * voting on its own criticality. Raising them is M4's decision and arrives
     * with the risk model.
     */
    private UUID insert(UUID orgId, UUID applicationId, Declaration declaration) {
        return jdbc.sql("""
                INSERT INTO entitlement (org_id, application_id, native_value, display_name, description)
                VALUES (:orgId, :applicationId, :nativeValue, :displayName, :description)
                     ON CONFLICT (org_id, application_id, native_value)
                     DO UPDATE SET display_name = COALESCE(entitlement.display_name, EXCLUDED.display_name),
                                   description  = COALESCE(entitlement.description, EXCLUDED.description)
                  RETURNING id
                """)
                .param("orgId", orgId)
                .param("applicationId", applicationId)
                .param("nativeValue", declaration.nativeValue())
                .param("displayName", declaration.displayName())
                .param("description", declaration.description())
                .query(UUID.class)
                .single();
    }

    /**
     * Whether this read knows something the catalogue does not.
     *
     * <p>Asked before writing rather than letting an update decide, so that the
     * common case writes nothing at all. Every row has a trigger that moves
     * {@code updated_at}, so an update that changes no value still leaves the
     * row looking as if somebody had said something about it, and a nightly
     * aggregation would redate the whole catalogue every night.
     */
    private static boolean missingDocumentationThatThisReadCanSupply(Known known, Declaration declaration) {
        return (known.displayName() == null && declaration.displayName() != null)
                || (known.description() == null && declaration.description() != null);
    }

    /**
     * Fills what is empty and overwrites nothing.
     *
     * <p>The direction is the decision, and it is not symmetrical with the
     * native value. What a system calls a group is the key, so there the system
     * is the authority and KAN-11's first migration made the catalogue follow
     * it. What a permission is <i>for</i> is governance: V003's descriptions say
     * things like "the base package nobody requests", which no directory knows
     * and nobody would write again. Keycloak serves no description for a group
     * at all, so a registry that overwrote this field would delete the only
     * documentation the platform has, on the first successful run, and report
     * success.
     *
     * <p>Which leaves one case this does not serve: a permission renamed in the
     * target system keeps the old display name here. That is deliberate too,
     * because the alternative is the overwrite above, and the way to take the
     * new name is to clear the field and let the next read fill it.
     */
    private void fillDocumentation(UUID orgId, UUID entitlementId, Declaration declaration) {
        jdbc.sql("""
                UPDATE entitlement
                   SET display_name = COALESCE(display_name, :displayName),
                       description  = COALESCE(description, :description)
                 WHERE org_id = :orgId
                   AND id = :entitlementId
                """)
                .param("displayName", declaration.displayName())
                .param("description", declaration.description())
                .param("orgId", orgId)
                .param("entitlementId", entitlementId)
                .update();
    }
}
