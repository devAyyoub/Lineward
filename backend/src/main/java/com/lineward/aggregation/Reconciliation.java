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
 * Compares what the target systems really hold against what Lineward says they
 * should, and writes down the differences.
 *
 * <p>The second half of M2 and the one that produces findings.
 * {@link TargetSystemAggregation} reads: it talks to the target systems and
 * stores what they said. This compares: it talks to nothing and reads only what
 * is already stored. Keeping them apart is what stops a target system that is
 * down from costing a night's findings, and it is why this interface has no
 * connector anywhere in its signature.
 *
 * <p><b>It can only reconcile what has been read.</b> An application with no
 * accounts stored is skipped rather than reconciled, and the distinction is the
 * whole safety of the operation: a system nobody has aggregated and a system
 * with nothing in it look identical from this side, and treating the first as
 * the second would report every access the platform expects as missing. The
 * guard is one row of evidence, not a flag somebody sets.
 */
public interface Reconciliation {

    /**
     * Reconciles every application this installation governs and has read at
     * least once.
     *
     * <p>Idempotent, and that is the property worth testing: a second pass over
     * an unchanged installation opens nothing, resolves nothing and moves the
     * heartbeat of the findings that were already there. A finding is one row
     * per subject, not one per pass, because the question it answers is what is
     * wrong now and not what was wrong every night since March.
     *
     * @return one report per application it reconciled, in the order it took
     *     them. An application it skipped for want of a read is not in the
     *     list, and is named in the log instead, for the reason
     *     {@code AggregationRunner} gives about an application with no
     *     connector
     */
    List<ReconciliationReport> reconcileEverything();
}
