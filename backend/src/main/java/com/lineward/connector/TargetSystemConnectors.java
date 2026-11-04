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

import java.util.Optional;

/**
 * Which connector reads which application.
 *
 * <p>The door M3 opens so that nothing outside it ever names a concrete
 * connector. A caller asks for an application's code and gets back something
 * that can read it, or nothing. That is the whole point of the nested packages:
 * {@code KeycloakConnector} and {@code LdapConnector} are internal, so the
 * module that aggregates cannot import one, cannot be given one by mistake, and
 * cannot be made to depend on how either is built.
 *
 * <p><b>It is also where the credentials stop.</b> Reaching a target system
 * needs a password, and the decision of 2026-11-03 is that it comes from the
 * environment: no secret in the repository, none in the database, and none in a
 * log, because the credential types of each connector redact themselves. A
 * column holding an administrator's password in clear is worse than an
 * environment variable, and encrypting one properly needs key management and
 * rotation that do not exist yet. When they do, this interface is what changes
 * and nothing above it.
 *
 * <p>An application with no configured connector is an empty answer and not an
 * error. An installation configures the systems it has reached agreement with,
 * and the rest are registered in the catalogue waiting for somebody to be given
 * a credential. What must not happen is that the absence goes unsaid, and
 * saying it is the caller's job: it knows whether it expected one.
 */
public interface TargetSystemConnectors {

    /**
     * @param applicationCode the {@code application.code} of the system to
     *     read, which is the name configuration keys a target by. Not the id:
     *     an id is whatever a migration produced, and nobody can write it into
     *     a configuration file and know they got it right
     * @return a connector for that application, or empty when nothing is
     *     configured for it
     */
    Optional<TargetSystemConnector> forApplication(String applicationCode);
}
