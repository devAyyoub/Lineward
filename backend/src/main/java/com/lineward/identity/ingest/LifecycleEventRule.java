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
package com.lineward.identity.ingest;

import com.lineward.identity.IdentityStatus;
import com.lineward.identity.LifecycleEvent;
import com.lineward.identity.source.AssertedIdentity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Classifies a change of lifecycle state as a joiner, a mover or a leaver.
 *
 * <p>Everything here hangs off one question, which is why the rule is four
 * branches rather than a case per situation: <b>is access due, and was it due
 * before?</b> Granting is what happens when it starts being due, revoking when
 * it stops, and recalculating when it stays due and the basis for it moved.
 * Written that way, the awkward cases need no code of their own. Somebody hired
 * with a future date is simply not due yet. Somebody who left before Lineward
 * ever read the source was never due. A record that contradicts itself gets the
 * reading that grants nothing, because that falls out of the question instead of
 * being a rule somebody has to remember.
 *
 * <p>The two inputs come from different places on purpose. The state after the
 * change is derived from the dates the source asserts, through
 * {@link IdentityStatusRule}. The state before it is the stored
 * {@code identity.status}, which is what Lineward last acted on. That is not the
 * comparison ADR-013 decision 4 warns about: the warning is about attributes,
 * where reading the governed row would turn a correction made inside Lineward
 * into news from HR. The status is the opposite case, because a status set here
 * is a governance decision and not noise.
 *
 * <p>Reading the stored status is also what lets the rule see time pass. A
 * person hired next month and a person leaving next week both produce an
 * unchanged payload the day their date arrives, so the fingerprint sees nothing
 * and an assertion-against-assertion comparison sees nothing either. The stored
 * status does: it still says what was true yesterday.
 *
 * <p>{@code SUSPENDED} is not produced by ingestion and is not handled here. It
 * arrives in phase 6, when the party responsible for an internally sourced
 * identity goes away, and reinstating one is a decision taken inside Lineward
 * rather than a lifecycle event discovered in a source.
 */
final class LifecycleEventRule {

    private LifecycleEventRule() {}

    /**
     * @param before the stored status, or {@code null} when the identity is new
     *     to Lineward
     * @param after the status derived from what the source now asserts
     * @param accessBasisMoved whether a field that access is decided on changed,
     *     as named by {@link #changedAccessAttributes}
     */
    static LifecycleEvent classify(IdentityStatus before, IdentityStatus after, boolean accessBasisMoved) {
        boolean wasDue = before == IdentityStatus.ACTIVE;
        boolean isDue = after == IdentityStatus.ACTIVE;

        if (isDue && !wasDue) {
            // Telling the two apart costs one comparison and buys an audit trail
            // that can answer "is this a new hire or somebody who came back",
            // which is a question auditors ask and M1 requires an answer to.
            return before == IdentityStatus.TERMINATED ? LifecycleEvent.REHIRE : LifecycleEvent.JOINER;
        }
        if (wasDue && !isDue) {
            return LifecycleEvent.LEAVER;
        }
        if (isDue && accessBasisMoved) {
            return LifecycleEvent.MOVER;
        }
        return LifecycleEvent.NONE;
    }

    /**
     * The same question asked of two assertions of the same source, which is the
     * form ingestion has at the point it notices the payload changed.
     *
     * @param previous what the source asserted last time, or {@code null} when
     *     this is the first assertion about this identity
     */
    static LifecycleEvent classify(
            IdentityStatus before, AssertedIdentity previous, AssertedIdentity current, LocalDate today) {
        IdentityStatus after =
                IdentityStatusRule.statusFor(current.hireDate(), current.terminationDate(), today);
        return classify(before, after, !changedAccessAttributes(previous, current).isEmpty());
    }

    /**
     * The fields a mover is about, named for the audit trail rather than for the
     * code: recalculating entitlements is only half of a mover, and the other
     * half is being able to say why something was taken away.
     *
     * <p>Six fields and not every field the source sends. A name or an email
     * that gets corrected is a change in the source and must not be a mover,
     * because recalculating access on every typo is how a mover stops meaning
     * anything. The hire and termination dates are left out too: they decide the
     * lifecycle state and are compared through it, so counting them here would
     * make every leaver a leaver and a mover at once.
     *
     * <p>The manager is in, and it is the one worth defending. Birthright rules
     * read the department, the job and the location, so a reporting line change
     * looks like nothing to them, and M1 lists it as an edge case for exactly
     * that reason. It still moves who approves this person's requests and who
     * certifies their access, and a reporting line nobody noticed changing is
     * how a manager ends up certifying access for somebody who no longer works
     * for them.
     *
     * @return the changed fields in declaration order, so that two runs of the
     *     same move read the same, and empty when there is nothing to compare
     *     against
     */
    static List<String> changedAccessAttributes(AssertedIdentity previous, AssertedIdentity current) {
        if (previous == null) {
            return List.of();
        }

        List<String> changed = new ArrayList<>();
        addIfChanged(changed, "department", previous.department(), current.department());
        addIfChanged(changed, "jobTitle", previous.jobTitle(), current.jobTitle());
        addIfChanged(changed, "location", previous.location(), current.location());
        addIfChanged(changed, "costCenter", previous.costCenter(), current.costCenter());
        addIfChanged(changed, "employmentType", previous.employmentType(), current.employmentType());
        // Named after what moved and not after the field the source uses: the
        // trail says the manager changed, not that managerEmployeeNumber did.
        addIfChanged(changed, "manager", previous.managerExternalId(), current.managerExternalId());
        return List.copyOf(changed);
    }

    private static void addIfChanged(List<String> changed, String name, String before, String after) {
        if (!Objects.equals(before, after)) {
            changed.add(name);
        }
    }
}
