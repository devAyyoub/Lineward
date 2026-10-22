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

import com.lineward.identity.IdentityLifecycleChanged;
import com.lineward.identity.IdentityStatus;
import com.lineward.identity.LifecycleEvent;
import com.lineward.identity.source.AssertedIdentity;
import com.lineward.identity.source.AuthoritativeSourceReader;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes what a source asserted, keeps the governed identity in step, and says
 * what the difference means.
 *
 * <p>Two tables per record, and the pairing is decision 4 of ADR-013:
 * {@code source_record} holds the source's own words and
 * {@code identity} holds Lineward's view of the same person. Comparing against
 * the first is what makes change detection honest; comparing against the second
 * would make a manual correction inside Lineward look like news from HR.
 *
 * <p>Everything here runs inside a transaction that already declared the tenant.
 * It does not open one itself, because the caller decides how much work shares a
 * transaction and that choice belongs with the run, not with the statement.
 */
class SourceRecordIngestion {

    private final JdbcClient jdbc;
    private final PayloadFingerprint fingerprint;
    private final AuthoritativeSourceReader source;

    SourceRecordIngestion(
            JdbcClient jdbc, PayloadFingerprint fingerprint, AuthoritativeSourceReader source) {
        this.jdbc = jdbc;
        this.fingerprint = fingerprint;
        this.source = source;
    }

    /** What one page of records did. */
    record PageOutcome(int created, int updated, int unchanged) {

        PageOutcome plus(PageOutcome other) {
            return new PageOutcome(
                    created + other.created, updated + other.updated, unchanged + other.unchanged);
        }

        static PageOutcome none() {
            return new PageOutcome(0, 0, 0);
        }
    }

    /**
     * Where a row already exists, what we know about it.
     *
     * <p>{@code status} is the one field read from the governed identity rather
     * than from the source, and that is the point of it: it is what Lineward
     * last acted on, so it is what a change of lifecycle state is measured
     * against. It is also the only thing that can see a date arrive, because on
     * that day the source asserts exactly the record it asserted yesterday.
     */
    private record Existing(UUID identityId, byte[] payloadHash, IdentityStatus status) {}

