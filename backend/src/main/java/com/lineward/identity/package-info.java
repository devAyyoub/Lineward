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
 * M1: identities, authoritative sources and the joiner, mover and leaver lifecycle.
 *
 * <p>Read in this order:
 * <ol>
 * <li>{@link GovernedAttributes} and {@link IdentityStatus}: the vocabulary, which is the fields that decide what access is due and the lifecycle states they can be in
 * <li>{@link IdentityLookup}: the read side, and what every other module is given
 * <li>{@link IdentityIngestion} and {@link IngestionReport}: the write side, which is bringing a source in and what one run did
 * <li>{@link LifecycleEvent} and {@link IdentityLifecycleChanged}: what leaves the module, as a classified change and as an event other modules listen for
 * </ol>
 *
 * <p>The machinery sits in nested packages and is therefore internal:
 * {@code source} reads the authoritative systems, {@code ingest} turns what
 * they assert into rows and events, {@code query} serves the read side, and
 * {@code config} wires it. Each has its own reading order.
 *
 * <p>Allowed dependencies are declared below and verified by
 * {@code ModularityTests}. The graph is a starting point: when a phase needs a
 * dependency that is not listed, the test fails and the decision becomes
 * explicit instead of silent (ADR-001).
 */
@ApplicationModule(allowedDependencies = { "shared" })
package com.lineward.identity;

import org.springframework.modulith.ApplicationModule;
