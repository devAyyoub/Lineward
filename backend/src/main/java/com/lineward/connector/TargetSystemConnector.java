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

/**
 * What every connector can do against a target system, reading only.
 *
 * <p>A target system is anything that holds accounts Lineward has to govern: an
 * identity provider, a corporate directory, a business application. Until this
 * interface existed, Lineward knew what the HR source claims about who exists
 * and what its own birthright rules say people ought to have, and nothing at
 * all about what is really switched on out there. The three operations below
 * are the smallest set that closes that gap: reach the system, list the
 * accounts in it, list the permissions it can grant.
 *
 * <p>Read only, and the writing half of M3 will arrive in phase 2 as a separate
 * interface rather than as more methods here. That is minimum privilege applied
 * to Lineward's own parts: a component holding a read only connector cannot be
 * talked into provisioning, because the capability is not reachable from the
 * type it holds. Growing this interface instead would hand every reader the
 * ability to delete an account and leave "it does not call that method" as the
 * only control.
 *
 * <p>Both reads are paged, and the cursor is the connector's own (see
 * {@link ReadCursor}). A directory with two hundred thousand entries will not
 * serve them in one response, and the ones that would will stop doing so at the
 * least convenient moment.
 *
 * <p>Nothing here mentions an organization. Which tenant a run belongs to is
 * settled before a connector is asked anything, by the transaction that reads
 * and writes around it (ADR-011), so a connector cannot be passed the wrong one.
 */
public interface TargetSystemConnector {

    /**
     * Which kind of system this connector speaks to, matching
     * {@code application.connector_type}.
     */
    ConnectorType type();

    /**
     * Whether the target system can be reached and read right now.
     *
     * <p>Returns the answer instead of throwing, because unreachable is an
     * answer. See {@link ConnectionCheck}.
     */
    ConnectionCheck testConnection();

    /**
     * Reads one page of the accounts that exist in the target system.
     *
     * @param resumeFrom a cursor from an earlier page, or {@code null} to start
     *     at the beginning
     * @param size       how many accounts to ask for. A request, not a promise:
     *     a target system is free to return fewer, and several do when a page
     *     would exceed their own limits, so a short page must not be read as the
     *     end. Only {@link ConnectorPage#next} says that
     * @throws ConnectorException if the read broke part way through. Failing is
     *     required rather than returning what arrived: a partial read reported
     *     as complete makes every account that did not arrive look deleted
     */
    ConnectorPage<DiscoveredAccount> readAccounts(ReadCursor resumeFrom, int size);

    /**
     * Reads one page of the permissions the target system can grant, whether or
     * not anybody holds them.
     *
     * @param resumeFrom a cursor from an earlier page, or {@code null} to start
     *     at the beginning
     * @param size       how many entitlements to ask for, with the same caveat
     *     as {@link #readAccounts}
     * @throws ConnectorException if the read broke part way through
     */
    ConnectorPage<DiscoveredEntitlement> readEntitlements(ReadCursor resumeFrom, int size);
}
