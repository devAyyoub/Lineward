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

import com.lineward.identity.IdentityLookup;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires the access module, and here rather than in a configuration package of
 * its own.
 *
 * <p>Separating them would mean making the rule types public to be constructed
 * from outside, which is the division section 10.4 of the working document calls
 * wrong: if splitting two classes forces what was internal to become public, the
 * split is in the wrong place. Everything in this package is one thing.
 *
 * <p>One bean and not four. The rules, the catalogue and the writer are built
 * inside it, so nothing else in the application can reach them, and a rule file
 * that cannot be trusted fails here, while the context is refreshing, rather
 * than on the night somebody joins: duplicate ids, a rule with no grants and a
 * rule that became universal by losing its last condition are all rejected by
 * the constructors this method calls.
 *
 * <p>What is not checked at startup is that every grant resolves to an
 * entitlement, because that needs the database and this does not own its
 * readiness. {@code ExpectedAccessTests} checks it against the real schema
 * instead, which is the half of ADR-013 decision 10 that says rules in the
 * repository are tested by the build.
 */
@Configuration
@EnableConfigurationProperties(BirthrightProperties.class)
class AccessConfiguration {

    @Bean
    BirthrightRecalculation birthrightRecalculation(
            IdentityLookup identities, JdbcClient jdbc, Clock clock, BirthrightProperties properties) {
        return new BirthrightRecalculation(
                identities,
                new BirthrightRules(properties.rules()),
                new ExpectedAccessWriter(jdbc, new EntitlementCatalog(jdbc), clock));
    }
}
