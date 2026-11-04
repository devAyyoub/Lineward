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

import com.lineward.aggregation.AggregationReport.Correlation;
import com.lineward.aggregation.ManualCorrelation;
import com.lineward.connector.DiscoveredAccount;
import com.lineward.identity.IdentityLookup;
import com.lineward.identity.IdentityResolution;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Who each account belongs to, decided by rule and occasionally by a person.
 *
 * <p>This is what the rest of M2 was for. An account with an owner is access
 * somebody is accountable for, and an account without one is the finding that
 * gets a platform bought. Everything in here is arranged so that the second
 * case is reported rather than guessed at.
 *
 * <p><b>The rules, in order, and why that order.</b> First the employee number
 * the account carries as an attribute, because that is a key the organization
 * issued and the authoritative source asserts; then the email address, because
 * that is a string somebody typed into a target system and two people can end
 * up sharing one. A platform that preferred the email would report an account
 * whose address nobody recognises as an orphan while the number on it says
 * exactly whose it is.
 *
 * <p><b>More than one match is refused and not resolved.</b> Two people
 * matching one account is not a tie to be broken: handing the account to
 * whichever row came back first would make one of them accountable for the
 * other's access, and nothing would ever say which. It is reported as ambiguous
 * and the account is left without an owner, which is a finding somebody can
 * fix. A multi valued attribute is covered by the same path rather than a case
 * of its own: every value is resolved and the matches are unioned, so an
 * account carrying two employee numbers for two different people is ambiguous
 * by the same arithmetic.
 *
 * <p><b>A higher rule that matched ambiguously stops the chain.</b> It does not
 * fall through to the email, and that is deliberate: the account carries a key
 * that points at two people, and picking one of them on a weaker signal is the
 * silent wrong answer this whole class exists to avoid.
 *
 * <p><b>A decision a person took is not a rule's to revisit.</b> Checked here
 * and also written into the statement the rules update with, which refuses a
 * row whose correlation is manual. The check in the code is for the counters;
 * the one in the statement is the control, and it is there because a control
 * that lives in whoever remembers to look is not a control (rule 10 of the
 * project document).
 *
 * <p>No transaction of its own. It runs inside the page's, with the write that
 * produced the rows, so a correlation and the account it points at cannot end
 * up on opposite sides of a crash.
 */
final class AccountCorrelation implements ManualCorrelation {

    /** By employee number, which is {@code source_record.external_id} (V001 says why it lives there). */
    private static final String BY_EMPLOYEE_NUMBER = "employee-number";

    private static final String BY_EMAIL = "email";

    /** The one value of {@code account.correlation_method} the rules must not touch. */
    private static final String MANUAL = "MANUAL";

    private final JdbcClient jdbc;
    private final IdentityResolution identities;
    private final IdentityLookup people;
    private final Clock clock;
    private final String employeeNumberAttribute;

