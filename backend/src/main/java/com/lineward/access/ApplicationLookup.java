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
package com.lineward.access;

import java.util.List;
import java.util.UUID;

/**
 * The applications an installation governs, for the modules that have to go and
 * read them.
 *
 * <p>The read side of M4, in the same spirit as {@code IdentityLookup}: no
 * search, no write and no row, because every one of those would be an
 * invitation for another module to do the catalogue's work. M2 needs to know
 * which systems to aggregate and nothing else, and this is that question.
 *
 * <p>One method and not two. An installation has tens of applications, so a
 * caller that wants one of them filters the list, which costs nothing and
 * leaves one query shape to maintain instead of two.
 */
public interface ApplicationLookup {

    /**
     * Every application of this organization that is switched on.
     *
     * <p>Disabled ones are left out rather than returned with a flag, because
     * {@code application.enabled} exists so that an operator can stop Lineward
     * reading a system, and a caller that has to remember to check the flag is
     * a caller that will eventually forget. An empty list is a legitimate
     * answer: an installation whose applications are all disabled aggregates
     * nothing.
     *
     * @param orgId the tenant, passed explicitly for the same reason
     *     {@code IdentityLookup} does it: a caller running inside somebody
     *     else's transaction cannot accidentally ask about another organization
     */
    List<GovernedApplication> enabledApplications(UUID orgId);
}
