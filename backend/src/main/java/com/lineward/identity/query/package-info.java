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
 * Internal to M1: the read side, which is the governed view of an identity as
 * the modules that decide things need it.
 *
 * <p>One type, {@link JdbcIdentityLookup}, which implements
 * {@code IdentityLookup} from the module's API. Separate from {@code ingest}
 * because reading and writing an identity change for different reasons: the
 * ingestion follows what a source sends, and the lookup follows what a
 * decision needs.
 */
package com.lineward.identity.query;