    /**
     * @param employeeNumberAttribute what the target systems call the employee
     *     number. Configuration and not a constant because every system names
     *     it differently, and one name for the installation rather than one per
     *     application because both systems this repository ships use
     *     {@code employeeNumber}: the realm declares it in its user profile and
     *     the directory sets the standard LDAP attribute of that name. A second
     *     kind of target system is what would make this per application, and
     *     that day it moves into the target's own configuration
     */
    AccountCorrelation(
            JdbcClient jdbc,
            IdentityResolution identities,
            IdentityLookup people,
            Clock clock,
            String employeeNumberAttribute) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.people = people;
        this.clock = clock;
        this.employeeNumberAttribute = employeeNumberAttribute;
    }

    Correlation apply(UUID orgId, List<AccountWriter.AccountRow> rows) {
        Correlation total = Correlation.NOTHING;
        for (AccountWriter.AccountRow row : rows) {
            total = total.plus(decide(orgId, row));
        }
        return total;
    }

    /**
     * One account, and the four outcomes it can have.
     *
     * <p>An account that was already correlated to the same person by the same
     * rule produces none of them, which is what makes a second run report
     * nothing and leaves {@code correlated_at} answering the question it is
     * for: when this was decided, not when it was last confirmed.
     */
    private Correlation decide(UUID orgId, AccountWriter.AccountRow row) {
        if (MANUAL.equals(row.correlationMethod())) {
            return Correlation.NOTHING;
        }

        Match match = firstMatch(orgId, row.read());

        if (match.identities().size() > 1) {
            return new Correlation(0, releaseIfHeld(orgId, row), 1, 0);
        }
        if (match.identities().isEmpty()) {
            return new Correlation(0, releaseIfHeld(orgId, row), 0, 1);
        }

        UUID owner = match.identities().getFirst();
        if (owner.equals(row.identityId()) && match.ruleCode().equals(row.correlationRuleCode())) {
            return Correlation.NOTHING;
        }
        // Counted from what the statement did and not from what this intended.
        // The two can differ, and the case where they differ is the one that
        // matters: a row the condition refused because a person had decided it.
        return new Correlation(link(orgId, row.id(), owner, match.ruleCode()), 0, 0, 0);
    }

    /** What matched, and how many, so that the caller decides what one and what several mean. */
    private record Match(String ruleCode, List<UUID> identities) {

        static final Match NONE = new Match(null, List.of());
    }

    private Match firstMatch(UUID orgId, DiscoveredAccount read) {
        Set<UUID> byNumber = new LinkedHashSet<>();
        for (String number : read.attributes().getOrDefault(employeeNumberAttribute, List.of())) {
            if (number != null && !number.isBlank()) {
                byNumber.addAll(identities.byExternalId(orgId, number));
            }
        }
        if (!byNumber.isEmpty()) {
            return new Match(BY_EMPLOYEE_NUMBER, List.copyOf(byNumber));
        }

        if (read.email() != null && !read.email().isBlank()) {
            List<UUID> byEmail = identities.byEmail(orgId, read.email());
            if (!byEmail.isEmpty()) {
                return new Match(BY_EMAIL, byEmail);
            }
        }
        return Match.NONE;
    }

    /**
     * The condition on {@code correlation_method} is the control.
     *
     * <p>A rule may write over nothing and over another rule's answer, and
     * never over a person's. Stating it in the statement rather than only in
     * the branch above means a mistake in the code that calls this cannot undo
     * a human decision: the row simply does not match.
     *
     * @return how many rows it wrote, which is what the report counts. The
     *     first version of this returned nothing and the caller counted a
     *     correlation it had merely attempted; removing the branch above to
     *     check that the condition here was doing the work showed the data
     *     protected and the number wrong
     */
    private int link(UUID orgId, UUID accountId, UUID identityId, String ruleCode) {
        return jdbc.sql("""
                UPDATE account
                   SET identity_id           = :identityId,
                       correlation_method    = 'RULE',
                       correlation_rule_code = :ruleCode,
                       correlated_at         = :now
                 WHERE org_id = :orgId
                   AND id = :accountId
                   AND (correlation_method IS NULL OR correlation_method = 'RULE')
                """)
                .param("identityId", identityId)
                .param("ruleCode", ruleCode)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC))
                .param("orgId", orgId)
                .param("accountId", accountId)
                .update();
    }

    /**
     * Takes the account back when the person it matched stops matching.
     *
     * <p>All four columns together, because the check constraint of V001
     * refuses half a correlation, and because the half that would survive is the
     * misleading one: an account still showing an owner is an account nobody
     * reviews, since it looks answered.
     *
     * @return how many rows it released, which is one or zero and is what the
     *     report counts. Read from the statement rather than assumed, so that a
     *     row the condition refused is not counted as released
     */
    private int releaseIfHeld(UUID orgId, AccountWriter.AccountRow row) {
        if (row.identityId() == null) {
            return 0;
        }
        return jdbc.sql("""
                UPDATE account
                   SET identity_id           = NULL,
                       correlation_method    = NULL,
                       correlation_rule_code = NULL,
                       correlated_at         = NULL
                 WHERE org_id = :orgId
                   AND id = :accountId
                   AND correlation_method = 'RULE'
                """)
                .param("orgId", orgId)
                .param("accountId", row.id())
                .update();
    }

    @Override
    public void correlate(UUID orgId, UUID accountId, UUID identityId) {
        // Asked before writing, and this is the only place it can be asked.
        // account.identity_id points across a module boundary, so ADR-001
        // leaves it without a foreign key, which means the database cannot
        // refuse an id that belongs to nobody.
        people.attributesOf(orgId, identityId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no identity " + identityId + " in organization " + orgId
                                + ", so no account can be said to belong to it"));

        int updated = jdbc.sql("""
                UPDATE account
                   SET identity_id           = :identityId,
                       correlation_method    = 'MANUAL',
                       correlation_rule_code = NULL,
                       correlated_at         = :now
                 WHERE org_id = :orgId
                   AND id = :accountId
                """)
                .param("identityId", identityId)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC))
                .param("orgId", orgId)
                .param("accountId", accountId)
                .update();

        if (updated == 0) {
            throw new IllegalArgumentException(
                    "no account " + accountId + " in organization " + orgId + " to correlate");
        }
    }
}
