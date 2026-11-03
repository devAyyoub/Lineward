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
package com.lineward.connector.keycloak;

import com.lineward.connector.ConnectionCheck;
import com.lineward.connector.ConnectorException;
import com.lineward.connector.ConnectorPage;
import com.lineward.connector.ConnectorType;
import com.lineward.connector.DiscoveredAccount;
import com.lineward.connector.DiscoveredEntitlement;
import com.lineward.connector.ReadCursor;
import com.lineward.connector.TargetSystemConnector;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Reads accounts and permissions out of a Keycloak realm.
 *
 * <p>The first implementation of {@link TargetSystemConnector}, and the point at
 * which Lineward stops only comparing its own two assertions, what HR claims
 * exists and what its birthright rules say is due, against each other.
 *
 * <p>Two things here are not obvious and both were settled by reading a running
 * Keycloak 26.7.5 rather than its documentation.
 *
 * <p><strong>The end of a read.</strong> Keycloak pages by offset and never says
 * that a page is the last one, so the only honest way to tell the end from a
 * short page is to ask for one more row than the caller wants. If the extra row
 * arrives there is more; if it does not, this was the end. Treating a short page
 * as the end instead would be the mistake the SPI exists to prevent: an
 * interrupted read would look like a complete picture, and every account that
 * did not arrive would look deleted to the reconciliation that follows.
 *
 * <p><strong>The catalogue is a tree.</strong>
 * {@code GET /admin/realms/{realm}/groups} returns only top level groups, and it
 * returns {@code subGroups} as an empty array even when {@code subGroupCount}
 * says there are children. Verified on 26.7.5: a realm holding eight groups
 * served three. So the catalogue is walked, not listed, and a page of this read
 * is a page of top level groups with each one's whole subtree expanded into it.
 * That makes the returned page larger than the requested size, which the SPI
 * allows because size is a request and not a promise, and it keeps the cursor a
 * plain offset over the top level that a resume can trust.
 *
 * <p>Nested groups are also why an entitlement's native value is its path and
 * never its name: one realm can hold {@code /payment-approve} and
 * {@code /intranet/payment-approve}, two different permissions whose leaf name
 * is the same string.
 */
public class KeycloakConnector implements TargetSystemConnector {

    /**
     * How many children are fetched at a time while walking a subtree.
     *
     * <p>Fixed rather than taken from the caller's size, because the caller's
     * size describes the page it wants and this is an internal read it never
     * sees. Large enough that an ordinary group costs one request.
     */
    private static final int CHILD_PAGE_SIZE = 100;

    /**
     * How deep a group tree may be before the walk refuses it.
     *
     * <p>A limit and not a recursion guard for its own sake. Without it a realm
     * nested deeply enough would end a read with a {@link StackOverflowError},
     * which kills the thread with no useful message and looks like a defect in
     * Lineward. Keycloak itself sets no limit, so the depth of a target system's
     * tree is somebody else's decision and has to be survivable.
     */
    private static final int MAX_GROUP_DEPTH = 64;

    private final KeycloakAdminApi api;
    private final KeycloakDocuments documents = new KeycloakDocuments();

    public KeycloakConnector(KeycloakCredentials credentials) {
        this.api = new KeycloakAdminApi(credentials);
    }

    @Override
    public ConnectorType type() {
        return ConnectorType.KEYCLOAK;
    }

    /**
     * Returns the answer rather than throwing, because a realm that cannot be
     * reached is the expected answer to this question.
     *
     * <p>Reachable means readable here and not merely awake: the check lists one
     * account, so a client whose {@code view-users} role was removed reports
     * unreachable now instead of at the first aggregation.
     *
     * <p>The detail names the realm and the client and deliberately not the
     * server version. Verified on 26.7.5: {@code /admin/serverinfo} answers a
     * read only service account with 200 and omits {@code systemInfo} from the
     * document, so the version is only legible to a full administrator. Asking
     * for it would mean 318 KB on the wire to learn nothing, and widening the
     * credential to make it legible would buy a nicer sentence with more
     * privilege than reading accounts needs.
     */
    @Override
    public ConnectionCheck testConnection() {
        try {
            return ConnectionCheck.reached(api.verifyReadable());
        } catch (ConnectorException unreachable) {
            // The message and not the chain: this goes in front of a person in
            // the console, where a stack trace is noise. It cannot carry the
            // secret, because nothing in this package ever puts it in a message.
            return ConnectionCheck.unreachable(unreachable.getMessage());
        }
    }

