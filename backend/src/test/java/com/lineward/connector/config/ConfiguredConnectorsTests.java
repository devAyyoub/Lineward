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
package com.lineward.connector.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lineward.connector.ConnectorType;
import com.lineward.connector.TargetSystemConnector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * What an installation declares about its target systems, turned into
 * connectors.
 *
 * <p>Two things are worth asserting here and neither is about reading a system.
 * The first is that a mistake in this configuration stops the application
 * starting rather than surfacing on the first scheduled run, because that is
 * the difference between a mistake somebody sees and a mistake nobody is
 * watching. The second is that a secret declared here does not come back out in
 * a string: Spring Boot prints configuration properties in more places than
 * anybody remembers, and a record prints all its components by default.
 *
 * <p>The last test reads the configuration this repository actually ships,
 * rather than a copy written here, for the reason {@code ShippedCatalogueTests}
 * gives: a copy agrees with whatever it was copied from on the day it was
 * written.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ConfiguredConnectorsTests {

    private static final ConnectorProperties.Keycloak REALM =
            new ConnectorProperties.Keycloak("http://localhost:8081", "lineward", "lineward-aggregator", "s3cret");

    private static final ConnectorProperties.Ldap DIRECTORY = new ConnectorProperties.Ldap(
            "ldap://localhost:1389",
            "ou=users,dc=lineward,dc=example",
            "ou=groups,dc=lineward,dc=example",
            "cn=lineward-aggregator,ou=services,dc=lineward,dc=example",
            "s3cret",
            "pwdAccountLockedTime");

    @Test
    void builds_the_kind_of_connector_each_target_describes() {
        ConfiguredConnectors connectors = new ConfiguredConnectors(new ConnectorProperties(Map.of(
                "intranet", new ConnectorProperties.Target(REALM, null),
                "corporate-directory", new ConnectorProperties.Target(null, DIRECTORY))));

        assertThat(connectors.forApplication("intranet"))
                .get()
                .extracting(TargetSystemConnector::type)
                .isEqualTo(ConnectorType.KEYCLOAK);
        assertThat(connectors.forApplication("corporate-directory"))
                .get()
                .extracting(TargetSystemConnector::type)
                .isEqualTo(ConnectorType.LDAP);
    }

    /**
     * Empty and not an exception. An installation registers the systems it
     * governs before somebody has been given a credential for each of them, and
     * the caller is the one that knows whether it expected one.
     */
    @Test
    void has_nothing_for_an_application_nobody_configured() {
        ConfiguredConnectors connectors = new ConfiguredConnectors(
                new ConnectorProperties(Map.of("intranet", new ConnectorProperties.Target(REALM, null))));

        assertThat(connectors.forApplication("corporate-directory")).isEmpty();
    }

    /**
     * A target names exactly one kind of system, which is the reason there is no
     * {@code type} field to contradict. Both blocks filled in is the
     * contradiction arriving by another route, and it has to be refused: there
     * would be no answer to which one wins.
     */
    @Test
    void refuses_a_target_that_describes_two_kinds_of_system() {
        assertThatThrownBy(() -> new ConnectorProperties.Target(REALM, DIRECTORY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one kind of system");
    }

    @Test
    void refuses_a_target_that_describes_none() {
        assertThatThrownBy(() -> new ConnectorProperties.Target(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one kind of system");
    }

    /**
     * The one that decides where a mistake is found. Every connector is built
     * while the context refreshes, so a target whose secret was never set
     * refuses here and the application does not start. Built on first use
     * instead, the same mistake would arrive at two in the morning on the first
     * scheduled run.
     */
    @Test
    void refuses_to_start_without_a_credential_instead_of_failing_on_the_first_run() {
        ConnectorProperties withNoSecret = new ConnectorProperties(Map.of(
                "intranet",
                new ConnectorProperties.Target(
                        new ConnectorProperties.Keycloak(
                                "http://localhost:8081", "lineward", "lineward-aggregator", ""),
                        null)));

        assertThatThrownBy(() -> new ConfiguredConnectors(withNoSecret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientSecret");
    }

    /**
     * A credential that only leaks when something goes wrong leaks exactly when
     * somebody is reading. The connectors' own credential types are tested for
     * this; these records hold the same values on their way there.
     */
    @Test
    void never_prints_a_secret() {
        ConnectorProperties properties = new ConnectorProperties(Map.of(
                "intranet", new ConnectorProperties.Target(REALM, null),
                "corporate-directory", new ConnectorProperties.Target(null, DIRECTORY)));

        assertThat(properties.toString())
                .doesNotContain("s3cret")
                .contains("<redacted>")
                .as("the rest has to stay readable, or nobody can tell which target is misconfigured")
                .contains("lineward-aggregator");
    }

    /**
     * The configuration this repository ships, read from the file and bound the
     * way Spring binds it, placeholders and defaults included.
     *
     * <p>What this catches is a typo in {@code application.yaml}: a misspelled
     * key binds to nothing, leaves the field null, and the credential type
     * refuses it at startup with a message about the field rather than about
     * the key. Here it fails in the build instead, next to the file.
     */
    @Test
    void the_configuration_this_repository_ships_builds_both_connectors() {
        ConfiguredConnectors connectors = new ConfiguredConnectors(shippedProperties());

        assertThat(connectors.forApplication("corporate-directory"))
                .get()
                .extracting(TargetSystemConnector::type)
                .isEqualTo(ConnectorType.LDAP);
        assertThat(connectors.forApplication("intranet"))
                .get()
                .extracting(TargetSystemConnector::type)
                .isEqualTo(ConnectorType.KEYCLOAK);
    }

    private static ConnectorProperties shippedProperties() {
        MutablePropertySources sources = new MutablePropertySources();
        shipped().forEach(sources::addLast);
        return new Binder(
                        ConfigurationPropertySources.from(sources),
                        new org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver(
                                sources))
                .bind("lineward.connector", ConnectorProperties.class)
                .orElseThrow(() -> new IllegalStateException(
                        "application.yaml declares no lineward.connector block, so nothing in this"
                                + " installation would ever be aggregated"));
    }

    private static List<PropertySource<?>> shipped() {
        try {
            return new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("could not read the shipped application.yaml", unreadable);
        }
    }
}
