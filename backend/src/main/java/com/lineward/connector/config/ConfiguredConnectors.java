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

import com.lineward.connector.TargetSystemConnector;
import com.lineward.connector.TargetSystemConnectors;
import com.lineward.connector.keycloak.KeycloakConnector;
import com.lineward.connector.keycloak.KeycloakCredentials;
import com.lineward.connector.ldap.LdapConnector;
import com.lineward.connector.ldap.LdapCredentials;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The connectors an installation has been configured with, built once at
 * startup.
 *
 * <p><b>Eagerly, and that is the decision worth reading.</b> A target whose
 * credential is missing or malformed fails here, while the context is
 * refreshing, with the application refusing to start. The alternative was
 * building each connector on first use, where the same mistake surfaces at two
 * in the morning on the first scheduled run, in a stack trace nobody is
 * watching. The credential types do the refusing: neither can be constructed
 * without every field, which is stated on both of them as the point of them.
 *
 * <p>This is also the only class in the platform that names a concrete
 * connector. Everything else holds the {@code TargetSystemConnector} interface,
 * so no other module can reach a specific one even by accident.
 */
final class ConfiguredConnectors implements TargetSystemConnectors, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConfiguredConnectors.class);

    private final Map<String, TargetSystemConnector> byApplicationCode;

    ConfiguredConnectors(ConnectorProperties properties) {
        Map<String, TargetSystemConnector> built = new LinkedHashMap<>();
        properties.targets().forEach((applicationCode, target) -> built.put(applicationCode, build(target)));
        this.byApplicationCode = Map.copyOf(built);
        log.info("Configured connectors for {}", byApplicationCode.keySet());
    }

    @Override
    public Optional<TargetSystemConnector> forApplication(String applicationCode) {
        return Optional.ofNullable(byApplicationCode.get(applicationCode));
    }

    private static TargetSystemConnector build(ConnectorProperties.Target target) {
        if (target.keycloak() != null) {
            ConnectorProperties.Keycloak settings = target.keycloak();
            return new KeycloakConnector(new KeycloakCredentials(
                    settings.baseUrl(), settings.realm(), settings.clientId(), settings.clientSecret()));
        }
        ConnectorProperties.Ldap settings = target.ldap();
        return new LdapConnector(new LdapCredentials(
                settings.url(),
                settings.userSearchBase(),
                settings.groupSearchBase(),
                settings.bindDn(),
                settings.bindPassword(),
                settings.lockMarkerAttribute()));
    }

    /**
     * Closes what holds something open.
     *
     * <p>A directory connector keeps a connection for as long as a paged read
     * is live, because the cookie of RFC 2696 dies with it, so a run abandoned
     * part way leaves one open. Spring calls this at shutdown because the type
     * is {@link AutoCloseable}, which is the one place where relying on the
     * framework is better than a hook somebody has to register.
     */
    @Override
    public void close() {
        byApplicationCode.forEach((applicationCode, connector) -> {
            if (connector instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception leftOpen) {
                    log.warn("Could not close the connector of '{}'", applicationCode, leftOpen);
                }
            }
        });
    }
}
