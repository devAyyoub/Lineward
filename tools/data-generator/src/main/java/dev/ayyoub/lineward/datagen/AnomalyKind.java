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
package dev.ayyoub.lineward.datagen;

/**
 * The anomalies the generator seeds on purpose.
 *
 * <p>Section 10.2 asks for 2 to 5 % of seeded anomalies so that the demo has
 * findings: a governance platform shown against clean data proves nothing,
 * because the interesting output of an IGA platform is the exception.
 *
 * <p>These six are not invented. Each one is an edge case the module sheets
 * already list, so ingesting this population exercises the branches that are
 * otherwise only reachable by hand:
 *
 * <ul>
 *   <li>{@link #MISSING_MANAGER}, {@link #FUTURE_HIRE_DATE},
 *       {@link #RETROACTIVE_TERMINATION}, {@link #MISSING_EMAIL} and
 *       {@link #TERMINATION_BEFORE_HIRE} are the M1 edge cases: future-dated
 *       joiner, retroactive leaver, identity with no manager, and incomplete or
 *       contradictory HR data.
 *   <li>{@link #DUPLICATE_EMAIL} is the M2 edge case of two identities matching
 *       the same account, and it is the one that breaks correlation by email.
 * </ul>
 */
public enum AnomalyKind {

    /** No manager, and not because they run the company. Nobody to certify their access. */
    MISSING_MANAGER,

    /** Hired in the future: the identity must exist and hold no access yet. */
    FUTURE_HIRE_DATE,

    /** Already gone when HR says so: termination_date is in the past. */
    RETROACTIVE_TERMINATION,

    /** No email, so correlation by email cannot resolve this person. */
    MISSING_EMAIL,

    /** Terminated before being hired. Contradictory, and a real extract contains it. */
    TERMINATION_BEFORE_HIRE,

    /** Shares an email with somebody else, so one account can match two identities. */
    DUPLICATE_EMAIL
}
