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
package dev.ayyoub.lineward.identity;

import java.time.Instant;

/**
 * Brings an authoritative source into Lineward's own tables.
 *
 * <p>This is step one and two of the walkthrough in section 4.12: ask the source
 * for its records, store what it said, and keep the governed view of each
 * identity in step with it. What it deliberately does not do yet is classify the
 * difference as a joiner, a mover or a leaver, nor recalculate any access from
 * it. That is the next task of phase 1, and it is a separate one because this
 * half is verifiable on its own: what Lineward stored either matches what the
 * source asserted or it does not.
 *
 * <p>Idempotent by design, not by accident. Running it twice over an unchanged
 * extract writes nothing, which is what makes a failure halfway through safe to
 * recover from by running it again.
 */
public interface IdentityIngestion {

    /**
     * Reads the whole population.
     *
     * <p>The expensive one, and the one that repairs anything the cheap one
     * cannot see, such as a manager who arrived after the person reporting to
     * them.
     */
    IngestionReport ingestEverything();

    /**
     * Reads only what the source changed after {@code since}.
     *
     * <p>The one that runs every night, and the only one that exercises the
     * source's modification timestamp. Building it late is how a timestamp that
     * no other read looks at stays wrong for months.
     */
    IngestionReport ingestChangesSince(Instant since);
}
