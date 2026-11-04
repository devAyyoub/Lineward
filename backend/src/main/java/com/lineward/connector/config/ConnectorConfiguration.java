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

import com.lineward.connector.TargetSystemConnectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires M3.
 *
 * <p>The properties are enabled here rather than on the application class, so
 * that the entry point of the monolith stays ignorant of what each module
 * configures, which is what {@code IdentityConfiguration} does for M1.
 */
@Configuration
@EnableConfigurationProperties(ConnectorProperties.class)
class ConnectorConfiguration {

    /**
     * One bean for every configured target, and no bean per connector.
     *
     * <p>A bean per connector would mean a bean whose type is a concrete
     * connector, which is exactly what the nested packages exist to prevent:
     * anything in the context could then ask for a {@code KeycloakConnector} by
     * type and get one.
     */
    @Bean
    TargetSystemConnectors targetSystemConnectors(ConnectorProperties properties) {
        return new ConfiguredConnectors(properties);
    }
}
