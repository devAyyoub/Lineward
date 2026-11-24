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
package com.lineward.connector.okta;

import static org.assertj.core.api.Assertions.assertThat;

import com.lineward.connector.okta.OktaDemoSlice.Case;
import com.lineward.connector.okta.OktaDemoSlice.SeedAccount;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the slice has to be true about itself, checkable without an Okta
 * organization or a network.
 *
 * <p>These exist because the expensive failure of this task is not a bad
 * request, it is discovering after seeding that the slice demonstrates nothing,
 * in an organization whose ten seats are now full and whose accounts hold the
 * only copy of what went wrong.
 */
class OktaDemoSliceTests {

    @Test
    @DisplayName("the slice leaves a seat free under Okta's cap")
    void fits_under_the_cap_with_room_to_spare() {
        int used = OktaDemoSlice.accounts().size() + OktaDemoSlice.SEATS_ALREADY_TAKEN;

        assertThat(used)
                .as("filling the last seat means the next mistake cannot be corrected by creating anything")
                .isLessThan(OktaDemoSlice.ACTIVE_USER_CAP);
    }

    @Test
    @DisplayName("every finding the demo promises has an account behind it")
    void covers_each_case() {
        List<Case> covered = OktaDemoSlice.accounts().stream().map(SeedAccount::demonstrates).toList();

        assertThat(covered)
                .as("an uncovered case is a finding the demo claims and cannot show")
                .contains(Case.MATCHED, Case.DORMANT, Case.LEAVER_STILL_ENABLED, Case.UNOWNED_SERVICE_ACCOUNT);
    }

    @Test
    @DisplayName("the people come from the HR seed and not from this file")
    void names_resolve_against_the_fixed_seed() {
        SeedAccount first = OktaDemoSlice.accounts().getFirst();

        // Pinned on purpose. If the simulator's seed ever moves, this fails
        // here rather than in an Okta organization that now holds strangers.
        assertThat(first.employeeNumber()).isEqualTo("E-0002");
        assertThat(first.email()).isEqualTo("brais.castro@lineward.example");
        assertThat(first.firstName()).isEqualTo("Brais");
    }

    @Test
    @DisplayName("exactly one account belongs to nobody, and it is the service account")
    void only_the_service_account_has_no_owner() {
        List<SeedAccount> ownerless =
                OktaDemoSlice.accounts().stream().filter(account -> !account.shouldMatch()).toList();

        assertThat(ownerless).hasSize(1);
        assertThat(ownerless.getFirst().demonstrates()).isEqualTo(Case.UNOWNED_SERVICE_ACCOUNT);
        assertThat(ownerless.getFirst().login()).startsWith("svc-");
    }

    @Test
    @DisplayName("two runs of the slice produce the same people")
    void is_deterministic() {
        assertThat(OktaDemoSlice.accounts()).isEqualTo(OktaDemoSlice.accounts());
    }

    @Test
    @DisplayName("every login is unique, because Okta rejects the second one")
    void logins_do_not_collide() {
        List<String> logins = OktaDemoSlice.accounts().stream().map(SeedAccount::login).toList();

        assertThat(logins).doesNotHaveDuplicates();
    }
}
