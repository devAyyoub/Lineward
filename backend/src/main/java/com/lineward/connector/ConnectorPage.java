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
package com.lineward.connector;

import java.util.List;

/**
 * One page of whatever a connector was asked to read, and where to carry on.
 *
 * <p>One type for accounts and for entitlements, because paging is the same
 * mechanism for both and two records differing only in their element type would
 * be a copy.
 *
 * <p>The end of a read is {@code next} being absent, and never an empty page.
 * That is the same problem {@code AssertedPage} solves with a total, solved
 * differently because here a total is not available: a directory paging by the
 * cookie of RFC 2696 does not know how many entries are left, and neither does
 * Okta. What every one of them does say explicitly is that there is no next
 * page, by returning an empty cookie or omitting the link, and that is what
 * {@link #last} records. The distinction it buys is the one that matters: a read
 * that ended is a complete picture of the target system, and a read that broke
 * is not. Mistake the second for the first and every account that did not
 * arrive looks like an account that was deleted, which the next phase acts on.
 * A broken read is a {@link ConnectorException}, not a page.
 *
 * @param items what this page carried, possibly empty
 * @param next  where to carry on, or {@code null} when the target system said
 *              there is no more
 */
public record ConnectorPage<T>(List<T> items, ReadCursor next) {

    public ConnectorPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** A page the target system says is the last one. */
    public static <T> ConnectorPage<T> last(List<T> items) {
        return new ConnectorPage<>(items, null);
    }

    /**
     * A page with more behind it.
     *
     * <p>Refuses a missing cursor rather than quietly behaving like
     * {@link #last}, because that confusion is the expensive one: it would cut a
     * read short and report the part as the whole.
     */
    public static <T> ConnectorPage<T> more(List<T> items, ReadCursor next) {
        if (next == null) {
            throw new IllegalArgumentException("a page with more behind it has to say where to carry on");
        }
        return new ConnectorPage<>(items, next);
    }

    /** Whether the target system said this was the last page. */
    public boolean exhausted() {
        return next == null;
    }
}
