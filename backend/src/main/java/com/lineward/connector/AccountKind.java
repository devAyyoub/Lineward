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
 * What a target system is willing to say about the nature of an account.
 *
 * <p><b>Two values, and the one that is missing is the design.</b> There is no
 * {@code PERSON}, because no target system asserts that an account belongs to a
 * human being. Keycloak and a directory both hold accounts that organizations
 * create for batch jobs, scripts and integrations, named like people and shaped
 * like people, and the system serving them cannot tell the difference. A
 * {@code PERSON} value would turn {@code UNKNOWN} into something that looks
 * like a failed detection, when it is the honest answer for almost everything.
 *
 * <p>So this enum only records the one case a system can prove, and the proof is
 * structural rather than textual: Keycloak returns a client's service account
 * from the clients endpoint, not from the user list, so an account that arrived
 * that way <em>is</em> a service account by construction. Guessing from the
 * {@code service-account-} prefix instead would be reading a naming convention
 * an administrator can change, and would put knowledge of Keycloak into modules
 * that must not have any.
 *
 * <p><b>What this is not.</b> It is not {@code identity.type}, which has
 * {@code HUMAN}, {@code SERVICE} and {@code AGENT}, because that answers a
 * different question: what kind of thing Lineward has decided someone is. This
 * one is only what a connector read. An account whose kind is {@code SERVICE}
 * does not get an identity of its own here: deciding who answers for a
 * non-human identity is M10, and the finding that nobody does is
 * {@code UNOWNED_IDENTITY} in M2, which V001 already distinguishes from an
 * orphan account for exactly this reason.
 */
public enum AccountKind {

    /**
     * The target system says, structurally, that this account exists to let a
     * machine act. Its owner is a question for a human to answer, and a
     * correlation rule must not invent one.
     */
    SERVICE,

    /**
     * The target system says nothing, which is the normal case and not a gap in
     * the read. Everything from an ordinary user list arrives this way.
     */
    UNKNOWN
}
