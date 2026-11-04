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
 * Internal to M4: the catalogue as the rest of the platform reads it and as a
 * target system fills it in.
 *
 * <p>Separate from {@code birthright} because the two change for different
 * reasons. A birthright rule changes when the organization decides who is due
 * what; the catalogue changes when a system out there turns out to hold
 * something, which is nobody's decision at all.
 *
 * <p>Read in this order:
 * <ol>
 * <li>{@link JdbcApplicationLookup}: which systems an aggregation is allowed to go and read
 * <li>{@link JdbcEntitlementRegistry}: what happens to a permission one of them turns out to have
 * </ol>
 */
package com.lineward.access.catalogue;
