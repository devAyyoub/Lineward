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

import com.lineward.connector.ConnectionCheck;
import com.lineward.connector.ConnectorException;
import com.lineward.connector.ConnectorPage;
import com.lineward.connector.ConnectorType;
import com.lineward.connector.DiscoveredAccount;
import com.lineward.connector.DiscoveredEntitlement;
import com.lineward.connector.ReadCursor;
import com.lineward.connector.TargetSystemConnector;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Reads accounts and groups out of one OpenLDAP directory.
 *
 * <p>Two decisions are worth knowing before the code, and a running directory
 * decided both of them rather than the documentation.
 *
 * <p><b>A read is a session, so a cursor outlives nothing.</b> A directory
 * continues a paged search by taking back a cookie it issued (RFC 2696), and
 * that cookie dies with the connection: measured on OpenLDAP 2.6.10 on
 * 2026-11-03, a cookie that works on the connection that issued it is refused
 * on a new one with "paged results cookie is invalid". The SPI's own
 * {@code ReadCursor} says a cursor is kept in {@code aggregation_run.checkpoint}
 * and resumed later, and for this connector that is not possible: a read parked
 * for an hour has to start again. So the cursor names the session that issued
 * it, and a cursor from any other session is refused out loud. Silently starting
 * over instead would hand back page one while the caller believed it was reading
 * page nine, and an aggregation that has seen a tenth of a directory and thinks
 * it has seen all of it revokes the rest.
 *
 * <p><b>Membership lives on the group, not on the account.</b> {@code memberOf}
 * is not an attribute a directory keeps unless an overlay is configured to
 * maintain it, and this one is not: asking for it returns an entry with the
 * attribute simply absent, which a connector would read as an account with no
 * access at all. The truth is on the other side, in the {@code member} attribute
 * of each group, so each account's memberships are one search for the groups
 * that name it. The SPI anticipated exactly this and says so: a connector reads
 * whichever direction the system keeps and inverts it inside itself.
 */
public class LdapConnector implements TargetSystemConnector, AutoCloseable {

    private final LdapCredentials credentials;
    private final LdapEntries entries = new LdapEntries();

    /**
     * The two reads in flight, one per kind, each with a connection of its own.
     *
     * <p>Separate connections because interleaving two paged searches on one is
     * a property nobody here has measured, and a cookie is cheap to keep and
     * expensive to get wrong. An unpaged search between two pages is measured
     * and fine, which is what lets the membership lookups ride along on the
     * accounts connection.
     */
    private PagedRead accountsRead;

    private PagedRead groupsRead;

    public LdapConnector(LdapCredentials credentials) {
        if (credentials == null) {
            throw new IllegalArgumentException("a directory connector with no credentials cannot read anything");
        }
        this.credentials = credentials;
    }

    @Override
    public ConnectorType type() {
        return ConnectorType.LDAP;
    }

    @Override
    public ConnectionCheck testConnection() {
        try (LdapDirectory probe = new LdapDirectory(credentials)) {
            return ConnectionCheck.reached(probe.verifyReadable());
        } catch (ConnectorException unreachable) {
            // The message and not the chain: this is read by a person in a
            // console, where a stack trace is noise. It cannot carry the
            // password, because nothing in this package puts it in a message.
            return ConnectionCheck.unreachable(unreachable.getMessage());
        }
    }

    /**
     * One page of accounts, each already carrying the groups that hold it.
     *
     * <p>Synchronized because this object now has a connection and a cookie in
     * it. One live aggregation per application is already the rule, enforced by
     * a unique index on {@code aggregation_run}, so this is not expected to be
     * contended: it is here so that misuse fails slowly rather than returning
     * two callers each other's pages.
     */
    @Override
    public synchronized ConnectorPage<DiscoveredAccount> readAccounts(ReadCursor resumeFrom, int size) {
        int wanted = requireAPageWorthAsking(size);
        PagedRead read = accountsRead = live(accountsRead, resumeFrom, "accounts");

        LdapDirectory.Page page = read.session.accounts(wanted, cookieOf(resumeFrom));
        List<DiscoveredAccount> accounts = new ArrayList<>(page.entries().size());
        for (DirectoryEntry entry : page.entries()) {
            accounts.add(entries.account(
                    entry, read.session.groupsHolding(entry.dn()), credentials.lockMarkerAttribute()));
        }
        if (page.last()) {
            accountsRead = read.finish();
            return ConnectorPage.last(accounts);
        }
        return ConnectorPage.more(accounts, read.cursorFor(page.cookie()));
    }

