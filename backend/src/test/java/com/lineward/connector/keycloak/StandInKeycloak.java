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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The few admin API endpoints the connector calls, served from lists held in
 * memory, with Keycloak's own paging semantics: {@code first} is an offset,
 * {@code max} a ceiling, and a short page is all the server says about the end.
 *
 * <p>Not a Keycloak and not trying to be. It exists for the edges of the
 * paging, which need sizes a real realm makes slow to arrange: a group with
 * more than a hundred children, a tree deeper than the connector allows, a
 * page of clients none of which has a service account. What the real server
 * answers is {@code KeycloakConnectorIntegrationTests}, against 26.7.5.
 */
final class StandInKeycloak implements AutoCloseable {

    static final String REALM = "lineward";

    private final HttpServer server;
    private final List<String> users = new ArrayList<>();
    private final Map<String, List<String>> groupsOfUser = new HashMap<>();
    private final List<String> clients = new ArrayList<>();
    private final Map<String, String> serviceAccountOfClient = new HashMap<>();
    private final List<String> topLevelGroups = new ArrayList<>();
    private final Map<String, List<String>> childrenOf = new LinkedHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    StandInKeycloak() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::serve);
        server.start();
    }

    KeycloakConnector connector() {
        return new KeycloakConnector(new KeycloakCredentials(
                "http://127.0.0.1:" + server.getAddress().getPort(), REALM, "lineward-aggregator", "s3cret"));
    }

    StandInKeycloak user(String id, String username, String... groupPaths) {
        users.add("{\"id\":\"%s\",\"username\":\"%s\",\"enabled\":true}".formatted(id, username));
        List<String> memberships = new ArrayList<>();
        for (String path : groupPaths) {
            memberships.add("{\"path\":\"%s\"}".formatted(path));
        }
        groupsOfUser.put(id, memberships);
        return this;
    }

    StandInKeycloak client(String id, boolean serviceAccount) {
        clients.add("{\"id\":\"%s\",\"clientId\":\"%s\",\"serviceAccountsEnabled\":%s}"
                .formatted(id, id, serviceAccount));
        if (serviceAccount) {
            String accountId = "sa-" + id;
            serviceAccountOfClient.put(id, "{\"id\":\"%s\",\"username\":\"service-account-%s\",\"enabled\":true}"
                    .formatted(accountId, id));
            groupsOfUser.put(accountId, List.of());
        }
        return this;
    }

    /** A group; with a parent it is a child of that group, without one a top level group. */
    StandInKeycloak group(String id, String path, String parentId) {
        String document = "{\"id\":\"%s\",\"name\":\"%s\",\"path\":\"%s\",\"subGroupCount\":%%d}"
                .formatted(id, path.substring(path.lastIndexOf('/') + 1), path);
        if (parentId == null) {
            topLevelGroups.add(id + "\n" + document);
        } else {
            childrenOf.computeIfAbsent(parentId, any -> new ArrayList<>()).add(id + "\n" + document);
        }
        return this;
    }

    /** The admin requests received, path and query, so a test can count round trips. */
    List<String> requests() {
        return requests;
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            body.readAllBytes();
        }
        String path = exchange.getRequestURI().getPath();
        Map<String, Integer> query = query(exchange.getRequestURI().getRawQuery());
        String admin = "/admin/realms/" + REALM;

        String answer;
        if (path.equals("/realms/" + REALM + "/protocol/openid-connect/token")) {
            answer = "{\"access_token\":\"stand-in\",\"expires_in\":300}";
        } else {
            requests.add(path + (exchange.getRequestURI().getRawQuery() == null
                    ? "" : "?" + exchange.getRequestURI().getRawQuery()));
            if (path.equals(admin + "/users")) {
                answer = array(page(users, query));
            } else if (path.startsWith(admin + "/users/") && path.endsWith("/groups")) {
                String id = path.substring((admin + "/users/").length(), path.length() - "/groups".length());
                answer = array(groupsOfUser.getOrDefault(id, List.of()));
            } else if (path.equals(admin + "/clients")) {
                answer = array(page(clients, query));
            } else if (path.startsWith(admin + "/clients/") && path.endsWith("/service-account-user")) {
                String id = path.substring(
                        (admin + "/clients/").length(), path.length() - "/service-account-user".length());
                answer = serviceAccountOfClient.get(id);
            } else if (path.equals(admin + "/groups")) {
                answer = array(page(documents(topLevelGroups), query));
            } else if (path.startsWith(admin + "/groups/") && path.endsWith("/children")) {
                String id = path.substring((admin + "/groups/").length(), path.length() - "/children".length());
                answer = array(page(documents(childrenOf.getOrDefault(id, List.of())), query));
            } else {
                answer = null;
            }
        }

        if (answer == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** Each stored group with its child count filled in, which is the field the connector believes. */
    private List<String> documents(List<String> stored) {
        List<String> documents = new ArrayList<>(stored.size());
        for (String entry : stored) {
            String id = entry.substring(0, entry.indexOf('\n'));
            String document = entry.substring(entry.indexOf('\n') + 1);
            documents.add(document.formatted(childrenOf.getOrDefault(id, List.of()).size()));
        }
        return documents;
    }

    private static List<String> page(List<String> all, Map<String, Integer> query) {
        int first = Math.min(query.getOrDefault("first", 0), all.size());
        int max = query.getOrDefault("max", 100);
        return all.subList(first, Math.min(all.size(), first + max));
    }

    private static String array(List<String> elements) {
        return "[" + String.join(",", elements) + "]";
    }

    private static Map<String, Integer> query(String raw) {
        Map<String, Integer> values = new HashMap<>();
        if (raw == null) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            values.put(
                    URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    Integer.parseInt(URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8)));
        }
        return values;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
