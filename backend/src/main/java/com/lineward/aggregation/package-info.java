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
 * M2: aggregation, correlation and reconciliation of the real state of accounts.
 *
 * <p>What is out here is what the rest of the platform may ask of M2, and it is
 * two types. The run itself is internal, in {@code store}, because nothing
 * outside this module should be able to drive one piece of it: reading a target
 * system, writing the accounts down and deciding who they belong to are three
 * steps of one operation, and a caller that could run the second without the
 * third would leave every account an orphan.
 *
 * <p>Read in this order:
 * <ol>
 * <li>{@link AggregationReport}: what one run against one system did, which is the shape of everything M2 answers
 * <li>{@link ManualCorrelation}: the one decision a person takes instead of a rule, and the guarantee that no rule undoes it
 * </ol>
 *
 * <p>Allowed dependencies are declared below and verified by
 * {@code ModularityTests}. The graph is a starting point: when a phase needs a
 * dependency that is not listed, the test fails and the decision becomes
 * explicit instead of silent (ADR-001).
 */
@ApplicationModule(allowedDependencies = { "shared", "identity", "access", "connector" })
package com.lineward.aggregation;

import org.springframework.modulith.ApplicationModule;
