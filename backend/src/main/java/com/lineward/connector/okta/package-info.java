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
/**
 * The Okta connector: reads accounts and permissions out of one organization.
 *
 * <p>Internal by construction, not by convention. A nested package of a Spring
 * Modulith module is not part of its API, so no other module can reach these
 * types even by a well meant shortcut: the rest of Lineward sees
 * {@code TargetSystemConnector} and never this. That boundary is verified by
 * {@code ModularityTests}.
 *
 * <p><b>The one target system this project cannot start a copy of.</b>
 * Keycloak and OpenLDAP are containers in {@code infra/} that start with their
 * tests, so those connectors are measured against a real server on every build.
 * An Okta organization is somebody's account and its credential is a private
 * key on somebody's machine, so the live checks here run only where one is
 * configured and the rest is pinned down by what gets signed and what gets
 * parsed.
 *
 * <p>Read in this order:
 * <ol>
 * <li>{@link com.lineward.connector.okta.OktaCredentials}: where an
 *     organization is and which service application reads it. Worth reading
 *     first because it is the one credential in this project that is a key and
 *     not a secret, and the comment says why that is Okta's decision
 * <li>{@link com.lineward.connector.okta.OktaProofs}: the two signed objects
 *     every call carries. Its comment holds the three stacked mechanisms, which
 *     is the part of Okta that no amount of reading about the endpoints would
 *     have revealed
 * <li>{@link com.lineward.connector.okta.OktaTokens}: the token exchange,
 *     including the first request that is supposed to fail
 * <li>{@link com.lineward.connector.okta.OktaAdminApi}: the calls, the paging,
 *     and the refusal to report a read that could not see the directory
 * </ol>
 *
 * <p>What this package needs from outside itself and does not decide: which
 * organization a given application maps to, which is the connector registry;
 * and the synthetic organization its catalogue will eventually be read from,
 * which is KAN-10 and which has to fit in ten active users.
 */
package com.lineward.connector.okta;
