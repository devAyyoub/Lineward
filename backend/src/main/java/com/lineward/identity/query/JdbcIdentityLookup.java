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
package com.lineward.identity.query;

import com.lineward.identity.GovernedAttributes;
import com.lineward.identity.IdentityLookup;
import com.lineward.identity.IdentityStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The governed view of an identity, read from the table M1 owns.
 *
 * <p>No transaction of its own, on purpose. The caller decides how much work
 * shares one, and the caller that matters here is a lifecycle listener running
 * inside the transaction that just wrote the row: a read on a new transaction
 * would not see it, and the access calculation would run against a row that
 * does not exist yet.
 *
 * <p>The {@code org_id} predicate is in the query although the row level
 * security policy already filters on the tenant. It is not redundant defence,
 * it is a different question: the policy decides which rows this connection may
 * see, and the predicate says which organization the caller asked about. Without
 * it, a caller passing the wrong organization would get the right row back and
 * never find out.
 */
public final class JdbcIdentityLookup implements IdentityLookup {

    private final JdbcClient jdbc;

    public JdbcIdentityLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<GovernedAttributes> attributesOf(UUID orgId, UUID identityId) {
        return jdbc.sql("""
                SELECT id, status, department, job_title, location, cost_center, employment_type
                  FROM identity
                 WHERE org_id = :orgId
                   AND id = :identityId
                """)
                .param("orgId", orgId)
                .param("identityId", identityId)
                .query((rs, row) -> new GovernedAttributes(
                        rs.getObject("id", UUID.class),
                        IdentityStatus.valueOf(rs.getString("status")),
                        rs.getString("department"),
                        rs.getString("job_title"),
                        rs.getString("location"),
                        rs.getString("cost_center"),
                        rs.getString("employment_type")))
                .optional();
    }
}
