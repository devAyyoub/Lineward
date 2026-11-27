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
package com.lineward.aggregation.reconcile;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long an account may go unused before Lineward says so.
 *
 * @param dormantAfter how long without recorded activity makes an account
 *                     dormant. Ninety days by default, which is the figure the
 *                     working document's glossary uses and the one auditors
 *                     ask about; configuration rather than a constant because
 *                     it is an organization's policy and not a property of the
 *                     software. One period for the installation and not one per
 *                     application, for the same reason the employee number
 *                     attribute is one: a second kind of answer is what would
 *                     make it per application, and nobody has needed one
 * @param onStartup    runs a reconciliation when the application starts. Off by
 *                     default. It exists so the pass can be driven end to end
 *                     against the real database before there is an API or a
 *                     schedule to drive it, which is why the aggregation and
 *                     the HR ingestion each have one
 */
@ConfigurationProperties(prefix = "lineward.reconciliation")
record ReconciliationProperties(Duration dormantAfter, boolean onStartup) {

    ReconciliationProperties {
        // Zero or less would make every account that was used a moment ago
        // dormant, which is the shape of a misconfiguration that produces
        // thousands of findings rather than none, and is therefore the one
        // worth refusing at startup instead of at three in the morning.
        if (dormantAfter == null || dormantAfter.isZero() || dormantAfter.isNegative()) {
            throw new IllegalArgumentException(
                    "lineward.reconciliation.dormant-after must be a positive period, got " + dormantAfter);
        }
    }
}
