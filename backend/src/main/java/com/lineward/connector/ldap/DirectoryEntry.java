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
package com.lineward.connector.ldap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One entry of a directory, as flat data: its name and its attributes.
 *
 * <p>The seam between fetching and understanding, and the reason it exists is
 * the same one that put a parser of its own in the Keycloak connector. The
 * protocol types are the directory API's, they carry a live connection behind
 * them, and nothing that holds one can be built in a test from bytes copied off
 * the wire. This record can, so the mapping is tested against entries a real
 * server printed rather than against entries this project invented.
 *
 * <p>Attributes are lists because a directory attribute is multi valued by
 * definition, not for generality: a real account in this project's own
 * directory carries two addresses.
 *
 * @param dn         the entry's distinguished name, which is how a directory
 *                   names things and what a group's membership refers to
 * @param attributes every attribute that was asked for and came back, by name
 */
record DirectoryEntry(String dn, Map<String, List<String>> attributes) {

    DirectoryEntry {
        if (dn == null || dn.isBlank()) {
            throw new IllegalArgumentException("a directory entry with no distinguished name cannot be referred to");
        }
        attributes = copyValues(attributes);
    }

    /** The first value of an attribute, or null when it is absent or empty. */
    String first(String attribute) {
        List<String> values = attributes.get(attribute);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    boolean has(String attribute) {
        return first(attribute) != null;
    }

    /**
     * Copies the values as well as the map, for the reason
     * {@code DiscoveredAccount} spells out: copying one level leaves the lists
     * belonging to the caller, and a caller that reuses a list while paging
     * would change entries that were already read.
     */
    private static Map<String, List<String>> copyValues(Map<String, List<String>> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        attributes.forEach((name, values) -> copy.put(name, values == null ? List.of() : List.copyOf(values)));
        return Map.copyOf(copy);
    }
}
