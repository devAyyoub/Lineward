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
package dev.ayyoub.lineward.identity.source;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One record exactly as an authoritative source asserts it.
 *
 * <p>Nothing here is a Lineward concept: no organization, no identity id, no
 * status. That separation is decision 4 of ADR-013 and it is the difference
 * between what the source says and what Lineward decided. Mixing them is how a
 * manual correction inside Lineward ends up looking like a change coming from
 * HR and fires a mover that nobody asked for.
 *
 * <p>Most fields are nullable because an HR extract is allowed to be
 * incomplete, and M1 lists incomplete and contradictory data as a case to
 * ingest and report rather than to reject.
 *
 * @param externalId        the natural key of the source: an employee number
 *                          here, an agent slug in phase 6. It lives on
 *                          {@code source_record} and not on {@code identity}
 * @param assertedAt        when the source says it last changed this record.
 *                          Not when we read it, which is what makes a
 *                          retroactive termination reconstructible
 * @param rawPayload        the record as it arrived, as text. Stored in
 *                          {@code source_record.payload} so that a field this
 *                          version of the code ignores is not lost. Not kept
 *                          byte for byte: that column is {@code jsonb}, so
 *                          PostgreSQL stores the parsed document and normalises
 *                          key order and whitespace. Every value survives, the
 *                          exact bytes do not
 */
public record AssertedIdentity(
        String externalId,
        String firstName,
        String lastName,
        String email,
        String department,
        String jobTitle,
        String location,
        String costCenter,
        String employmentType,
        LocalDate hireDate,
        LocalDate terminationDate,
        String managerExternalId,
        Instant assertedAt,
        String rawPayload) {

    public AssertedIdentity {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("a source record without an external id cannot be correlated");
        }
        if (assertedAt == null) {
            throw new IllegalArgumentException(
                    "source record " + externalId + " carries no modification timestamp");
        }
    }

    /**
     * How this person is named in Lineward.
     *
     * <p>The source hands over the parts and the consumer composes them, which
     * is how a real HR system behaves, so the composition rule belongs here.
     * Falls back to the external id when the source gives no name at all,
     * because {@code identity.display_name} is NOT NULL and refusing to ingest
     * a nameless record would hide exactly the data quality problem that ought
     * to show up as a finding.
     */
    public String displayName() {
        String composed = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
        return composed.isEmpty() ? externalId : composed;
    }
}
