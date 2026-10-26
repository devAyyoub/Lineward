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
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.docs.Documenter;

/**
 * Writes the module inventory from the code, so that nobody maintains it.
 *
 * <p>This is the half of the problem that should not be written by hand. A
 * package's reading order is intent and only a person can state it, which is
 * what {@code PackageReadingOrderTests} enforces. Which types a module exposes,
 * which are internal and which events it publishes is not intent, it is a fact
 * about the bytecode, and a hand written list of facts is a list that goes
 * stale without telling anyone.
 *
 * <p>What it is worth, measured against its output on 2026-10-26 rather than
 * against its API: the canvas is a map of what a module <em>talks to</em>, not
 * an index of what is <em>inside</em> it. The canvas for {@code access} records
 * that it holds a reference to {@code IdentityLookup} and listens to
 * {@code IdentityLifecycleChanged}, which is exactly the thing a reader cannot
 * work out without grepping and which a hand written note would get wrong
 * within a month. The canvas for {@code connector} is three lines, because a
 * module of plain records and one interface has no beans, no events and no
 * configuration properties to report. So this complements the reading orders
 * and does not replace them.
 *
 * <p>The output goes to {@code target}, which means it is rebuilt by every
 * build and versioned by none. That is the decision of 2026-10-26 and the
 * reason is diffs: a generated file under version control turns every
 * unrelated commit into a commit that also touches the documentation. When
 * there is a release worth publishing a diagram for, it is one command away.
 *
 * <p>It needs no Spring context, because it reads the compiled classes rather
 * than a running application.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ModuleDocumentationTests {

    private static final String OUTPUT_FOLDER = "target/modules";

    @Test
    void writes_a_canvas_and_a_diagram_for_every_module() throws IOException {
        new Documenter(ModularityTests.MODULES, Documenter.Options.defaults().withOutputFolder(OUTPUT_FOLDER))
                .writeDocumentation();

        Path output = Path.of(OUTPUT_FOLDER);
        assertThat(output).isDirectory();

        // Asserted and not merely generated: a Documenter that silently wrote
        // nothing would otherwise leave this test passing while the thing it
        // exists to produce is absent.
        assertThat(Files.list(output).map(each -> each.getFileName().toString()).toList())
                .as("one canvas and one diagram per module, plus the aggregating document")
                .anyMatch(name -> name.startsWith("module-connector"))
                .anyMatch(name -> name.startsWith("module-identity"))
                .anyMatch(name -> name.endsWith(".puml"))
                .anyMatch(name -> name.endsWith(".adoc"));
    }

    @Test
    void records_what_a_module_reaches_into_other_modules_for() throws IOException {
        new Documenter(ModularityTests.MODULES, Documenter.Options.defaults().withOutputFolder(OUTPUT_FOLDER))
                .writeModuleCanvases();

        String canvas = Files.readString(Path.of(OUTPUT_FOLDER, "module-access.adoc"));

        // The property worth locking in: both of these lines are true because
        // the code is shaped that way, so a new cross module dependency turns
        // up here on the next build without anyone writing it down. This is
        // also the honest limit of the canvas, recorded on 2026-10-26 after
        // reading its output: it reports relationships between modules, so a
        // module with no beans and no events gets an almost empty one.
        assertThat(canvas)
                .as("M4 reads identities through the one type M1 exposes for it")
                .contains("IdentityLookup")
                .as("and reacts to the lifecycle event rather than polling for changes")
                .contains("IdentityLifecycleChanged");
    }
}
