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
package com.lineward.access.birthright;

import com.lineward.access.birthright.ExpectedAccessWriter.Outcome;
import com.lineward.identity.GovernedAttributes;
import com.lineward.identity.IdentityLifecycleChanged;
import com.lineward.identity.IdentityLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

/**
 * Recalculates what an identity is due whenever its lifecycle moves.
 *
 * <p>The first consumer of {@link IdentityLifecycleChanged}, and the piece that
 * turns a detection into an access. Until this existed, ingestion noticed that
 * somebody had joined and nothing happened.
 *
 * <p><b>It recalculates rather than applying the event.</b> The event says what
 * happened and this asks what is due now, which means a joiner, a mover, a
 * leaver and a rehire all take the same path: read the attributes, work out the
 * set, write the difference. There is no branch per event type, and the reason
 * to keep it that way is that the branch per event type is where a mover gets
 * its new access and keeps the old.
 *
 * <p><b>Why {@code @EventListener} and not the asynchronous one.</b> The
 * contract is written on the event itself: a listener runs inside the
 * transaction that stored the change, so there is no window in which an event
 * can be lost, and the condition is that a listener may not do anything a
 * rollback cannot undo. Writing expected access is exactly that, and
 * {@code @ApplicationModuleListener} would run after the commit, which is the
 * annotation this project does not use.
 *
 * <p>So a failure here rolls the stored change back with it. That is deliberate:
 * an identity whose access could not be worked out is better left undetected and
 * retried than stored as handled, because the second is indistinguishable from a
 * person nobody granted anything to.
 */
class BirthrightRecalculation {

    private static final Logger log = LoggerFactory.getLogger(BirthrightRecalculation.class);

    private final IdentityLookup identities;
    private final BirthrightRules rules;
    private final ExpectedAccessWriter expectedAccess;

    BirthrightRecalculation(
            IdentityLookup identities, BirthrightRules rules, ExpectedAccessWriter expectedAccess) {
        this.identities = identities;
        this.rules = rules;
        this.expectedAccess = expectedAccess;
    }

    @EventListener
    void recalculateOn(IdentityLifecycleChanged event) {
        GovernedAttributes attributes = identities
                .attributesOf(event.orgId(), event.identityId())
                .orElseThrow(() -> new IllegalStateException(
                        "lifecycle event " + event.event() + " for identity " + event.externalId()
                                + " (" + event.identityId() + ") in organization " + event.orgId()
                                + ", which does not exist. The event is published inside the "
                                + "transaction that writes the identity, so this cannot happen "
                                + "unless the two have come apart"));

        Outcome outcome = expectedAccess.reconcile(
                event.orgId(), event.identityId(), rules.dueFor(attributes));

        // One line per identity, so debug and not info: a full read of the
        // synthetic population is five thousand of these. The counters of a run
        // belong to the ingestion report, and the per decision trail belongs to
        // the audit log of KAN-14, which does not exist yet.
        if (log.isDebugEnabled() && outcome.changedAnything()) {
            log.debug(
                    "{} for {}: {} access granted, {} retired",
                    event.event(),
                    event.externalId(),
                    outcome.granted(),
                    outcome.retired());
        }
    }
}
