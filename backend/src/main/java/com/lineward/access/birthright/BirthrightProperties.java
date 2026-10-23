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
package com.lineward.access.birthright;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The birthright rules as the configuration declares them.
 *
 * <p>They live in {@code birthright-rules.yaml}, which {@code application.yaml}
 * imports, rather than inside it. A file of its own is the artefact ADR-013
 * decision 10 is buying: it is what a reviewer reads to know what everybody in
 * this organization holds, and burying it among datasource settings would cost
 * exactly that.
 *
 * @param rules in declaration order, which the file's reader sees and the access
 *     trail follows. An installation with no rules is allowed and grants
 *     nothing, because the alternative is a platform that refuses to start
 *     before anybody has written a rule
 */
@ConfigurationProperties(prefix = "lineward.access.birthright")
record BirthrightProperties(List<BirthrightRule> rules) {

    BirthrightProperties {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }
}
