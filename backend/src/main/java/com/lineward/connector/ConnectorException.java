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
 * A read broke part way through.
 *
 * <p>It exists so that a caller can tell connector trouble from a bug in
 * Lineward without catching everything. The two have different outcomes: a run
 * that hit this is {@code INTERRUPTED} and resumable from its checkpoint, and a
 * run that hit anything else is a defect. Catching {@link RuntimeException} to
 * find the first would swallow the second.
 *
 * <p>Unchecked, because no caller can do anything useful at the point of
 * failure. The decision of whether to retry, open a circuit breaker or stop
 * belongs to whoever owns the run, which is phase 2.
 *
 * <p>What it must not be used for: a target system that is simply unreachable
 * when asked, which is a {@link ConnectionCheck}, and an empty result, which is
 * a legitimate answer.
 */
public class ConnectorException extends RuntimeException {

    private final ConnectorType type;

    public ConnectorException(ConnectorType type, String message) {
        this(type, message, null);
    }

    public ConnectorException(ConnectorType type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    /** Which kind of connector failed, for the error of the run and for the per connector circuit breaker of phase 2. */
    public ConnectorType type() {
        return type;
    }
}
