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
 * The lifecycle state of an identity, mirroring the check constraint on
 * {@code identity.status}.
 *
 * <p>{@code PENDING} is the one worth explaining: it covers the joiner with a
 * hire date in the future, which M1 lists as an edge case. The record has to
 * exist, because HR already asserts the person, and it has to hold no access
 * until the date arrives. A model with only active and terminated has to choose
 * between inventing the identity late and granting access early.
 *
 * <p>{@code SUSPENDED} is not produced by ingestion. It is what phase 6 sets
 * when the party responsible for an internally sourced identity goes away,
 * which is how an agent stops working when its sponsor leaves.
 */
public enum IdentityStatus {
    PENDING,
    ACTIVE,
    SUSPENDED,
    TERMINATED
}
