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

import com.lineward.connector.okta.OktaDemoSlice.SeedAccount;
import com.lineward.connector.okta.OktaWriteApi.Answer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Puts {@link OktaDemoSlice} into a real Okta organization, and takes it out
 * again.
 *
 * <p><b>Reruns converge rather than duplicate.</b> The seeder reads what is
 * there before writing and treats an account that already exists as done, not
 * as a failure. This matters more here than it would elsewhere: the free plan
 * allows ten active users, so a seeder that created a second copy of everything
 * on its second run would fill the organization and leave no room to fix it.
 *
 * <p><b>Passwords are random and are never written down.</b> An Okta account
 * created without credentials lands in {@code PROVISIONED}, which {@link
 * OktaDocuments} reads as {@code DISABLED}, and a disabled account demonstrates
 * none of the findings this slice exists for. So each account is created with a
 * password, generated here from {@link SecureRandom} and discarded when the run
 * ends. It is deliberately <b>not</b> derived from the population seed: the
 * people have to be reproducible, and a reproducible password in a public
 * repository is a credential, not a fixture. Nobody signs in as these accounts.
 */
final class OktaOrganisationSeeder {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Long enough and mixed enough for Okta's default password policy. */
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#$%^&*";

    /** Far above anything this organization can hold, which is what makes a full page a signal. */
    private static final int PAGE = 200;

    private final OktaWriteApi api;

    OktaOrganisationSeeder(OktaWriteApi api) {
        this.api = api;
    }

    /** What a run did, so the caller can print it and a test can assert on it. */
    record Report(
            List<String> groupsCreated,
            List<String> groupsAlreadyThere,
            List<String> accountsCreated,
            List<String> accountsAlreadyThere,
            List<String> membershipsAdded,
            List<String> refusals) {

        boolean clean() {
            return refusals.isEmpty();
        }

        @Override
        public String toString() {
            return "groups created=" + groupsCreated + " already there=" + groupsAlreadyThere
                    + "\naccounts created=" + accountsCreated + " already there=" + accountsAlreadyThere
                    + "\nmemberships added=" + membershipsAdded.size()
                    + "\nrefusals=" + (refusals.isEmpty() ? "none" : refusals);
        }
    }

    /**
     * Creates everything the slice names that is not there yet.
     *
     * <p>Groups first, because a membership needs one, and accounts after, so
     * that a run interrupted between the two leaves empty groups rather than
     * accounts nobody can place.
     */
    Report seed() {
        List<String> groupsCreated = new ArrayList<>();
        List<String> groupsThere = new ArrayList<>();
        List<String> accountsCreated = new ArrayList<>();
        List<String> accountsThere = new ArrayList<>();
        List<String> memberships = new ArrayList<>();
        List<String> refusals = new ArrayList<>();

        Map<String, String> groupIds = existingGroups();
        for (String name : OktaDemoSlice.groups()) {
            if (groupIds.containsKey(name)) {
                groupsThere.add(name);
                continue;
            }
            Answer created = api.createGroup(
                    "{\"profile\":{\"name\":\"" + name + "\",\"description\":\"Lineward demo slice\"}}");
            if (created.ok()) {
                groupIds.put(name, JSON.readTree(created.body()).path("id").asString(null));
                groupsCreated.add(name);
            } else {
                refusals.add("group " + name + ": " + created.describe());
            }
        }

        Map<String, String> userIds = existingUsers();
        for (SeedAccount account : OktaDemoSlice.accounts()) {
            String id = userIds.get(account.login());
            if (id == null) {
                Answer created = api.createUser(bodyFor(account));
                if (!created.ok()) {
                    refusals.add("account " + account.login() + ": " + created.describe());
                    continue;
                }
                id = JSON.readTree(created.body()).path("id").asString(null);
                userIds.put(account.login(), id);
                accountsCreated.add(account.login());
            } else {
                accountsThere.add(account.login());
            }

            String group = OktaDemoSlice.groupOf(account);
            String groupId = groupIds.get(group);
            if (groupId == null) {
                refusals.add("membership " + account.login() + " in " + group + ": the group is not there");
                continue;
            }
            Answer joined = api.addToGroup(groupId, id);
            if (joined.ok()) {
                memberships.add(account.login() + " -> " + group);
            } else {
                refusals.add("membership " + account.login() + " in " + group + ": " + joined.describe());
            }
        }

        return new Report(groupsCreated, groupsThere, accountsCreated, accountsThere, memberships, refusals);
    }

