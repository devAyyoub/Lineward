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
 * Internal to M1: turning what an authoritative source asserts into Lineward's
 * own tables, and into lifecycle events.
 *
 * <p>Read in this order:
 * <ol>
 * <li>{@link HrIngestionRunner}: the entry point, which drives one run page by page and then links managers
 * <li>{@link SourceRecordIngestion}: one record, from asserted payload to governed identity
 * <li>{@link PayloadFingerprint}: the question that comes first, which is whether the source is saying anything new
 * <li>{@link IdentityStatusRule}: the governed status, derived from the dates the source asserts
 * <li>{@link LifecycleEventRule}: the same change read as a joiner, a mover or a leaver
 * <li>{@link ManagerReconciliation}: the second pass, which needs every identity to exist before it can link them
 * </ol>
 *
 * <p>Why the manager pass is last and separate: a source names a manager by an
 * identifier that may belong to a record later in the same run, so linking
 * during the first pass would depend on the order the pages arrive in.
 */
package com.lineward.identity.ingest;
