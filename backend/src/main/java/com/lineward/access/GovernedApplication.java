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
package com.lineward.access;

import java.util.UUID;

/**
 * One application Lineward governs, as the modules that read it need it.
 *
 * <p>The counterpart of {@code GovernedAttributes} on the M4 side: the few
 * fields another module needs to do its work, and not the row. {@code
 * criticality}, {@code owner_identity_id} and the timestamps are deliberately
 * absent, because a run that reads a target system has no business deciding
 * anything with them.
 *
 * @param id            the application's id, which is what an {@code account}
 *                      and an {@code entitlement} row point at
 * @param code          the stable name a rule, a connector's configuration and
 *                      a human all use, for example {@code intranet}
 * @param name          what to show a person
 * @param connectorType which kind of system holds the accounts, as the string
 *                      the {@code application_connector_type_check} constraint
 *                      allows. A string and not an enum on purpose: {@code
 *                      ConnectorType} belongs to M3, which M4 does not depend
 *                      on, and importing it here to look tidier would make the
 *                      catalogue depend on the connectors it is read by.
 *                      Whoever needs the enum maps it, and a value this module
 *                      cannot interpret is a value it was never meant to
 *                      interpret
 */
public record GovernedApplication(UUID id, String code, String name, String connectorType) {}
