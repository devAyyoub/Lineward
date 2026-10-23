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
 * Which kind of target system a connector speaks to.
 *
 * <p>An enum and not a string because this is the value that decides which
 * connector reads an application, and a typo in a string would pick nothing at
 * runtime instead of failing to compile.
 *
 * <p>It mirrors the {@code application.connector_type} check constraint of
 * {@code V001__phase_1_core_schema.sql} and has to stay in step with it: a value
 * here that the constraint rejects turns into a failed insert at aggregation
 * time, which is the worst place to find out. The constraint is the control,
 * this enum is what keeps an unknown value from being produced in the first
 * place.
 *
 * <p>{@code OKTA} is deliberately absent. The Okta connector is KAN-9, in this
 * same phase by the decision of 2026-10-22, and it needs the value added here
 * and in the constraint by a migration. Adding it now would declare a connector
 * type the schema refuses.
 */
public enum ConnectorType {

    /** Keycloak through its admin API: a modern identity provider over REST. */
    KEYCLOAK,

    /** OpenLDAP: the classic corporate directory, with groups and {@code memberOf}. */
    LDAP,

    /** An application that speaks SCIM 2.0, the provisioning standard of RFC 7643 and RFC 7644. */
    SCIM,

    /**
     * The purpose built unstable application of phase 2, with configurable
     * latency and random errors. It is a connector type and not a test fixture
     * because resilience is only demonstrable against something that really
     * fails.
     */
    FLAKY
}
