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
 * <p>Published once per detected event, <b>inside</b> the transaction that
 * stores the change, and that is the whole delivery design. A listener runs
 * synchronously in that same transaction, so there is no window between storing
 * and announcing: if the listener fails, the change that caused it is rolled
 * back too, and if the transaction fails, the listener's work goes with it. An
 * event cannot be lost because it is never in flight.
 *
 * <p><b>The condition that keeps this true: a listener may not do anything a
 * rollback cannot undo.</b> Writing to Lineward's own tables is fine, which is
 * what calculating the access an identity is due amounts to. Creating an account
 * in a target system is not, and it does not belong in a listener at all: it
 * goes through a durable task, which phase 2 needs anyway because a target
 * system being unreachable is a certainty rather than a risk. Spring Modulith's
 * {@code @ApplicationModuleListener} is asynchronous and runs after the commit,
 * so it is the annotation this project does not use, and avoiding it is what
 * makes its event publication registry unnecessary.
 *
 * <p><b>What this does not give, said plainly:</b> a lost event is not
 * re-detected. The comparison that produced it is against stored state, and the
 * same transaction already brought that state up to date, so a difference
 * announced once is never announced again. What survives is better than a
 * replay: the attributes and the status are stored, so the access an identity is
 * due is recomputable from them at any time. That is what reconciliation does,
 * and it is the real safety net here, for the same reason it has to exist
 * anyway: somebody will grant access outside Lineward, and no event mechanism
 * notices that.
 *
 * <p>Nothing listens yet. The consumers are the birthright rules of phase 1 and
 * the audit log of M8, which is where the trail of these events belongs because
 * {@code source_record} keeps only the latest assertion.
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
