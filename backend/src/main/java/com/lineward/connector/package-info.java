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
/**
 * M3: the connector SPI. Concrete connectors live in nested packages and are therefore internal: no other module can reach a specific connector, only the SPI.
 *
 * <p>Kept flat, like {@code shared} and for the same reason turned inside out.
 * By the Spring Modulith convention a module's base package is its public API
 * and every nested package is internal, so splitting these types into
 * sub-packages would not organise the API, it would hide it. The division
 * section 10.4 asks for is the one that already exists here and is the point of
 * the module: the SPI out here, each connector in its own nested package where
 * nothing outside can reach it, not even by accident, and not even by a
 * well-meant shortcut.
 *
 * <p>Read in this order:
 * <ol>
 * <li>{@link TargetSystemConnector}: the interface, which is three read operations and the whole API
 * <li>{@link ConnectorPage} and {@link ReadCursor}: how a read advances and how it ends
 * <li>{@link DiscoveredAccount} and {@link DiscoveredEntitlement}: what a read gives back
 * <li>{@link ConnectionCheck} and {@link ConnectorException}: unreachable, which is an answer, against broken, which is not
 * <li>{@link ConnectorType} and {@link AccountStatus}: the two closed vocabularies, each mirroring a check constraint
 * </ol>
 *
 * <p>Allowed dependencies are declared below and verified by
 * {@code ModularityTests}. The graph is a starting point: when a phase needs a
 * dependency that is not listed, the test fails and the decision becomes
 * explicit instead of silent (ADR-001).
 */
@ApplicationModule(allowedDependencies = { "shared" })
package com.lineward.connector;

import org.springframework.modulith.ApplicationModule;
