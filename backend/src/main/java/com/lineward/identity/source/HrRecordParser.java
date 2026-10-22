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

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the HR system's JSON into asserted records.
 *
 * <p>Separate from the reader that fetches it, for two reasons. One is that
 * talking HTTP and understanding a payload are different jobs. The other is the
 * lesson of 2026-10-16: sixteen tests passed while the simulator's main
 * operation rejected every real client, because the tests built the request in
 * Java and therefore never sent a message shaped like the ones that arrive. With
 * the parser reachable on its own, a test can feed it text copied from the wire
 * instead of objects built from these same classes.
 *
 * <p>It parses the tree by hand rather than binding to a record, which is not
 * ceremony: the raw text of each element has to survive into
 * {@code source_record.payload}, and a binder throws it away.
 */
class HrRecordParser {

    private final JsonMapper json;

    HrRecordParser(JsonMapper json) {
        this.json = json;
    }

    AssertedPage parsePage(String body) {
        JsonNode root = json.readTree(body);
        JsonNode content = root.path("content");
        if (!content.isArray()) {
            throw new IllegalStateException("the source returned a page with no content array");
        }

        List<AssertedIdentity> records = new ArrayList<>(content.size());
        for (JsonNode element : content) {
            records.add(parseRecord(element));
        }
        return new AssertedPage(
                records,
                root.path("page").asInt(),
                root.path("size").asInt(),
                root.path("totalElements").asInt(),
                root.path("totalPages").asInt());
    }

    /**
     * One record from its text, which is how a test feeds it a payload copied
     * from the wire instead of one built from these same classes.
     */
    AssertedIdentity parseRecord(String body) {
        return parseRecord(json.readTree(body));
    }

    AssertedIdentity parseRecord(JsonNode node) {
        return new AssertedIdentity(
                text(node, "employeeNumber"),
                text(node, "firstName"),
                text(node, "lastName"),
                text(node, "email"),
                text(node, "department"),
                text(node, "jobTitle"),
                text(node, "location"),
                text(node, "costCenter"),
                text(node, "employmentType"),
                date(node, "hireDate"),
                date(node, "terminationDate"),
                text(node, "managerEmployeeNumber"),
                modifiedAt(node),
                node.toString());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    private static LocalDate date(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : LocalDate.parse(value);
    }

    /**
     * Required, and the loud failure is the point.
     *
     * <p>A source that will not say when it changed a record cannot support an
     * incremental read. Defaulting to the current instant would be worse than
     * failing: every record would look as if it had just changed, so the first
     * incremental read would return the whole population and the symptom would
     * read as a bug in the reader rather than in the data.
     */
    private static Instant modifiedAt(JsonNode node) {
        String value = text(node, "lastModifiedAt");
        if (value == null) {
            throw new IllegalStateException(
                    "record " + text(node, "employeeNumber") + " has no lastModifiedAt, "
                            + "so an incremental read of this source is not possible");
        }
        return Instant.parse(value);
    }
}
