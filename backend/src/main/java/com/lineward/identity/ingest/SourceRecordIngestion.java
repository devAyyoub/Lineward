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

import com.lineward.identity.IdentityStatus;
import com.lineward.identity.source.AssertedIdentity;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes what a source asserted, and keeps the governed identity in step.
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

    SourceRecordIngestion(JdbcClient jdbc, PayloadFingerprint fingerprint) {
        this.jdbc = jdbc;
        this.fingerprint = fingerprint;
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

    /** Where a row already exists, what we know about it. */
    private record Existing(UUID identityId, byte[] payloadHash) {}

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

    PageOutcome ingestPage(UUID orgId, UUID sourceSystemId, List<AssertedIdentity> records, LocalDate today) {
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

            if (known == null) {
                UUID identityId = insertIdentity(orgId, sourceSystemId, asserted, today);
                insertSourceRecord(orgId, sourceSystemId, identityId, asserted, hash);
                created++;
            } else if (Arrays.equals(known.payloadHash(), hash)) {
                unchanged++;
            } else {
                updateIdentity(orgId, known.identityId(), asserted, today);
                updateSourceRecord(orgId, known.identityId(), asserted, hash);
                updated++;
            }
        }

        return new PageOutcome(created, updated, unchanged);
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
        jdbc.sql("""
                SELECT external_id, identity_id, payload_hash
                  FROM source_record
                 WHERE org_id = :orgId
                   AND source_system_id = :sourceSystemId
                   AND external_id IN (:externalIds)
                """)
                .param("orgId", orgId)
                .param("sourceSystemId", sourceSystemId)
                .param("externalIds", externalIds)
                .query((rs, row) -> byExternalId.put(
                        rs.getString("external_id"),
                        new Existing(rs.getObject("identity_id", UUID.class), rs.getBytes("payload_hash"))))
                .list();
        return byExternalId;
    }

    private UUID insertIdentity(UUID orgId, UUID sourceSystemId, AssertedIdentity asserted, LocalDate today) {
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
                .param("status", statusOf(asserted, today))
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
    private void updateIdentity(UUID orgId, UUID identityId, AssertedIdentity asserted, LocalDate today) {
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
                .param("status", statusOf(asserted, today))
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

    private static String statusOf(AssertedIdentity asserted, LocalDate today) {
        IdentityStatus status =
                IdentityStatusRule.statusFor(asserted.hireDate(), asserted.terminationDate(), today);
        return status.name();
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
