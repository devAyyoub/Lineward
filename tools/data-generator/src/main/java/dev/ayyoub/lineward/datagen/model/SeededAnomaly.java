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
package dev.ayyoub.lineward.datagen.model;

/**
 * A record of an anomaly the generator planted, and where.
 *
 * <p>This catalogue is the reason the generator is useful rather than merely
 * random. An anomaly that exists but cannot be located is untestable: a test
 * would have to search the whole population to assert that detection worked,
 * and a demo could not point at the finding it expects. Returning the list of
 * what was planted turns "the platform found something" into "the platform
 * found exactly the twelve things that are there, and no more".
 *
 * @param kind            which anomaly
 * @param employeeNumber  the employee carrying it
 * @param detail          what was changed, for a human reading the catalogue
 */
public record SeededAnomaly(AnomalyKind kind, String employeeNumber, String detail) {}
