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
package com.lineward.aggregation.store;

import com.lineward.connector.DiscoveredAccount;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * One page of accounts, turned into rows.
 *
 * <p>The first thing in Lineward that writes down what is really switched on
 * out there, and the shape of it is an upsert and two subtractions. An account
 * already stored keeps its row, because the row's id is what everything else
 * points at and because {@code first_seen_at} is evidence: an orphan that has
 * existed for six years is a different finding from one created last week.
 * Memberships are reconciled the way {@code ExpectedAccessWriter} reconciles
 * the expected side, two sets and one subtraction in each direction, and for
 * the same reason: what is in both is left alone, so reading an unchanged
 * system writes almost nothing.
 *
 * <p><b>What it must never do is delete an account.</b> An account that did not
 * come back is an account this read did not see, and those are the same
 * sentence only when the read was complete. A narrowed service account, a
 * filtered search or a target system under load would otherwise arrive as a
 * wave of terminations. What makes the absence visible instead is
 * {@code last_seen_at} not moving, and acting on it is M2's reconciliation,
 * where somebody can see the evidence.
 *
 * <p>Memberships are different and are deleted, because they arrive attached to
 * the account that holds them: a permission missing from an account's own list
 * is a permission that account no longer holds, not a page that did not arrive.
 * Keeping the row would make Lineward report access that was taken away, which
 * is the reconciliation failure that gets a platform switched off.
 *
 * <p>No transaction of its own. The caller decides how much shares one, and the
 * caller is a run that gives each page its own, so a read that breaks halfway
 * leaves the earlier pages written and a repeat finds them.
 */
final class AccountWriter {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    AccountWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * One page's rows, and what the page did.
     *
     * <p>The rows come back because correlation runs next and needs them: the
     * id of each account and what the target system said about it. Handing them
     * over costs nothing, and the alternative was a second pass that re-reads
     * from the database what this one had in its hands.
     */
    record Written(Outcome counts, List<AccountRow> rows) {}

    /**
     * One account as it now stands, paired with the read it came from.
     *
     * @param id                 the row's id
     * @param read               what the target system reported, which is where
     *                           the correlation rules look for an identifier
     * @param identityId         who Lineward believed it belongs to before this
     *                           run, or null
     * @param correlationMethod  how that was decided, {@code RULE} or
     *                           {@code MANUAL} or null. Carried rather than
     *                           looked up again, because the rules must not
     *                           revisit a decision a person took
     * @param correlationRuleCode which rule decided it, or null
     */
    record AccountRow(
            UUID id,
            DiscoveredAccount read,
            UUID identityId,
            String correlationMethod,
            String correlationRuleCode) {}

    /** What Lineward already holds for one native id. */
    private record Stored(UUID id, UUID identityId, String correlationMethod, String correlationRuleCode) {}

    /** What one page did. */
    record Outcome(int created, int updated, int grantsAdded, int grantsRemoved) {

        static final Outcome NOTHING = new Outcome(0, 0, 0, 0);

        Outcome plus(Outcome other) {
            return new Outcome(
                    created + other.created,
                    updated + other.updated,
                    grantsAdded + other.grantsAdded,
                    grantsRemoved + other.grantsRemoved);
        }
    }

    /**
     * @param entitlementIds every native value this page's accounts hold,
     *     mapped to its entitlement id. Resolved by the caller before anything
     *     is written, because an account whose permission cannot be resolved has
     *     to fail the page rather than be stored with half its access
     */
    Written write(
            UUID orgId,
            UUID applicationId,
            Collection<DiscoveredAccount> page,
            Map<String, UUID> entitlementIds) {
        if (page.isEmpty()) {
            return new Written(Outcome.NOTHING, List.of());
        }

        Map<String, Stored> stored = loadStored(orgId, applicationId, page);

        Outcome total = Outcome.NOTHING;
        List<AccountRow> rows = new ArrayList<>();
        for (DiscoveredAccount account : page) {
            Stored known = stored.get(account.nativeId());
            boolean isNew = known == null;
            UUID accountId = isNew ? insert(orgId, applicationId, account) : known.id();
            if (!isNew) {
                update(orgId, accountId, account);
            }
            total = total.plus(new Outcome(isNew ? 1 : 0, isNew ? 0 : 1, 0, 0))
                    .plus(reconcileMemberships(orgId, accountId, account, entitlementIds, isNew));
            rows.add(new AccountRow(
                    accountId,
                    account,
                    isNew ? null : known.identityId(),
                    isNew ? null : known.correlationMethod(),
                    isNew ? null : known.correlationRuleCode()));
        }
        return new Written(total, rows);
    }

