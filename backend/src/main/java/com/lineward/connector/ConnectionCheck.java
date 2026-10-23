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
package com.lineward.connector;

/**
 * The answer to "can you reach that system, and as whom".
 *
 * <p>A returned value and not an exception, because an unreachable target system
 * is the expected answer to this question and not a failure of Lineward. The
 * console needs to show why a connector cannot read: an expired credential, a
 * host that does not resolve and a service account that lost its read
 * permission look identical from outside and are three different jobs for three
 * different people.
 *
 * <p>{@link #detail} is for a person and goes in front of one, so it says what
 * was reached and as whom rather than repeating a stack trace. It must never
 * carry the credential it just used.
 *
 * @param reachable whether the connector could talk to the target system and
 *                  was allowed to read
 * @param detail    what to show a human: the version and the account on
 *                  success, the reason on failure
 */
public record ConnectionCheck(boolean reachable, String detail) {

    public ConnectionCheck {
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("a connection check that explains nothing is not an answer");
        }
    }

    /** Reached the target system and allowed to read it. */
    public static ConnectionCheck reached(String detail) {
        return new ConnectionCheck(true, detail);
    }

    /** Could not reach it, or reached it and was not allowed to read. */
    public static ConnectionCheck unreachable(String detail) {
        return new ConnectionCheck(false, detail);
    }
}
