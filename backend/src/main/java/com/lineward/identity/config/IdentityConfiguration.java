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
package com.lineward.identity.config;

import com.lineward.identity.IdentityIngestion;
import com.lineward.identity.IdentityLookup;
import com.lineward.identity.ingest.HrIngestionRunner;
import com.lineward.identity.query.JdbcIdentityLookup;
import com.lineward.identity.source.AuthoritativeSourceReader;
import com.lineward.identity.source.HrApiSourceReader;
import com.lineward.shared.TenantProperties;
import com.lineward.shared.TenantTransactions;
import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires the identity module.
 *
 * <p>The properties are enabled here rather than on the application class, so
 * that the entry point of the monolith stays ignorant of what each module
 * configures. A module that needs a setting adds it next to itself.
 */
@Configuration
@EnableConfigurationProperties(HrSourceProperties.class)
class IdentityConfiguration {

    @Bean
    AuthoritativeSourceReader hrSourceReader(HrSourceProperties properties) {
        return new HrApiSourceReader(properties.baseUrl());
    }

    /**
     * The read side of M1, which the modules that decide access ask instead of
     * querying the identity table themselves (ADR-001).
     */
    @Bean
    IdentityLookup identityLookup(JdbcClient jdbc) {
        return new JdbcIdentityLookup(jdbc);
    }

    @Bean
    IdentityIngestion identityIngestion(
            AuthoritativeSourceReader source,
            JdbcClient jdbc,
            TenantTransactions tenants,
            Clock clock,
            ApplicationEventPublisher events,
            TenantProperties tenant,
            HrSourceProperties properties) {
        return HrIngestionRunner.create(
                source,
                jdbc,
                tenants,
                clock,
                events,
                tenant.orgId(),
                properties.sourceSystemCode(),
                properties.pageSize());
    }

    /**
     * A development trigger, and nothing more.
     *
     * <p>No HTTP endpoint for this yet, on purpose. The platform has no
     * authentication layer at all, and an unauthenticated endpoint that writes
     * identity data is a worse thing to add than a flag somebody has to set on
     * the command line. The periodic schedule and the API arrive with the tasks
     * that need them.
     */
    @Bean
    @ConditionalOnProperty(name = "lineward.identity.hr.ingest-on-startup", havingValue = "true")
    ApplicationRunner initialHrIngestion(IdentityIngestion ingestion) {
        return args -> ingestion.ingestEverything();
    }
}
