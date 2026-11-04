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
package com.lineward.aggregation.store;

import com.lineward.access.ApplicationLookup;
import com.lineward.access.GovernedApplication;
import com.lineward.aggregation.AggregationReport;
import com.lineward.aggregation.TargetSystemAggregation;
import com.lineward.connector.TargetSystemConnector;
import com.lineward.connector.TargetSystemConnectors;
import com.lineward.shared.TenantTransactions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives one aggregation across every system the installation governs.
 *
 * <p>Three questions in order, and each has a module that owns the answer: M4
 * says which applications are switched on, M3 says which of them there is a
 * credential for, and {@link ApplicationAggregation} does the reading. Nothing
 * here knows what kind of system anything is.
 *
 * <p>Except for one check, which is the one place that can make it. The kind of
 * system is written down twice, by necessity rather than by choice:
 * {@code application.connector_type} is the governance record of what a system
 * is, and the configuration of M3 says how to reach it. Only this class sees
 * both, so only this class can notice that they disagree, and a disagreement
 * means somebody pointed the intranet's configuration at the directory. It
 * fails rather than reading, because the read would write the directory's
 * accounts under the intranet and the catalogue would be wrong in a way nobody
 * could untangle afterwards.
 */
final class AggregationRunner implements TargetSystemAggregation {

    private static final Logger log = LoggerFactory.getLogger(AggregationRunner.class);

    private final ApplicationLookup applications;
    private final TargetSystemConnectors connectors;
    private final ApplicationAggregation aggregation;
    private final TenantTransactions tenants;
    private final UUID orgId;

    AggregationRunner(
            ApplicationLookup applications,
            TargetSystemConnectors connectors,
            ApplicationAggregation aggregation,
            TenantTransactions tenants,
            UUID orgId) {
        this.applications = applications;
        this.connectors = connectors;
        this.aggregation = aggregation;
        this.tenants = tenants;
        this.orgId = orgId;
    }

    @Override
    public List<AggregationReport> aggregateEverything() {
        List<GovernedApplication> governed =
                tenants.inTenant(orgId, () -> applications.enabledApplications(orgId));

        List<AggregationReport> reports = new ArrayList<>();
        for (GovernedApplication application : governed) {
            Optional<TargetSystemConnector> connector = connectors.forApplication(application.code());
            if (connector.isEmpty()) {
                // Said out loud, because the alternative is an application that
                // is never aggregated and never mentioned, which reads from
                // every report as a system with nothing in it.
                log.info("Skipping '{}': the installation governs it and no connector is configured for it",
                        application.code());
                continue;
            }
            reports.add(aggregation.read(application, agreeing(application, connector.get())));
        }

        log.info("Aggregated {} of the {} applications this installation governs",
                reports.size(), governed.size());
        return List.copyOf(reports);
    }

    private static TargetSystemConnector agreeing(
            GovernedApplication application, TargetSystemConnector connector) {
        if (!connector.type().name().equals(application.connectorType())) {
            throw new IllegalStateException(
                    "application '" + application.code() + "' is registered as a "
                            + application.connectorType() + " system and the connector configured for it"
                            + " speaks to " + connector.type() + ". Reading it would store one system's"
                            + " accounts under the other, so nothing is read until the two agree");
        }
        return connector;
    }
}
