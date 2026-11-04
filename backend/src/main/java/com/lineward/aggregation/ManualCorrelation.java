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
package com.lineward.aggregation;

import java.util.UUID;

/**
 * Says who an account belongs to when the rules could not.
 *
 * <p>The half of correlation that no rule can do, and every installation needs
 * it. An account whose identifier matches two people, a contractor whose
 * directory entry carries no employee number, a service account somebody owns
 * personally: these are not failures of the rules, they are the cases a person
 * has to decide. Without a door for that decision the platform would have a
 * permanent list of orphans that nobody can ever close, and the list is what
 * governance is measured on.
 *
 * <p><b>A decision taken here is not a rule's to revisit.</b> That is the one
 * guarantee this interface makes, and it is enforced where it cannot be
 * skipped: the statement the rules write with refuses a row whose correlation
 * is manual, so a rule cannot undo a person's decision even if the code that
 * calls it is wrong. {@code identity_responsibility.origin} exists for the same
 * reason on the identity side, and the reason is that an automated pass that
 * silently reverts a human correction teaches people not to correct anything.
 *
 * <p>No endpoint reaches this yet, which is deliberate rather than unfinished.
 * The platform has no authentication layer, and an unauthenticated endpoint
 * that can hand somebody's account to anybody is a worse thing to add than a
 * method with no caller. Who took the decision and when belongs in the audit
 * trail of KAN-14, and until that exists this records only that it was manual.
 */
public interface ManualCorrelation {

    /**
     * @param orgId      the tenant
     * @param accountId  the account, which has to exist
     * @param identityId the person it belongs to, which has to exist.
     *     {@code account.identity_id} is a soft reference across a module
     *     boundary and therefore has no foreign key behind it, so this is the
     *     only place the existence can be checked at all
     * @throws IllegalArgumentException if either does not exist in this
     *     organization. Refused rather than stored, because an account
     *     correlated to nothing is worse than an orphan: it reads as answered
     */
    void correlate(UUID orgId, UUID accountId, UUID identityId);
}
