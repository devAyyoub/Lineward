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
package dev.ayyoub.lineward.hrsim.model;

import java.time.LocalDate;

/**
 * Terminate somebody, to provoke a leaver.
 *
 * <p>A date in the past is accepted on purpose: that is the retroactive
 * termination, where HR says today that somebody left three weeks ago. Those
 * three weeks are the window in which their access was still live, and being
 * able to reconstruct it is the difference between an audit finding and an
 * answer.
 */
public record TerminationRequest(LocalDate terminationDate) {}
