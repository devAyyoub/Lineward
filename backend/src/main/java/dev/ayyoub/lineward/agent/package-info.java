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
 * M10: agent identities, delegated authority, effective authority and kill switch.
 *
 * <p>Allowed dependencies are declared below and verified by
 * {@code ModularityTests}. The graph is a starting point: when a phase needs a
 * dependency that is not listed, the test fails and the decision becomes
 * explicit instead of silent (ADR-001).
 */
@ApplicationModule(allowedDependencies = { "shared", "identity", "access" })
package dev.ayyoub.lineward.agent;

import org.springframework.modulith.ApplicationModule;