    @Override
    public synchronized ConnectorPage<DiscoveredEntitlement> readEntitlements(ReadCursor resumeFrom, int size) {
        int wanted = requireAPageWorthAsking(size);
        PagedRead read = groupsRead = live(groupsRead, resumeFrom, "the catalogue");

        LdapDirectory.Page page = read.session.groups(wanted, cookieOf(resumeFrom));
        List<DiscoveredEntitlement> catalogue = new ArrayList<>(page.entries().size());
        for (DirectoryEntry entry : page.entries()) {
            catalogue.add(entries.entitlement(entry));
        }
        if (page.last()) {
            groupsRead = read.finish();
            return ConnectorPage.last(catalogue);
        }
        return ConnectorPage.more(catalogue, read.cursorFor(page.cookie()));
    }

    /** Releases whatever is still open, for a caller that abandons a read. */
    @Override
    public synchronized void close() {
        accountsRead = accountsRead == null ? null : accountsRead.finish();
        groupsRead = groupsRead == null ? null : groupsRead.finish();
    }

    /**
     * The session a call belongs to: a new one when there is no cursor, the
     * live one when the cursor is its own, and a refusal otherwise.
     */
    private PagedRead live(PagedRead current, ReadCursor resumeFrom, String what) {
        if (resumeFrom == null) {
            if (current != null) {
                current.finish();
            }
            return new PagedRead(new LdapDirectory(credentials));
        }
        if (current == null || !current.issued(resumeFrom)) {
            throw new ConnectorException(
                    ConnectorType.LDAP,
                    "this cursor belongs to a read of " + what + " that is no longer open. A directory's paging"
                            + " cookie dies with the connection that issued it, so a read cannot be resumed later:"
                            + " start it again from the beginning");
        }
        return current;
    }

    private static int requireAPageWorthAsking(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("a page of " + size + " accounts is not a read, it is a loop");
        }
        return size;
    }

    private static byte[] cookieOf(ReadCursor cursor) {
        if (cursor == null) {
            return null;
        }
        int separator = cursor.token().indexOf('|');
        if (separator < 0) {
            throw new ConnectorException(
                    ConnectorType.LDAP, "this cursor was not issued by the directory connector");
        }
        try {
            return Base64.getUrlDecoder().decode(cursor.token().substring(separator + 1));
        } catch (IllegalArgumentException malformed) {
            throw new ConnectorException(
                    ConnectorType.LDAP, "this cursor's continuation value is not readable", malformed);
        }
    }

    /**
     * One paged read: the connection it runs on and the identity its cursors
     * carry.
     *
     * <p>The identity is what makes a stale cursor loud. It is not a secret and
     * does not need to be: it only has to differ between reads, because the
     * failure it prevents is an accident and not an attack.
     */
    private static final class PagedRead {

        private final String id = UUID.randomUUID().toString();

        private final LdapDirectory session;

        private PagedRead(LdapDirectory session) {
            this.session = session;
        }

        private boolean issued(ReadCursor cursor) {
            return cursor.token().startsWith(id + "|");
        }

        private ReadCursor cursorFor(byte[] cookie) {
            return new ReadCursor(id + "|" + Base64.getUrlEncoder().withoutPadding().encodeToString(cookie));
        }

        /** Closes the connection and returns null, so the caller can forget it in one line. */
        private PagedRead finish() {
            session.close();
            return null;
        }
    }
}
