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
 * The OpenLDAP connector: reads accounts and groups out of one directory.
 *
 * <p>Internal by construction, not by convention. A nested package of a Spring
 * Modulith module is not part of its API, so no other module can reach these
 * types even by a well meant shortcut: the rest of Lineward sees
 * {@code TargetSystemConnector} and never this. That boundary is verified by
 * {@code ModularityTests}.
 *
 * <p>Read in this order:
 * <ol>
 * <li>{@link com.lineward.connector.ldap.LdapConnector}: the SPI
 *     implementation, and the only type anything outside this package builds.
 *     Its class comment holds the two decisions worth knowing, that a read is a
 *     session and that membership lives on the group
 * <li>{@link com.lineward.connector.ldap.LdapCredentials}: where a directory is,
 *     which account reads it, and what that directory calls a locked account
 * <li>{@link com.lineward.connector.ldap.LdapDirectory}: one live connection and
 *     the searches that run over it, including the paging control and the one
 *     filter that takes an argument from outside
 * <li>{@link com.lineward.connector.ldap.DirectoryEntry}: an entry as flat data,
 *     which is the seam that lets the mapping be tested without a server
 * <li>{@link com.lineward.connector.ldap.LdapEntries}: a directory entry turned
 *     into what the SPI holds
 * </ol>
 *
 * <p>The split between the last two is the same one the Keycloak connector
 * makes, and for the same reason: fetching an entry needs a server,
 * understanding one does not, and a mapping reachable without a server is a
 * mapping a test can feed entries copied from a terminal.
 *
 * <p>What this package needs from outside itself, and does not decide: which
 * directory a given application maps to, which is the connector registry of
 * KAN-11; where a discovered account's attributes and creation date are stored,
 * also KAN-11; and dormancy, which this directory will not answer at all without
 * the access log overlay and which belongs to KAN-12.
 *
 * <p>What it needs from the directory, and cannot do for itself: the access
 * rules in {@code infra/ldap/init/01-access-rules.sh}. Without them a directory
 * built from this image serves every password hash to anybody who asks,
 * including with no credential at all.
 */
package com.lineward.connector.ldap;
