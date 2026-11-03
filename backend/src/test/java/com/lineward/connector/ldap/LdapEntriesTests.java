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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lineward.connector.AccountStatus;
import com.lineward.connector.DiscoveredAccount;
import com.lineward.connector.DiscoveredEntitlement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * What a directory entry says, turned into what the SPI holds.
 *
 * <p>Every entry below is what OpenLDAP 2.6.10 actually returned, read on
 * 2026-11-03 from the directory this repository ships and pasted in unchanged,
 * in the LDIF the server prints. Building them out of this project's own classes
 * instead would prove that the project agrees with itself.
 *
 * <p>The mapping lives apart from the protocol so that this test needs no
 * server: understanding an entry and fetching it are different jobs, and only
 * the second one needs a directory. The reads themselves are pinned down by
 * {@code LdapConnectorIntegrationTests}.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LdapEntriesTests {

    /** A full account: a login name, a display name, an address, and the two attributes correlation needs. */
    private static final String FULL_ACCOUNT =
            """
            dn: uid=a.ferrero,ou=users,dc=lineward,dc=example
            uid: a.ferrero
            cn: Alba Ferrero
            sn: Ferrero
            displayName: Alba Ferrero
            mail: alba.ferrero@lineward.example
            employeeNumber: E-0001
            departmentNumber: Treasury
            entryUUID: 9a850974-6c14-1041-95c3-776d66725057
            createTimestamp: 20261103205510Z""";

    /**
     * Two addresses on one account, which is the shape that decided the SPI
     * holds a map of lists. This is what the directory returned, not a
     * possibility allowed for.
     */
    private static final String TWO_ADDRESSES =
            """
            dn: uid=b.novak,ou=users,dc=lineward,dc=example
            uid: b.novak
            cn: Borna Novak
            sn: Novak
            displayName: Borna Novak
            mail: borna.novak@lineward.example
            mail: b.novak@lineward.example
            employeeNumber: E-0002
            departmentNumber: Engineering
            entryUUID: b84efdc0-6c14-1041-962e-178370dbc62e
            createTimestamp: 20261103205600Z""";

    /** An account the password policy has locked. */
    private static final String LOCKED_ACCOUNT =
            """
            dn: uid=i.sorensen,ou=users,dc=lineward,dc=example
            uid: i.sorensen
            cn: Ingrid Sorensen
            sn: Sorensen
            displayName: Ingrid Sorensen
            mail: ingrid.sorensen@lineward.example
            employeeNumber: E-0009
            departmentNumber: Legal and Compliance
            pwdAccountLockedTime: 20261001090000Z
            entryUUID: 9a86167a-6c14-1041-95cb-776d66725057
            createTimestamp: 20261103205510Z""";

    /**
     * An account named by its common name instead of a login name, with
     * brackets and an apostrophe in it, and with no address at all. A directory
     * that has survived a migration is full of these.
     */
    private static final String NO_LOGIN_NAME =
            """
            dn: cn=K. O'Hara (contractor),ou=users,dc=lineward,dc=example
            cn: K. O'Hara (contractor)
            sn: O'Hara
            departmentNumber: Engineering
            entryUUID: 9a8651bc-6c14-1041-95cd-776d66725057
            createTimestamp: 20261103205510Z""";

    private static final String GROUP =
            """
            dn: cn=dept-treasury,ou=groups,dc=lineward,dc=example
            cn: dept-treasury
            description: Department group for Treasury.
            entryUUID: 9a86b580-6c14-1041-95d4-776d66725057""";

    /** The attribute this directory marks a locked account with. Configured, never assumed. */
    private static final String LOCK_MARKER = "pwdAccountLockedTime";

    private final LdapEntries entries = new LdapEntries();

    @Test
    void reads_everything_a_real_account_carries() {
        DiscoveredAccount account = entries.account(entry(FULL_ACCOUNT), List.of(), LOCK_MARKER);

        // The entry's own identifier and not its distinguished name: moving an
        // account to another organizational unit changes the name and would
        // report one account deleted and another created.
        assertThat(account.nativeId()).isEqualTo("9a850974-6c14-1041-95c3-776d66725057");
        assertThat(account.username()).isEqualTo("a.ferrero");
        assertThat(account.email()).isEqualTo("alba.ferrero@lineward.example");
        assertThat(account.displayName()).isEqualTo("Alba Ferrero");
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.createdInTargetAt()).isEqualTo(Instant.parse("2026-11-03T20:55:10Z"));
        assertThat(account.attributes().get("employeeNumber")).containsExactly("E-0001");
        assertThat(account.attributes().get("departmentNumber")).containsExactly("Treasury");
    }

    @Test
    void keeps_every_value_of_a_multi_valued_attribute() {
        DiscoveredAccount account = entries.account(entry(TWO_ADDRESSES), List.of(), LOCK_MARKER);

        // The field holds one address because the SPI has one field, so the map
        // has to hold both or the second address is lost in silence.
        assertThat(account.email()).isEqualTo("borna.novak@lineward.example");
        assertThat(account.attributes().get("mail"))
                .containsExactly("borna.novak@lineward.example", "b.novak@lineward.example");
    }

    /**
     * A directory has no switched off flag of its own. What it has is the lock
     * the password policy keeps, and {@code LOCKED} is the status the SPI
     * already has for exactly that, so reporting {@code DISABLED} would say
     * somebody made a decision about this account when nobody did.
     */
    @Test
    void reads_a_locked_account_as_locked_and_not_as_disabled() {
        assertThat(entries.account(entry(LOCKED_ACCOUNT), List.of(), LOCK_MARKER).status())
                .isEqualTo(AccountStatus.LOCKED);
    }

    @Test
    void treats_an_unconfigured_lock_marker_as_unknown_and_not_as_active() {
        assertThat(entries.account(entry(FULL_ACCOUNT), List.of(), null).status())
                .isEqualTo(AccountStatus.UNKNOWN);
        assertThat(entries.account(entry(LOCKED_ACCOUNT), List.of(), null).status())
                .isEqualTo(AccountStatus.UNKNOWN);
    }

    @Test
    void names_an_account_by_its_relative_name_when_the_directory_gives_it_no_login_name() {
        DiscoveredAccount account = entries.account(entry(NO_LOGIN_NAME), List.of(), LOCK_MARKER);

        assertThat(account.username()).isEqualTo("K. O'Hara (contractor)");
        assertThat(account.email()).isNull();
        assertThat(account.displayName()).isEqualTo("K. O'Hara (contractor)");
    }

    /**
     * OpenLDAP reports no last use on an entry, verified on 2.6.10: it would
     * take the access log overlay, which this directory does not configure.
     * Absent has to read as "cannot tell", because filling it in would report
     * the whole directory as dormant.
     */
    @Test
    void leaves_the_last_activity_unset_because_the_directory_does_not_report_it() {
        assertThat(entries.account(entry(FULL_ACCOUNT), List.of(), LOCK_MARKER).lastActivityAt())
                .isNull();
    }

    @Test
    void refuses_an_account_with_no_stable_identifier() {
        String withoutUuid = FULL_ACCOUNT.replace("entryUUID: 9a850974-6c14-1041-95c3-776d66725057\n", "");

        assertThatThrownBy(() -> entries.account(entry(withoutUuid), List.of(), LOCK_MARKER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be correlated");
    }

    /**
     * A date nobody can read is not a reason to drop an account. The account is
     * the finding; the date is evidence that happens to be unreadable.
     */
    @Test
    void leaves_the_creation_date_unset_when_the_directory_reports_one_it_cannot_parse() {
        String broken = FULL_ACCOUNT.replace("createTimestamp: 20261103205510Z", "createTimestamp: the-first-of-never");

        assertThat(entries.account(entry(broken), List.of(), LOCK_MARKER).createdInTargetAt())
                .isNull();
    }

    @Test
    void holds_the_memberships_it_is_given_and_an_empty_list_when_there_are_none() {
        List<String> groups = List.of(
                "cn=all-staff,ou=groups,dc=lineward,dc=example",
                "cn=dept-treasury,ou=groups,dc=lineward,dc=example");

        assertThat(entries.account(entry(FULL_ACCOUNT), groups, LOCK_MARKER).entitlementValues())
                .containsExactlyElementsOf(groups);
        assertThat(entries.account(entry(NO_LOGIN_NAME), List.of(), LOCK_MARKER).entitlementValues())
                .isEmpty();
    }

    /**
     * The distinguished name and not the common name. V003 seeded this
     * catalogue with distinguished names for the same reason: a name is what a
     * connector sends, and two groups in different parts of a tree can share a
     * common name.
     */
    @Test
    void names_an_entitlement_by_its_distinguished_name_and_shows_its_common_name() {
        DiscoveredEntitlement entitlement = entries.entitlement(entry(GROUP));

        assertThat(entitlement.nativeValue()).isEqualTo("cn=dept-treasury,ou=groups,dc=lineward,dc=example");
        assertThat(entitlement.displayName()).isEqualTo("dept-treasury");
        assertThat(entitlement.description()).isEqualTo("Department group for Treasury.");
    }

    /**
     * The list of attributes a read asks the directory for, pinned here because
     * it is a control and not a detail.
     *
     * <p>Two things must stay true of it. It must name the two operational
     * attributes, because a directory serves those only when asked by name and
     * an account without {@code entryUUID} cannot be correlated at all. And it
     * must never name a password attribute, nor be replaced by "give me
     * everything", because the next directory somebody points this connector at
     * may be willing to serve one.
     */
    @Test
    void asks_the_directory_for_named_attributes_and_never_for_a_password() {
        assertThat(LdapDirectory.ACCOUNT_ATTRIBUTES)
                .contains(LdapEntries.UUID_ATTRIBUTE, LdapEntries.CREATED_ATTRIBUTE)
                .doesNotContain("userPassword", "*", "+")
                .allSatisfy(attribute -> assertThat(attribute).isNotBlank());
    }

    /**
     * Reads the LDIF above the way {@code ldapsearch} prints it, so that the
     * test data can be pasted from a terminal and stay comparable to it.
     *
     * <p>Rejects a base64 value rather than decoding one. No attribute this
     * connector asks for is returned that way, and a helper that guessed would
     * turn a value it misread into a test that passes.
     */
    private static DirectoryEntry entry(String ldif) {
        String dn = null;
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        for (String line : ldif.lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (line.contains(":: ")) {
                throw new IllegalArgumentException("base64 in test data, which this helper will not guess at: " + line);
            }
            int colon = line.indexOf(": ");
            if (colon < 0) {
                throw new IllegalArgumentException("not an LDIF line: " + line);
            }
            String name = line.substring(0, colon);
            String value = line.substring(colon + 2);
            if (name.equals("dn")) {
                dn = value;
            } else {
                attributes.computeIfAbsent(name, any -> new ArrayList<>()).add(value);
            }
        }
        return new DirectoryEntry(dn, attributes);
    }
}
