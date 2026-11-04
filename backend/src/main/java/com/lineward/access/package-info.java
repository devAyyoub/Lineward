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
 * M4: applications, entitlements, business roles, access lineage and role mining.
 *
 * <p>What is out here is what other modules are allowed to ask of the
 * catalogue, and it is three types because that is all anybody has needed so
 * far. The rules that decide who is due what are internal, in
 * {@code birthright}; the queries and the writes are internal, in
 * {@code catalogue}.
 *
 * <p>Read in this order:
 * <ol>
 * <li>{@link ApplicationLookup} and {@link GovernedApplication}: which systems this installation governs, which is what an aggregation needs before it can read one
 * <li>{@link EntitlementRegistry}: the one write this module exposes, for a permission a target system turns out to have
 * </ol>
 *
 * <p>Allowed dependencies are declared below and verified by
 * {@code ModularityTests}. The graph is a starting point: when a phase needs a
 * dependency that is not listed, the test fails and the decision becomes
 * explicit instead of silent (ADR-001).
 */
@ApplicationModule(allowedDependencies = { "shared", "identity" })
package com.lineward.access;

import org.springframework.modulith.ApplicationModule;
