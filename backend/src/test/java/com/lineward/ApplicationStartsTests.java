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
package com.lineward;

import static org.assertj.core.api.Assertions.assertThat;

import com.lineward.aggregation.ManualCorrelation;
import com.lineward.aggregation.TargetSystemAggregation;
import com.lineward.connector.TargetSystemConnectors;
import com.lineward.shared.TenantProperties;
import com.lineward.shared.TenantTransactions;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The application starts, with the configuration this repository ships.
 *
 * <p>Nothing proved this before, and every other test in the project builds its
 * collaborators by hand: a bean that cannot be created, a property that does not
 * bind and a module whose wiring asks for something nobody declares are all
 * invisible to them and fatal on the first start. {@code ModularityTests} reads
 * the module graph without starting a context, so it does not see any of it
 * either.
 *
 * <p>No target system runs here and none needs to. Both connectors are built
 * while the context refreshes, which is where a missing credential is supposed
 * to be refused, and neither constructor opens a connection: the configuration
 * is validated without touching anybody's network. That is a property of the
 * design worth having a test notice.
 */
@SpringBootTest
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApplicationStartsTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6")
            .withInitScript("db/test-roles.sql");

    /**
     * Only the address. The two roles, their passwords and every other setting
     * are the shipped ones on purpose: overriding them would test a
     * configuration that no installation uses.
     */
    @DynamicPropertySource
    static void pointAtTheContainer(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    }

    @Autowired
    private TargetSystemAggregation aggregation;

    @Autowired
    private ManualCorrelation manualCorrelation;

    @Autowired
    private TargetSystemConnectors connectors;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TenantTransactions tenants;

    @Autowired
    private TenantProperties tenant;

    @Autowired
    private ApplicationContext context;

    @Test
    void wires_the_aggregation_and_both_of_the_connectors_it_reads_with() {
        assertThat(aggregation).isNotNull();
        assertThat(manualCorrelation).isNotNull();
        assertThat(connectors.forApplication("intranet")).as("the realm this repository ships").isPresent();
        assertThat(connectors.forApplication("corporate-directory"))
                .as("the directory this repository ships")
                .isPresent();
    }

    /**
     * Starting the application does not go and read anybody's systems.
     *
     * <p>The trigger that would is off by default, and this is what says so. An
     * aggregation reads a target system with an administrator's credential and
     * writes what it finds, and a platform that did that merely by being started
     * would be impossible to run anywhere near production by accident.
     *
     * <p>Through {@link TenantTransactions} and not as a plain query, because
     * the running application cannot read this table without declaring which
     * organization it is asking about: the policy calls
     * {@code current_setting('app.current_org')} with no default, so the query
     * fails rather than returning somebody else's rows. The first version of
     * this test asked directly and was refused, which is the isolation of
     * ADR-011 working in the assembled application rather than in a test that
     * built the pieces itself.
     */
    @Test
    void reads_nothing_until_somebody_asks_it_to() {
        assertThat(context.getBeanNamesForType(ApplicationRunner.class))
                .as("the development triggers are conditional on a property, and both are off by default")
                .isEmpty();

        int accounts = tenants.inTenant(tenant.orgId(), () -> jdbc.sql("SELECT count(*) FROM account")
                .query(Integer.class)
                .single());

        assertThat(accounts)
                .as("so nothing is in the table an aggregation writes")
                .isZero();
    }
}
