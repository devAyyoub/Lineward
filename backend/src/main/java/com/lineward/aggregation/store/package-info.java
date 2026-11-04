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
 * Internal to M2: reading a target system and writing down what it said.
 *
 * <p>Read in this order:
 * <ol>
 * <li>{@link ApplicationAggregation}: one run against one system, page by page
 * <li>{@link AccountWriter}: one page of accounts, turned into rows
 * <li>{@link AccountCorrelation}: who each of those accounts belongs to, by rule or by a person's decision
 * <li>{@link AggregationRunner}: the same thing across every system the installation governs, which is the module's entry point
 * <li>{@link AggregationProperties} and {@link AggregationConfiguration}: how much is read at a time and how the module is wired
 * </ol>
 */
package com.lineward.aggregation.store;
