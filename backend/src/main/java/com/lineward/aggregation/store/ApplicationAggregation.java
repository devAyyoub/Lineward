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

import com.lineward.access.EntitlementRegistry;
import com.lineward.access.EntitlementRegistry.Declaration;
import com.lineward.access.GovernedApplication;
import com.lineward.aggregation.AggregationReport;
import com.lineward.aggregation.AggregationReport.Correlation;
import com.lineward.connector.ConnectionCheck;
import com.lineward.connector.ConnectorPage;
import com.lineward.connector.DiscoveredAccount;
import com.lineward.connector.DiscoveredEntitlement;
import com.lineward.connector.ReadCursor;
import com.lineward.connector.TargetSystemConnector;
import com.lineward.shared.TenantTransactions;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One run against one target system: ask whether it is there, read what it can
 * grant, read who has it, and write both down.
 *
 * <p>The catalogue is read before the accounts, and that order is the only one
 * that works. A membership is stored by entitlement id, so an account's groups
 * cannot be written until the groups exist as rows; reading the catalogue first
 * means almost every membership resolves to something already registered, and
 * the ones that do not are handled where they turn up.
 *
 * <p>One transaction per page, like {@code HrIngestionRunner} and for the same
 * reason: a fifty thousand account directory in a single transaction holds locks
 * for its whole duration and loses everything on the last failure. Per page, a
 * read that breaks halfway leaves the earlier pages written, which is safe
 * because registering and upserting are both idempotent. Running it again
 * re-reads them and finds nothing to do.
 *
 * <p>Nothing here opens the transaction that talks to the target system, and
 * that is deliberate. The reads happen outside it, so a directory that takes
 * four minutes to answer does not hold a database transaction open for four
 * minutes.
 *
 * <p><b>What is deliberately missing.</b> No row in {@code aggregation_run},
 * which is the table that records a run and its resume point: writing half of
 * it now would mean writing the other half twice, and the whole of it is
 * KAN-13, including the checkpoint this would have to leave null. So a run that
 * fails is reported by failing, and a run that succeeds is reported by its
 * return value and one log line, which is all anything can consume while
 * nothing schedules a run.
 */
final class ApplicationAggregation {

    private static final Logger log = LoggerFactory.getLogger(ApplicationAggregation.class);

    private final EntitlementRegistry catalogue;
    private final AccountWriter accounts;
    private final AccountCorrelation correlation;
    private final TenantTransactions tenants;
    private final Clock clock;
    private final UUID orgId;
    private final int pageSize;

    ApplicationAggregation(
            EntitlementRegistry catalogue,
            AccountWriter accounts,
            AccountCorrelation correlation,
            TenantTransactions tenants,
            Clock clock,
            UUID orgId,
            int pageSize) {
        this.catalogue = catalogue;
        this.accounts = accounts;
        this.correlation = correlation;
        this.tenants = tenants;
        this.clock = clock;
        this.orgId = orgId;
        this.pageSize = pageSize;
    }

    /**
     * @throws com.lineward.connector.ConnectorException if a read broke part way
     *     through. Allowed out rather than turned into a report, because a
     *     report is a statement that the system was read and a partial read is
     *     not one. The pages already written stay written
     */
    AggregationReport read(GovernedApplication application, TargetSystemConnector connector) {
        Instant startedAt = clock.instant();

        // Asked first, and the answer is not an exception. A system that will
        // not answer has to be distinguishable from a system with nothing in it,
        // because the second is a statement about access and the first is a
        // statement about the network.
        ConnectionCheck reachable = connector.testConnection();
        if (!reachable.reachable()) {
            log.warn("Aggregation of '{}' did not start: {}", application.code(), reachable.detail());
            return AggregationReport.unreachable(
                    application.code(), reachable.detail(), startedAt, clock.instant());
        }

        Map<String, UUID> registered = new HashMap<>();
        int entitlementsSeen = readCatalogue(application, connector, registered);
        Seen seen = readAccounts(application, connector, registered);

        AggregationReport report = new AggregationReport(
                application.code(),
                true,
                reachable.detail(),
                entitlementsSeen,
                seen.accounts(),
                seen.written().created(),
                seen.written().updated(),
                seen.written().grantsAdded(),
                seen.written().grantsRemoved(),
                seen.correlated(),
                startedAt,
                clock.instant());

        log.info("Aggregated '{}': {} permissions and {} accounts read; {} accounts new, {} seen again;"
                        + " {} memberships appeared, {} were taken away; correlation linked {}, unlinked {},"
                        + " left {} ambiguous and {} unmatched",
                report.applicationCode(),
                report.entitlementsSeen(),
                report.accountsSeen(),
                report.accountsCreated(),
                report.accountsUpdated(),
                report.grantsAdded(),
                report.grantsRemoved(),
                report.correlation().linked(),
                report.correlation().unlinked(),
                report.correlation().ambiguous(),
                report.correlation().unmatched());

        return report;
    }

