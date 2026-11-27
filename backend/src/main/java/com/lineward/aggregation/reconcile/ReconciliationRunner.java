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

import com.lineward.access.AccessExpectation;
import com.lineward.access.ApplicationLookup;
import com.lineward.access.GovernedApplication;
import com.lineward.aggregation.FindingType;
import com.lineward.aggregation.Reconciliation;
import com.lineward.aggregation.ReconciliationReport;
import com.lineward.aggregation.ReconciliationReport.Tally;
import com.lineward.aggregation.reconcile.AccountConditions.StoredAccount;
import com.lineward.shared.TenantTransactions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives one reconciliation across every system the installation governs.
 *
 * <p>The mirror of {@code AggregationRunner} and deliberately poorer than it:
 * no connectors, no credentials and no network. Everything it needs is already
 * stored, which is what lets a night's findings survive a target system being
 * down.
 *
 * <p><b>One transaction per application</b>, and the reason is the comparison
 * rather than performance. The expected side and the real side are read inside
 * it, so an aggregation that finishes halfway through this pass cannot make
 * Lineward compare yesterday's accounts against today's expectations and report
 * the gap between two moments as drift.
 */
final class ReconciliationRunner implements Reconciliation {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRunner.class);

    /**
     * The kinds of finding this pass answers for, which is every kind that is
     * about a target system.
     *
     * <p>{@link FindingType#UNOWNED_IDENTITY} is absent because nothing can
     * produce it yet and because it would not belong here even then: it is
     * about an identity, which no single target system owns.
     */
    private static final List<FindingType> EVALUATED = List.of(
            FindingType.ORPHAN_ACCOUNT,
            FindingType.DORMANT_ACCOUNT,
            FindingType.DRIFT_UNEXPECTED_GRANT,
            FindingType.DRIFT_MISSING_GRANT);

    private final ApplicationLookup applications;
    private final AccessExpectation expectation;
    private final AccountConditions accounts;
    private final GrantDrift drift;
    private final FindingLedger ledger;
    private final TenantTransactions tenants;
    private final Clock clock;
    private final UUID orgId;
    private final Duration dormantAfter;

    ReconciliationRunner(
            ApplicationLookup applications,
            AccessExpectation expectation,
            AccountConditions accounts,
            GrantDrift drift,
            FindingLedger ledger,
            TenantTransactions tenants,
            Clock clock,
            UUID orgId,
            Duration dormantAfter) {
        this.applications = applications;
        this.expectation = expectation;
        this.accounts = accounts;
        this.drift = drift;
        this.ledger = ledger;
        this.tenants = tenants;
        this.clock = clock;
        this.orgId = orgId;
        this.dormantAfter = dormantAfter;
    }

    @Override
    public List<ReconciliationReport> reconcileEverything() {
        List<GovernedApplication> governed = tenants.inTenant(orgId, () -> applications.enabledApplications(orgId));

        List<ReconciliationReport> reports = new ArrayList<>();
        for (GovernedApplication application : governed) {
            tenants.inTenant(orgId, () -> reconcile(application)).ifPresent(reports::add);
        }

        log.info("Reconciled {} of the {} applications this installation governs, {} findings opened and {} resolved",
                reports.size(),
                governed.size(),
                reports.stream().mapToInt(ReconciliationReport::openedTotal).sum(),
                reports.stream().mapToInt(ReconciliationReport::resolvedTotal).sum());
        return List.copyOf(reports);
    }

    /**
     * One system, or nothing when there is no evidence to reconcile it against.
     *
     * <p><b>No accounts means no read, not an empty system</b>, and the two are
     * indistinguishable from here. Reconciling on that basis would report every
     * provisioned access as missing and every finding of a previous pass as
     * resolved, which is the one way this operation can do real damage: it
     * would close yesterday's orphans by concluding that the accounts they are
     * about have gone away. Skipped and said out loud, for the reason
     * {@code AggregationRunner} gives about an application with no connector.
     */
    private Optional<ReconciliationReport> reconcile(GovernedApplication application) {
        Instant startedAt = clock.instant();
        OffsetDateTime now = OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC);

        List<StoredAccount> stored = accounts.read(orgId, application.id());
        if (stored.isEmpty()) {
            log.info("Skipping '{}': the installation governs it and no account of it has ever been read,"
                    + " so there is nothing to compare and an empty comparison would resolve its findings",
                    application.code());
            return Optional.empty();
        }

        Set<Finding> found = new LinkedHashSet<>(AccountConditions.findings(stored, now, dormantAfter));
        found.addAll(GrantDrift.findings(
                drift.realGrants(orgId, application.id()),
                expectation.liveGrants(orgId, application.id())));

        Map<FindingType, Tally> tallies = new EnumMap<>(FindingType.class);
        // Seeded with zeros so that every pass reports the same four lines. A
        // kind of finding that silently disappears from a report when it finds
        // nothing is a kind nobody notices has stopped being checked.
        EVALUATED.forEach(type -> tallies.put(type, Tally.nothing(type)));
        ledger.reconcile(orgId, application.id(), expectation.catalogueOf(orgId, application.id()), found, now)
                .forEach((type, tally) -> tallies.merge(type, tally, Tally::plus));

        boolean dormancyAssessed = AccountConditions.reportsActivity(stored);
        if (!dormancyAssessed) {
            log.info("'{}' reports no activity for any of its {} accounts, so dormancy is not assessed for it",
                    application.code(), stored.size());
        }
        return Optional.of(
                ReconciliationReport.of(application.code(), dormancyAssessed, tallies, startedAt, clock.instant()));
    }
}
