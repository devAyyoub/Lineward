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

import com.lineward.connector.okta.OktaDemoSlice.SeedAccount;
import com.lineward.connector.okta.OktaOrganisationSeeder.Report;
import com.lineward.connector.okta.OktaWriteApi.Answer;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seeds the demo organization and checks that what came back is what the slice
 * asked for.
 *
 * <p>These write to a real Okta organization, so they run only where the
 * seeding credential is configured and are skipped everywhere else, continuous
 * integration included. That makes them useless as a regression net and
 * valuable as the thing that proves the seeding actually happened, which is why
 * the run and its counts belong in the delivery rather than in a report nobody
 * reads.
 *
 * <p>The guard names {@code OKTA_SEEDER_ORG_URL} and not the connector's
 * {@code OKTA_ORG_URL}: a shell holding only the read credential must not be
 * able to start a run that writes.
 *
 * <p>To run them:
 *
 * <pre>
 * set -a; . ~/.lineward/okta-seeder.env; set +a
 * mvn -pl backend test -Dtest=OktaSeedingLiveTests
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "OKTA_SEEDER_ORG_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OktaSeedingLiveTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OktaWriteApi api = OktaOrganisationSeeder.fromEnvironment();
    private final OktaOrganisationSeeder seeder = new OktaOrganisationSeeder(api);

    @Test
    @Order(1)
    @DisplayName("seeding leaves the organization holding the whole slice")
    void seeds_the_slice() {
        Report report = seeder.seed();

        assertThat(report.refusals())
                .as("a refusal here means the role or the scopes are too narrow, and the slice is half in")
                .isEmpty();

        Map<String, String> users = seeder.existingUsers();
        for (SeedAccount account : OktaDemoSlice.accounts()) {
            assertThat(users).containsKey(account.login());
        }
    }

    @Test
    @Order(2)
    @DisplayName("every seeded account is active, because a provisioned one reads as disabled")
    void every_account_is_active() {
        for (SeedAccount account : OktaDemoSlice.accounts()) {
            JsonNode user = fetch(account.login());

            assertThat(user.path("status").asString(""))
                    .as("%s is %s, and OktaDocuments turns anything but ACTIVE into a disabled account,"
                            + " which demonstrates none of the findings this slice exists for",
                            account.login(), user.path("status").asString(""))
                    .isEqualTo("ACTIVE");
        }
    }

    @Test
    @Order(3)
    @DisplayName("the employee number correlation matches on is in the profile, except where it must not be")
    void carries_the_employee_number() {
        for (SeedAccount account : OktaDemoSlice.accounts()) {
            JsonNode profile = fetch(account.login()).path("profile");

            if (account.shouldMatch()) {
                assertThat(profile.path("employeeNumber").asString(null))
                        .as("without it this account cannot be matched to the person HR asserts")
                        .isEqualTo(account.employeeNumber());
            } else {
                assertThat(profile.has("employeeNumber"))
                        .as("the unowned account is only unowned while nothing identifies its owner")
                        .isFalse();
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("seeding twice creates nothing the second time")
    void is_idempotent() {
        Report again = seeder.seed();

        assertThat(again.accountsCreated())
                .as("a second copy of everybody would fill the ten seats and leave no room to undo it")
                .isEmpty();
        assertThat(again.groupsCreated()).isEmpty();
        assertThat(again.refusals()).isEmpty();
    }

    private JsonNode fetch(String login) {
        Answer answer = api.send(
                "GET",
                System.getenv("OKTA_SEEDER_ORG_URL") + "/api/v1/users/" + login,
                null,
                "the account " + login);
        assertThat(answer.ok()).as("reading back %s: %s", login, answer.describe()).isTrue();
        return JSON.readTree(answer.body());
    }
}