    @Override
    public ConnectorPage<DiscoveredAccount> readAccounts(ReadCursor resumeFrom, int size) {
        int offset = offsetOf(resumeFrom);
        int wanted = requireRoomForTheExtraRow(size);

        List<JsonNode> fetched = documents.elements(api.users(offset, wanted + 1));
        boolean more = fetched.size() > wanted;
        List<JsonNode> users = more ? fetched.subList(0, wanted) : fetched;

        List<DiscoveredAccount> accounts = new ArrayList<>(users.size());
        for (JsonNode user : users) {
            accounts.add(documents.account(user, membershipsOf(user)));
        }
        return more ? ConnectorPage.more(accounts, cursorAt(offset + wanted)) : ConnectorPage.last(accounts);
    }

    @Override
    public ConnectorPage<DiscoveredEntitlement> readEntitlements(ReadCursor resumeFrom, int size) {
        int offset = offsetOf(resumeFrom);
        int wanted = requireRoomForTheExtraRow(size);

        List<JsonNode> fetched = documents.elements(api.topLevelGroups(offset, wanted + 1));
        boolean more = fetched.size() > wanted;
        List<JsonNode> roots = more ? fetched.subList(0, wanted) : fetched;

        List<DiscoveredEntitlement> entitlements = new ArrayList<>();
        for (JsonNode root : roots) {
            collectSubtree(root, 1, entitlements);
        }
        return more ? ConnectorPage.more(entitlements, cursorAt(offset + wanted)) : ConnectorPage.last(entitlements);
    }

    /** The memberships of one user, which is where the account's entitlement values come from. */
    private List<JsonNode> membershipsOf(JsonNode user) {
        String id = documents.userId(user);
        if (id == null) {
            // Let the record refuse it, so that the message about an account
            // with no identifier is written in one place.
            return List.of();
        }
        return documents.elements(api.groupsOfUser(id));
    }

    /** A group and everything under it, depth first, so a parent is always listed before its children. */
    private void collectSubtree(JsonNode group, int depth, List<DiscoveredEntitlement> into) {
        if (depth > MAX_GROUP_DEPTH) {
            throw new ConnectorException(
                    ConnectorType.KEYCLOAK,
                    "the group tree of this realm is deeper than " + MAX_GROUP_DEPTH
                            + " levels at " + documents.entitlement(group).nativeValue()
                            + ", so the catalogue was not read rather than read in part");
        }
        into.add(documents.entitlement(group));
        if (!documents.hasChildren(group)) {
            return;
        }

        String parentId = documents.groupId(group);
        int offset = 0;
        while (true) {
            List<JsonNode> fetched =
                    documents.elements(api.childGroups(parentId, offset, CHILD_PAGE_SIZE + 1));
            boolean more = fetched.size() > CHILD_PAGE_SIZE;
            List<JsonNode> children = more ? fetched.subList(0, CHILD_PAGE_SIZE) : fetched;
            for (JsonNode child : children) {
                collectSubtree(child, depth + 1, into);
            }
            if (!more) {
                return;
            }
            offset += CHILD_PAGE_SIZE;
        }
    }

    private static ReadCursor cursorAt(int offset) {
        return new ReadCursor(String.valueOf(offset));
    }

    /**
     * The offset a cursor carries.
     *
     * <p>An offset is what Keycloak's {@code first} parameter is, wrapped so
     * that nothing outside this package can read it, increment it or pass a page
     * number in its place. A directory would keep the cookie of RFC 2696 in the
     * same field and no caller could tell.
     */
    private static int offsetOf(ReadCursor resumeFrom) {
        if (resumeFrom == null) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(resumeFrom.token());
            if (offset < 0) {
                throw new NumberFormatException(resumeFrom.token());
            }
            return offset;
        } catch (NumberFormatException notOurs) {
            // Loud, because the alternative is restarting the read from zero and
            // reporting it as a resume, which silently reads part of the
            // population twice and calls it a complete picture.
            throw new ConnectorException(
                    ConnectorType.KEYCLOAK,
                    "this cursor was not issued by the Keycloak connector, so a resume from it would be a guess",
                    notOurs);
        }
    }

    /**
     * Refuses a page size below one.
     *
     * <p>Not defensiveness: the extra row that tells this connector where a read
     * ends is what makes a size of zero dangerous. Asking Keycloak for one row,
     * keeping none of it and reporting more behind would hand back an endless
     * run of empty pages, and an aggregation would never finish and never fail.
     */
    private static int requireRoomForTheExtraRow(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("a page of " + size + " rows cannot make progress through a read");
        }
        return size;
    }
}
