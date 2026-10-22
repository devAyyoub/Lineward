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
package com.lineward.identity.ingest;

import com.lineward.identity.IdentityIngestion;
import com.lineward.identity.IdentityLifecycleChanged;
import com.lineward.identity.IngestionReport;
import com.lineward.identity.LifecycleEvent;
import com.lineward.identity.source.AssertedIdentity;
import com.lineward.identity.source.AssertedPage;
import com.lineward.identity.source.AuthoritativeSourceReader;
import com.lineward.shared.TenantTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives one ingestion run: read every page, store what was read, then link the
 * managers.
 *
 * <p>One transaction per page rather than one for the run. A five thousand
 * record extract in a single transaction holds locks for its whole duration and
 * loses everything on the last failure; per page, a crash halfway leaves the
 * earlier pages ingested, which is safe precisely because the operation is
 * idempotent. Running it again re-reads them and finds nothing to do.
 */
public class HrIngestionRunner implements IdentityIngestion {

    private static final Logger log = LoggerFactory.getLogger(HrIngestionRunner.class);

    private final AuthoritativeSourceReader source;
    private final SourceRecordIngestion records;
    private final ManagerReconciliation managers;
    private final TenantTransactions tenants;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final UUID orgId;
    private final String sourceSystemCode;
    private final int pageSize;

    /**
     * Builds a run with its internals wired up.
     *
     * <p>The collaborators this hides are package private and stay that way:
     * what the rest of the application needs is the {@link IdentityIngestion}
     * interface, and a factory keeps the pieces behind it from becoming public
     * just so that a configuration class in another package can name them.
     */
    public static HrIngestionRunner create(
            AuthoritativeSourceReader source,
            JdbcClient jdbc,
            TenantTransactions tenants,
            Clock clock,
            ApplicationEventPublisher events,
            UUID orgId,
            String sourceSystemCode,
            int pageSize) {
        JsonMapper json = JsonMapper.builder().build();
        return new HrIngestionRunner(
                source,
                new SourceRecordIngestion(jdbc, new PayloadFingerprint(json), source),
                new ManagerReconciliation(jdbc),
                tenants,
                clock,
                events,
                orgId,
                sourceSystemCode,
                pageSize);
    }

    HrIngestionRunner(
            AuthoritativeSourceReader source,
            SourceRecordIngestion records,
            ManagerReconciliation managers,
            TenantTransactions tenants,
            Clock clock,
            ApplicationEventPublisher events,
            UUID orgId,
            String sourceSystemCode,
            int pageSize) {
        this.source = source;
        this.records = records;
        this.managers = managers;
        this.tenants = tenants;
        this.clock = clock;
        this.events = events;
        this.orgId = orgId;
        this.sourceSystemCode = sourceSystemCode;
        this.pageSize = pageSize;
    }

    @Override
    public IngestionReport ingestEverything() {
        return ingest(null);
    }

    @Override
    public IngestionReport ingestChangesSince(Instant since) {
        return ingest(since);
    }

