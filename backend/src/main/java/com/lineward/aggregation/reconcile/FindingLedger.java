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
package com.lineward.aggregation.reconcile;

import com.lineward.aggregation.FindingType;
import com.lineward.aggregation.ReconciliationReport.Tally;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The findings of one target system, brought into line with what a pass found.
 *
 * <p><b>What is stored is a condition, not an observation.</b> A finding is one
 * row for as long as the problem lasts, which is why the table has a partial
 * unique index per subject and a {@code last_seen_at} rather than one row per
 * night. The difference matters to whoever reads the list: "this account has
 * been an orphan since March" is a sentence somebody acts on, and ninety rows
 * saying it was an orphan on ninety nights is the same sentence made unusable.
 *
 * <p><b>Why the whole set is loaded and compared in memory.</b> The alternative
 * is an upsert per candidate, which needs either {@code ON CONFLICT} inference
 * against a partial index with {@code NULLS NOT DISTINCT} or a predicate built
 * out of {@code IS NOT DISTINCT FROM}, and in both cases the count of what was
 * opened comes from reading a PostgreSQL internal. Three statements over sets
 * give the same result, say exactly how many rows each one touched, and are
 * bounded by the findings of one application rather than by its accounts.
 *
 * <p>No transaction of its own. It runs inside the pass's, with the reads that
 * produced the candidates, so that a finding and the state that justifies it
 * cannot come from two different moments.
 */
final class FindingLedger {

    private static final String OPEN = "OPEN";

    /**
     * How a finding is closed when its condition stops holding.
     *
     * <p>Externally, because that is the truth and the other four values would
     * all be claims. Lineward did not revoke the access, nobody declared it
     * legitimate through the platform and nobody accepted it as an exception:
     * the account acquired an owner, or was used, or the grant disappeared, and
     * who did it is a question only the audit trail of KAN-14 will be able to
     * answer.
     */
    private static final String RESOLVED_EXTERNALLY = "RESOLVED_EXTERNALLY";

    /**
     * The one closed state that is a standing decision rather than a past
     * event.
     *
     * <p>The other three say this instance was dealt with, so the same
     * condition appearing again is news and opens a new finding. This one says
     * somebody looked at the condition and decided it is allowed to go on
     * happening, which is the opposite instruction: a pass that opened a fresh
     * finding every night for an accepted exception would overrule the person
     * who accepted it, one row at a time, and the list would fill with exactly
     * the problems somebody has already answered for.
     */
    private static final String ACCEPTED_EXCEPTION = "ACCEPTED_EXCEPTION";

    private final JdbcClient jdbc;

    FindingLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes what this pass concluded about one application and says what
     * changed.
     *
     * @param orgId         the tenant
     * @param applicationId the target system whose findings are in scope.
     *     Scope is the reason the whole stored set is not touched: a pass over
     *     the directory must not close a finding about Keycloak, and the only
     *     thing that distinguishes them is the subject's application
     * @param catalogue     every entitlement of this application, which is how
     *     a drift finding is recognised as belonging to it. An account finding
     *     is recognised by its account's application instead
     * @param found         every condition that holds right now. A condition
     *     absent from this set and open in the table is a problem that has
     *     stopped happening
     * @param now           the pass's clock reading, used for every row it
     *     writes so that one pass leaves one timestamp
     * @return what happened per kind of finding
     */
    Map<FindingType, Tally> reconcile(
            UUID orgId, UUID applicationId, Set<UUID> catalogue, Set<Finding> found, OffsetDateTime now) {

        Map<Finding, Known> known = knownFindings(orgId, applicationId, catalogue);
        Map<FindingType, Tally> tallies = new EnumMap<>(FindingType.class);

        List<UUID> confirmed = new ArrayList<>();
        for (Finding finding : found) {
            Known existing = known.get(finding);
            if (existing == null) {
                open(orgId, finding, now);
                tallies.merge(finding.type(), new Tally(finding.type(), 1, 0, 0), Tally::plus);
            } else if (existing.standing()) {
                // Touched and not counted. The condition still holds, which is
                // worth recording on the row somebody will read when they ask
                // whether the exception is still being used, and it is not a
                // finding any more, which is what the person who accepted it
                // decided.
                confirmed.add(existing.id());
            } else {
                confirmed.add(existing.id());
                tallies.merge(finding.type(), new Tally(finding.type(), 0, 1, 0), Tally::plus);
            }
        }

        List<UUID> resolved = new ArrayList<>();
        for (Map.Entry<Finding, Known> entry : known.entrySet()) {
            if (!entry.getValue().standing() && !found.contains(entry.getKey())) {
                resolved.add(entry.getValue().id());
                FindingType type = entry.getKey().type();
                tallies.merge(type, new Tally(type, 0, 0, 1), Tally::plus);
            }
        }

        touch(orgId, confirmed, now);
        close(orgId, resolved, now);
        return tallies;
    }

    /**
     * A finding already in the table, and whether it is a standing decision.
     *
     * @param standing true for an accepted exception, which suppresses the
     *     condition instead of being confirmed or closed by it
     */
    private record Known(UUID id, boolean standing) {}

