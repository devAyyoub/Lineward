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

import com.lineward.connector.ConnectionCheck;
import com.lineward.connector.ConnectorException;
import com.lineward.connector.ConnectorPage;
import com.lineward.connector.ConnectorType;
import com.lineward.connector.DiscoveredAccount;
import com.lineward.connector.DiscoveredEntitlement;
import com.lineward.connector.ReadCursor;
import com.lineward.connector.TargetSystemConnector;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Reads one Okta organization: who has an account in it and what it can grant.
 *
 * <p>Three decisions worth knowing, and a real organization fixed all three.
 *
 * <p><b>An empty read is not a finished read until the credential has been
 * shown to see anything.</b> An application holding the read scopes and no
 * administrator role is served HTTP 200 and an empty list by the users
 * endpoint, while groups and applications answer 403. Believing the empty list
 * would report an organization with no accounts, and the phase that compares
 * expected against real would then treat every account in it as deleted. So an
 * empty first page is not returned until {@link OktaAdminApi#verifyReadable()}
 * has proved the directory is visible, by reading the built in {@code Everyone}
 * group that no organization can be without. Keycloak taught the same lesson by
 * silently trimming a list; Okta teaches it by succeeding.
 *
 * <p><b>The catalogue is two populations, walked behind one cursor.</b> Groups
 * first and then applications, because in Okta an assignment to an application
 * is a grant of its own and not a consequence of a group. The cursor says which
 * population it is in, for the reason the Keycloak connector's does: one that
 * did not would let a resumed read start the first population again and report
 * the second as empty. It also means a page can be empty and still not be the
 * end, which is legitimate here and nowhere near as alarming as it sounds: the
 * handover from one population to the next carries a cursor, and {@code
 * ConnectorPage} promises only that the absent cursor is the end.
 *
 * <p><b>What this connector reports that no other one can.</b> Okta carries
 * {@code lastLogin} on the user, so an account read from here says when it was
 * last used. Neither Keycloak nor a directory will say, so dormancy had no
 * source in this project until now.
 *
 * <p>What it does not see: Okta's machine identities are applications and not
 * users, so the service application this connector authenticates as does not
 * appear in its own account list. That is the mirror image of Keycloak, where a
 * client's service account is reachable as a user, and it means the machine
 * accounts of an Okta organization are not covered by this card.
 */
public class OktaConnector implements TargetSystemConnector {

    private final OktaAdminApi api;
    private final OktaDocuments documents = new OktaDocuments();

    public OktaConnector(OktaCredentials credentials) {
        this.api = new OktaAdminApi(credentials, Clock.systemUTC());
    }

    @Override
    public ConnectorType type() {
        return ConnectorType.OKTA;
    }

    @Override
    public ConnectionCheck testConnection() {
        try {
            return ConnectionCheck.reached(api.verifyReadable());
        } catch (ConnectorException unreachable) {
            // The message and not the chain: this goes in front of a person in
            // the console, where a stack trace is noise.
            return ConnectionCheck.unreachable(unreachable.getMessage());
        }
    }

    /**
     * One page of the organization's accounts, with what each one holds.
     *
     * <p>Two extra calls per account, for its groups and its applications,
     * which is the shape Okta offers: there is no endpoint serving users with
     * either. The SPI anticipated this and says each connector inverts whatever
     * direction is cheap, so the cost of the choice stays in here.
     */
    @Override
    public ConnectorPage<DiscoveredAccount> readAccounts(ReadCursor resumeFrom, int size) {
        OktaAdminApi.Page page = api.users(size, after(resumeFrom));
        List<JsonNode> users = documents.list(page.body());
        refuseIfBlind(users.isEmpty(), resumeFrom == null);

        List<DiscoveredAccount> accounts = new ArrayList<>();
        for (JsonNode user : users) {
            String id = documents.id(user);
            List<String> held = new ArrayList<>(documents.groupValues(api.groupsOfUser(id)));
            held.addAll(documents.applicationValues(api.appLinksOfUser(id)));
            accounts.add(documents.account(user, held));
        }
        return page.after() == null
                ? ConnectorPage.last(accounts)
                : ConnectorPage.more(accounts, new ReadCursor(page.after()));
    }

    /**
     * One page of what the organization can grant, from whichever of its two
     * populations the cursor is in.
     */
    @Override
    public ConnectorPage<DiscoveredEntitlement> readEntitlements(ReadCursor resumeFrom, int size) {
        Walk walk = Walk.of(resumeFrom);
        return walk.population() == Population.GROUPS ? readGroups(walk, size) : readApplications(walk, size);
    }

    private ConnectorPage<DiscoveredEntitlement> readGroups(Walk walk, int size) {
        OktaAdminApi.Page page = api.groups(size, walk.after());
        List<JsonNode> groups = documents.list(page.body());
        refuseIfBlind(groups.isEmpty(), walk.after() == null);

        List<DiscoveredEntitlement> entitlements =
                groups.stream().map(documents::group).toList();
        // The handover. A page carrying the switch to the other population is a
        // page with more behind it, even when this one was the last of its own.
        return ConnectorPage.more(
                entitlements,
                page.after() == null
                        ? new Walk(Population.APPLICATIONS, null).issue()
                        : new Walk(Population.GROUPS, page.after()).issue());
    }

    private ConnectorPage<DiscoveredEntitlement> readApplications(Walk walk, int size) {
        OktaAdminApi.Page page = api.apps(size, walk.after());
        List<DiscoveredEntitlement> entitlements =
                documents.list(page.body()).stream().map(documents::application).toList();
        return page.after() == null
                ? ConnectorPage.last(entitlements)
                : ConnectorPage.more(entitlements, new Walk(Population.APPLICATIONS, page.after()).issue());
    }

    /**
     * The control, and it is here rather than in a configuration check on
     * purpose.
     *
     * <p>A check that runs when somebody presses "test connection" is a check
     * that passed months ago. This one runs on the read itself, so a role
     * removed between the two cannot turn an organization into an empty one.
     * It costs two calls, and only on the read that would otherwise report
     * nothing.
     */
    private void refuseIfBlind(boolean nothingCameBack, boolean firstPage) {
        if (nothingCameBack && firstPage) {
            api.verifyReadable();
        }
    }

    private static String after(ReadCursor cursor) {
        return cursor == null ? null : cursor.token();
    }

    /** Which of the catalogue's two populations a read is walking. */
    private enum Population {
        GROUPS,
        APPLICATIONS
    }

    /**
     * Where a catalogue read has got to: which population, and how far into it.
     *
     * <p>Serialised as {@code groups:00g1abc}, with the part after the colon
     * being Okta's own continuation value and possibly empty at the start of a
     * population. Parsing is forgiving of nothing: a cursor this connector did
     * not issue is refused rather than read as the beginning, because starting
     * over silently is how a resumed read reports a partial catalogue as the
     * whole of it.
     */
    private record Walk(Population population, String after) {

        static Walk of(ReadCursor cursor) {
            if (cursor == null) {
                return new Walk(Population.GROUPS, null);
            }
            String token = cursor.token();
            int separator = token.indexOf(':');
            if (separator < 0) {
                throw new ConnectorException(
                        ConnectorType.OKTA,
                        "this is not a cursor the Okta connector issued, so where a catalogue read had got"
                                + " to is unknown, and carrying on from the beginning would report a part of"
                                + " the catalogue as all of it");
            }
            String name = token.substring(0, separator);
            String after = token.substring(separator + 1);
            Population population;
            try {
                population = Population.valueOf(name);
            } catch (IllegalArgumentException unknown) {
                throw new ConnectorException(
                        ConnectorType.OKTA, "the Okta connector has no catalogue population called '" + name + "'");
            }
            return new Walk(population, after.isEmpty() ? null : after);
        }

        ReadCursor issue() {
            return new ReadCursor(population.name() + ":" + (after == null ? "" : after));
        }
    }
}
