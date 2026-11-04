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
package com.lineward.identity.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Properties of the HR record parser, checked over generated records rather
 * than over the handful a person thinks to write.
 *
 * <p>Generated with a fixed seed per run, so a failure names the seed and the
 * case and reproduces exactly. No property testing library, because the
 * project adds none without approval, and a loop over a seeded {@link Random}
 * is enough for what is asserted here: two properties, each over a thousand
 * records.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HrRecordParserPropertiesTests {

    private static final long SEED = 20261104L;
    private static final int CASES = 1_000;

    private static final List<String> TEXT_FIELDS = List.of(
            "employeeNumber", "firstName", "lastName", "email", "department", "jobTitle", "location",
            "costCenter", "employmentType", "managerEmployeeNumber");

    private final JsonMapper json = JsonMapper.builder().build();
    private final HrRecordParser parser = new HrRecordParser(json);

    /**
     * Whatever text the source puts in a field is the text Lineward reads, and
     * the stored payload reads back as the same record. Quotes, backslashes,
     * control characters and characters outside the basic plane included,
     * because the payload is stored and parsed again later by
     * {@code interpret}, and a value that changed on the way would be a change
     * nobody made.
     */
    @Test
    void any_text_in_a_well_formed_record_is_read_back_exactly() {
        Random random = new Random(SEED);
        for (int i = 0; i < CASES; i++) {
            Map<String, String> values = new LinkedHashMap<>();
            ObjectNode record = json.createObjectNode();
            for (String field : TEXT_FIELDS) {
                String value = field.equals("employeeNumber") ? "E-" + i + awkwardText(random) : awkwardText(random);
                values.put(field, value);
                record.put(field, value);
            }
            LocalDate hired = LocalDate.of(2000, 1, 1).plusDays(random.nextInt(10_000));
            record.put("hireDate", hired.toString());
            Instant modified = Instant.ofEpochSecond(1_600_000_000L + random.nextInt(100_000_000));
            record.put("lastModifiedAt", modified.toString());

            AssertedIdentity read = parser.parseRecord(record.toString());
            String context = "seed " + SEED + ", case " + i + ": " + record;

            assertThat(read.externalId()).as(context).isEqualTo(values.get("employeeNumber"));
            assertThat(read.firstName()).as(context).isEqualTo(values.get("firstName"));
            assertThat(read.department()).as(context).isEqualTo(values.get("department"));
            assertThat(read.managerExternalId()).as(context).isEqualTo(values.get("managerEmployeeNumber"));
            assertThat(read.hireDate()).as(context).isEqualTo(hired);
            assertThat(read.assertedAt()).as(context).isEqualTo(modified);
            assertThat(parser.parseRecord(read.rawPayload())).as(context).isEqualTo(read);
        }
    }

    /**
     * A record with any one field replaced by a value of any JSON type is
     * either read or refused, and refused with an exception that says the
     * input was wrong. Never a {@code NullPointerException} or a
     * {@code ClassCastException}, which would say the parser was wrong and
     * which nothing upstream knows to treat as a bad record.
     */
    @Test
    void any_value_of_any_type_in_any_field_is_read_or_refused_as_input() {
        Random random = new Random(SEED + 1);
        List<String> fields = new java.util.ArrayList<>(TEXT_FIELDS);
        fields.addAll(List.of("hireDate", "terminationDate", "lastModifiedAt"));

        for (int i = 0; i < CASES; i++) {
            ObjectNode record = json.createObjectNode();
            record.put("employeeNumber", "E-" + i);
            record.put("lastModifiedAt", "2026-10-15T00:00:00Z");
            String field = fields.get(random.nextInt(fields.size()));
            putAnything(record, field, random);
            String context = "seed " + (SEED + 1) + ", case " + i + ": " + record;

            try {
                parser.parseRecord(record.toString());
            } catch (IllegalStateException | IllegalArgumentException | DateTimeException refusedAsInput) {
                assertThat(refusedAsInput.getMessage()).as(context).isNotBlank();
            } catch (RuntimeException parserBug) {
                throw new AssertionError(context, parserBug);
            }
        }
    }

    private static void putAnything(ObjectNode record, String field, Random random) {
        switch (random.nextInt(8)) {
            case 0 -> record.putNull(field);
            case 1 -> record.put(field, random.nextLong());
            case 2 -> record.put(field, random.nextDouble());
            case 3 -> record.put(field, random.nextBoolean());
            case 4 -> record.putArray(field).add(awkwardText(random));
            case 5 -> record.putObject(field).put("value", awkwardText(random));
            case 6 -> record.put(field, awkwardText(random));
            default -> record.put(field, "2026-13-45");
        }
    }

    /** Text with the characters that break hand written escaping, in random amounts. */
    private static String awkwardText(Random random) {
        String alphabet = "aZ09 -_.,;:'\"\\/{}[]()<>&%$#@!?*+=\t\n\u0000éñ中ا";
        StringBuilder text = new StringBuilder();
        int length = 1 + random.nextInt(24);
        for (int i = 0; i < length; i++) {
            if (random.nextInt(20) == 0) {
                text.appendCodePoint(0x1F600 + random.nextInt(50));
            } else {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
        }
        return text.toString();
    }
}
