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

import com.lineward.access.ExpectedGrant;
import com.lineward.aggregation.FindingType;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The difference between the access a target system really grants and the
 * access Lineward says it should.
 *
 * <p>This is the pair the working document calls the engine of phase 1,
 * {@code access_assignment} against {@code account_entitlement}, and the
 * subtraction is done here because the finding is M2's. The expected side
 * arrives through {@link com.lineward.access.AccessExpectation} rather than
 * from a query, because the rows belong to M4.
 *
 * <p><b>The two subtractions are not symmetric, and that is the design.</b>
 *
 * <ul>
 * <li><b>Unexpected</b> is reality minus everything that is due, whether or not
 * anything has provisioned it. An access that a birthright rule says the person
 * should have is not granted outside the platform just because the platform has
 * not been the one to grant it yet, and reporting it would be accusing the
 * organization of its own policy.
 * <li><b>Missing</b> is only what Lineward records as really in place, minus
 * reality. {@code PENDING} means nothing has provisioned it; subtracting that
 * from reality would report every expectation the platform holds as a failure.
 * In phase 1 nothing ever moves an assignment to {@code ACTIVE}, which
 * {@code ExpectedAccessWriter} states on the writing side, so this subtraction
 * is empty until provisioning exists. That is the correct answer and not a
 * missing feature: there is nothing yet that could have failed.
 * </ul>
 *
 * <p><b>Only accounts with an owner take part.</b> A finding of this kind names
 * an identity, which the schema enforces, so an orphan's memberships cannot be
 * expressed as drift and are not silently dropped either: the account itself is
 * already a finding, and the access it holds is the reason that finding
 * matters.
 */
final class GrantDrift {

    private final JdbcClient jdbc;

    GrantDrift(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every access this target system really grants to somebody Lineward can
     * name.
     *
     * <p>The join to {@code account} is what makes the pair an identity's
     * rather than an account's, and it is an inner join on a column that may be
     * null, so an orphan's memberships drop out here by construction rather
     * than by a filter somebody has to remember.
     */
    Set<ExpectedGrant.Pair> realGrants(UUID orgId, UUID applicationId) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT a.identity_id, ae.entitlement_id
                  FROM account_entitlement ae
                  JOIN account a ON a.id = ae.account_id AND a.org_id = ae.org_id
                 WHERE ae.org_id = :orgId
                   AND a.application_id = :applicationId
                   AND a.identity_id IS NOT NULL
                """)
                .param("orgId", orgId)
                .param("applicationId", applicationId)
                .query((rs, row) -> new ExpectedGrant.Pair(
                        rs.getObject("identity_id", UUID.class), rs.getObject("entitlement_id", UUID.class)))
                .list());
    }

    /**
     * The two drift findings of one target system.
     *
     * <p>Pure, and takes both sides as arguments, so the asymmetry above can be
     * tested on four rows without a database: the case that matters is the one
     * where a pending expectation and an active one produce different answers
     * from the same reality.
     *
     * @param real     what the system grants, from {@link #realGrants}
     * @param expected what Lineward says is due, from
     *     {@link com.lineward.access.AccessExpectation#liveGrants}. The same
     *     pair may appear more than once when two sources grant it, which the
     *     set arithmetic collapses: a pair is due or it is not, and twice due
     *     is not more due
     */
    static Set<Finding> findings(Set<ExpectedGrant.Pair> real, Collection<ExpectedGrant> expected) {
        Set<ExpectedGrant.Pair> due = new LinkedHashSet<>();
        Set<ExpectedGrant.Pair> provisioned = new LinkedHashSet<>();
        for (ExpectedGrant grant : expected) {
            due.add(grant.pair());
            if (grant.provisioned()) {
                provisioned.add(grant.pair());
            }
        }

        Set<Finding> findings = new LinkedHashSet<>();
        for (ExpectedGrant.Pair pair : real) {
            if (!due.contains(pair)) {
                findings.add(Finding.about(
                        FindingType.DRIFT_UNEXPECTED_GRANT, pair.identityId(), pair.entitlementId()));
            }
        }
        for (ExpectedGrant.Pair pair : provisioned) {
            if (!real.contains(pair)) {
                findings.add(Finding.about(
                        FindingType.DRIFT_MISSING_GRANT, pair.identityId(), pair.entitlementId()));
            }
        }
        return findings;
    }
}
