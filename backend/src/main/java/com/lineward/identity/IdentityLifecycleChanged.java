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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Announces that an identity's lifecycle state moved, and therefore that access
 * is due to change.
 *
 * <p>Published once per detected event, after the transaction that stored the
 * change has committed. Nothing listens yet: the consumers are birthright rules
 * and provisioning, which are later tasks of phase 1, and the audit log of M8,
 * which is where the trail of these events belongs because {@code source_record}
 * keeps only the latest assertion.
 *
 * <p>Carried in memory, which is a limit worth knowing rather than discovering.
 * A listener that fails, or a process that dies between the commit and the
 * publication, loses the event; the identity is stored correctly either way and
 * the next full read detects the same difference again, because the comparison
 * is against stored state and not against a cursor. Making delivery durable is
 * what the Spring Modulith event publication registry does, and adding it is a
 * decision of its own rather than something to slip in here.
 *
 * @param orgId the organization, carried explicitly because a listener runs
 *     outside the transaction that declared the tenant and has no other way to
 *     know which one this belongs to
 * @param identityId the governed identity, which is the handle every consumer
 *     works with
 * @param externalId the key the source uses, which is the only name a person
 *     reading a log or an audit export recognises
 * @param event what has to happen to this identity's access
 * @param status the lifecycle state the identity is now in, so a listener does
 *     not have to re-derive whether a date has arrived
 * @param changedAttributes the fields a mover moved, in a stable order, and
 *     empty for every other event. An audit entry that says only "this person
 *     moved" cannot answer why something was taken away
 * @param assertedAt when the source says its record last changed. Not when this
 *     was detected, and for an event caused by a date arriving it is older than
 *     the event itself, because the source asserted nothing new that day
 */
public record IdentityLifecycleChanged(
        UUID orgId,
        UUID identityId,
        String externalId,
        LifecycleEvent event,
        IdentityStatus status,
        List<String> changedAttributes,
        Instant assertedAt) {

    public IdentityLifecycleChanged {
        if (event == LifecycleEvent.NONE) {
            throw new IllegalArgumentException(
                    "identity " + externalId + ": NONE is the absence of an event and is not published. "
                            + "Publishing it would make a list of events where most of them mean "
                            + "nothing, and a consumer would have to filter what should never have "
                            + "been sent");
        }
        changedAttributes = List.copyOf(changedAttributes);
    }
}
