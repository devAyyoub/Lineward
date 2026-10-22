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
package com.lineward.identity;

import java.time.Instant;

/**
 * What one ingestion run did.
 *
 * <p>The three identity counts are separate on purpose. A run where everything
 * is unchanged is the normal case and the one that proves the run is idempotent:
 * reading the same extract twice must not look like five thousand changes. A run
 * where everything counts as updated means change detection is broken, and that
 * failure is invisible if the only number reported is "records read".
 *
 * @param recordsRead         how many records the source handed over
 * @param identitiesCreated   external ids seen for the first time
 * @param identitiesUpdated   records whose asserted content changed
 * @param identitiesUnchanged records the source asserted identically
 * @param managersLinked      responsibility rows opened, including repointed ones
 * @param managersUnlinked    responsibility rows closed because the source no
 *                            longer asserts a manager
 * @param managersUnresolved  managers the source names that could not be
 *                            resolved to an identity. Not an error: an
 *                            incremental read can see somebody before it sees
 *                            their manager, and the next full read fixes it.
 *                            Counted because a control that silently drops data
 *                            is worse than one that fails
 * @param managersPreserved   responsibility rows left alone because somebody
 *                            assigned them inside Lineward. An HR ingest must
 *                            not overwrite those, which is the reason
 *                            {@code identity_responsibility.origin} exists
 * @param highWaterMark       the newest modification instant seen, which is what
 *                            the next incremental read should ask from. Not the
 *                            clock: a record changed while the run was in
 *                            progress would fall in the gap between the two and
 *                            never be read again
 */
public record IngestionReport(
        int recordsRead,
        int identitiesCreated,
        int identitiesUpdated,
        int identitiesUnchanged,
        int managersLinked,
        int managersUnlinked,
        int managersUnresolved,
        int managersPreserved,
        Instant startedAt,
        Instant finishedAt,
        Instant highWaterMark) {}
