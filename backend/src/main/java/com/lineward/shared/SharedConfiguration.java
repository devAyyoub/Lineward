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
package com.lineward.shared;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The few beans every module needs.
 *
 * <p>The clock is one of them, and it is a bean rather than a call to
 * {@code Instant.now()} so that a test can fix it. Half of what M1 decides is a
 * comparison against today: whether a hire date is still in the future, whether
 * a termination has taken effect. Asserting on that against the real clock means
 * asserting on when the test happened to run.
 *
 * <p>UTC and not the system zone, deliberately. Whether somebody has started
 * working yet must not depend on where the server is.
 */
@Configuration
@EnableConfigurationProperties(TenantProperties.class)
class SharedConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TenantTransactions tenantTransactions(PlatformTransactionManager transactionManager, JdbcClient jdbc) {
        return new TenantTransactions(transactionManager, jdbc);
    }
}
