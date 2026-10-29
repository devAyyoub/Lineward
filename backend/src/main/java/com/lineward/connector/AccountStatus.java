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
 * What the target system says about an account's own state.
 *
 * <p>The five values the {@code account.status} check constraint of
 * {@code V001__phase_1_core_schema.sql} allows, so a connector cannot invent a
 * sixth that the insert would reject.
 *
 * <p>No target system has all five. Keycloak reports {@code enabled} and
 * nothing else, verified on 26.7.5; a directory can also lock an entry, and
 * Okta has a deprovisioned state that is neither gone nor usable. A connector
 * maps its own vocabulary onto these and says {@link #UNKNOWN} when it cannot.
 */
public enum AccountStatus {

    /** The account can be used to log in. */
    ACTIVE,

    /** The account exists and is switched off. Still an account, still a finding if nobody owns it. */
    DISABLED,

    /** Locked out, typically by a password policy, rather than switched off on purpose. */
    LOCKED,

    /**
     * The target system keeps the account as a tombstone. Distinct from an
     * account that stopped being returned by a read, which is what deletion
     * normally looks like from here.
     */
    DELETED,

    /**
     * The system does not say.
     *
     * <p>The default on purpose, and never {@link #ACTIVE}. Reading silence as
     * active invents live access that nobody granted, and the whole point of
     * aggregation is to stop Lineward reciting what it assumes.
     */
    UNKNOWN
}