    /**
     * Removes the accounts this slice created, and only those.
     *
     * <p>Deactivate and then delete, which is the order Okta enforces. Groups
     * are left behind on purpose: the role can create them and not remove them,
     * and an empty group costs nothing against a cap counted in users.
     */
    List<String> reset() {
        Map<String, String> userIds = existingUsers();
        List<String> removed = new ArrayList<>();
        for (SeedAccount account : OktaDemoSlice.accounts()) {
            String id = userIds.get(account.login());
            if (id == null) {
                continue;
            }
            api.deactivateUser(id);
            Answer gone = api.deleteUser(id);
            removed.add(account.login() + " -> " + gone.status());
        }
        return removed;
    }

    /** Logins to identifiers, for every user in the organization. */
    Map<String, String> existingUsers() {
        return index(api.users(PAGE, null), "users", user -> user.path("profile").path("login").asString(""));
    }

    /** Group names to identifiers. */
    Map<String, String> existingGroups() {
        return index(api.groups(PAGE, null), "groups", group -> group.path("profile").path("name").asString(""));
    }

    /**
     * One page, and a refusal to pretend it was all of them.
     *
     * <p>This reads a single page rather than following continuations, which
     * is safe only while the organization stays far smaller than a page: it is
     * capped at ten users and holds a handful of groups. Rather than write a
     * paging loop that would never run, and therefore never be known to work,
     * the assumption is checked. A full page means the assumption has expired,
     * and the seeder stops instead of deciding that an account it could not
     * see does not exist and creating a second one.
     */
    private static Map<String, String> index(Answer page, String what, java.util.function.Function<JsonNode, String> key) {
        if (!page.ok()) {
            throw new IllegalStateException("could not list the organization's " + what + ": " + page.describe());
        }
        JsonNode documents = JSON.readTree(page.body());
        if (documents.size() >= PAGE) {
            throw new IllegalStateException(
                    "this organization has at least " + PAGE + " " + what + ", so one page is no longer all of them."
                            + " The seeder needs to follow Okta's continuation before it can be trusted to tell"
                            + " an existing account from a missing one");
        }
        Map<String, String> found = new LinkedHashMap<>();
        for (JsonNode document : documents) {
            found.put(key.apply(document), document.path("id").asString(""));
        }
        return found;
    }

    private static String bodyFor(SeedAccount account) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("firstName", account.firstName());
        profile.put("lastName", account.lastName());
        profile.put("email", account.email());
        profile.put("login", account.login());
        if (account.employeeNumber() != null) {
            profile.put("employeeNumber", account.employeeNumber());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("profile", profile);
        body.put("credentials", Map.of("password", Map.of("value", password())));
        return JSON.writeValueAsString(body);
    }

    private static String password() {
        SecureRandom random = new SecureRandom();
        StringBuilder out = new StringBuilder("Aa1!");
        for (int i = 0; i < 24; i++) {
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    /**
     * The seeding credential, which is a different application from the one the
     * connector reads with.
     *
     * <p>Its variables are named apart from the connector's on purpose. Both
     * credentials point at the same organization and live in the same
     * directory, and a shell that had sourced the wrong file would otherwise
     * run the seeder as the reader or the reader as the seeder. Different
     * names make that mistake impossible rather than unlikely.
     */
    static OktaWriteApi fromEnvironment() {
        try {
            return new OktaWriteApi(
                    OktaCredentials.fromPem(
                            System.getenv("OKTA_SEEDER_ORG_URL"),
                            System.getenv("OKTA_SEEDER_CLIENT_ID"),
                            System.getenv("OKTA_SEEDER_KEY_ID"),
                            Files.readString(Path.of(System.getenv("OKTA_SEEDER_PRIVATE_KEY_FILE")))),
                    Clock.systemUTC());
        } catch (IOException unreadable) {
            throw new IllegalStateException(
                    "OKTA_SEEDER_PRIVATE_KEY_FILE does not name a readable key", unreadable);
        }
    }

    /** The groups the slice asks for, exposed so a test can check the organization against it. */
    static Set<String> expectedGroups() {
        return OktaDemoSlice.groups();
    }
}
