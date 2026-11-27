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

import com.lineward.access.AccessExpectation;
import com.lineward.access.ApplicationLookup;
import com.lineward.aggregation.Reconciliation;
import com.lineward.shared.TenantProperties;
import com.lineward.shared.TenantTransactions;
import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires the reconciliation half of M2.
 *
 * <p>Its own configuration and its own package, unlike the aggregation's, and
 * the test of section 10.4 is what decides it: splitting these two does not
 * force anything internal to become public. The reconciliation needs nothing
 * from {@code store} and {@code store} needs nothing from here, because they
 * meet in the database and not in a method call. What they share is the module
 * boundary and the tables M2 owns.
 *
 * <p>One bean, which is the whole API of this package. Everything it is built
 * from stays package private, so nothing else in the application can run half a
 * reconciliation: deciding what is wrong and writing down what is wrong are two
 * steps of one operation, and a caller that could do the first without the
 * second would produce a report nobody can act on afterwards.
 */
@Configuration
@EnableConfigurationProperties(ReconciliationProperties.class)
class ReconciliationConfiguration {

    @Bean
    Reconciliation reconciliation(
            ApplicationLookup applications,
            AccessExpectation expectation,
            JdbcClient jdbc,
            TenantTransactions tenants,
            Clock clock,
            TenantProperties tenant,
            ReconciliationProperties properties) {
        return new ReconciliationRunner(
                applications,
                expectation,
                new AccountConditions(jdbc),
                new GrantDrift(jdbc),
                new FindingLedger(jdbc),
                tenants,
                clock,
                tenant.orgId(),
                properties.dormantAfter());
    }

    /**
     * A development trigger, and nothing more, for the reason
     * {@code AggregationConfiguration} gives about its own: the platform has no
     * authentication layer, and an unauthenticated endpoint that lists every
     * account in the organization nobody is accountable for is a worse thing to
     * add than a flag somebody has to set on the command line.
     *
     * <p><b>Ordered last of the three, and the annotation is the whole of it.</b>
     * Spring runs {@code ApplicationRunner} beans in no particular order unless
     * they declare one, and on a fresh installation this ran first: it skipped
     * all three systems for want of a read, and the ingestion and the
     * aggregation then happened behind it. Nothing was damaged, because
     * declining to reconcile what has not been read is exactly what the runner
     * does, but the trigger reported nothing and the javadoc here claimed an
     * order that did not exist. Seen on 2026-11-27 and written down in section
     * 14 of the working document.
     */
    @Bean
    @ConditionalOnProperty(name = "lineward.reconciliation.on-startup", havingValue = "true")
    @Order(3)
    ApplicationRunner initialReconciliation(Reconciliation reconciliation) {
        return args -> reconciliation.reconcileEverything();
    }
}
