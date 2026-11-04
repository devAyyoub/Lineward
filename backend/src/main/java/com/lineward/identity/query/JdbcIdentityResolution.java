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

import com.lineward.identity.IdentityResolution;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The two lookups correlation needs, read from the tables M1 owns.
 *
 * <p>No transaction of its own, like the rest of the read side: the caller is an
 * aggregation that has a page of accounts open in one, and a read on a new
 * transaction would not see what that one has just written.
 */
public final class JdbcIdentityResolution implements IdentityResolution {

    private final JdbcClient jdbc;

    public JdbcIdentityResolution(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Distinct, because the shape of the table allows the same identity to be
     * reached twice. {@code source_record} has one row per identity per source
     * system, so two sources asserting the same number for the same person are
     * two rows and one answer, which is not ambiguity and must not be reported
     * as such.
     */
    @Override
    public List<UUID> byExternalId(UUID orgId, String externalId) {
        return jdbc.sql("""
                SELECT DISTINCT identity_id
                  FROM source_record
                 WHERE org_id = :orgId
                   AND external_id = :externalId
                """)
                .param("orgId", orgId)
                .param("externalId", externalId)
                .query(UUID.class)
                .list();
    }

    /**
     * {@code lower()} on both sides rather than a case insensitive collation,
     * because the index on this column is a plain one and a comparison the
     * index cannot use is a scan of the whole population. At five thousand rows
     * neither is measurable; the reason to write it this way is that the day it
     * is, the fix is an expression index on exactly this expression and no
     * change here.
     */
    @Override
    public List<UUID> byEmail(UUID orgId, String email) {
        return jdbc.sql("""
                SELECT id
                  FROM identity
                 WHERE org_id = :orgId
                   AND lower(email) = lower(:email)
                """)
                .param("orgId", orgId)
                .param("email", email)
                .query(UUID.class)
                .list();
    }
}
