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

/**
 * Reads an authoritative source, one page at a time.
 *
 * <p>Deliberately not a connector of M3. A connector talks to a target system
 * to find out which accounts exist there and writes to it; this reads the system
 * that owns the truth about who exists at all. They sit on opposite sides of the
 * model, and ADR-013 decision 3 makes the authoritative source a row in
 * {@code source_system} precisely so that the agent registry of phase 6 plugs in
 * here without a second code path.
 *
 * <p>Pages rather than one call, because a real HR system has hundreds of
 * thousands of records and will not serve them in one response.
 */
public interface AuthoritativeSourceReader {

    /**
     * @param changedSince only records the source modified strictly after this
     *     instant, or {@code null} for the whole population. The incremental
     *     read is the cheap one and the only one that exercises the source's
     *     modification timestamp, which is why it is built early rather than
     *     last
     */
    AssertedPage readPage(int page, int size, Instant changedSince);
}