    /** What the account half of the run read, wrote and decided. */
    private record Seen(int accounts, AccountWriter.Outcome written, Correlation correlated) {}

    /**
     * Every permission the system says it can grant, registered page by page.
     *
     * @param registered filled in as it goes, so that the account pages can
     *     resolve a membership without asking the database again
     */
    private int readCatalogue(
            GovernedApplication application, TargetSystemConnector connector, Map<String, UUID> registered) {
        int seen = 0;
        ReadCursor cursor = null;
        boolean more = true;
        while (more) {
            ConnectorPage<DiscoveredEntitlement> page = connector.readEntitlements(cursor, pageSize);
            seen += page.items().size();
            List<Declaration> declared = page.items().stream()
                    .map(entitlement -> new Declaration(
                            entitlement.nativeValue(), entitlement.displayName(), entitlement.description()))
                    .toList();
            registered.putAll(
                    tenants.inTenant(orgId, () -> catalogue.register(orgId, application.id(), declared)));
            cursor = page.next();
            more = !page.exhausted();
        }
        return seen;
    }

    private Seen readAccounts(
            GovernedApplication application, TargetSystemConnector connector, Map<String, UUID> registered) {
        int seen = 0;
        AccountWriter.Outcome written = AccountWriter.Outcome.NOTHING;
        Correlation correlated = Correlation.NOTHING;
        ReadCursor cursor = null;
        boolean more = true;
        while (more) {
            ConnectorPage<DiscoveredAccount> page = connector.readAccounts(cursor, pageSize);
            seen += page.items().size();
            PageOutcome outcome = tenants.inTenant(orgId, () -> {
                registered.putAll(registerWhatOnlyTheAccountsMention(application, page.items(), registered));
                AccountWriter.Written rows = accounts.write(orgId, application.id(), page.items(), registered);
                // Inside the page's transaction, with the rows it decided about.
                // A correlation stored without the account it points at, or an
                // account stored without the owner this run found for it, are
                // both states that a crash between two transactions would
                // create and nothing would ever repair.
                return new PageOutcome(rows.counts(), correlation.apply(orgId, rows.rows()));
            });
            written = written.plus(outcome.written());
            correlated = correlated.plus(outcome.correlated());
            cursor = page.next();
            more = !page.exhausted();
        }
        return new Seen(seen, written, correlated);
    }

    /** One page's two answers, because a transaction returns one value. */
    private record PageOutcome(AccountWriter.Outcome written, Correlation correlated) {}

    /**
     * A permission that turns up on an account and not in the catalogue.
     *
     * <p>Rare and not impossible, and the membership is itself the proof that
     * the permission exists: an account cannot be in a group that is not there.
     * It happens when a group is created between the two reads, when the
     * catalogue read is filtered more narrowly than the accounts, and on any
     * system that does not offer a catalogue at all. Registering it here is what
     * lets the membership be stored; the alternative is dropping real access on
     * the floor.
     *
     * <p>Inside the page's transaction, with the write that needs it, so that a
     * failure cannot leave a registered permission whose membership never
     * arrived.
     */
    private Map<String, UUID> registerWhatOnlyTheAccountsMention(
            GovernedApplication application,
            List<DiscoveredAccount> page,
            Map<String, UUID> registered) {
        Map<String, Declaration> missing = new LinkedHashMap<>();
        for (DiscoveredAccount account : page) {
            for (String nativeValue : account.entitlementValues()) {
                if (!registered.containsKey(nativeValue)) {
                    missing.putIfAbsent(nativeValue, Declaration.of(nativeValue));
                }
            }
        }
        if (missing.isEmpty()) {
            return Map.of();
        }
        log.info("'{}' reports {} permissions on accounts that its catalogue did not list: {}",
                application.code(), missing.size(), missing.keySet());
        return catalogue.register(orgId, application.id(), missing.values());
    }
}
