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
 * Internal to M4: the access an identity is due by rule, with no request and no
 * approval in the way.
 *
 * <p>Kept flat, and the compiler is what decides that. Every type in here is
 * package private, so splitting the vocabulary, the engine and the wiring into
 * sub-packages would mean opening all eight of them. Section 10.4 of the
 * project document forbids exactly that trade: a folder gained, a guarantee
 * lost. What the package needs is an order to read it in, not a division.
 *
 * <p>Read in this order:
 * <ol>
 * <li>{@link BirthrightRule} and {@link BirthrightAttribute}: the vocabulary, which is one rule and the only identity fields a rule may read
 * <li>{@link BirthrightRules}: the engine, which answers what one identity is due
 * <li>{@link EntitlementCatalog}: translates the names a rule uses into the ids a row needs
 * <li>{@link ExpectedAccessWriter}: brings the stored expectation into line with the answer, granting and revoking
 * <li>{@link BirthrightRecalculation}: the listener that runs all of the above when a lifecycle state moves
 * <li>{@link BirthrightProperties} and {@link AccessConfiguration}: how the rules are loaded and the module wired
 * </ol>
 *
 * <p>The dependency runs one way down that list, and the lifecycle event that
 * starts it arrives from M1 as {@code IdentityLifecycleChanged}.
 */
package com.lineward.access.birthright;
