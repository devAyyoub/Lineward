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
/**
 * Internal to M3: which connector reads which application, and where its
 * credential comes from.
 *
 * <p>The only package in the platform that names a concrete connector. It is
 * internal for that reason: what the rest of the application is given is
 * {@code TargetSystemConnectors}, so nothing outside M3 can reach a particular
 * kind of system.
 *
 * <p>Read in this order:
 * <ol>
 * <li>{@link ConnectorProperties}: what an installation declares about each target system, with every secret coming from the environment and every record redacting its own
 * <li>{@link ConfiguredConnectors}: the connectors built from that, once, at startup, so a missing credential stops the application instead of the first run
 * <li>{@link ConnectorConfiguration}: the wiring, which publishes the interface and never a connector
 * </ol>
 */
package com.lineward.connector.config;
