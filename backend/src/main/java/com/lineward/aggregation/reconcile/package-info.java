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
 * Internal to M2: comparing what was read against what was expected, and
 * writing down the differences.
 *
 * <p>Separate from {@code store} because the two change for different reasons
 * and depend on different things. Reading a target system changes when a system
 * out there changes, and it needs connectors, credentials and a network;
 * deciding that what was read is wrong changes when the organization's idea of
 * wrong changes, and it needs nothing but the database. Neither package
 * references the other.
 *
 * <p>Read in this order:
 * <ol>
 * <li>{@link Finding}: what a problem is identified by, which is the key everything here compares on
 * <li>{@link AccountConditions}: the two findings that are about an account, orphan and dormant
 * <li>{@link GrantDrift}: the two that are about an access, and why the comparison is not symmetric
 * <li>{@link FindingLedger}: how a condition becomes a row, stays one row while it lasts, and closes when it stops
 * <li>{@link ReconciliationRunner}: the same thing across every system the installation governs, which is the package's entry point
 * <li>{@link ReconciliationProperties} and {@link ReconciliationConfiguration}: how long is too long, and how the package is wired
 * </ol>
 */
package com.lineward.aggregation.reconcile;
