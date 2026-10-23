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
package com.lineward.connector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A connector that serves documents written as text.
 *
 * <p>It is not the Keycloak connector, which is KAN-7: there is no HTTP here, no
 * admin credential and no paging against a real API. What it is, is the only
 * honest way to write a contract test for an interface that has no wire format
 * of its own. The SPI is deliberately format agnostic, so proving that its types
 * can carry a real system's answer needs some real system's answer, and the
 * documents the tests feed it are Keycloak 26.7.5 admin API responses pasted in
 * from a running container on 2026-10-23.
 *
 * <p>Why text rather than constructing {@link DiscoveredAccount} directly: the
 * failure of 2026-10-16 was sixteen tests that all built their payload in Java
 * and therefore never saw a message shaped like the ones that arrive. A double
 * that parses is a double that can be wrong in the same way the real connector
 * will be.
 *
 * <p>It pages with a real cursor and can break part way through, because those
 * are the two behaviours the SPI exists to pin down and a double that ignored
 * them would let a paging bug through.
 */
public class LiteralTargetSystemConnector implements TargetSystemConnector {

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<SeededAccount> accounts = new ArrayList<>();
    private final List<String> catalogue = new ArrayList<>();
    private String unreachableBecause;
    private int pagesBeforeBreaking = Integer.MAX_VALUE;

    /**
     * One account as the target system serves it.
     *
     * <p>Two documents and not one, because that is how Keycloak answers: the
     * user list gives the account, and a second call per user gives the groups
     * it belongs to. The SPI hides that second call behind
     * {@code entitlementValues}, so the double has to perform it too.
     *
     * @param userDocument  one element of {@code GET /admin/realms/{realm}/users}
     * @param groupDocuments the elements of
     *     {@code GET /admin/realms/{realm}/users/{id}/groups}
     */
    public record SeededAccount(String userDocument, List<String> groupDocuments) {}

    /** An account with the group documents its own membership call returns. */
    public static SeededAccount account(String userDocument, String... groupDocuments) {
        return new SeededAccount(userDocument, Arrays.asList(groupDocuments));
    }

    /** Replaces the accounts the system holds. */
    public LiteralTargetSystemConnector serving(SeededAccount... seeded) {
        accounts.clear();
        accounts.addAll(Arrays.asList(seeded));
        return this;
    }

    /** Replaces the permission catalogue, as {@code GET /admin/realms/{realm}/groups} returns it. */
    public LiteralTargetSystemConnector withCatalogue(String... groupDocuments) {
        catalogue.clear();
        catalogue.addAll(Arrays.asList(groupDocuments));
        return this;
    }

    /** Makes the system refuse to answer, which is a check and not a failure. */
    public LiteralTargetSystemConnector unreachable(String reason) {
        this.unreachableBecause = reason;
        return this;
    }

    /** Makes the read break once it has served this many pages, which is a failure and not a check. */
    public LiteralTargetSystemConnector breakingAfter(int pages) {
        this.pagesBeforeBreaking = pages;
        return this;
    }

    @Override
    public ConnectorType type() {
        return ConnectorType.KEYCLOAK;
    }

    @Override
    public ConnectionCheck testConnection() {
        return unreachableBecause == null
                ? ConnectionCheck.reached("Keycloak 26.7.5, read as service-account-lineward")
                : ConnectionCheck.unreachable(unreachableBecause);
    }

    @Override
    public ConnectorPage<DiscoveredAccount> readAccounts(ReadCursor resumeFrom, int size) {
        return page(accounts, resumeFrom, size, this::toAccount);
    }

    @Override
    public ConnectorPage<DiscoveredEntitlement> readEntitlements(ReadCursor resumeFrom, int size) {
        return page(catalogue, resumeFrom, size, this::toEntitlement);
    }

    /**
     * Offsets as the cursor token, which is what Keycloak's {@code first}
     * parameter is. A directory would put the cookie of RFC 2696 in the same
     * place, and nothing outside this class can tell the difference, which is
     * the property {@link ReadCursor} is for.
     */
    private <S, T> ConnectorPage<T> page(
            List<S> source, ReadCursor resumeFrom, int size, java.util.function.Function<S, T> map) {

        int offset = resumeFrom == null ? 0 : Integer.parseInt(resumeFrom.token());
        if (offset / Math.max(size, 1) >= pagesBeforeBreaking) {
            throw new ConnectorException(type(), "the connection dropped after " + offset + " records");
        }

        int to = Math.min(offset + size, source.size());
        List<T> items = source.subList(offset, to).stream().map(map).toList();
        return to >= source.size() ? ConnectorPage.last(items) : ConnectorPage.more(items, new ReadCursor(String.valueOf(to)));
    }

    private DiscoveredAccount toAccount(SeededAccount seeded) {
        JsonNode user = json.readTree(seeded.userDocument());
        return new DiscoveredAccount(
                text(user, "id"),
                text(user, "username"),
                text(user, "email"),
                displayName(user),
                status(user),
                createdAt(user),
                // Keycloak does not report a last login on the user
                // representation, verified on 26.7.5. Dormancy there needs the
                // events API, which is KAN-12's problem and not the SPI's.
                null,
                seeded.groupDocuments().stream().map(document -> text(json.readTree(document), "path")).toList(),
                attributes(user));
    }

    private DiscoveredEntitlement toEntitlement(String groupDocument) {
        JsonNode group = json.readTree(groupDocument);
        // The path and not the name: nested groups make the name ambiguous, and
        // /finance/approvers is a different permission from /hr/approvers.
        return new DiscoveredEntitlement(text(group, "path"), text(group, "name"), text(group, "description"));
    }

    /**
     * {@code null} when the system says nothing, so that the record's own
     * default decides. Keycloak always sends {@code enabled}; not every system
     * does, and reading silence as active is the mistake
     * {@link AccountStatus#UNKNOWN} exists to prevent.
     */
    private static AccountStatus status(JsonNode user) {
        JsonNode enabled = user.path("enabled");
        if (enabled.isMissingNode() || enabled.isNull()) {
            return null;
        }
        return enabled.asBoolean() ? AccountStatus.ACTIVE : AccountStatus.DISABLED;
    }

    private static String displayName(JsonNode user) {
        String composed = ((text(user, "firstName") == null ? "" : text(user, "firstName"))
                        + " "
                        + (text(user, "lastName") == null ? "" : text(user, "lastName")))
                .trim();
        return composed.isEmpty() ? null : composed;
    }

    private static Instant createdAt(JsonNode user) {
        JsonNode created = user.path("createdTimestamp");
        return created.isMissingNode() || created.isNull() ? null : Instant.ofEpochMilli(created.asLong());
    }

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
