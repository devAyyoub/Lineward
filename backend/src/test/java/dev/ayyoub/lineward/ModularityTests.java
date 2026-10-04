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
package dev.ayyoub.lineward;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifies the module boundaries declared in ADR-001.
 *
 * <p>This test is what makes the boundaries real. Without it, the module
 * structure is a diagram. It fails when a module reaches into another module's
 * internal packages, when a dependency is not declared in
 * {@code @ApplicationModule(allowedDependencies = ...)}, or when a cycle appears.
 *
 * <p>Its blind spot is documented in ADR-001: it analyses Java types, not the
 * database. Two modules can look decoupled here and be fused by SQL.
 */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(LinewardApplication.class);

    @Test
    void verifiesModuleBoundaries() {
        MODULES.verify();
    }

    @Test
    void printsModuleStructure() {
        MODULES.forEach(System.out::println);
    }
}
