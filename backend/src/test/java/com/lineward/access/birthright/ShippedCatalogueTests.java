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

import static org.assertj.core.api.Assertions.assertThat;

import com.lineward.shared.TenantTransactions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The seeded catalogue against what the shipped target systems actually publish.
 *
 * <p>This is the check nobody had, and the gap cost a defect that lived in the
 * repository for eleven days. {@code ExpectedAccessTests} already proves that
 * every birthright rule grants something the catalogue registers, so the rules
 * and the migration cannot disagree. Both of them could still disagree with the
 * target system, and did: V003 named an intranet role {@code employee} while the
 * Keycloak connector reads {@code /employee}, which is the same permission under
 * two names. Nothing failed, because nothing compared them.
 *
 * <p>What a mismatch costs, which is why this is a test and not a note. An
 * entitlement in the catalogue that no target system publishes is a grant
 * Lineward can never fulfil, and once KAN-12 reconciles expected against real it
 * is worse than useless: every birthright grant reads as missing in the target
 * system and every group of the target system reads as unexpected. The first
 * drift report would have been false positives end to end.
 *
 * <p>The direction of the assertion is deliberate. Seeded values must be
 * published; published values need not be seeded. A target system holding a
 * group Lineward does not register is normal, and discovering those is what
 * aggregation is for.
 */
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ShippedCatalogueTests {

    private static final String OWNER_USER = "lineward_owner";
    private static final String OWNER_PASSWORD = "lineward_owner_local";
    private static final String APP_USER = "lineward_app";
    private static final String APP_PASSWORD = "lineward_app_local";

    /** Inserted by V002, so the test reads the catalogue of the installation's organization. */
    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    /**
     * The configuration this repository ships, read from the files and not from
     * a copy in this test. A copy would agree with whatever it was copied from
     * on the day it was written.
     */
    private static final Path REALM_FILE = Path.of("../infra/keycloak/import/lineward-realm.json");

    private static final Path DIRECTORY_TREE = Path.of("../infra/ldap/import/01-lineward-tree.ldif");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6")
            .withInitScript("db/test-roles.sql");

    private static JdbcClient jdbc;
    private static TenantTransactions tenants;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .placeholders(Map.of("app_role", APP_USER))
                .load()
                .migrate();

        DataSource appDataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
        jdbc = JdbcClient.create(appDataSource);
        tenants = new TenantTransactions(new JdbcTransactionManager(appDataSource), jdbc);
    }

    @Test
    void names_every_intranet_role_the_way_keycloak_reports_it() {
        Set<String> published = groupPathsOfTheShippedRealm();

        assertThat(published).as("the shipped realm has groups to compare against").isNotEmpty();
        assertThat(seededValuesOf("intranet"))
                .isNotEmpty()
                .allSatisfy(seeded -> assertThat(published)
                        .as("V003 registers intranet role '%s', which the shipped realm does not"
                                + " publish. A connector reads the group's path, so a catalogue"
                                + " naming it any other way grants something that cannot be"
                                + " fulfilled", seeded)
                        .contains(seeded));
    }

    @Test
    void names_every_directory_group_the_way_the_directory_holds_it() {
        Set<String> published = groupNamesOfTheShippedDirectory();

        assertThat(published).as("the shipped directory has groups to compare against").isNotEmpty();
        assertThat(seededValuesOf("corporate-directory"))
                .isNotEmpty()
                .allSatisfy(seeded -> assertThat(published)
                        .as("V003 registers directory group '%s', which the shipped tree does not"
                                + " hold. A connector reads the group's distinguished name, so a"
                                + " catalogue naming it any other way grants something that cannot"
                                + " be fulfilled", seeded)
                        .contains(seeded));
    }

    /**
     * The native values of one application, as the catalogue holds them.
     *
     * <p>Read through the application's code and not its id, because the code is
     * what a rule, a connector and a human all use, and an id would tie this
     * test to the order the migration inserted rows in.
     */
    private Set<String> seededValuesOf(String applicationCode) {
        return tenants.inTenant(ORG, () -> new LinkedHashSet<>(jdbc
                .sql("""
                        SELECT e.native_value
                          FROM entitlement e
                          JOIN application a ON a.org_id = e.org_id AND a.id = e.application_id
                         WHERE e.org_id = :orgId
                           AND a.code = :code
                         ORDER BY e.native_value
                        """)
                .param("orgId", ORG)
                .param("code", applicationCode)
                .query(String.class)
                .list()));
    }

    /**
     * Every group path of the shipped realm, built the way Keycloak builds one:
     * a leading slash and a segment per level.
     *
     * <p>The nesting matters and is the reason this walks the tree instead of
     * reading names. A realm can hold {@code /payment-approve} and
     * {@code /intranet/payment-approve} at once, and they are two different
     * permissions whose leaf name is the same string.
     */
    private static Set<String> groupPathsOfTheShippedRealm() {
        String document = read(REALM_FILE, Files::readString);
        JsonNode realm = JsonMapper.builder().build().readTree(document);
        Set<String> paths = new LinkedHashSet<>();
        collectPaths(realm.path("groups"), "", paths);
        return paths;
    }

    private static void collectPaths(JsonNode groups, String prefix, Set<String> into) {
        for (JsonNode group : groups) {
            String path = prefix + "/" + group.path("name").asString();
            into.add(path);
            collectPaths(group.path("subGroups"), path, into);
        }
    }

    /**
     * Every group of the shipped directory, by distinguished name.
     *
     * <p>Reads the LDIF rather than starting a server: what is under test is
     * whether two files in this repository agree, and a running directory would
     * only hand back what this file put in it.
     */
    private static Set<String> groupNamesOfTheShippedDirectory() {
        List<String> lines = read(DIRECTORY_TREE, file -> Files.readAllLines(file));
        Set<String> groups = new LinkedHashSet<>();
        List<String> entry = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                addIfGroup(entry, groups);
                entry = new ArrayList<>();
            } else if (!line.startsWith("#")) {
                entry.add(line);
            }
        }
        addIfGroup(entry, groups);
        return groups;
    }

    /**
     * An entry counts as a group when it says so itself, by object class. Going
     * by the organizational unit it sits in would pass a test against a tree
     * that had put an account under {@code ou=groups}.
     */
    private static void addIfGroup(List<String> entry, Set<String> into) {
        boolean isGroup = entry.stream().anyMatch(line -> line.equals("objectClass: groupOfNames"));
        if (!isGroup) {
            return;
        }
        entry.stream()
                .filter(line -> line.startsWith("dn: "))
                .map(line -> line.substring("dn: ".length()))
                .forEach(into::add);
    }

    private static <T> T read(Path file, ThrowingReader<T> reader) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("no file at " + file.toAbsolutePath()
                    + ". This test compares the catalogue against the configuration this repository"
                    + " ships; run it from the backend module directory, which is where Maven puts"
                    + " the working directory");
        }
        try {
            return reader.read(file);
        } catch (IOException unreadable) {
            throw new IllegalStateException("could not read " + file.toAbsolutePath(), unreadable);
        }
    }

    private interface ThrowingReader<T> {
        T read(Path file) throws IOException;
    }
}
