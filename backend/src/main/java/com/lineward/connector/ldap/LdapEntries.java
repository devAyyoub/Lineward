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
package com.lineward.connector.ldap;

import com.lineward.connector.AccountStatus;
import com.lineward.connector.DiscoveredAccount;
import com.lineward.connector.DiscoveredEntitlement;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A directory entry turned into what the SPI holds.
 *
 * <p>No connection and no protocol here on purpose, so that every decision
 * below is checkable against entries a real server printed. The three worth
 * knowing:
 *
 * <ol>
 * <li><b>An account is identified by {@code entryUUID}, never by its
 *     distinguished name.</b> A name says where an entry sits in the tree, and
 *     moving somebody between organizational units is an ordinary Tuesday in a
 *     directory. Matching on the name would report that account as one deleted
 *     and one created, which in reconciliation means revoking everything the
 *     person had and granting it again.
 * <li><b>A locked account is {@code LOCKED} and never {@code DISABLED}.</b>
 *     OpenLDAP has no switched off flag of its own. What it has is the lock the
 *     password policy keeps, and the SPI already has a status for exactly that.
 *     Which attribute carries the lock is configured rather than guessed,
 *     because the convention differs between directories, and when nothing is
 *     configured the status is {@code UNKNOWN}: reading silence as active would
 *     invent live access nobody granted.
 * <li><b>Every value read is kept.</b> The map holds every attribute that came
 *     back, including the ones that also became fields, because a field holds
 *     one string and an account in this project's own directory already has two
 *     addresses. Only the three that are fields in their own right are left out
 *     of it.
 * </ol>
 */
class LdapEntries {

    /** The entry's own identifier, assigned by the server and never reused. */
    static final String UUID_ATTRIBUTE = "entryUUID";

    static final String CREATED_ATTRIBUTE = "createTimestamp";

    private static final String LOGIN_NAME = "uid";
    private static final String COMMON_NAME = "cn";
    private static final String DISPLAY_NAME = "displayName";
    private static final String MAIL = "mail";
    private static final String DESCRIPTION = "description";

    /**
     * Generalized time, which is what a directory reports a date in: fourteen
     * digits and an offset, optionally with a fraction of a second between
     * them. OpenLDAP 2.6.10 printed {@code 20261103205510Z}; the optional
     * fraction is in here because the format allows it and dropping a date
     * because of a decimal point would lose the evidence of an orphan's age.
     */
    private static final DateTimeFormatter GENERALIZED_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyyMMddHHmmss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .appendPattern("X")
            .toFormatter();

    DiscoveredAccount account(DirectoryEntry entry, List<String> entitlementValues, String lockMarkerAttribute) {
        String nativeId = entry.first(UUID_ATTRIBUTE);
        if (nativeId == null) {
            throw new IllegalArgumentException("an account with no " + UUID_ATTRIBUTE
                    + " cannot be correlated, reconciled or found again: " + entry.dn());
        }
        return new DiscoveredAccount(
                nativeId,
                usernameOf(entry),
                entry.first(MAIL),
                displayNameOf(entry),
                statusOf(entry, lockMarkerAttribute),
                instantOrNull(entry.first(CREATED_ATTRIBUTE)),
                // OpenLDAP reports no last use on an entry, verified on 2.6.10.
                // It would take the access log overlay, which is KAN-12's
                // problem and not something to fake here.
                null,
                entitlementValues,
                attributesOf(entry, lockMarkerAttribute));
    }

    DiscoveredEntitlement entitlement(DirectoryEntry entry) {
        return new DiscoveredEntitlement(entry.dn(), entry.first(COMMON_NAME), entry.first(DESCRIPTION));
    }

    /**
     * The login name, or the name the entry is filed under when it has none.
     *
     * <p>Returning nothing would be the easy answer and the wrong one: an
     * account with no {@code uid} is exactly the kind a first aggregation is run
     * to find, and whoever reads the finding needs something to recognise it by.
     */
    private static String usernameOf(DirectoryEntry entry) {
        String login = entry.first(LOGIN_NAME);
        return login != null ? login : relativeNameOf(entry.dn());
    }

    private static String displayNameOf(DirectoryEntry entry) {
        String display = entry.first(DISPLAY_NAME);
        return display != null ? display : entry.first(COMMON_NAME);
    }

    private static AccountStatus statusOf(DirectoryEntry entry, String lockMarkerAttribute) {
        if (lockMarkerAttribute == null || lockMarkerAttribute.isBlank()) {
            return AccountStatus.UNKNOWN;
        }
        return entry.has(lockMarkerAttribute) ? AccountStatus.LOCKED : AccountStatus.ACTIVE;
    }

    private static Map<String, List<String>> attributesOf(DirectoryEntry entry, String lockMarkerAttribute) {
        Set<String> alreadyAField = lockMarkerAttribute == null
                ? Set.of(UUID_ATTRIBUTE, CREATED_ATTRIBUTE)
                : Set.of(UUID_ATTRIBUTE, CREATED_ATTRIBUTE, lockMarkerAttribute);
        Map<String, List<String>> rest = new LinkedHashMap<>();
        entry.attributes().forEach((name, values) -> {
            if (!alreadyAField.contains(name)) {
                rest.put(name, values);
            }
        });
        return rest;
    }

    /**
     * The value of the first component of a distinguished name, unescaped.
     *
     * <p>{@code cn=K. O'Hara (contractor),ou=users,...} is a real entry of this
     * project's directory, so the comma cannot simply be split on: it can sit
     * inside a value, escaped as {@code \,}.
     */
    private static String relativeNameOf(String dn) {
        StringBuilder name = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < dn.length(); i++) {
            char character = dn.charAt(i);
            if (escaped) {
                name.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == ',') {
                break;
            } else {
                name.append(character);
            }
        }
        int equals = name.indexOf("=");
        return equals < 0 ? name.toString() : name.substring(equals + 1);
    }

    /**
     * A date the directory reported, or nothing when it cannot be read.
     *
     * <p>Nothing rather than an exception: the account is the finding and the
     * date is evidence about it, so an unreadable date is worth losing and an
     * account is not.
     */
    private static Instant instantOrNull(String generalizedTime) {
        if (generalizedTime == null) {
            return null;
        }
        try {
            return Instant.from(GENERALIZED_TIME.parse(generalizedTime));
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }
}
