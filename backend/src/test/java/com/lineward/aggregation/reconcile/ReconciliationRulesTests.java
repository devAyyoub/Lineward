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

import static org.assertj.core.api.Assertions.assertThat;

import com.lineward.access.ExpectedGrant;
import com.lineward.aggregation.FindingType;
import com.lineward.aggregation.reconcile.AccountConditions.StoredAccount;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * What makes a stored row a finding, decided without a database.
 *
 * <p>Every one of these rules is a sentence somebody will be shown next to a
 * person's name, and the expensive mistakes are not in the SQL. They are in the
 * four exclusions: a service account is not an orphan, a deleted account is not
 * an orphan, an unmeasurable system has no dormancy, and an access that is due
 * but unprovisioned is neither drift nor failure. Each one is the difference
 * between a list people act on and a list people stop reading.
 *
 * <p>No PostgreSQL anywhere, on purpose. These are decisions about rows, the
 * rows are four fields each, and a test that had to migrate a schema to assert
 * one of them would be slower and would say less.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ReconciliationRulesTests {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-27T09:00:00Z");

    private static final Duration NINETY_DAYS = Duration.ofDays(90);

    private static final UUID LUCIA = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID MARTA = UUID.fromString("00000000-0000-7000-8000-00000000000b");
    private static final UUID TREASURY = UUID.fromString("00000000-0000-7000-8000-0000000000c1");
    private static final UUID INTRANET = UUID.fromString("00000000-0000-7000-8000-0000000000c2");

    // --- orphans -----------------------------------------------------------

    @Test
    void an_account_nobody_could_place_is_an_orphan() {
        StoredAccount lost = account(false, "UNKNOWN", "ACTIVE", null);

        assertThat(AccountConditions.findings(List.of(lost), NOW, NINETY_DAYS))
                .containsExactly(Finding.about(FindingType.ORPHAN_ACCOUNT, lost.id()));
    }

    @Test
    void an_account_with_an_owner_is_not_an_orphan() {
        assertThat(AccountConditions.findings(List.of(account(true, "UNKNOWN", "ACTIVE", null)), NOW, NINETY_DAYS))
                .isEmpty();
    }

    /**
     * The exclusion that keeps the list readable. A target system that serves
     * an account as a machine's has said no person is supposed to be behind it,
     * so correlation refuses to guess an owner and this refuses to call the
     * absence a failure. Without it, every service account of every system
     * joins the list of orphans somebody is supposed to chase, and a list that
     * cried wolf is a list nobody opens.
     */
    @Test
    void a_service_account_nobody_could_place_is_not_an_orphan() {
        assertThat(AccountConditions.findings(List.of(account(false, "SERVICE", "ACTIVE", null)), NOW, NINETY_DAYS))
                .isEmpty();
    }

    /** An account the target system has said is gone is not access anybody holds. */
    @Test
    void a_deleted_account_nobody_could_place_is_not_an_orphan() {
        assertThat(AccountConditions.findings(List.of(account(false, "UNKNOWN", "DELETED", null)), NOW, NINETY_DAYS))
                .isEmpty();
    }

    // --- dormancy ----------------------------------------------------------

    @Test
    void an_account_unused_for_longer_than_the_period_is_dormant() {
        StoredAccount stale = account(true, "UNKNOWN", "ACTIVE", NOW.minusDays(91));

        assertThat(AccountConditions.findings(List.of(stale), NOW, NINETY_DAYS))
                .containsExactly(Finding.about(FindingType.DORMANT_ACCOUNT, stale.id()));
    }

    @Test
    void an_account_used_inside_the_period_is_not_dormant() {
        assertThat(AccountConditions.findings(
                        List.of(account(true, "UNKNOWN", "ACTIVE", NOW.minusDays(89))), NOW, NINETY_DAYS))
                .isEmpty();
    }

    /**
     * The strongest statement Okta's organization can make: {@code lastLogin}
     * is written when somebody really signs in and never by the API that
     * created the account, so an account with none has never been used. It only
     * means that where the system measures use at all, which the next test is
     * about.
     */
    @Test
    void an_account_never_used_in_a_system_that_measures_use_is_dormant() {
        StoredAccount never = account(true, "UNKNOWN", "ACTIVE", null);
        StoredAccount used = account(true, "UNKNOWN", "ACTIVE", NOW.minusDays(1));

        assertThat(AccountConditions.findings(List.of(never, used), NOW, NINETY_DAYS))
                .containsExactly(Finding.about(FindingType.DORMANT_ACCOUNT, never.id()));
    }

    /**
     * The exclusion that stops a directory reporting itself. LDAP has no
     * last-login attribute at all, so every account comes back with a null, and
     * a rule that read null as "never used" would open a finding for every
     * person in the organization on the first night.
     */
    @Test
    void a_system_that_measures_use_for_nobody_reports_no_dormancy() {
        List<StoredAccount> directory = List.of(
                account(true, "UNKNOWN", "ACTIVE", null),
                account(true, "UNKNOWN", "ACTIVE", null),
                account(true, "UNKNOWN", "ACTIVE", null));

        assertThat(AccountConditions.reportsActivity(directory)).isFalse();
        assertThat(AccountConditions.findings(directory, NOW, NINETY_DAYS)).isEmpty();
    }

    /** Unused because it was switched off is the outcome somebody wanted, not the risk. */
    @Test
    void a_disabled_account_is_not_dormant() {
        List<StoredAccount> accounts = List.of(
                account(true, "UNKNOWN", "DISABLED", null),
                account(true, "UNKNOWN", "ACTIVE", NOW.minusDays(1)));

        assertThat(AccountConditions.findings(accounts, NOW, NINETY_DAYS)).isEmpty();
    }

    /** One account can be both, and the two findings are about different problems. */
    @Test
    void an_account_can_be_an_orphan_and_dormant_at_once() {
        StoredAccount leaver = account(false, "UNKNOWN", "ACTIVE", NOW.minusDays(200));

        assertThat(AccountConditions.findings(List.of(leaver), NOW, NINETY_DAYS))
                .containsExactlyInAnyOrder(
                        Finding.about(FindingType.ORPHAN_ACCOUNT, leaver.id()),
                        Finding.about(FindingType.DORMANT_ACCOUNT, leaver.id()));
    }

    // --- drift -------------------------------------------------------------

    @Test
    void an_access_the_system_grants_and_nothing_says_is_due_is_unexpected() {
        assertThat(GrantDrift.findings(Set.of(new ExpectedGrant.Pair(LUCIA, TREASURY)), List.of()))
                .containsExactly(Finding.about(FindingType.DRIFT_UNEXPECTED_GRANT, LUCIA, TREASURY));
    }

    /**
     * The asymmetry, from the unexpected side. An access a birthright rule says
     * is due is not granted outside the platform just because the platform has
     * not been the one to grant it: reporting it would be accusing the
     * organization of following its own policy, and in phase 1, where every
     * expectation is pending, it would report all of them.
     */
    @Test
    void an_access_that_is_due_but_unprovisioned_is_not_unexpected_when_the_system_has_it() {
        assertThat(GrantDrift.findings(
                        Set.of(new ExpectedGrant.Pair(LUCIA, TREASURY)),
                        List.of(new ExpectedGrant(LUCIA, TREASURY, false))))
                .isEmpty();
    }

    /**
     * The asymmetry, from the missing side, and the one worth stating out loud:
     * the same reality and the same pair give opposite answers depending on
     * whether Lineward believes the access was put in place.
     */
    @Test
    void an_access_recorded_as_provisioned_and_absent_from_the_system_is_missing() {
        assertThat(GrantDrift.findings(Set.of(), List.of(new ExpectedGrant(LUCIA, TREASURY, true))))
                .containsExactly(Finding.about(FindingType.DRIFT_MISSING_GRANT, LUCIA, TREASURY));
    }

    @Test
    void an_access_that_is_merely_due_and_absent_from_the_system_is_not_missing() {
        assertThat(GrantDrift.findings(Set.of(), List.of(new ExpectedGrant(LUCIA, TREASURY, false))))
                .isEmpty();
    }

    /**
     * Two sources granting the same access is what the partial unique index of
     * {@code access_assignment} allows, so the expected side really does arrive
     * with duplicates. Twice due is not more due, and a pair that appeared
     * twice must not produce two findings.
     */
    @Test
    void an_access_due_from_two_sources_is_one_expectation() {
        assertThat(GrantDrift.findings(
                        Set.of(),
                        List.of(
                                new ExpectedGrant(LUCIA, TREASURY, true),
                                new ExpectedGrant(LUCIA, TREASURY, true))))
                .containsExactly(Finding.about(FindingType.DRIFT_MISSING_GRANT, LUCIA, TREASURY));
    }

    /** Both directions at once, because a real system drifts both ways on the same night. */
    @Test
    void the_two_drifts_are_counted_separately() {
        Set<ExpectedGrant.Pair> real = Set.of(new ExpectedGrant.Pair(MARTA, INTRANET));
        List<ExpectedGrant> expected = List.of(new ExpectedGrant(LUCIA, TREASURY, true));

        assertThat(GrantDrift.findings(real, expected))
                .containsExactlyInAnyOrder(
                        Finding.about(FindingType.DRIFT_UNEXPECTED_GRANT, MARTA, INTRANET),
                        Finding.about(FindingType.DRIFT_MISSING_GRANT, LUCIA, TREASURY));
    }

    private static StoredAccount account(
            boolean correlated, String kind, String status, OffsetDateTime lastActivityAt) {
        return new StoredAccount(
                UUID.randomUUID(),
                correlated,
                kind,
                status,
                lastActivityAt == null ? null : lastActivityAt.withOffsetSameInstant(ZoneOffset.UTC));
    }
}
