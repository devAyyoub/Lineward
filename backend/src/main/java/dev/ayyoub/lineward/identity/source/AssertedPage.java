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
package dev.ayyoub.lineward.identity.source;

import java.util.List;

/**
 * One page of asserted records, with the totals the source reports.
 *
 * <p>{@code totalElements} is not decoration. It is the only way an ingestion
 * run can tell "the extract ended" apart from "the connection dropped", and
 * treating an empty page as the end is how a partial read gets mistaken for a
 * complete one. That mistake is expensive in this domain in a specific way: a
 * complete read that is missing people looks exactly like a wave of
 * terminations, and the next phase acts on it.
 */
public record AssertedPage(
        List<AssertedIdentity> records,
        int page,
        int size,
        int totalElements,
        int totalPages) {}
