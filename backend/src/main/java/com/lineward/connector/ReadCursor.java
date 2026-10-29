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

/**
 * Where a paged read carries on from, in whatever form the connector needs.
 *
 * <p>Opaque on purpose, and this is the one design decision of the SPI worth
 * reading twice. A page number would have been simpler and would not survive
 * contact with a real directory: LDAP pages with the simple paged results
 * control of RFC 2696, where the server hands back a cookie that is an opaque
 * byte string and the client has to send it back unchanged, and Okta pages by
 * giving the URL of the next page in a {@code Link} header. Neither is a
 * number, and neither can be reconstructed by the caller. Keycloak does page by
 * offset, so a number would have fitted one connector out of three.
 *
 * <p>A record around one string, rather than the bare string, so that the token
 * cannot be read, incremented or compared by anything other than the connector
 * that issued it, and so that a page number cannot be passed by mistake.
 *
 * <p>Nothing says which connector issued it, which looks like a gap and is not.
 * A cursor is only ever stored in {@code aggregation_run.checkpoint}, a row
 * keyed by application with a unique index allowing one live run per
 * application, so the question "is this cursor mine" is already answered by
 * where the cursor is kept. A field repeating it here would be a second answer
 * that can disagree with the first. Resuming from the stored checkpoint is
 * KAN-13.
 *
 * @param token the connector's own continuation value, never interpreted
 *     outside it
 */
public record ReadCursor(String token) {

    public ReadCursor {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("a read cursor with no token cannot resume anything");
        }
    }
}
