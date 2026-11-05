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
package com.lineward.connector.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where each target system is and which account reads it.
 *
 * <p>Keyed by {@code application.code}, so that what is in the database and
 * what is in the configuration are joined on the one name a human can write
 * down and check. An id would be whatever a migration produced.
 *
 * <p><b>Every password here comes from the environment</b> (the decision of
 * 2026-11-03). What the shipped {@code application.yaml} carries is a
 * placeholder and a local default that only works against the throwaway
 * containers of {@code infra/docker-compose.yml}, which is the same thing it
 * already does for the database. A real installation sets the variables, and
 * nothing in this repository knows a credential that reaches anything real.
 *
 * <p><b>Every record here redacts its own secret.</b> A record prints all its
 * components by default, and Spring Boot prints configuration properties in
 * more places than anybody remembers: a debug log, an exception during binding,
 * the {@code configprops} endpoint. Redacting at the type means no caller has
 * to remember, which is what the connectors' own credential types do.
 */
@ConfigurationProperties(prefix = "lineward.connector")
record ConnectorProperties(Map<String, Target> targets) {

    ConnectorProperties {
        targets = targets == null ? Map.of() : Map.copyOf(targets);
    }

    /**
     * One target system, which names exactly one kind of system.
     *
     * <p>There is no {@code type} field, and leaving it out is the point. The
     * kind of system is which block was filled in, so a configuration cannot
     * say {@code LDAP} and then describe a Keycloak realm. One field fewer is
     * one contradiction fewer, and this one would have been a contradiction
     * nobody noticed until a run wrote a directory's accounts under the
     * intranet.
     */
    record Target(Keycloak keycloak, Ldap ldap, Okta okta) {

        Target {
            long described = java.util.stream.Stream.of(keycloak, ldap, okta)
                    .filter(java.util.Objects::nonNull)
                    .count();
            if (described != 1) {
                throw new IllegalArgumentException("a target names exactly one kind of system, so give it a"
                        + " keycloak block, an ldap block or an okta block, and exactly one of the three");
            }
        }
    }

    /** @see com.lineward.connector.keycloak.KeycloakConnector */
    record Keycloak(String baseUrl, String realm, String clientId, String clientSecret) {

        @Override
        public String toString() {
            return "Keycloak[baseUrl=" + baseUrl + ", realm=" + realm + ", clientId=" + clientId
                    + ", clientSecret=<redacted>]";
        }
    }

    /**
     * @see com.lineward.connector.okta.OktaConnector
     *
     * <p>A path and not the key, which is the one block here that does not end
     * in a secret. Okta authenticates a service application by signature, so
     * what Lineward holds is a private key, and a key is better kept in a file
     * with permissions than in an environment variable: variables are readable
     * from a process listing on some systems, land in crash dumps, and get
     * inherited by every child process. The file is read once at startup.
     */
    record Okta(String orgUrl, String clientId, String keyId, String privateKeyFile) {}

    /** @see com.lineward.connector.ldap.LdapConnector */
    record Ldap(
            String url,
            String userSearchBase,
            String groupSearchBase,
            String bindDn,
            String bindPassword,
            String lockMarkerAttribute) {

        @Override
        public String toString() {
            return "Ldap[url=" + url
                    + ", userSearchBase=" + userSearchBase
                    + ", groupSearchBase=" + groupSearchBase
                    + ", bindDn=" + bindDn
                    + ", bindPassword=<redacted>"
                    + ", lockMarkerAttribute=" + lockMarkerAttribute + "]";
        }
    }
}
