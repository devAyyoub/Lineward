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

import com.lineward.connector.AccountStatus;
import com.lineward.connector.DiscoveredAccount;
import com.lineward.connector.DiscoveredEntitlement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns Keycloak's admin API documents into what the SPI holds.
 *
 * <p>Separate from {@link KeycloakAdminApi}, which fetches them, for the same
 * reason {@code HrRecordParser} is separate from its reader: talking HTTP and
 * understanding a payload are different jobs, and only the first needs a server.
 * Keeping the mapping reachable on its own is also what lets a test feed it text
 * copied from the wire instead of objects built from these same classes, which
 * is the lesson of 2026-10-16.
 *
 * <p>Its own {@link JsonMapper}, private, so that a change to the platform's
 * serialization settings cannot quietly change how an external contract is read.
 */
class KeycloakDocuments {

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * One account from its user document and the documents of the groups it
     * belongs to.
     *
     * <p>Two sources and not one, because that is how Keycloak answers: the user
     * list gives the account and a second call per user gives its memberships.
     *
     * @param userDocument   one element of {@code GET /admin/realms/{realm}/users}
     * @param groupDocuments the elements of
     *     {@code GET /admin/realms/{realm}/users/{id}/groups}
     */
    DiscoveredAccount account(String userDocument, List<String> groupDocuments) {
        return account(json.readTree(userDocument), groupDocuments.stream().map(json::readTree).toList());
    }

    DiscoveredAccount account(JsonNode user, List<JsonNode> groups) {
        return new DiscoveredAccount(
                text(user, "id"),
                text(user, "username"),
                text(user, "email"),
                displayName(user),
                status(user),
                createdAt(user),
                // Keycloak reports no last login on the user representation,
                // verified on 26.7.5. Dormancy there needs the events API, which
                // is KAN-12's problem and not this connector's.
                null,
                groups.stream().map(group -> text(group, "path")).toList(),
                attributes(user));
    }

    /** One permission of the catalogue, from a group document. */
    DiscoveredEntitlement entitlement(String groupDocument) {
        return entitlement(json.readTree(groupDocument));
    }

    DiscoveredEntitlement entitlement(JsonNode group) {
        // The path and not the name. Verified on 26.7.5: a realm can hold
        // /probe-beta and /probe-alpha/probe-beta at once, two different
        // permissions whose leaf name is the same string. Naming the
        // entitlement by its name would collapse them into one, so an account
        // in either would appear to hold both.
        return new DiscoveredEntitlement(text(group, "path"), text(group, "name"), text(group, "description"));
    }

    /**
     * Whether this group has children that a separate call has to fetch.
     *
     * <p>Reads {@code subGroupCount} and deliberately not {@code subGroups}.
     * Verified on 26.7.5: {@code GET /admin/realms/{realm}/groups} returns
     * {@code subGroups} as an empty array even for a group with four children,
     * and only the count admits they exist. A walk that trusted the array would
     * stop at the top level and report part of the catalogue as all of it, which
     * then reads as "that permission was deleted".
     */
    boolean hasChildren(String groupDocument) {
        return hasChildren(json.readTree(groupDocument));
    }

    boolean hasChildren(JsonNode group) {
        return group.path("subGroupCount").asInt(0) > 0;
    }

    /** The identifier the child walk addresses, which is the UUID and not the path. */
    String groupId(String groupDocument) {
        return groupId(json.readTree(groupDocument));
    }

    String groupId(JsonNode group) {
        return text(group, "id");
    }

    /**
     * The identifier the membership call addresses.
     *
     * <p>Named apart from {@link #groupId} although both read the same field.
     * Keycloak happens to call it {@code id} on every representation, and a
     * single method would read as if a user and a group were interchangeable at
     * the call site, which is where a wrong identifier gets passed.
     */
    String userId(JsonNode user) {
        return text(user, "id");
    }

    /** The elements of a JSON array response, so that the caller pages over documents and not text. */
    List<JsonNode> elements(String arrayDocument) {
        JsonNode root = json.readTree(arrayDocument);
        if (!root.isArray()) {
            throw new IllegalStateException("expected a JSON array from the admin API, got " + root.getNodeType());
        }
        List<JsonNode> elements = new ArrayList<>(root.size());
        root.forEach(elements::add);
        return elements;
    }

    /**
     * {@code null} when the document says nothing, so that
     * {@link DiscoveredAccount}'s own default decides and the reading of silence
     * lives in one place.
     */
    private static AccountStatus status(JsonNode user) {
        JsonNode enabled = user.path("enabled");
        if (enabled.isMissingNode() || enabled.isNull()) {
            return null;
        }
        return enabled.asBoolean() ? AccountStatus.ACTIVE : AccountStatus.DISABLED;
    }

    /** Composed, because Keycloak has no display name of its own, and null rather than blank when it has neither half. */
    private static String displayName(JsonNode user) {
        String first = text(user, "firstName");
        String last = text(user, "lastName");
        String composed = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return composed.isEmpty() ? null : composed;
    }

    /** Epoch milliseconds, which is how Keycloak serialises it. */
    private static Instant createdAt(JsonNode user) {
        JsonNode created = user.path("createdTimestamp");
        return created.isMissingNode() || created.isNull() ? null : Instant.ofEpochMilli(created.asLong());
    }

    /**
     * Every attribute Keycloak exposes, each keeping all of its values.
     *
     * <p>Absent rather than empty is the normal case, and it is not the same
     * thing as an account with no attributes: Keycloak drops any attribute the
     * realm's user profile does not declare, and it drops it when the account is
     * written. So an empty map here can mean the realm was misconfigured when
     * the data was loaded, which is a condition of KAN-10 and not something this
     * connector can detect.
     */
    private static Map<String, List<String>> attributes(JsonNode user) {
        JsonNode attributes = user.path("attributes");
        if (!attributes.isObject()) {
            return Map.of();
        }
        Map<String, List<String>> values = new LinkedHashMap<>();
        attributes
                .properties()
                .forEach(attribute -> values.put(
                        attribute.getKey(),
                        attribute.getValue().valueStream().map(JsonNode::asString).toList()));
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }
}
