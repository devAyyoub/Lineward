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
package com.lineward.identity.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decides whether a source is saying something new.
 *
 * <p>Stored in {@code source_record.payload_hash}, so re-reading an extract is
 * one comparison per record instead of a walk over every field.
 *
 * <p>Two things about what goes into the hash, and the second one is the whole
 * reason this class exists rather than a call to {@code String.hashCode}.
 *
 * <p>The field order is normalised, because a source is free to serialise the
 * same record with its keys in a different order and that is not a change.
 *
 * <p>The source's own modification timestamp is excluded. Some HR systems
 * re-stamp every record on a nightly batch without changing anything in it. With
 * the timestamp inside the hash, every one of those records would be stored
 * again and reported as changed, and the next task of phase 1 would read that as
 * a mover: five thousand access recalculations triggered by a batch job. That is
 * the same failure {@code source_record} exists to prevent, one level down. The
 * verbatim payload still keeps the timestamp, so nothing is lost; the hash
 * answers a narrower question, which is whether the source changed what it says
 * about this person.
 */
class PayloadFingerprint {

    /**
     * The source's bookkeeping, not its assertion about the person. The name is
     * the HR system's; a second source with a different name for it gets its own
     * fingerprint, which is the point at which this becomes a property of the
     * source rather than a constant here.
     */
    private static final String SOURCE_TIMESTAMP_FIELD = "lastModifiedAt";

    private final JsonMapper json;

    PayloadFingerprint(JsonMapper json) {
        this.json = json;
    }

    byte[] of(String rawPayload) {
        JsonNode root = json.readTree(rawPayload);

        // A TreeMap, so the bytes fed to the digest do not depend on the order
        // the source happened to write its fields in. Values keep their JSON
        // form, which keeps the string "1" distinct from the number 1.
        Map<String, JsonNode> canonical = new TreeMap<>();
        for (Map.Entry<String, JsonNode> field : root.properties()) {
            if (!SOURCE_TIMESTAMP_FIELD.equals(field.getKey())) {
                canonical.put(field.getKey(), field.getValue());
            }
        }

        return sha256(json.writeValueAsString(canonical));
    }

    private static byte[] sha256(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every Java platform implementation.
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
