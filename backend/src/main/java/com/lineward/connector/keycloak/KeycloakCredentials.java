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
package com.lineward.connector.keycloak;

/**
 * Where a Keycloak realm is and which service account reads it.
 *
 * <p>Passed in rather than read from configuration here, because which realm a
 * given application maps to is a per application decision and the registry that
 * makes it is KAN-11. Nothing in this package has a default: a connector with
 * no credential is a connector that cannot be built, which is the point.
 *
 * <p>{@link #toString()} is overridden, and that is a control and not tidiness.
 * A record's generated {@code toString} prints every component, so one log line
 * or one exception message built with string concatenation would publish the
 * client secret. Redacting at the type means no caller has to remember to.
 *
 * @param baseUrl      where Keycloak serves, with no trailing slash, for
 *                     example {@code http://localhost:8081}
 * @param realm        the realm to read. Not {@code master}: the accounts
 *                     Lineward governs live in a realm of their own, and the
 *                     service account's rights are scoped to it
 * @param clientId     the confidential client whose service account reads,
 *                     holding {@code view-users} and nothing more
 * @param clientSecret that client's secret. Never written to a log, an
 *                     exception or a {@code ConnectionCheck}
 */
public record KeycloakCredentials(String baseUrl, String realm, String clientId, String clientSecret) {

    public KeycloakCredentials {
        require(baseUrl, "baseUrl");
        require(realm, "realm");
        require(clientId, "clientId");
        require(clientSecret, "clientSecret");
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a Keycloak connector cannot be built without " + name);
        }
    }

    /** The realm's own base, under which both the token endpoint and the admin API live. */
    String realmUrl() {
        return baseUrl + "/realms/" + realm;
    }

    /** The admin API base for this realm. */
    String adminUrl() {
        return baseUrl + "/admin/realms/" + realm;
    }

    /** Everything but the secret, so that printing this cannot leak it. */
    @Override
    public String toString() {
        return "KeycloakCredentials[baseUrl=" + baseUrl + ", realm=" + realm + ", clientId=" + clientId
                + ", clientSecret=<redacted>]";
    }
}
