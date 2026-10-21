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
package dev.ayyoub.lineward.identity.source;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * A source that serves records written as text.
 *
 * <p>It parses them with the same parser the HTTP reader uses, so a test gets
 * the real field mapping without a socket, and the payload it hands over is the
 * text a test wrote rather than a record this project serialised. That is the
 * point: the 2026-10-16 failure was sixteen tests that all built their payload
 * in Java and therefore never sent a message shaped like the ones that arrive.
 *
 * <p>It also pages and filters by modification instant, because those are the
 * two behaviours an ingestion run depends on and a double that ignores them
 * would let a paging bug through.
 */
public class LiteralJsonSourceReader implements AuthoritativeSourceReader {

    private final HrRecordParser parser = new HrRecordParser(JsonMapper.builder().build());
    private final List<String> records = new ArrayList<>();
    private Integer reportedTotal;

    /** Replaces what the source serves. */
    public LiteralJsonSourceReader serving(String... recordJson) {
        records.clear();
        records.addAll(Arrays.asList(recordJson));
        return this;
    }

    /**
     * Makes the source claim a total it does not serve, which is what a dropped
     * connection looks like from the reading end.
     */
    public LiteralJsonSourceReader claimingTotal(Integer total) {
        this.reportedTotal = total;
        return this;
    }

    @Override
    public AssertedPage readPage(int page, int size, Instant changedSince) {
        List<AssertedIdentity> matching = records.stream()
                .map(parser::parseRecord)
                .filter(record -> changedSince == null || record.assertedAt().isAfter(changedSince))
                .toList();

        int total = reportedTotal == null ? matching.size() : reportedTotal;
        int from = Math.min(page * size, matching.size());
        int to = Math.min(from + size, matching.size());
        int totalPages = (int) Math.ceil((double) total / size);

        return new AssertedPage(matching.subList(from, to), page, size, total, totalPages);
    }
}
