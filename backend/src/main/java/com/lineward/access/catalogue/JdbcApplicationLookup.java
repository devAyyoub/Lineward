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

import com.lineward.access.ApplicationLookup;
import com.lineward.access.GovernedApplication;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The applications of an organization, read from the table M4 owns.
 *
 * <p>No transaction of its own, like the rest of the platform's readers: the
 * caller decides how much work shares one, and the caller here is an
 * aggregation that is about to spend minutes talking to a target system. A
 * transaction held open across that would be a transaction held open across
 * somebody else's network.
 *
 * <p>The {@code org_id} predicate is in the query although the row level
 * security policy already filters on the tenant, for the reason
 * {@code JdbcIdentityLookup} gives: the policy decides which rows this
 * connection may see, and the predicate says which organization the caller
 * asked about.
 */
public final class JdbcApplicationLookup implements ApplicationLookup {

    private final JdbcClient jdbc;

    public JdbcApplicationLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Ordered by code, so that a run processes the applications in the same
     * order every night. Not for the sake of the order itself but for the logs:
     * two runs that read the same systems in a different sequence are two runs
     * nobody can diff.
     */
    @Override
    public List<GovernedApplication> enabledApplications(UUID orgId) {
        return jdbc.sql("""
                SELECT id, code, name, connector_type
                  FROM application
                 WHERE org_id = :orgId
                   AND enabled
                 ORDER BY code
                """)
                .param("orgId", orgId)
                .query((rs, row) -> new GovernedApplication(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("connector_type")))
                .list();
    }
}
