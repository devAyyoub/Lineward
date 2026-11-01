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
 * The Keycloak connector: reads accounts and permissions out of one realm.
 *
 * <p>Internal by construction, not by convention. A nested package of a Spring
 * Modulith module is not part of its API, so no other module can reach these
 * types even by a well meant shortcut: the rest of Lineward sees
 * {@code TargetSystemConnector} and never this. That boundary is verified by
 * {@code ModularityTests}.
 *
 * <p>Read in this order:
 * <ol>
 * <li>{@link com.lineward.connector.keycloak.KeycloakConnector}: the SPI
 *     implementation, and the only type anything outside this package builds.
 *     Its class comment holds the two decisions worth knowing, how a read ends
 *     and why the catalogue is a tree
 * <li>{@link com.lineward.connector.keycloak.KeycloakCredentials}: where a realm
 *     is and which service account reads it
 * <li>{@link com.lineward.connector.keycloak.KeycloakAdminApi}: the four admin
 *     API calls and the token that authorises them
 * <li>{@link com.lineward.connector.keycloak.KeycloakDocuments}: Keycloak's JSON
 *     turned into what the SPI holds
 * </ol>
 *
 * <p>The split between the last two is the same one {@code identity.source}
 * makes between its reader and its parser, and for the same reason: fetching a
 * payload needs a server, understanding one does not, and a parser reachable
 * without a server is a parser a test can feed bytes copied from the wire.
 *
 * <p>What this package needs from outside itself, and does not decide: which
 * realm a given application maps to, which is the connector registry of KAN-11;
 * where a discovered account's attributes and creation date are stored, which is
 * also KAN-11; and dormancy, which Keycloak will not answer from the user
 * representation at all and which needs its events API in KAN-12.
 */
package com.lineward.connector.keycloak;
