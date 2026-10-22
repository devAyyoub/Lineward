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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The payloads here are text, copied from what the HR simulator actually
 * returns, rather than objects built from the project's own classes. That is the
 * lesson of 2026-10-16: a payload built in Java can only ever be the payload
 * this code already knows how to produce.
 */
class PayloadFingerprintTests {

    private static final String E0002 = """
            {"employeeNumber":"E-0002","firstName":"Brais","lastName":"Castro",\
            "email":"brais.castro@lineward.example","department":"Engineering",\
            "jobTitle":"Head of Engineering","location":"Remote","costCenter":"CC-ENG-100",\
            "employmentType":"EMPLOYEE","hireDate":"2018-04-27","terminationDate":null,\
            "managerEmployeeNumber":"E-0001","lastModifiedAt":"2018-04-27T00:00:00Z"}""";

    private final PayloadFingerprint fingerprint = new PayloadFingerprint(JsonMapper.builder().build());

    @Test
    void theSamePayloadFingerprintsTheSame() {
        assertThat(fingerprint.of(E0002)).isEqualTo(fingerprint.of(E0002));
    }

    /** A source is free to order its fields differently, and that is not news. */
    @Test
    void theFieldOrderDoesNotMatter() {
        String reordered = """
                {"lastModifiedAt":"2018-04-27T00:00:00Z","managerEmployeeNumber":"E-0001",\
                "terminationDate":null,"hireDate":"2018-04-27","employmentType":"EMPLOYEE",\
                "costCenter":"CC-ENG-100","location":"Remote","jobTitle":"Head of Engineering",\
                "department":"Engineering","email":"brais.castro@lineward.example",\
                "lastName":"Castro","firstName":"Brais","employeeNumber":"E-0002"}""";

        assertThat(fingerprint.of(reordered)).isEqualTo(fingerprint.of(E0002));
    }

    /**
     * The one that matters. An HR system that re-stamps every record on a
     * nightly batch has not changed anything about anybody, and reading it as a
     * change would mean a mover per employee and an access recalculation behind
     * each one.
     */
    @Test
    void aNewModificationTimestampAloneIsNotAChange() {
        String restamped = E0002.replace("\"lastModifiedAt\":\"2018-04-27T00:00:00Z\"",
                "\"lastModifiedAt\":\"2026-10-21T03:00:00Z\"");

        assertThat(restamped).isNotEqualTo(E0002);
        assertThat(fingerprint.of(restamped)).isEqualTo(fingerprint.of(E0002));
    }

    @Test
    void aChangedAttributeIsAChange() {
        String moved = E0002.replace("\"department\":\"Engineering\"", "\"department\":\"Risk\"");

        assertThat(fingerprint.of(moved)).isNotEqualTo(fingerprint.of(E0002));
    }

    /** A removed email is a change, and the one that breaks correlation later. */
    @Test
    void aClearedAttributeIsAChange() {
        String withoutEmail =
                E0002.replace("\"email\":\"brais.castro@lineward.example\"", "\"email\":null");

        assertThat(fingerprint.of(withoutEmail)).isNotEqualTo(fingerprint.of(E0002));
    }

    /** A string and a number that look alike must not collide. */
    @Test
    void theJsonTypeIsPartOfTheFingerprint() {
        String asText = "{\"costCenter\":\"100\",\"lastModifiedAt\":\"2026-10-01T00:00:00Z\"}";
        String asNumber = "{\"costCenter\":100,\"lastModifiedAt\":\"2026-10-01T00:00:00Z\"}";

        assertThat(fingerprint.of(asText)).isNotEqualTo(fingerprint.of(asNumber));
    }
}
