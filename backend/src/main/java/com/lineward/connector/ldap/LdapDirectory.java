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

import com.lineward.connector.ConnectorException;
import com.lineward.connector.ConnectorType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.Control;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.PagedResultsControl;
import javax.naming.ldap.PagedResultsResponseControl;

/**
 * One live connection to a directory, and the searches Lineward runs over it.
 *
 * <p><b>A connection, and not a client.</b> That is the decision of this class
 * and it is forced by the protocol. A paged search in LDAP continues by sending
 * back a cookie the server handed over (RFC 2696), and that cookie belongs to
 * the connection it was issued on: measured against OpenLDAP 2.6.10 on
 * 2026-11-03, replaying a valid cookie on a new connection is refused with
 * "paged results cookie is invalid". So a paged read is a session that has to
 * stay open from its first page to its last, which is why this object is
 * closeable and why the connector holds one per read rather than one per
 * connector.
 *
 * <p>A second, unpaged search between two pages is fine, verified the same day:
 * the membership lookup of each account runs on this same connection without
 * disturbing the cookie.
 *
 * <p>No library was added for any of this. The directory API in the JDK speaks
 * RFC 2696 through {@code PagedResultsControl}, including the constructor that
 * takes a cookie.
 */
class LdapDirectory implements AutoCloseable {

    /**
     * The attributes every account read asks for, by name.
     *
     * <p>Named one by one and never as "everything", for two reasons, and the
     * first one is not a preference. <b>Asking for everything does not include
     * the two attributes this connector most depends on.</b> {@code entryUUID}
     * and {@code createTimestamp} are operational: the server maintains them and
     * serves them only when they are named. Measured on 2026-11-03 by asking
     * for all attributes instead, which left every account without an
     * identifier and failed every read.
     *
     * <p>The second is that a named list cannot accidentally carry a password
     * hash into Lineward's own tables, the day somebody points this connector at
     * a directory whose access rules are laxer than the one here. The directory
     * this repository ships refuses to serve {@code userPassword} to this
     * credential at all, so that refusal is what the test next to it measures;
     * this list is the independent second reason, and it is guarded by a test of
     * its own.
     */
    static final String[] ACCOUNT_ATTRIBUTES = {
        LdapEntries.UUID_ATTRIBUTE, LdapEntries.CREATED_ATTRIBUTE,
        "uid", "cn", "sn", "displayName", "mail", "employeeNumber", "departmentNumber"
    };

    private static final String[] GROUP_ATTRIBUTES = {LdapEntries.UUID_ATTRIBUTE, "cn", "description"};

    static final String ACCOUNT_FILTER = "(objectClass=inetOrgPerson)";
    static final String GROUP_FILTER = "(objectClass=groupOfNames)";

    /**
     * Which groups hold one account.
     *
     * <p>{@code {0}} is a placeholder the directory API escapes, and it is the
     * only value in this package that comes from outside it. What that buys,
     * measured against OpenLDAP 2.6.10 on 2026-11-03 rather than assumed:
     *
     * <p>This project's own directory holds {@code cn=svc-payroll (2011}, a name
     * a migration truncated. The bracket is legal in a name and illegal in a
     * filter, so pasting the name in produces a filter the server will not parse
     * at all and the read of that account fails. With the placeholder it is just
     * a value.
     *
     * <p>And the thing that turned out NOT to be true, kept here because it was
     * the reason this looked like an injection: an asterisk in a name widens
     * nothing on this attribute. {@code member} holds distinguished names, and
     * LDAP defines no substring matching for those, so a wildcard in one matches
     * literally and finds nothing. The defence is against a filter that cannot
     * be parsed, and against the same habit on an attribute where substrings do
     * apply.
     */
    static final String MEMBERSHIP_FILTER = "(&" + GROUP_FILTER + "(member={0}))";

    private final LdapCredentials credentials;

    private LdapContext context;

    LdapDirectory(LdapCredentials credentials) {
        this.credentials = credentials;
    }

    /** One page of accounts, with the cookie that continues it. */
    Page accounts(int size, byte[] cookie) {
        String[] attributes = withLockMarker();
        return page(credentials.userSearchBase(), ACCOUNT_FILTER, null, attributes, size, cookie);
    }

    /** One page of the catalogue, with the cookie that continues it. */
    Page groups(int size, byte[] cookie) {
        return page(credentials.groupSearchBase(), GROUP_FILTER, null, GROUP_ATTRIBUTES, size, cookie);
    }

    /** The distinguished names of the groups one account belongs to. */
    List<String> groupsHolding(String accountDn) {
        try {
            // No paging control on this one, which also clears the one the page
            // before it left set: a control stays on the context until it is
            // replaced.
            context().setRequestControls(null);
            NamingEnumeration<SearchResult> found = context()
                    .search(credentials.groupSearchBase(), MEMBERSHIP_FILTER, new Object[] {accountDn}, names());
            List<String> groups = new ArrayList<>();
            while (found.hasMore()) {
                groups.add(found.next().getNameInNamespace());
            }
            return groups;
        } catch (NamingException refused) {
            throw failure("the directory would not say which groups hold an account", refused);
        }
    }

