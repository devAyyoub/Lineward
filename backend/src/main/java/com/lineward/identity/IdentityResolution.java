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
package com.lineward.identity;

import java.util.List;
import java.util.UUID;

/**
 * Finds the person an outside identifier belongs to.
 *
 * <p>Separate from {@code IdentityLookup}, which answers what Lineward knows
 * about an identity it can already name. This answers the question before that
 * one: an account turns up in a target system carrying a number and an email,
 * and nothing says whose it is. Correlating them is M2's work and the two
 * lookups it needs are M1's data, so they live here rather than as a join
 * across the boundary (ADR-001).
 *
 * <p><b>Both return a list, and that is the design decision.</b> Returning one
 * identity would mean this interface deciding what to do when two people match,
 * and the honest answer is that it cannot: the synthetic population deliberately
 * contains two people sharing an email address, because real ones do. A list
 * makes the ambiguity the caller's to handle, and the caller refuses to guess.
 * An {@code Optional} would have been the quiet version of the same situation,
 * where whichever row the database happened to return first becomes the owner of
 * somebody else's account.
 *
 * <p>Nothing here writes, and nothing here reads an identity's attributes. The
 * ids come back and the caller asks {@code IdentityLookup} if it needs more,
 * which keeps this interface from growing into the query API that KAN-16 owns.
 */
public interface IdentityResolution {

    /**
     * The identities an authoritative source asserted under this identifier,
     * which for the HR source is the employee number.
     *
     * <p>Across every source system of the organization, because an account in
     * a directory carries a number and not the name of whoever issued it. With
     * one authoritative source that is exactly one lookup; with two, a number
     * meaning different people in each arrives here as two matches instead of a
     * silent choice between them.
     *
     * @param orgId      the tenant
     * @param externalId the identifier as the target system spells it. Not
     *     normalised, trimmed or case folded: an employee number is a key and a
     *     platform that quietly changes keys to make them match is a platform
     *     that correlates the wrong people
     * @return the matching identities, empty when nothing matches, more than one
     *     when the match is ambiguous
     */
    List<UUID> byExternalId(UUID orgId, String externalId);

    /**
     * The identities with this email address.
     *
     * @param email as the target system reports it. Compared case insensitively,
     *     which is the one normalisation that is safe here: the local part of an
     *     address is technically case sensitive and no mail system has treated
     *     it that way in decades, so {@code M.Ferreira@} and {@code m.ferreira@}
     *     are the same mailbox and failing to match them would leave real
     *     accounts orphaned
     * @return the matching identities, empty when nothing matches, more than one
     *     when two people share an address
     */
    List<UUID> byEmail(UUID orgId, String email);
}
