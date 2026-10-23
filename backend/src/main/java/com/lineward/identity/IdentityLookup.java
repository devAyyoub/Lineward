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

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the governed view of one identity, for the modules that decide things
 * about it.
 *
 * <p>The read side of M1, kept to the one shape other modules need. It is
 * deliberately not a repository: there is no list, no search and no write here,
 * because every one of those would be an invitation for another module to do
 * identity's work. KAN-16 adds the query API, and it belongs on this side of the
 * boundary rather than in whoever asks first.
 *
 * <p>An implementation runs in whatever transaction the caller already opened
 * and does not start one, which is what lets a lifecycle listener read an
 * identity that the same transaction has only just written.
 */
public interface IdentityLookup {

    /**
     * @param orgId the tenant, passed explicitly rather than read from
     *     configuration, so that a caller running inside somebody else's
     *     transaction cannot accidentally ask about a different organization
     *     than the one that transaction declared
     * @return empty when no identity with that id belongs to that organization.
     *     Empty rather than an exception, because the caller knows whether a
     *     missing identity is impossible or merely unexpected, and only the
     *     caller can say which
     */
    Optional<GovernedAttributes> attributesOf(UUID orgId, UUID identityId);
}
