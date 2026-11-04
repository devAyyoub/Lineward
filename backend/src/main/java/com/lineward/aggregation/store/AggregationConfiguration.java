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
package com.lineward.aggregation.store;

import com.lineward.access.ApplicationLookup;
import com.lineward.access.EntitlementRegistry;
import com.lineward.aggregation.ManualCorrelation;
import com.lineward.aggregation.TargetSystemAggregation;
import com.lineward.connector.TargetSystemConnectors;
import com.lineward.identity.IdentityLookup;
import com.lineward.identity.IdentityResolution;
import com.lineward.shared.TenantProperties;
import com.lineward.shared.TenantTransactions;
import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires M2, and here rather than in a configuration package of its own.
 *
 * <p>Separating them would mean making the run, the writer and the correlation
 * public to be constructed from outside, which is the division section 10.4 of
 * the working document calls wrong: if splitting two classes forces what was
 * internal to become public, the split is in the wrong place. The same
 * reasoning put {@code AccessConfiguration} inside {@code birthright}.
 *
 * <p>Two beans, and they are the module's whole API. One drives a run, the
 * other is the door a person's decision comes through. Everything they are
 * built from stays package private, so nothing else in the application can run
 * half of an aggregation: reading a target system, writing the accounts down
 * and deciding who they belong to are three steps of one operation, and an
 * account written without the third is an orphan that nobody planted.
 */
@Configuration
@EnableConfigurationProperties(AggregationProperties.class)
class AggregationConfiguration {

    @Bean
    TargetSystemAggregation targetSystemAggregation(
            ApplicationLookup applications,
            TargetSystemConnectors connectors,
            EntitlementRegistry catalogue,
            AccountCorrelation correlation,
            JdbcClient jdbc,
            TenantTransactions tenants,
            Clock clock,
            TenantProperties tenant,
            AggregationProperties properties) {
        return new AggregationRunner(
                applications,
                connectors,
                new ApplicationAggregation(
                        catalogue,
                        new AccountWriter(jdbc, JsonMapper.builder().build()),
                        correlation,
                        tenants,
                        clock,
                        tenant.orgId(),
                        properties.pageSize()),
                tenants,
                tenant.orgId());
    }

    /**
     * The rules and the manual door, which are one object.
     *
     * <p>A bean of a package private type, so that only this package can ask
     * for it: the run above and the interface below are the two things that
     * may, and nothing else in the context can name the type to depend on it.
     */
    @Bean
    AccountCorrelation accountCorrelation(
            IdentityResolution identities,
            IdentityLookup people,
            JdbcClient jdbc,
            Clock clock,
            AggregationProperties properties) {
        return new AccountCorrelation(
                jdbc, identities, people, clock, properties.employeeNumberAttribute());
    }

    /**
     * The same object, published as the door a person's decision comes through.
     *
     * <p>Not a second instance: the rules and the manual path write the same
     * four columns and have to agree about which of them a rule may touch, so
     * two objects would be two places that know what a correlation looks like.
     */
    @Bean
    ManualCorrelation manualCorrelation(AccountCorrelation correlation) {
        return correlation;
    }

    /**
     * A development trigger, and nothing more.
     *
     * <p>No HTTP endpoint, for the reason {@code IdentityConfiguration} gives
     * about its own: the platform has no authentication layer, and an
     * unauthenticated endpoint that reads every target system with an
     * administrator's credential is a worse thing to add than a flag somebody
     * has to set on the command line. The schedule and the API arrive with the
     * tasks that need them.
     */
    @Bean
    @ConditionalOnProperty(name = "lineward.aggregation.on-startup", havingValue = "true")
    ApplicationRunner initialAggregation(TargetSystemAggregation aggregation) {
        return args -> aggregation.aggregateEverything();
    }
}
