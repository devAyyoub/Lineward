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

import com.lineward.connector.DiscoveredAccount;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.naming.ldap.Rdn;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * Properties of how a directory entry becomes an account, over generated
 * entries. Seeded, so a failure names its case and reproduces; see
 * {@code HrRecordParserPropertiesTests} for why there is no library.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LdapEntriesPropertiesTests {

    private static final long SEED = 20261104L;
    private static final int CASES = 1_000;

    private final LdapEntries entries = new LdapEntries();

    /**
     * Any value a directory can file an entry under, escaped the way RFC 4514
     * says, comes back as the name the account is known by when it has no
     * login name. Written two ways, both allowed by RFC 4514: by the JDK's own
     * {@link Rdn#escapeValue}, which escapes with a backslash and the
     * character, and by a writer that escapes every character that is not a
     * letter or a digit as hexadecimal UTF-8 bytes. Measured against writers
     * independent of the reader, and seen failing against the loop the reader
     * replaced, which read only the first form.
     */
    @Test
    void the_relative_name_of_any_escaped_value_reads_back_as_that_value() {
        Random random = new Random(SEED);
        for (int i = 0; i < CASES; i++) {
            String value = awkwardValue(random);
            String escaped = random.nextBoolean() ? Rdn.escapeValue(value) : hexEscaped(value);
            String dn = "cn=" + escaped + ",ou=users,dc=lineward,dc=example";
            DirectoryEntry entry = new DirectoryEntry(dn, Map.of(LdapEntries.UUID_ATTRIBUTE, List.of("id-" + i)));

            DiscoveredAccount account = entries.account(entry, List.of(), null);

            assertThat(account.username()).as("seed %d, case %d, dn %s", SEED, i, dn).isEqualTo(value);
        }
    }

    /**
     * A creation date the directory reports in any shape is evidence or
     * nothing, and never the reason an account is lost: the account is the
     * finding and the date is a detail about it.
     */
    @Test
    void any_creation_timestamp_is_read_or_left_out_and_never_loses_the_account() {
        Random random = new Random(SEED + 1);
        String alphabet = "0123456789Z+-.,: T";
        for (int i = 0; i < CASES; i++) {
            StringBuilder timestamp = new StringBuilder();
            int length = random.nextInt(24);
            for (int c = 0; c < length; c++) {
                timestamp.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            DirectoryEntry entry = new DirectoryEntry(
                    "uid=x,ou=users,dc=lineward,dc=example",
                    Map.of(LdapEntries.UUID_ATTRIBUTE, List.of("id-" + i),
                            LdapEntries.CREATED_ATTRIBUTE, List.of(timestamp.toString())));

            DiscoveredAccount account = entries.account(entry, List.of(), null);

            assertThat(account.nativeId()).as("seed %d, case %d, timestamp '%s'", SEED + 1, i, timestamp)
                    .isEqualTo("id-" + i);
        }
    }

    /**
     * Every character that is not an ASCII letter or digit, as {@code \XX} per
     * UTF-8 byte, except a final space.
     *
     * <p>The exception is the JDK's and is recorded rather than worked around:
     * on JDK 25, {@code Rdn.unescapeValue("abc\\20")} returns {@code abc},
     * dropping a trailing space that RFC 4514 allows to be escaped that way,
     * while {@code "abc\\ "} keeps it. Measured on 2026-11-04, when this
     * property first ran. The cost is a trailing space in the fallback name of
     * an account with no login name, which is not worth a parser of our own.
     */
    private static String hexEscaped(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == ' ' && i == value.length() - 1) {
                escaped.append("\\ ");
            } else if (character < 128 && Character.isLetterOrDigit(character)) {
                escaped.append(character);
            } else {
                for (byte b : String.valueOf(character).getBytes(StandardCharsets.UTF_8)) {
                    escaped.append('\\').append(String.format("%02X", b & 0xFF));
                }
            }
        }
        return escaped.toString();
    }

    /** Values with every character RFC 4514 makes special, in every position, leading and trailing spaces included. */
    private static String awkwardValue(Random random) {
        String alphabet = "ab Z9,+\"\\<>;=#.-()'\u00e9\u4e2d";
        StringBuilder value = new StringBuilder();
        int length = 1 + random.nextInt(16);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }
}