    private IngestionReport ingest(Instant changedSince) {
        Instant startedAt = clock.instant();
        LocalDate today = LocalDate.now(clock);
        UUID sourceSystemId =
                tenants.inTenant(orgId, () -> records.resolveSourceSystemId(orgId, sourceSystemCode));

        SourceRecordIngestion.PageOutcome identities = SourceRecordIngestion.PageOutcome.none();

        // Subject employee number to the manager the source names, collected
        // while reading because the manager may be on a later page. Fine at the
        // scale of section 10.2; a source with hundreds of thousands of records
        // would want a second streaming pass instead of this map.
        Map<String, String> assertedManagers = new LinkedHashMap<>();

        // Counted rather than kept: a first full read of a real population is
        // thousands of joiners, and holding them all to count them at the end
        // would be the one part of this run whose memory grows with the
        // organization.
        Map<LifecycleEvent, Integer> detected = new EnumMap<>(LifecycleEvent.class);

        int recordsRead = 0;
        int reportedTotal = 0;
        Instant highWaterMark = changedSince;
        int page = 0;
        int totalPages = 1;

        do {
            AssertedPage current = source.readPage(page, pageSize, changedSince);
            totalPages = current.totalPages();
            reportedTotal = current.totalElements();
            recordsRead += current.records().size();

            for (AssertedIdentity asserted : current.records()) {
                assertedManagers.put(asserted.externalId(), asserted.managerExternalId());
                if (highWaterMark == null || asserted.assertedAt().isAfter(highWaterMark)) {
                    highWaterMark = asserted.assertedAt();
                }
            }

            // Inside the page's transaction, which is what makes a lost event
            // impossible rather than recoverable: a listener that calculates
            // access writes it in the same unit of work that stored the change,
            // so either both are there or neither is. The rule that keeps this
            // true is on IdentityLifecycleChanged, and it is that nothing in a
            // listener leaves the process.
            identities = identities.plus(tenants.inTenant(orgId, () -> records.ingestPage(
                    orgId, sourceSystemId, current.records(), today, (IdentityLifecycleChanged event) -> {
                        detected.merge(event.event(), 1, Integer::sum);
                        events.publishEvent(event);
                    })));
            page++;
        } while (page < totalPages);

        // The source said how many records it had, so a run that read fewer has
        // not finished, whatever the loop thinks. Failing here is the point: a
        // short read looks identical to a complete read of a smaller company,
        // and the next task of phase 1 would turn the difference into a wave of
        // terminations.
        if (recordsRead != reportedTotal) {
            throw new IllegalStateException(
                    "incomplete read: the source reported " + reportedTotal + " records and "
                            + recordsRead + " were read. Not treating this as a complete extract");
        }

        ManagerReconciliation.Outcome responsibilities = reconcileManagers(sourceSystemId, assertedManagers);

        IngestionReport report = new IngestionReport(
                recordsRead,
                identities.created(),
                identities.updated(),
                identities.unchanged(),
                detected.getOrDefault(LifecycleEvent.JOINER, 0),
                detected.getOrDefault(LifecycleEvent.MOVER, 0),
                detected.getOrDefault(LifecycleEvent.LEAVER, 0),
                detected.getOrDefault(LifecycleEvent.REHIRE, 0),
                responsibilities.linked(),
                responsibilities.unlinked(),
                responsibilities.unresolved(),
                responsibilities.preserved(),
                startedAt,
                clock.instant(),
                highWaterMark);

        log.info("Ingested {} from source '{}': {} created, {} updated, {} unchanged; "
                        + "lifecycle {} joiners, {} movers, {} leavers, {} rehires; "
                        + "managers {} linked, {} unlinked, {} unresolved, {} preserved",
                changedSince == null ? "the whole population" : "changes since " + changedSince,
                sourceSystemCode,
                report.identitiesCreated(), report.identitiesUpdated(), report.identitiesUnchanged(),
                report.joiners(), report.movers(), report.leavers(), report.rehires(),
                report.managersLinked(), report.managersUnlinked(),
                report.managersUnresolved(), report.managersPreserved());

        return report;
    }

    private ManagerReconciliation.Outcome reconcileManagers(
            UUID sourceSystemId, Map<String, String> assertedManagers) {
        ManagerReconciliation.Outcome total = ManagerReconciliation.Outcome.none();
        for (Map<String, String> batch : batches(assertedManagers, pageSize)) {
            total = total.plus(
                    tenants.inTenant(orgId, () -> managers.reconcile(orgId, sourceSystemId, batch)));
        }
        return total;
    }

    /** Same batch size as the pages, so one transaction covers a comparable slice. */
    private static List<Map<String, String>> batches(Map<String, String> all, int batchSize) {
        List<Map<String, String>> batches = new ArrayList<>();
        Map<String, String> current = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : all.entrySet()) {
            current.put(entry.getKey(), entry.getValue());
            if (current.size() == batchSize) {
                batches.add(current);
                current = new LinkedHashMap<>();
            }
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }
}