    /**
     * The findings this application answers for, keyed by what they are about:
     * the open ones, which this pass confirms or closes, and the accepted
     * exceptions, which it may do neither to.
     *
     * <p>Two queries and not one with an {@code OR}, because the two kinds of
     * finding are recognised by different evidence: an account finding by the
     * application its account belongs to, a drift finding by the application
     * its entitlement belongs to. Joining both into one predicate would also
     * make the empty catalogue case, which is an application nothing has read,
     * into a query that cannot be written.
     *
     * <p>A finding whose subject is an identity alone belongs to no application
     * and is therefore in neither query. Nothing writes one yet
     * ({@link FindingType#UNOWNED_IDENTITY} says why), and when something does,
     * closing it will not be this method's job: it is not about a target system.
     */
    private Map<Finding, Known> knownFindings(UUID orgId, UUID applicationId, Set<UUID> catalogue) {
        Map<Finding, Known> stored = new HashMap<>();

        jdbc.sql("""
                SELECT f.id, f.type, f.state, f.account_id
                  FROM reconciliation_finding f
                  JOIN account a ON a.id = f.account_id AND a.org_id = f.org_id
                 WHERE f.org_id = :orgId
                   AND f.state IN (:open, :accepted)
                   AND a.application_id = :applicationId
                """)
                .param("orgId", orgId)
                .param("open", OPEN)
                .param("accepted", ACCEPTED_EXCEPTION)
                .param("applicationId", applicationId)
                .query((rs, row) -> stored.put(
                        Finding.about(
                                FindingType.valueOf(rs.getString("type")), rs.getObject("account_id", UUID.class)),
                        known(rs.getObject("id", UUID.class), rs.getString("state"))))
                .list();

        if (catalogue.isEmpty()) {
            return stored;
        }
        jdbc.sql("""
                SELECT id, type, state, identity_id, entitlement_id
                  FROM reconciliation_finding
                 WHERE org_id = :orgId
                   AND state IN (:open, :accepted)
                   AND entitlement_id IN (:catalogue)
                """)
                .param("orgId", orgId)
                .param("open", OPEN)
                .param("accepted", ACCEPTED_EXCEPTION)
                .param("catalogue", new LinkedHashSet<>(catalogue))
                .query((rs, row) -> stored.put(
                        Finding.about(
                                FindingType.valueOf(rs.getString("type")),
                                rs.getObject("identity_id", UUID.class),
                                rs.getObject("entitlement_id", UUID.class)),
                        known(rs.getObject("id", UUID.class), rs.getString("state"))))
                .list();
        return stored;
    }

    private static Known known(UUID id, String state) {
        return new Known(id, ACCEPTED_EXCEPTION.equals(state));
    }

    /**
     * {@code detected_by_run_id} stays null, and that is a gap rather than a
     * choice. The column points at {@code aggregation_run}, which nothing
     * writes yet: {@code ApplicationAggregation} says it deliberately leaves
     * the table alone until KAN-13 builds the whole of it, checkpoint included.
     * Writing an invented run id to fill the column would be worse than a null,
     * because a null reads as unknown and a wrong id reads as evidence.
     */
    private void open(UUID orgId, Finding finding, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO reconciliation_finding (
                    org_id, type, state, account_id, identity_id, entitlement_id,
                    first_detected_at, last_seen_at)
                VALUES (
                    :orgId, :type, :open,
                    CAST(:accountId AS uuid), CAST(:identityId AS uuid), CAST(:entitlementId AS uuid),
                    :now, :now)
                """)
                .param("orgId", orgId)
                .param("type", finding.type().name())
                .param("open", OPEN)
                .param("accountId", finding.accountId())
                .param("identityId", finding.identityId())
                .param("entitlementId", finding.entitlementId())
                .param("now", now)
                .update();
    }

    /**
     * Moves the heartbeat of findings that still hold, and nothing else.
     *
     * <p>{@code first_detected_at} is untouched on purpose: it answers how long
     * this has been wrong, which is the question that turns a list of problems
     * into a list ordered by how badly somebody has been ignoring them.
     */
    private void touch(UUID orgId, List<UUID> findings, OffsetDateTime now) {
        if (findings.isEmpty()) {
            return;
        }
        jdbc.sql("""
                UPDATE reconciliation_finding
                   SET last_seen_at = :now,
                       updated_at   = :now
                 WHERE org_id = :orgId
                   AND state IN (:open, :accepted)
                   AND id IN (:findings)
                """)
                .param("orgId", orgId)
                .param("open", OPEN)
                .param("accepted", ACCEPTED_EXCEPTION)
                .param("now", now)
                .param("findings", findings)
                .update();
    }

    /**
     * The condition on {@code state} is the control, in the same place and for
     * the same reason as the one in {@code AccountCorrelation}: a finding a
     * person has already closed, as an accepted exception or in any other way,
     * must not be closed again by a pass. The branch above decides it and this
     * enforces it, so a mistake in the branch cannot overwrite somebody's
     * decision or its date. The row simply does not match.
     */
    private void close(UUID orgId, List<UUID> findings, OffsetDateTime now) {
        if (findings.isEmpty()) {
            return;
        }
        jdbc.sql("""
                UPDATE reconciliation_finding
                   SET state       = :resolved,
                       resolved_at = :now,
                       updated_at  = :now
                 WHERE org_id = :orgId
                   AND state = :open
                   AND id IN (:findings)
                """)
                .param("orgId", orgId)
                .param("resolved", RESOLVED_EXTERNALLY)
                .param("open", OPEN)
                .param("now", now)
                .param("findings", findings)
                .update();
    }
}
