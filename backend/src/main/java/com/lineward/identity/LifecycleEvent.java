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

/**
 * What an authoritative source said that changes what access is due.
 *
 * <p>Joiner, mover and leaver are the three moments of a working life that force
 * somebody to touch access, and the mover is the one that gets done badly: the
 * new access is granted and the old one stays, which is how a person ends up
 * with the permissions of every job they have ever held. Lineward is not told
 * about these moments, it discovers them by comparing what the source asserts
 * today with what it asserted last time.
 *
 * <p>Each value is a statement about work that is due now, not about the
 * person's story. That is what makes them safe to act on: a consumer that sees
 * {@code JOINER} grants, one that sees {@code LEAVER} revokes, and neither has
 * to re-derive whether the date has arrived. The cases where nothing is due are
 * {@code NONE} rather than a value of their own, because an event that means
 * "no action" and an event that means "act" must not look alike at the point
 * somebody reads the list.
 */
public enum LifecycleEvent {

    /**
     * Access is due and this identity has never held any: somebody started.
     *
     * <p>Covers the future dated joiner arriving at their hire date, which the
     * source cannot announce because it asserts the same record every day.
     */
    JOINER,

    /**
     * The identity keeps its access and the basis for it moved: a different
     * department, job, location, cost centre, employment type or manager.
     *
     * <p>The event names what changed, because recalculating entitlements is
     * only half of a mover: the other half is being able to answer why
     * something was taken away.
     */
    MOVER,

    /**
     * Access is no longer due and the identity holds some: everything has to go.
     *
     * <p>Named after the person leaving because that is what causes it almost
     * always, but the rule is about access rather than about resignation. A
     * record whose hire date is pushed into the future also lands here, and the
     * safe reading of that contradiction is the one that grants nothing.
     */
    LEAVER,

    /**
     * A returning employee: the identity that was terminated is asserted as
     * working here again.
     *
     * <p>The access work is a joiner's. It is a value of its own because the
     * audit trail of somebody who came back is not the audit trail of somebody
     * new, and M1 requires reactivating the identity rather than duplicating it.
     */
    REHIRE,

    /**
     * Nothing to do. The source changed a spelling, said nothing new, announced
     * a departure that has not arrived, or keeps sending somebody who left long
     * before Lineward first read the source.
     */
    NONE
}