    UUID resolveSourceSystemId(UUID orgId, String code) {
        return jdbc.sql("SELECT id FROM source_system WHERE org_id = :orgId AND code = :code")
                .param("orgId", orgId)
                .param("code", code)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "no source system '" + code + "' in organization " + orgId
                                + ". The migration that registers it has not run, or the configured"
                                + " organization is not the one it was registered in"));
    }

    /**
     * @param detected called once per lifecycle event, inside this page's
     *     transaction. Collecting them rather than publishing them here is what
     *     lets the caller announce only what committed: an event published from
     *     inside a transaction that then rolls back is an announcement of work
     *     that was never stored
     */
    PageOutcome ingestPage(
            UUID orgId,
            UUID sourceSystemId,
            List<AssertedIdentity> records,
            LocalDate today,
            Consumer<IdentityLifecycleChanged> detected) {
        if (records.isEmpty()) {
            return PageOutcome.none();
        }

        Map<String, Existing> existing = loadExisting(
                orgId, sourceSystemId, records.stream().map(AssertedIdentity::externalId).toList());

        int created = 0;
        int updated = 0;
        int unchanged = 0;

        for (AssertedIdentity asserted : records) {
            byte[] hash = fingerprint.of(asserted.rawPayload());
            Existing known = existing.get(asserted.externalId());
            IdentityStatus status =
                    IdentityStatusRule.statusFor(asserted.hireDate(), asserted.terminationDate(), today);

            if (known == null) {
                UUID identityId = insertIdentity(orgId, sourceSystemId, asserted, status);
                insertSourceRecord(orgId, sourceSystemId, identityId, asserted, hash);
                created++;
                announce(
                        detected,
                        orgId,
                        identityId,
                        asserted,
                        LifecycleEventRule.classify(null, status, false),
                        status,
                        List.of());
            } else if (Arrays.equals(known.payloadHash(), hash)) {
                // The source asserted the same record as last time, so the only
                // thing that can have moved is which side of a date today falls
                // on: a notice period ending, or a future dated hire starting.
                // Nothing is written unless that happened, which is what keeps a
                // re-read of an unchanged extract free of writes.
                unchanged++;
                LifecycleEvent event = LifecycleEventRule.classify(known.status(), status, false);
                if (event != LifecycleEvent.NONE) {
                    updateStatus(orgId, known.identityId(), status);
                }
                announce(detected, orgId, known.identityId(), asserted, event, status, List.of());
            } else {
                AssertedIdentity previous = source.interpret(loadPayload(orgId, known.identityId()));
                List<String> moved = LifecycleEventRule.changedAccessAttributes(previous, asserted);
                LifecycleEvent event =
                        LifecycleEventRule.classify(known.status(), status, !moved.isEmpty());

                updateIdentity(orgId, known.identityId(), asserted, status);
                updateSourceRecord(orgId, known.identityId(), asserted, hash);
                updated++;
                announce(
                        detected,
                        orgId,
                        known.identityId(),
                        asserted,
                        event,
                        status,
                        event == LifecycleEvent.MOVER ? moved : List.of());
            }
        }

        return new PageOutcome(created, updated, unchanged);
    }

    /**
     * One place that knows {@code NONE} is the absence of an event, so that
     * three call sites do not each remember to check it.
     */
    private static void announce(
            Consumer<IdentityLifecycleChanged> detected,
            UUID orgId,
            UUID identityId,
            AssertedIdentity asserted,
            LifecycleEvent event,
            IdentityStatus status,
            List<String> movedAttributes) {
        if (event == LifecycleEvent.NONE) {
            return;
        }
        detected.accept(new IdentityLifecycleChanged(
                orgId,
                identityId,
                asserted.externalId(),
                event,
                status,
                movedAttributes,
                asserted.assertedAt()));
    }

    /**
     * One query for the whole page instead of one per record.
     *
     * <p>The lookup goes through {@code source_record} and not through
     * {@code identity}, because the employee number lives there. That is not an
     * accident of the schema: ingestion has to find the identity before it knows
     * which one it is, and the only handle it has is the key the source uses.
     */
    private Map<String, Existing> loadExisting(UUID orgId, UUID sourceSystemId, Collection<String> externalIds) {
        Map<String, Existing> byExternalId = new HashMap<>();
        // A join, which the rest of this project avoids, and allowed here
        // because both tables belong to M1. What ADR-001 asks not to form is the
        // habit of joining across a module boundary, not of joining two tables
        // the same module owns.
        //
        // The payload is left out on purpose. It is only needed for the records
        // whose fingerprint changed, which in a steady state is a handful out of
        // thousands, and carrying every stored document across the wire to throw
        // almost all of them away is the kind of cost that only shows up at full
        // population size.
        jdbc.sql("""
                SELECT sr.external_id, sr.identity_id, sr.payload_hash, i.status
                  FROM source_record sr
                  JOIN identity i ON i.org_id = sr.org_id AND i.id = sr.identity_id
                 WHERE sr.org_id = :orgId
                   AND sr.source_system_id = :sourceSystemId
                   AND sr.external_id IN (:externalIds)
                """)
                .param("orgId", orgId)
                .param("sourceSystemId", sourceSystemId)
                .param("externalIds", externalIds)
                .query((rs, row) -> byExternalId.put(
                        rs.getString("external_id"),
                        new Existing(
                                rs.getObject("identity_id", UUID.class),
                                rs.getBytes("payload_hash"),
                                IdentityStatus.valueOf(rs.getString("status")))))
                .list();
        return byExternalId;
    }

    /**
     * The previous assertion, fetched one record at a time because only a record
     * whose fingerprint changed needs it.
     */
    private String loadPayload(UUID orgId, UUID identityId) {
        return jdbc.sql("""
                SELECT cast(payload as text)
                  FROM source_record
                 WHERE org_id = :orgId AND identity_id = :identityId
                """)
                .param("orgId", orgId)
                .param("identityId", identityId)
                .query(String.class)
                .single();
    }

    private UUID insertIdentity(
            UUID orgId, UUID sourceSystemId, AssertedIdentity asserted, IdentityStatus status) {
        // HUMAN is hardcoded because this source produces people. When the agent
        // registry of phase 6 is added it becomes a property of the source row,
        // not a branch here: ADR-013 decision 3 models the source as data
        // precisely so that governing a new population means inserting a row.
        return jdbc.sql("""
                INSERT INTO identity (
                        org_id, type, status, source_system_id, display_name, email, department,
                        job_title, location, cost_center, employment_type, hire_date, termination_date)
                VALUES (:orgId, 'HUMAN', :status, :sourceSystemId, :displayName, :email, :department,
                        :jobTitle, :location, :costCenter, :employmentType, :hireDate, :terminationDate)
                RETURNING id
                """)
                .param("orgId", orgId)
                .param("status", status.name())
                .param("sourceSystemId", sourceSystemId)
                .param("displayName", asserted.displayName())
                .param("email", asserted.email())
                .param("department", asserted.department())
                .param("jobTitle", asserted.jobTitle())
                .param("location", asserted.location())
                .param("costCenter", asserted.costCenter())
                .param("employmentType", asserted.employmentType())
                .param("hireDate", asserted.hireDate())
                .param("terminationDate", asserted.terminationDate())
                .query(UUID.class)
                .single();
    }

    /**
     * Overwrites the typed attributes with what the source now says.
     *
     * <p>This is also what a rehire looks like, and it needs no case of its own:
     * the same employee number resolves to the same identity, the status is
     * derived again from the new dates and the identity comes back to life
     * instead of being duplicated. M1 asks for exactly that, and a second
     * identity for a returning employee is the kind of duplicate that breaks
     * correlation for years.
     */
    private void updateIdentity(
            UUID orgId, UUID identityId, AssertedIdentity asserted, IdentityStatus status) {
        jdbc.sql("""
                UPDATE identity
                   SET status = :status,
                       display_name = :displayName,
                       email = :email,
                       department = :department,
                       job_title = :jobTitle,
                       location = :location,
                       cost_center = :costCenter,
                       employment_type = :employmentType,
                       hire_date = :hireDate,
                       termination_date = :terminationDate
                 WHERE org_id = :orgId AND id = :identityId
                """)
                .param("status", status.name())
                .param("displayName", asserted.displayName())
                .param("email", asserted.email())
                .param("department", asserted.department())
                .param("jobTitle", asserted.jobTitle())
                .param("location", asserted.location())
                .param("costCenter", asserted.costCenter())
                .param("employmentType", asserted.employmentType())
                .param("hireDate", asserted.hireDate())
                .param("terminationDate", asserted.terminationDate())
                .param("orgId", orgId)
                .param("identityId", identityId)
                .update();
    }

    /**
     * The payload goes in as text and is stored as a parsed document, because
     * the column is {@code jsonb}. Key order and whitespace do not survive that,
     * which is worth knowing before anybody treats the stored value as the
     * source's exact bytes: it is equivalent JSON, not a copy of the wire.
     *
     * <p>It does not weaken the fingerprint, and the reason is that
     * {@link PayloadFingerprint} normalises field order on its own, so the hash
     * recomputed from the stored document equals the hash stored beside it. That
     * property is asserted rather than assumed, in
     * {@code IdentityIngestionTests}.
     */
    private void insertSourceRecord(
            UUID orgId, UUID sourceSystemId, UUID identityId, AssertedIdentity asserted, byte[] hash) {
        jdbc.sql("""
                INSERT INTO source_record (
                        org_id, source_system_id, identity_id, external_id,
                        payload, payload_hash, asserted_at)
                VALUES (:orgId, :sourceSystemId, :identityId, :externalId,
                        cast(:payload as jsonb), :payloadHash, :assertedAt)
                """)
                .param("orgId", orgId)
                .param("sourceSystemId", sourceSystemId)
                .param("identityId", identityId)
                .param("externalId", asserted.externalId())
                .param("payload", asserted.rawPayload())
                .param("payloadHash", hash)
                .param("assertedAt", atUtc(asserted))
                .update();
    }

    /**
     * One row per identity, updated in place.
     *
     * <p>{@code ingested_at} moves to now and {@code asserted_at} to whenever
     * the source says the change happened, which are not the same instant and
     * must not be collapsed into one. HR can say today that somebody left three
     * weeks ago, and those three weeks are the window in which their access was
     * still live. Being able to reconstruct it is the difference between an
     * answer and an audit finding.
     */
    private void updateSourceRecord(UUID orgId, UUID identityId, AssertedIdentity asserted, byte[] hash) {
        jdbc.sql("""
                UPDATE source_record
                   SET payload = cast(:payload as jsonb),
                       payload_hash = :payloadHash,
                       asserted_at = :assertedAt,
                       ingested_at = now()
                 WHERE org_id = :orgId AND identity_id = :identityId
                """)
                .param("payload", asserted.rawPayload())
                .param("payloadHash", hash)
                .param("assertedAt", atUtc(asserted))
                .param("orgId", orgId)
                .param("identityId", identityId)
                .update();
    }

    /**
     * The status on its own, for the day a date arrives.
     *
     * <p>Nothing else is touched, and that is deliberate: the source asserted
     * the same record as last time, so overwriting the attributes with values
     * identical to the stored ones would move {@code updated_at} and make the
     * row look as if HR had said something.
     */
    private void updateStatus(UUID orgId, UUID identityId, IdentityStatus status) {
        jdbc.sql("""
                UPDATE identity
                   SET status = :status
                 WHERE org_id = :orgId AND id = :identityId
                """)
                .param("status", status.name())
                .param("orgId", orgId)
                .param("identityId", identityId)
                .update();
    }

    /**
     * The driver takes an {@code OffsetDateTime} for a {@code timestamptz}
     * without guessing a zone, which an {@code Instant} does not do on every
     * version.
     */
    private static OffsetDateTime atUtc(AssertedIdentity asserted) {
        return OffsetDateTime.ofInstant(asserted.assertedAt(), ZoneOffset.UTC);
    }
}
