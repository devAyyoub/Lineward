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

/**
 * The kinds of problem a reconciliation can find.
 *
 * <p>The names are the strings of {@code reconciliation_finding_type_check} in
 * V001, character for character, because the constraint is the authority on
 * what the column may hold and a second vocabulary here would be a second
 * authority. A value added to one without the other fails at the first insert,
 * which is the intended direction: the database refuses rather than the
 * platform inventing a type nobody can store.
 *
 * <p>Each one carries which column of the finding row names its subject, and
 * that is not decoration. {@code reconciliation_finding_subject_check} refuses
 * a row whose subject does not match its type, so the pairing exists in the
 * schema already; naming it here means the code that writes a finding cannot
 * assemble one the database will reject.
 */
public enum FindingType {

    /**
     * An account no rule and no person could place.
     *
     * <p>Never a service account. A target system that serves one says so, and
     * an account that nobody is supposed to be able to place is not a failure
     * of correlation: putting every service account on a list of orphans to
     * chase is how the list stops being read.
     */
    ORPHAN_ACCOUNT(Subject.ACCOUNT),

    /** An account the target system has not seen used for longer than the configured period. */
    DORMANT_ACCOUNT(Subject.ACCOUNT),

    /**
     * A non-human identity nobody answers for.
     *
     * <p><b>Nothing produces this yet, and the reason is in the schema rather
     * than in the code.</b> The subject check of V001 requires an identity, and
     * a service account read from a target system has none: V007 says so in as
     * many words, that an account whose kind is {@code SERVICE} still has no
     * identity of its own and that who answers for a non-human identity is a
     * decision a person takes. Until registering one is built, the condition
     * this names cannot be written down about anything. The value stays in the
     * enum because the constraint has it and because leaving it out would hide
     * the gap rather than record it.
     */
    UNOWNED_IDENTITY(Subject.IDENTITY),

    /**
     * An access that exists in the target system and that nothing says is due.
     *
     * <p>Granted outside the platform, or left behind by a move that the target
     * system never heard about. The one finding of the four that is about
     * privilege rather than about an account.
     */
    DRIFT_UNEXPECTED_GRANT(Subject.GRANT),

    /**
     * An access Lineward records as in place and that the target system does
     * not have.
     *
     * <p>Only ever about an assignment Lineward considers provisioned. An
     * access that is merely due and not yet provisioned is absent from every
     * target system by definition, and reporting that would make the first
     * report a list of every expectation the platform holds.
     */
    DRIFT_MISSING_GRANT(Subject.GRANT);

    /** Which of the finding row's three subject columns this type fills. */
    public enum Subject {
        ACCOUNT,
        IDENTITY,
        GRANT
    }

    private final Subject subject;

    FindingType(Subject subject) {
        this.subject = subject;
    }

    public Subject subject() {
        return subject;
    }
}
