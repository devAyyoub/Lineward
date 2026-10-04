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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Lineward modular monolith.
 *
 * <p>Each direct sub-package of this one is an application module (ADR-001).
 * Nested packages inside a module are internal to it and must not be referenced
 * from other modules; Spring Modulith enforces this.
 */
@SpringBootApplication
public class LinewardApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinewardApplication.class, args);
    }
}
