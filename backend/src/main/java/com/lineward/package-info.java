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
 * Lineward: identity governance for humans and AI agents. This is the way in to
 * the whole backend.
 *
 * <p>One deployable modular monolith, split into application modules by
 * business capability and not by technical layer (ADR-001). The rule that makes
 * the split mean anything is the one Spring Modulith enforces and
 * {@code ModularityTests} verifies: <strong>a module's base package is its
 * public API and every nested package is internal.</strong> So a flat base
 * package is not an untidy one, it is the API; the machinery is in the nested
 * packages, and each package carries its own reading order in its
 * {@code package-info}.
 *
 * <p>Built so far, which is the only part worth reading:
 * <ul>
 * <li>{@code shared}: M0, what every module may depend on, and which depends on nothing
 * <li>{@code identity}: M1, identities, authoritative sources and the joiner, mover and leaver lifecycle
 * <li>{@code connector}: M3, the read-only interface every target system connector implements
 * <li>{@code access.birthright}: part of M4, the access an identity is due by rule
 * </ul>
 *
 * <p>The rest, {@code aggregation}, {@code provisioning}, {@code request},
 * {@code sod}, {@code certification}, {@code audit}, {@code authz},
 * {@code agent} and {@code assist}, are placeholders holding a declared
 * dependency graph and no code yet. Their {@code package-info} says what they
 * will hold; the roadmap in the project document says when.
 *
 * <p>Two things to know before reading any of it. Every query runs inside a
 * transaction that declares which organization it serves, which is what the
 * row level security of ADR-011 rests on, so {@code TenantTransactions} is not
 * a convenience. And the database is the one place the module boundaries are
 * not enforced by the compiler, which is the documented blind spot of
 * {@code ModularityTests}.
 */
package com.lineward;
