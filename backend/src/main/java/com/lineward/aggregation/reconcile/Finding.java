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
package com.lineward.aggregation.reconcile;

import com.lineward.aggregation.FindingType;
import java.util.UUID;

/**
 * One problem, identified by what it is about rather than by its row.
 *
 * <p>This is the key of {@code reconciliation_finding_open_key}, the partial
 * unique index that allows one open finding per subject, expressed as a type so
 * that the comparison between what a pass found and what was already stored is
 * set arithmetic and not a query per candidate.
 *
 * <p><b>The three subject fields are nullable and exactly one combination of
 * them is filled</b>, which the schema also says with a check constraint. They
 * are not collapsed into one field because they point at three different
 * tables, and a single column holding "the id of something" is how a finding
 * about an account ends up compared equal to a finding about an identity that
 * happens to share no id at all.
 */
record Finding(FindingType type, UUID accountId, UUID identityId, UUID entitlementId) {

    /** An account nobody could place, or one nobody has used. */
    static Finding about(FindingType type, UUID accountId) {
        return new Finding(type, accountId, null, null);
    }

    /** A difference between what an identity holds and what it is due. */
    static Finding about(FindingType type, UUID identityId, UUID entitlementId) {
        return new Finding(type, null, identityId, entitlementId);
    }
}
