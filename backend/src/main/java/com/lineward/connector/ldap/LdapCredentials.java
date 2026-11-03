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

/**
 * Where a directory is, which account reads it, and what that directory calls a
 * locked account.
 *
 * <p>The last one is configuration and not a constant because there is nothing
 * to be a constant: LDAP standardises no "switched off" flag, so every
 * directory marks it differently and OpenLDAP's own answer, the lock the
 * password policy keeps, does not even exist until an overlay is configured.
 * Guessing an attribute name would mean reporting every account of a directory
 * that uses a different one as plainly active, which is how a platform invents
 * live access. Left out, the connector says {@code UNKNOWN} and the person
 * reading the report knows it was not told.
 *
 * @param url                  the directory's address, {@code ldap://host:port}
 * @param userSearchBase       where accounts live, {@code ou=users,dc=...}
 * @param groupSearchBase      where groups live, and both bases are given
 *                             rather than derived because a real directory
 *                             keeps them wherever it kept them in 2009
 * @param bindDn               the account Lineward reads as, which needs read
 *                             and nothing else: the directory this repository
 *                             ships denies it every write, and a test watches
 *                             each refusal
 * @param bindPassword         its password, which {@link #toString()} never
 *                             prints
 * @param lockMarkerAttribute  the attribute whose presence means locked, or
 *                             null when nobody said
 */
public record LdapCredentials(
        String url,
        String userSearchBase,
        String groupSearchBase,
        String bindDn,
        String bindPassword,
        String lockMarkerAttribute) {

    public LdapCredentials {
        url = required(url, "a directory connector needs the address of a directory");
        userSearchBase = required(userSearchBase, "a directory connector needs to be told where accounts live");
        groupSearchBase = required(groupSearchBase, "a directory connector needs to be told where groups live");
        bindDn = required(bindDn, "reading this directory needs an account to read as");
        bindPassword = required(bindPassword, "reading this directory needs a password to bind with");
        lockMarkerAttribute = lockMarkerAttribute == null || lockMarkerAttribute.isBlank()
                ? null
                : lockMarkerAttribute.trim();
    }

    /**
     * Everything except the password.
     *
     * <p>Overridden because a record prints every component by default, so the
     * password would reach the first log line that mentions a connector. The
     * test for this one is the test for the whole rule: a credential that only
     * leaks when something goes wrong leaks exactly when somebody is reading.
     */
    @Override
    public String toString() {
        return "LdapCredentials[url=" + url
                + ", userSearchBase=" + userSearchBase
                + ", groupSearchBase=" + groupSearchBase
                + ", bindDn=" + bindDn
                + ", bindPassword=<redacted>"
                + ", lockMarkerAttribute=" + lockMarkerAttribute + "]";
    }

    private static String required(String value, String complaint) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(complaint);
        }
        return value.trim();
    }
}
