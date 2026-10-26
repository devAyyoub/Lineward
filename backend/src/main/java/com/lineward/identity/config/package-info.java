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
 * Internal to M1: configuration properties and the beans that wire the module.
 *
 * <p>Read in this order:
 * <ol>
 * <li>{@link HrSourceProperties}: where the HR system is and how much of it to read at a time
 * <li>{@link IdentityConfiguration}: what that configuration is wired into
 * </ol>
 *
 * <p>Separate from {@code ingest} and {@code source} because configuration is
 * the one thing an installation changes without changing code, and because
 * nothing in here is reached from the other two: the dependency runs inwards
 * only.
 */
package com.lineward.identity.config;