    /**
     * One query for the page instead of one per account, and it brings back the
     * correlation along with the id. Not because the writer uses it: because
     * the step after this one does, and asking for it here costs nothing while
     * asking for it later costs a query per account.
     */
    private Map<String, Stored> loadStored(
            UUID orgId, UUID applicationId, Collection<DiscoveredAccount> page) {
        Map<String, Stored> byNativeId = new HashMap<>();
        jdbc.sql("""
                SELECT native_id, id, identity_id, correlation_method, correlation_rule_code
                  FROM account
                 WHERE org_id = :orgId
                   AND application_id = :applicationId
                   AND native_id IN (:nativeIds)
                """)
                .param("orgId", orgId)
                .param("applicationId", applicationId)
                .param("nativeIds", page.stream().map(DiscoveredAccount::nativeId).toList())
                .query((rs, row) -> byNativeId.put(
                        rs.getString("native_id"),
                        new Stored(
                                rs.getObject("id", UUID.class),
                                rs.getObject("identity_id", UUID.class),
                                rs.getString("correlation_method"),
                                rs.getString("correlation_rule_code"))))
                .list();
        return byNativeId;
    }

    /**
     * {@code identity_id} is left null, and so are the three columns that go
     * with it. An account arrives not knowing whose it is: correlation is a
     * separate decision with its own rules and its own record of how it was
     * reached, and the check constraint on this table refuses a correlation
     * that does not say how and when.
     */
    private UUID insert(UUID orgId, UUID applicationId, DiscoveredAccount account) {
        return jdbc.sql("""
                INSERT INTO account (
                        org_id, application_id, native_id, username, email, display_name, status,
                        last_activity_at, created_in_target_at, attributes)
                VALUES (:orgId, :applicationId, :nativeId, :username, :email, :displayName, :status,
                        :lastActivityAt, :createdInTargetAt, cast(:attributes as jsonb))
                RETURNING id
                """)
                .param("orgId", orgId)
                .param("applicationId", applicationId)
                .param("nativeId", account.nativeId())
                .param("username", account.username())
                .param("email", account.email())
                .param("displayName", account.displayName())
                .param("status", account.status().name())
                .param("lastActivityAt", atUtc(account.lastActivityAt()))
                .param("createdInTargetAt", atUtc(account.createdInTargetAt()))
                .param("attributes", json.writeValueAsString(account.attributes()))
                .query(UUID.class)
                .single();
    }

    /**
     * Overwrites what the system currently says, and keeps what it has stopped
     * saying.
     *
     * <p>The two halves are not a style choice. A username, an email, a display
     * name and a status are current state, so a value that has gone really has
     * gone and storing the old one would invent access or invent a person. When
     * the account was created in the target system and when it was last used
     * are facts about the past, and a system that stops reporting them has not
     * unmade them: Keycloak reports a creation timestamp and a directory
     * usually does not, so a run through a different connector would otherwise
     * erase what the first one learned.
     *
     * <p>{@code last_seen_at} moves on every read, which is what makes "this
     * account stopped coming back" detectable. The {@code account} trigger of
     * V001 moves {@code updated_at} with it, so on this table that column means
     * last touched; the migration says so.
     */
    private void update(UUID orgId, UUID accountId, DiscoveredAccount account) {
        jdbc.sql("""
                UPDATE account
                   SET username             = :username,
                       email                = :email,
                       display_name         = :displayName,
                       status               = :status,
                       last_activity_at     = COALESCE(:lastActivityAt, last_activity_at),
                       created_in_target_at = COALESCE(:createdInTargetAt, created_in_target_at),
                       attributes           = cast(:attributes as jsonb),
                       last_seen_at         = now()
                 WHERE org_id = :orgId
                   AND id = :accountId
                """)
                .param("username", account.username())
                .param("email", account.email())
                .param("displayName", account.displayName())
                .param("status", account.status().name())
                .param("lastActivityAt", atUtc(account.lastActivityAt()))
                .param("createdInTargetAt", atUtc(account.createdInTargetAt()))
                .param("attributes", json.writeValueAsString(account.attributes()))
                .param("orgId", orgId)
                .param("accountId", accountId)
                .update();
    }

