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
package com.lineward.connector;

/**
 * One permission that exists in a target system, whether or not anybody holds it.
 *
 * <p>Read separately from the accounts, and that separation is the point. An
 * entitlement nobody holds still has to appear in the catalogue: it is what a
 * request can ask for in M5, and it is how "nobody has this any more" becomes
 * visible instead of invisible. Reading permissions only through the accounts
 * that hold them would make the catalogue a shadow of current membership.
 *
 * <p>Three fields and no more, because these are the three a target system
 * actually knows. {@code entitlement.risk} and {@code entitlement.requestable}
 * exist in the same table and are deliberately not here: how dangerous a
 * permission is and whether anyone may ask for it are governance decisions that
 * belong to M4, and a target system has no opinion on either. A connector that
 * could set them would be a target system voting on its own criticality.
 *
 * @param nativeValue the identifier the target system uses, and the value an
 *                    account's {@code entitlementValues} refers to: a group
 *                    name in a directory, a role name in Keycloak, an
 *                    application assignment in Okta
 * @param displayName what to show a human, when the system offers something
 *                    friendlier than the identifier
 * @param description what the system says it is for, which is usually nothing
 *                    and occasionally the only documentation that exists
 */
public record DiscoveredEntitlement(String nativeValue, String displayName, String description) {

    public DiscoveredEntitlement {
        if (nativeValue == null || nativeValue.isBlank()) {
            throw new IllegalArgumentException("an entitlement with no native value cannot be referred to");
        }
    }
}
