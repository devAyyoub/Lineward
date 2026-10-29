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
 * Internal to M1: the authoritative sources Lineward reads, and the shape of
 * what they assert.
 *
 * <p>Deliberately not the connectors of M3. A connector asks a target system
 * which accounts exist there; this asks the system that owns the truth about
 * who exists at all. They sit on opposite sides of the model.
 *
 * <p>Read in this order:
 * <ol>
 * <li>{@link AuthoritativeSourceReader}: the interface, which is paged reads plus reading a stored payload back
 * <li>{@link AssertedIdentity} and {@link AssertedPage}: what a read returns, and the totals a source reports
 * <li>{@link HrApiSourceReader}: the one implementation, over HTTP against the demo HR system
 * <li>{@link HrRecordParser}: the field names of that particular source, kept in one place on purpose
 * </ol>
 */
package com.lineward.identity.source;