    /**
     * What the account holds now against what the table says it held.
     *
     * <p>A freshly inserted account held nothing a moment ago, so the read is
     * skipped for it rather than asked and answered empty.
     */
    private Outcome reconcileMemberships(
            UUID orgId,
            UUID accountId,
            DiscoveredAccount account,
            Map<String, UUID> entitlementIds,
            boolean isNew) {
        Set<UUID> held = resolve(account, entitlementIds);
        Set<UUID> stored = isNew ? Set.of() : storedMemberships(orgId, accountId);

        Set<UUID> arrived = new LinkedHashSet<>(held);
        arrived.removeAll(stored);
        arrived.forEach(entitlementId -> grant(orgId, accountId, entitlementId));

        Set<UUID> gone = new LinkedHashSet<>(stored);
        gone.removeAll(held);
        if (!gone.isEmpty()) {
            revoke(orgId, accountId, gone);
        }

        Set<UUID> unchanged = new LinkedHashSet<>(held);
        unchanged.retainAll(stored);
        if (!unchanged.isEmpty()) {
            markSeen(orgId, accountId, unchanged);
        }

        return new Outcome(0, 0, arrived.size(), gone.size());
    }

    /**
     * Fails rather than skipping a permission it cannot resolve.
     *
     * <p>The caller registers every value of the page before this runs, so a
     * miss here is a bug in the run and not news from the target system.
     * Skipping it would store the account with less access than it has, which
     * is the quietest possible failure: the reconciliation that follows would
     * report the difference as access nobody granted.
     */
    private static Set<UUID> resolve(DiscoveredAccount account, Map<String, UUID> entitlementIds) {
        Set<UUID> held = new LinkedHashSet<>();
        for (String nativeValue : account.entitlementValues()) {
            UUID entitlementId = entitlementIds.get(nativeValue);
            if (entitlementId == null) {
                throw new IllegalStateException(
                        "account '" + account.nativeId() + "' holds '" + nativeValue
                                + "', which this run did not register in the catalogue. Storing the account"
                                + " without it would report less access than it has");
            }
            held.add(entitlementId);
        }
        return held;
    }

    private Set<UUID> storedMemberships(UUID orgId, UUID accountId) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT entitlement_id
                  FROM account_entitlement
                 WHERE org_id = :orgId
                   AND account_id = :accountId
                """)
                .param("orgId", orgId)
                .param("accountId", accountId)
                .query(UUID.class)
                .list());
    }

    /**
     * The conflict clause is what makes a repeated page cheap instead of fatal:
     * two runs of the same system can both find a membership missing, and the
     * loser of that race moves the heartbeat instead of failing.
     */
    private void grant(UUID orgId, UUID accountId, UUID entitlementId) {
        jdbc.sql("""
                INSERT INTO account_entitlement (org_id, account_id, entitlement_id)
                VALUES (:orgId, :accountId, :entitlementId)
                     ON CONFLICT (org_id, account_id, entitlement_id)
                     DO UPDATE SET last_seen_at = now()
                """)
                .param("orgId", orgId)
                .param("accountId", accountId)
                .param("entitlementId", entitlementId)
                .update();
    }

    /**
     * A delete and not a state column, because this table is the real state and
     * real state has no "used to". What happened and when belongs in the audit
     * trail of KAN-14, and until that exists the history of a membership that
     * went away is the reconciliation finding that noticed it.
     */
    private void revoke(UUID orgId, UUID accountId, Set<UUID> entitlementIds) {
        jdbc.sql("""
                DELETE FROM account_entitlement
                 WHERE org_id = :orgId
                   AND account_id = :accountId
                   AND entitlement_id IN (:entitlementIds)
                """)
                .param("orgId", orgId)
                .param("accountId", accountId)
                .param("entitlementIds", entitlementIds)
                .update();
    }

    /** One statement for every membership that is still there, rather than one each. */
    private void markSeen(UUID orgId, UUID accountId, Set<UUID> entitlementIds) {
        jdbc.sql("""
                UPDATE account_entitlement
                   SET last_seen_at = now()
                 WHERE org_id = :orgId
                   AND account_id = :accountId
                   AND entitlement_id IN (:entitlementIds)
                """)
                .param("orgId", orgId)
                .param("accountId", accountId)
                .param("entitlementIds", entitlementIds)
                .update();
    }

    /**
     * The driver takes an {@code OffsetDateTime} for a {@code timestamptz}
     * without guessing a zone, which an {@code Instant} does not do on every
     * version. Same reasoning as {@code SourceRecordIngestion}.
     */
    private static OffsetDateTime atUtc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
