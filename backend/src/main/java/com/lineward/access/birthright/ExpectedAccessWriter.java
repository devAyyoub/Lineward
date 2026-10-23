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
import com.lineward.access.birthright.BirthrightRules.DueAccess;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Brings what Lineward expects an identity to hold into line with what the rules
 * say it is due.
 *
 * <p>Two sets and one subtraction in each direction. What is due and not held is
 * granted, what is held and no longer due is retired, and what is in both is
 * left alone. The last of the three is the one that matters most: it is why
 * running this again over an unchanged identity writes nothing, which is what
 * makes a run that failed halfway safe to repeat, and what stops a nightly
 * recalculation from re-granting the same access every night.
 *
 * <p><b>Why a mover is not a special case.</b> The surplus of a move falls out
 * of the same subtraction as the revocation of a leaver, because the question
 * was never "what changed" but "what is due now". Privilege creep, which is the
 * failure M1 names, is what happens when the new access is computed and the
 * surplus is left to somebody's memory.
 *
 * <p><b>What the two rows mean, because the states here are easy to overread.</b>
 * This table is the <i>expected</i> state (ADR-013 decision 8), so {@code
 * PENDING} is "Lineward says this is due and nothing has provisioned it yet" and
 * {@code REVOKED} is "Lineward no longer says it is due". Neither is a statement
 * about the target system: the account that still has the group is real state, it
 * lives in {@code account_entitlement}, and the difference between the two is
 * exactly the drift M2 reports. Nothing in phase 1 moves a row to {@code ACTIVE},
 * because nothing provisions yet and claiming otherwise would make the first
 * drift report a list of five thousand lies.
 *
 * <p><b>What is deliberately missing.</b> The configurable grace period M1 asks
 * for on a mover's surplus is not here: it needs something that sweeps expiries
 * when the period runs out, and a grace period without the sweep is access that
 * is never retired at all. The audit trail of each grant and retirement is
 * KAN-14's, and until it exists the history of these decisions is the rows
 * themselves and nothing more.
 *
 * <p>No transaction of its own, like the rest of this package. It runs inside
 * the one that detected the lifecycle event, so a failure here takes the stored
 * change down with it.
 */
final class ExpectedAccessWriter {

    private final JdbcClient jdbc;
    private final EntitlementCatalog catalogue;
    private final Clock clock;

    ExpectedAccessWriter(JdbcClient jdbc, EntitlementCatalog catalogue, Clock clock) {
        this.jdbc = jdbc;
        this.catalogue = catalogue;
        this.clock = clock;
    }

    /** What a recalculation did, for the counters of a run. */
    record Outcome(int granted, int retired) {

        static final Outcome NOTHING = new Outcome(0, 0);

        Outcome plus(Outcome other) {
            return new Outcome(granted + other.granted, retired + other.retired);
        }

        boolean changedAnything() {
            return granted > 0 || retired > 0;
        }
    }

    /**
     * One live birthright assignment, identified the way the schema identifies
     * it.
     *
     * <p>The rule is part of the identity of a row and not a label on it, which
     * is the shape of the unique index on a live assignment: two rules granting
     * the same entitlement are two reasons for it, and retiring one must not
     * take the access away. The same reasoning the schema gives for a birthright
     * grant and an approved request coexisting applies one level down.
     */
    private record Held(UUID entitlementId, String ruleId) {}

    Outcome reconcile(UUID orgId, UUID identityId, Set<DueAccess> due) {
        Map<Held, UUID> held = loadLiveAssignments(orgId, identityId);
        Map<Held, Grant> wanted = resolve(orgId, due);

        int granted = 0;
        for (Map.Entry<Held, Grant> entry : wanted.entrySet()) {
            if (!held.containsKey(entry.getKey())) {
                grant(orgId, identityId, entry.getKey());
                granted++;
            }
        }

        int retired = 0;
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (Map.Entry<Held, UUID> entry : held.entrySet()) {
            if (!wanted.containsKey(entry.getKey())) {
                retire(orgId, entry.getValue(), now);
                retired++;
            }
        }

        return new Outcome(granted, retired);
    }

    /**
     * Resolved before anything is written, so that a rule pointing at an
     * entitlement the catalogue does not have fails the whole recalculation
     * instead of applying the half of it that happened to resolve.
     */
    private Map<Held, Grant> resolve(UUID orgId, Set<DueAccess> due) {
        Map<Held, Grant> wanted = new LinkedHashMap<>();
        for (DueAccess access : due) {
            Grant grant = new Grant(access.application(), access.entitlement());
            wanted.put(new Held(catalogue.idOf(orgId, grant, access.ruleId()), access.ruleId()), grant);
        }
        return wanted;
    }

    private Map<Held, UUID> loadLiveAssignments(UUID orgId, UUID identityId) {
        Map<Held, UUID> live = new HashMap<>();
        // Only BIRTHRIGHT rows, and this is the line that keeps the origins
        // apart. Phase 3 writes REQUEST and ROLE rows into this same table, and
        // a recalculation that read them as its own would revoke an approved
        // request because no birthright rule happens to grant it.
        jdbc.sql("""
                SELECT id, entitlement_id, source_detail
                  FROM access_assignment
                 WHERE org_id = :orgId
                   AND identity_id = :identityId
                   AND source = 'BIRTHRIGHT'
                   AND state IN ('PENDING', 'ACTIVE')
                """)
                .param("orgId", orgId)
                .param("identityId", identityId)
                .query((rs, row) -> live.put(
                        new Held(rs.getObject("entitlement_id", UUID.class), rs.getString("source_detail")),
                        rs.getObject("id", UUID.class)))
                .list();
        return live;
    }

    /**
     * {@code granted_at} stays null on purpose. It is when the access was really
     * given, and nothing has given it: writing the current time here would turn
     * "we decided this is due" into "this is in place", which is the one
     * statement this table must not make on behalf of a target system.
     */
    private void grant(UUID orgId, UUID identityId, Held wanted) {
        jdbc.sql("""
                INSERT INTO access_assignment (
                    org_id, identity_id, entitlement_id, source, source_detail, state)
                VALUES (:orgId, :identityId, :entitlementId, 'BIRTHRIGHT', :ruleId, 'PENDING')
                """)
                .param("orgId", orgId)
                .param("identityId", identityId)
                .param("entitlementId", wanted.entitlementId())
                .param("ruleId", wanted.ruleId())
                .update();
    }

    /**
     * Retiring is an update of the row and not a delete, because the row is the
     * only record of the access ever having been due. Deleting it would answer
     * "why did this person lose this" with silence, and the partial unique index
     * only covers live states, so a revoked row leaves room for the same access
     * to be granted again if somebody moves back.
     */
    private void retire(UUID orgId, UUID assignmentId, OffsetDateTime now) {
        jdbc.sql("""
                UPDATE access_assignment
                   SET state = 'REVOKED', revoked_at = :now
                 WHERE org_id = :orgId
                   AND id = :assignmentId
                """)
                .param("now", now)
                .param("orgId", orgId)
                .param("assignmentId", assignmentId)
                .update();
    }
}
