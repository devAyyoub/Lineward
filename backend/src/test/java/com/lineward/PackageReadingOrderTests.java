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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Every package says what it is, and every package with more than one type says
 * where to start reading it.
 *
 * <p>Why this is a test and not a convention in the project document: Ayyoub
 * opened {@code connector} on 2026-10-26, saw ten files and could not tell
 * which one was the way in. A rule written down is a rule that holds until
 * someone is in a hurry; a rule that breaks the build holds.
 *
 * <p>What it deliberately does not check is whether the order is any good. It
 * checks that somebody was made to think about it, which is the part a machine
 * can do.
 *
 * <p>It reads the source tree rather than the compiled classes, because a
 * package's Javadoc is not retained in the bytecode and {@code package-info}
 * only produces a class file when it carries an annotation.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PackageReadingOrderTests {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/lineward");

    /** How the application's own package is named in a failure message, since relativising it gives the empty string. */
    private static final String ROOT = "(root)";

    /** The marker the reading order starts with, kept in one place so the test and the packages agree. */
    private static final String READING_ORDER = "<p>Read in this order:";

    /**
     * Packages excused from the reading order, with the reason.
     *
     * <p>Only the application root, which holds the entry point and nothing
     * else, so an order of one step would say nothing. Its
     * {@code package-info} is still required, and is the map of the modules. A
     * module that is only a placeholder has no types to order and is not
     * excused, it simply does not qualify.
     */
    private static final List<String> WITHOUT_READING_ORDER = List.of(ROOT);

    /**
     * Guards the guard. Pointing the test at a path that does not exist, or at
     * one that no longer holds the source, would leave every assertion below
     * iterating an empty list and passing. A test that cannot fail is worse
     * than no test, because it reports a property nobody is checking.
     */
    @Test
    void finds_the_source_tree_it_is_supposed_to_be_reading() {
        assertThat(SOURCE_ROOT)
                .as("run from the backend module directory, where Maven puts the working directory")
                .isDirectory();
        assertThat(packagesWithTypes().stream().map(PackageUnderTest::name).toList())
                .as("these four have held code since phase 1 opened, so finding none of them means the walk is looking"
                        + " at the wrong tree rather than that the tree is clean")
                .contains(ROOT, "connector", "identity", "shared");
    }

    @Nested
    class Every_populated_package {

        @Test
        void has_a_package_info() {
            List<String> missing = packagesWithTypes().stream()
                    .filter(each -> !Files.exists(each.directory().resolve("package-info.java")))
                    .map(PackageUnderTest::name)
                    .toList();

            assertThat(missing)
                    .as("a package with types in it and no package-info.java gives a reader no way in")
                    .isEmpty();
        }

        @Test
        void says_where_to_start_reading_when_it_holds_more_than_one_type() {
            List<String> missing = packagesWithTypes().stream()
                    .filter(each -> each.typeCount() > 1)
                    .filter(each -> !WITHOUT_READING_ORDER.contains(each.name()))
                    .filter(each -> !each.packageInfo().contains(READING_ORDER))
                    .map(PackageUnderTest::name)
                    .toList();

            assertThat(missing)
                    .as("expected the Javadoc of each of these to contain \"%s\"", READING_ORDER)
                    .isEmpty();
        }

        @Test
        void names_as_many_steps_as_it_promises() {
            List<String> malformed = packagesWithTypes().stream()
                    .filter(each -> each.packageInfo().contains(READING_ORDER))
                    .filter(each -> !each.packageInfo().contains("<li>{@link "))
                    .map(PackageUnderTest::name)
                    .toList();

            assertThat(malformed)
                    .as("a reading order with no linked step is a heading, not an order")
                    .isEmpty();
        }
    }

    /** One package of the main source tree, named as the reader sees it. */
    private record PackageUnderTest(String name, Path directory, int typeCount, String packageInfo) {}

    private static List<PackageUnderTest> packagesWithTypes() {
        try (Stream<Path> directories = Files.walk(SOURCE_ROOT)) {
            List<PackageUnderTest> found = new ArrayList<>();
            directories.filter(Files::isDirectory).sorted().forEach(directory -> {
                int types = countTypes(directory);
                if (types > 0) {
                    String relative = SOURCE_ROOT.relativize(directory).toString();
                    found.add(new PackageUnderTest(
                            relative.isEmpty() ? ROOT : relative,
                            directory,
                            types,
                            read(directory.resolve("package-info.java"))));
                }
            });
            found.sort(Comparator.comparing(PackageUnderTest::name));
            return found;
        } catch (IOException problem) {
            throw new UncheckedIOException("could not walk " + SOURCE_ROOT.toAbsolutePath(), problem);
        }
    }

    /** Types declared directly in this package, which is every source file but {@code package-info}. */
    private static int countTypes(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return (int) files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .filter(name -> !name.equals("package-info.java"))
                    .count();
        } catch (IOException problem) {
            throw new UncheckedIOException("could not list " + directory, problem);
        }
    }

    private static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file) : "";
        } catch (IOException problem) {
            throw new UncheckedIOException("could not read " + file, problem);
        }
    }
}