    /**
     * Reads one account, to prove the connection works and that this credential
     * is allowed to read with it.
     *
     * <p>Readable and not merely awake: a bind succeeds for an account that has
     * lost every access rule, so a check that stopped at the bind would report
     * a directory as fine until the first aggregation came back empty.
     */
    String verifyReadable() {
        page(credentials.userSearchBase(), ACCOUNT_FILTER, null, new String[] {"uid"}, 1, null);
        return "directory '" + credentials.userSearchBase() + "' at " + credentials.url()
                + ", read as '" + credentials.bindDn() + "'";
    }

    private Page page(String base, String filter, Object[] arguments, String[] attributes, int size, byte[] cookie) {
        try {
            LdapContext live = context();
            live.setRequestControls(new Control[] {
                cookie == null
                        ? new PagedResultsControl(size, Control.CRITICAL)
                        : new PagedResultsControl(size, cookie, Control.CRITICAL)
            });
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setReturningAttributes(attributes);

            NamingEnumeration<SearchResult> found = arguments == null
                    ? live.search(base, filter, controls)
                    : live.search(base, filter, arguments, controls);
            List<DirectoryEntry> entries = new ArrayList<>();
            while (found.hasMore()) {
                entries.add(entryOf(found.next()));
            }
            return new Page(entries, cookieOf(live));
        } catch (IOException | NamingException refused) {
            throw failure("the directory would not serve a page of '" + base + "'", refused);
        }
    }

    private static DirectoryEntry entryOf(SearchResult result) throws NamingException {
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        Attributes found = result.getAttributes();
        NamingEnumeration<? extends Attribute> each = found.getAll();
        while (each.hasMore()) {
            Attribute attribute = each.next();
            List<String> values = new ArrayList<>(attribute.size());
            for (int i = 0; i < attribute.size(); i++) {
                Object value = attribute.get(i);
                if (value != null) {
                    values.add(value.toString());
                }
            }
            attributes.put(attribute.getID(), values);
        }
        return new DirectoryEntry(result.getNameInNamespace(), attributes);
    }

    /**
     * The cookie of the page just read, or null when the server says that was
     * the last one.
     *
     * <p>Unlike Keycloak, a directory does announce the end: an empty cookie
     * means there is nothing behind this page, so nothing here has to guess
     * from a short page.
     */
    private static byte[] cookieOf(LdapContext live) throws NamingException {
        Control[] responses = live.getResponseControls();
        if (responses == null) {
            return null;
        }
        for (Control response : responses) {
            if (response instanceof PagedResultsResponseControl paged) {
                byte[] cookie = paged.getCookie();
                return cookie == null || cookie.length == 0 ? null : cookie;
            }
        }
        return null;
    }

    private String[] withLockMarker() {
        if (credentials.lockMarkerAttribute() == null) {
            return ACCOUNT_ATTRIBUTES;
        }
        String[] attributes = new String[ACCOUNT_ATTRIBUTES.length + 1];
        System.arraycopy(ACCOUNT_ATTRIBUTES, 0, attributes, 0, ACCOUNT_ATTRIBUTES.length);
        attributes[ACCOUNT_ATTRIBUTES.length] = credentials.lockMarkerAttribute();
        return attributes;
    }

    private static SearchControls names() {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        // No attributes at all: the name is in the result either way, and asking
        // for nothing is the cheapest answer to "which groups".
        controls.setReturningAttributes(new String[0]);
        return controls;
    }

    private LdapContext context() throws NamingException {
        if (context == null) {
            Hashtable<String, String> environment = new Hashtable<>();
            environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            environment.put(Context.PROVIDER_URL, credentials.url());
            environment.put(Context.SECURITY_AUTHENTICATION, "simple");
            environment.put(Context.SECURITY_PRINCIPAL, credentials.bindDn());
            environment.put(Context.SECURITY_CREDENTIALS, credentials.bindPassword());
            // Never follow a referral. A directory can answer "ask that other
            // server", and a connector that obliged would bind this credential
            // somewhere nobody authorised and aggregate a system nobody
            // registered.
            environment.put(Context.REFERRAL, "throw");
            context = new InitialLdapContext(environment, null);
        }
        return context;
    }

    /**
     * Wraps a failure without letting the credential into the message.
     *
     * <p>The directory API puts the failed operation in its message, which can
     * carry a distinguished name and never carries the password: the password
     * only ever travels in the environment of {@link #context()}.
     */
    private ConnectorException failure(String what, Exception cause) {
        return new ConnectorException(ConnectorType.LDAP, what + ": " + cause.getMessage(), cause);
    }

    @Override
    public void close() {
        LdapContext closing = context;
        context = null;
        if (closing != null) {
            try {
                closing.close();
            } catch (NamingException ignored) {
                // Closing a connection that is already gone is not a failure of
                // the read that just finished, and there is nobody left to tell.
            }
        }
    }

    /** A page of entries and the cookie that carries on from it. */
    record Page(List<DirectoryEntry> entries, byte[] cookie) {

        boolean last() {
            return cookie == null;
        }
    }
}
