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

import com.lineward.connector.AccountKind;
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
 * Okta's JSON turned into what the SPI holds.
 *
 * <p>No HTTP here, so every mapping below is checked by feeding it bytes of
 * the shape a real organization served on 2026-11-04.
 *
 * <p><b>An entitlement in Okta is two different things, and both are grants.</b>
 * Group membership is the familiar one. Being assigned to an application is the
 * other, and it is direct: a person can hold an application without belonging
 * to any group that grants it, measured on the organization this connector was
 * built against. A catalogue of groups alone would therefore miss most of the
 * access an Okta organization actually hands out, and every one of those
 * assignments would read as a permission nobody expected once reconciliation
 * compares the two sides. So the native value carries which of the two it is.
 *
 * <p><b>The native value is an identifier and not a name</b>, which is the
 * opposite of the Keycloak connector, where it is the group's path. Both follow
 * the same rule, that the native value is whatever the target system uses to
 * refer to the thing on both sides of a membership. Keycloak serves a user's
 * groups by path and Okta serves them as objects with ids, and an id survives
 * the rename that a path does not.
 */
final class OktaDocuments {

    /** What a group's native value starts with, so that the two namespaces cannot meet. */
    private static final String GROUP = "group:";

    /** And an application's. Okta's own ids happen to be prefixed too, but that is a convention it owns, not a promise. */
    private static final String APPLICATION = "app:";

    private final JsonMapper json = JsonMapper.builder().build();

    /** The elements of a JSON array, which is what every list endpoint returns. */
    List<JsonNode> list(String document) {
        List<JsonNode> elements = new ArrayList<>();
        json.readTree(document).forEach(elements::add);
        return elements;
    }

    /** The identifier of any Okta object. */
    String id(JsonNode node) {
        return node.path("id").asString(null);
    }

    /**
     * One account, from a user document and whatever it was found to hold.
     *
     * @param user              one element of {@code /api/v1/users}
     * @param entitlementValues the native values of its groups and its
     *                          applications, which arrive from two separate
     *                          calls because Okta serves them separately
     */
    DiscoveredAccount account(JsonNode user, List<String> entitlementValues) {
        JsonNode profile = user.path("profile");
        return new DiscoveredAccount(
                id(user),
                profile.path("login").asString(null),
                profile.path("email").asString(null),
                displayName(profile),
                status(user.path("status").asString("")),
                // Always unknown, and not because the read is poor. Okta's
                // machine identities are applications and not users: the
                // service application this connector authenticates as does not
                // appear in the user list at all. So there is nothing here to
                // claim, and claiming otherwise from a naming convention is
                // what AccountKind exists to prevent. Reading those
                // applications as accounts of their own is a question this
                // card does not answer.
                AccountKind.UNKNOWN,
                timestamp(user.path("created").asString(null)),
                // The one thing no other connector in this project can report.
                // Keycloak does not carry a last login on the user and a
                // directory does not carry one on the entry, so dormancy had no
                // source until here.
                timestamp(user.path("lastLogin").asString(null)),
                entitlementValues,
                attributes(profile));
    }

    /** One permission of the catalogue, from a group. */
    DiscoveredEntitlement group(JsonNode group) {
        JsonNode profile = group.path("profile");
        return new DiscoveredEntitlement(
                GROUP + id(group), profile.path("name").asString(null), profile.path("description").asString(null));
    }

    /**
     * One permission of the catalogue, from an application.
     *
     * <p>The label and not the name: {@code name} is Okta's own identifier for
     * the kind of integration, so every custom application made the same way
     * shares it, while the label is what an administrator typed and what a
     * reviewer will recognise in a finding.
     */
    DiscoveredEntitlement application(JsonNode app) {
        String label = app.path("label").asString(null);
        return new DiscoveredEntitlement(
                APPLICATION + id(app),
                label != null ? label : app.path("name").asString(null),
                "application, signed on with " + app.path("signOnMode").asString("an unstated method"));
    }

    /** The native values of the groups one account belongs to. */
    List<String> groupValues(String groupsDocument) {
        return list(groupsDocument).stream().map(group -> GROUP + id(group)).toList();
    }

    /**
     * The native values of the applications one account is assigned to.
     *
     * <p>{@code appInstanceId} and not {@code id}: the identifier of the link
     * is not the identifier of the application, and matching on it would leave
     * every assignment pointing at a catalogue entry that does not exist.
     */
    List<String> applicationValues(String appLinksDocument) {
        return list(appLinksDocument).stream()
                .map(link -> APPLICATION + link.path("appInstanceId").asString(null))
                .toList();
    }

    private static String displayName(JsonNode profile) {
        String first = profile.path("firstName").asString("");
        String last = profile.path("lastName").asString("");
        String full = (first + " " + last).strip();
        return full.isEmpty() ? null : full;
    }

    /**
     * Okta's eight states onto the five the schema allows.
     *
     * <p>{@code STAGED} and {@code PROVISIONED} become {@code DISABLED} rather
     * than {@code UNKNOWN}: the vocabulary cannot say "created and not
     * activated yet", and the question a reviewer is asking is whether this
     * account can be used right now, which has the same answer. {@code
     * RECOVERY} and {@code PASSWORD_EXPIRED} become {@code ACTIVE} for the
     * mirror of that reason: they are live accounts in the middle of a password
     * dance, and reporting them as switched off would hide standing access.
     *
     * <p>Anything unrecognised becomes {@code UNKNOWN} and never {@code ACTIVE},
     * because a state Okta adds after this was written must not arrive as
     * working access nobody granted.
     */
    private static AccountStatus status(String okta) {
        return switch (okta) {
            case "ACTIVE", "RECOVERY", "PASSWORD_EXPIRED" -> AccountStatus.ACTIVE;
            case "STAGED", "PROVISIONED", "SUSPENDED" -> AccountStatus.DISABLED;
            case "LOCKED_OUT" -> AccountStatus.LOCKED;
            case "DEPROVISIONED" -> AccountStatus.DELETED;
            default -> AccountStatus.UNKNOWN;
        };
    }

    private static Instant timestamp(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    /**
     * Everything the profile says, for the correlation rules to match on.
     *
     * <p>Null valued fields are left out rather than carried as empty. Okta
     * returns the whole profile shape on every user, so {@code secondEmail}
     * arrives as null for almost everybody, and keeping those would fill the
     * stored attributes of an organization with thousands of rows saying
     * nothing. A field that is absent and a field that is null mean the same
     * thing here, which is the one case where flattening two states into one
     * loses nothing.
     */
    private static Map<String, List<String>> attributes(JsonNode profile) {
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        profile.propertyStream().forEach(property -> {
            List<String> values = valuesOf(property.getValue());
            if (!values.isEmpty()) {
                attributes.put(property.getKey(), values);
            }
        });
        return attributes;
    }

    private static List<String> valuesOf(JsonNode value) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (value.isArray()) {
            List<String> values = new ArrayList<>();
            value.forEach(element -> {
                if (!element.isNull()) {
                    values.add(element.asString());
                }
            });
            return values;
        }
        String single = value.asString();
        return single == null || single.isBlank() ? List.of() : List.of(single);
    }
}
