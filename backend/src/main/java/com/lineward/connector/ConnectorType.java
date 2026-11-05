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
 * <p>{@code OKTA} arrived on 2026-11-05 with KAN-9, and with
 * {@code V008__the_connector_type_okta_the_schema_refused.sql} in the same
 * commit. Until then this comment said it was deliberately absent, because an
 * enum value the constraint rejects compiles cleanly and fails at the end of
 * the first aggregation.
 */
public enum ConnectorType {

    /** Keycloak through its admin API: a modern identity provider over REST. */
    KEYCLOAK,

    /**
     * OpenLDAP: the classic corporate directory, with groups.
     *
     * <p>And not with {@code memberOf}, which is what this said until a running
     * directory was asked: that attribute only exists where an overlay has been
     * configured to maintain it, so membership is read from the group and not
     * from the account. Measured on 2.6.10 on 2026-11-03.
     */
    LDAP,

    /**
     * Okta: an identity provider delivered as a service, read through its
     * administration API.
     *
     * <p>The one of the three Lineward cannot run a copy of. Keycloak and
     * OpenLDAP are containers in {@code infra/}, so their connectors are
     * verified against a server that starts with the test. An Okta
     * organization is somebody's account, which is why this connector's live
     * checks only run where one is configured and why its parsing is pinned
     * down by documents captured from a real one instead.
     */
    OKTA,

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
