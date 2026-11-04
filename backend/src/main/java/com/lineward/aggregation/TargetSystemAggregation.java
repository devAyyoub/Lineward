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
package com.lineward.aggregation;

import java.util.List;

/**
 * Reads every target system this installation governs and writes down what it
 * found.
 *
 * <p>The write side of M2, and the counterpart of {@code IdentityIngestion} on
 * the other half of the comparison: that one brings in what the authoritative
 * source claims about who exists, this one brings in what the target systems
 * say is really switched on. Reconciling the two is KAN-12, and it has nothing
 * to compare until this has run.
 *
 * <p>One method, because there is one operation anybody has asked for. An
 * incremental read of one application and a read of one system on demand are
 * both reasonable and neither exists yet: the first needs the checkpoints of
 * KAN-13 and the second needs somebody to ask, and inventing either now would
 * mean two more shapes to keep working.
 */
public interface TargetSystemAggregation {

    /**
     * Reads every enabled application that has a connector configured.
     *
     * <p>An application with no connector is skipped and said so in the log,
     * not failed: an installation registers the systems it governs before it
     * has been given a credential for each, and refusing to aggregate the ones
     * it can reach would be refusing to do the possible half of the work.
     *
     * @return one report per application it read, in the order it read them.
     *     The reports say whether each system answered at all, which is what
     *     stops a system that was unreachable from reading as a system with no
     *     accounts
     * @throws com.lineward.connector.ConnectorException if a read broke part
     *     way through. Not caught and turned into a report: a partial read
     *     reported as a complete one makes every account that did not arrive
     *     look deleted, and the next phase acts on that
     */
    List<AggregationReport> aggregateEverything();
}
