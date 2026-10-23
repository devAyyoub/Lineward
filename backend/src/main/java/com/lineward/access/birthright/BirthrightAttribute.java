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

import com.lineward.identity.GovernedAttributes;

/**
 * The identity attributes a birthright rule is allowed to read.
 *
 * <p>A closed list and not a free string, and that is the useful property of it:
 * a rule naming an attribute that is not here fails when the configuration is
 * read, at startup, rather than quietly matching nobody. A typo in a rule that
 * grants access is not a configuration error that shows up in a log, it is a
 * person who never gets their access, or everybody getting it.
 *
 * <p>Five and not six. The manager is missing on purpose: who somebody reports
 * to moves who approves and who certifies their access, which is governance, but
 * it is not a reason to hold an entitlement. A rule that granted access by
 * manager would make the access of a whole team depend on one person's job
 * change.
 */
enum BirthrightAttribute {

    DEPARTMENT,
    JOB_TITLE,
    LOCATION,
    COST_CENTER,
    EMPLOYMENT_TYPE;

    /**
     * Reads this attribute off an identity.
     *
     * <p>Here rather than in the matching code, so that adding an attribute is
     * one place: the constant and its accessor are the same change.
     *
     * @return the asserted value, or {@code null} when the source said nothing.
     *     What a null means for access is decided by the caller, and
     *     {@link BirthrightRule} decides that it matches nothing
     */
    String of(GovernedAttributes attributes) {
        return switch (this) {
            case DEPARTMENT -> attributes.department();
            case JOB_TITLE -> attributes.jobTitle();
            case LOCATION -> attributes.location();
            case COST_CENTER -> attributes.costCenter();
            case EMPLOYMENT_TYPE -> attributes.employmentType();
        };
    }
}
