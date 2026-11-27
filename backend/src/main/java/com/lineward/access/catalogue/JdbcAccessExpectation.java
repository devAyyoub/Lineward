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
package com.lineward.access.catalogue;

import com.lineward.access.AccessExpectation;
import com.lineward.access.ExpectedGrant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The expected state of one target system, read from the two tables M4 owns.
 *
 * <p>No transaction of its own, like the rest of the platform's readers: the
 * caller decides how much work shares one. Here the caller is a reconciliation,
 * which reads both sides of its comparison inside one transaction so that an
 * aggregation finishing halfway through cannot make the expectation and the
 * reality come from two different moments.
 *
 * <p>The {@code org_id} predicate is in both queries although the row level
 * security policy already filters on the tenant, for the reason
 * {@link JdbcApplicationLookup} gives.
 */
public final class JdbcAccessExpectation implements AccessExpectation {

    private final JdbcClient jdbc;

    public JdbcAccessExpectation(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The join to {@code entitlement} is what scopes this to one application,
     * and it is a join and not a subquery on purpose: {@code access_assignment}
     * has a real foreign key to {@code entitlement}, both tables belong to this
     * module, and the application an assignment is about is only knowable
     * through it.
     */
    @Override
    public List<ExpectedGrant> liveGrants(UUID orgId, UUID applicationId) {
        return jdbc.sql("""
                SELECT a.identity_id, a.entitlement_id, a.state
                  FROM access_assignment a
                  JOIN entitlement e ON e.id = a.entitlement_id AND e.org_id = a.org_id
                 WHERE a.org_id = :orgId
                   AND e.application_id = :applicationId
                   AND a.state IN ('PENDING', 'ACTIVE')
                """)
                .param("orgId", orgId)
                .param("applicationId", applicationId)
                .query((rs, row) -> new ExpectedGrant(
                        rs.getObject("identity_id", UUID.class),
                        rs.getObject("entitlement_id", UUID.class),
                        "ACTIVE".equals(rs.getString("state"))))
                .list();
    }

    @Override
    public Set<UUID> catalogueOf(UUID orgId, UUID applicationId) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT id
                  FROM entitlement
                 WHERE org_id = :orgId
                   AND application_id = :applicationId
                """)
                .param("orgId", orgId)
                .param("applicationId", applicationId)
                .query(UUID.class)
                .list());
    }
}
