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
package dev.ayyoub.lineward.identity.ingest;

import dev.ayyoub.lineward.identity.IdentityStatus;
import java.time.LocalDate;

/**
 * Derives the governed status from the dates the source asserts.
 *
 * <p>The order of the two checks is the interesting part. A termination that has
 * taken effect wins over everything, including a record that contradicts itself.
 * The generated population contains people whose termination date precedes their
 * hire date, because a real extract contains them too, and the safe reading of
 * "this is nonsense" is the one that grants nothing. The contradiction is a
 * finding for M2 to raise, not a reason to hand out access while somebody works
 * out what the source meant.
 *
 * <p>A termination date in the future leaves the identity active until then,
 * which is the correct reading of a notice period.
 */
final class IdentityStatusRule {

    private IdentityStatusRule() {}

    static IdentityStatus statusFor(LocalDate hireDate, LocalDate terminationDate, LocalDate today) {
        if (terminationDate != null && !terminationDate.isAfter(today)) {
            return IdentityStatus.TERMINATED;
        }
        if (hireDate != null && hireDate.isAfter(today)) {
            return IdentityStatus.PENDING;
        }
        return IdentityStatus.ACTIVE;
    }
}
