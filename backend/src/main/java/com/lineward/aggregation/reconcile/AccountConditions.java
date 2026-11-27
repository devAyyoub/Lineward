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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What is wrong with the accounts of one target system, taken from the accounts
 * themselves.
 *
 * <p>Two findings and one question each. An orphan is an account nobody is
 * accountable for; a dormant account is one nobody uses. They are kept in the
 * same class because they are the same read: one pass over the accounts of an
 * application, decided row by row, and splitting them would be two scans of the
 * same table to answer two questions about the same row.
 *
 * <p>The reading and the deciding are separate methods on purpose. What the
 * table holds is a fact, what makes it a finding is a rule, and the rule is
 * where the mistakes are: it can be tested on a handful of rows built by hand
 * without a database anywhere near it.
 */
final class AccountConditions {

    private static final String SERVICE = "SERVICE";
    private static final String ACTIVE = "ACTIVE";
    private static final String DELETED = "DELETED";

    private final JdbcClient jdbc;

    AccountConditions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One stored account, with the four columns these two rules read.
     *
     * @param correlated     whether anything or anybody has said whose it is.
     *     A boolean and not the identity id, because neither rule cares who the
     *     owner is, only that there is one, and carrying the id would invite a
     *     third rule to use it without saying so
     * @param kind           what the target system said the account is, as
     *     V007 stores it: {@code SERVICE} or {@code UNKNOWN}
     * @param status         what the target system said the account's state is
     * @param lastActivityAt when the target system last saw it used, or null
     *     when the system does not say. <b>Null is not "never used"</b>: a
     *     directory has no such attribute at all, so the same null means
     *     "never" in one system and "unmeasurable" in another
     */
    record StoredAccount(UUID id, boolean correlated, String kind, String status, OffsetDateTime lastActivityAt) {}

    List<StoredAccount> read(UUID orgId, UUID applicationId) {
        return jdbc.sql("""
                SELECT id, identity_id, kind, status, last_activity_at
                  FROM account
                 WHERE org_id = :orgId
                   AND application_id = :applicationId
                 ORDER BY id
                """)
                .param("orgId", orgId)
                .param("applicationId", applicationId)
                .query((rs, row) -> new StoredAccount(
                        rs.getObject("id", UUID.class),
                        rs.getObject("identity_id", UUID.class) != null,
                        rs.getString("kind"),
                        rs.getString("status"),
                        rs.getObject("last_activity_at", OffsetDateTime.class)))
                .list();
    }

    /**
     * Whether this target system measures activity at all, which decides
     * whether dormancy is a question it can be asked.
     *
     * <p><b>Measured from the data and not declared in configuration</b>, and
     * the alternative is what makes this worth a method of its own. A flag per
     * application would be a second place to keep the truth, and it would be
     * wrong the day a connector starts reporting activity. One account carrying
     * a timestamp is proof the system reports it; no account carrying one is
     * proof of nothing, and the honest answer to a question that cannot be
     * answered is to say so rather than to answer no.
     *
     * <p>What it costs is stated so that nobody discovers it later: a system
     * that reports activity and all of whose accounts have never been used
     * reads as a system that does not report activity, and reports no dormancy.
     * Being quiet about accounts nobody can measure is the safe direction of
     * that error.
     */
    static boolean reportsActivity(List<StoredAccount> accounts) {
        return accounts.stream().anyMatch(account -> account.lastActivityAt() != null);
    }

    /**
     * The conditions that hold right now, as findings.
     *
     * <p><b>An orphan is never a service account</b>, and that is the one
     * exclusion here that is a governance decision rather than arithmetic. A
     * target system that serves an account as a machine's has told Lineward
     * that no person is supposed to be behind it, so correlation refuses to
     * guess an owner for it (V007 says why) and this refuses to call the
     * absence a failure. The condition that service accounts really do have,
     * that nobody answers for them, is
     * {@link FindingType#UNOWNED_IDENTITY} and cannot be written down yet.
     *
     * <p><b>A deleted account is not an orphan either.</b> The target system
     * has said it is gone; an account nobody owns and that no longer exists is
     * not access anybody holds, and listing it would fill the list with
     * history.
     *
     * <p><b>Only an active account can be dormant.</b> A disabled or locked one
     * is unused because it was switched off, which is the outcome somebody
     * wanted rather than the risk this finding is about.
     *
     * @param accounts     every account of the application, as {@link #read}
     *     returned them
     * @param now          the pass's clock reading
     * @param dormantAfter how long without recorded use makes an account
     *     dormant
     */
    static Set<Finding> findings(List<StoredAccount> accounts, OffsetDateTime now, Duration dormantAfter) {
        boolean measurable = reportsActivity(accounts);
        OffsetDateTime stale = now.minus(dormantAfter);

        Set<Finding> findings = new LinkedHashSet<>();
        for (StoredAccount account : accounts) {
            if (!account.correlated()
                    && !SERVICE.equals(account.kind())
                    && !DELETED.equals(account.status())) {
                findings.add(Finding.about(FindingType.ORPHAN_ACCOUNT, account.id()));
            }
            if (measurable && ACTIVE.equals(account.status()) && unused(account, stale)) {
                findings.add(Finding.about(FindingType.DORMANT_ACCOUNT, account.id()));
            }
        }
        return findings;
    }

    /**
     * Never used counts as unused, and only in a system that measures use.
     *
     * <p>The caller has already established that this system reports activity,
     * so a null here is the system saying the account has never been used
     * rather than the system having nothing to say. That is the strongest
     * statement Okta's organization can make about dormancy, because
     * {@code lastLogin} is written when somebody really signs in and never by
     * the API that created the account.
     */
    private static boolean unused(StoredAccount account, OffsetDateTime stale) {
        return account.lastActivityAt() == null || account.lastActivityAt().isBefore(stale);
    }
}
