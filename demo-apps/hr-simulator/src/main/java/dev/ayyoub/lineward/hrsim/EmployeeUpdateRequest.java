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
package dev.ayyoub.lineward.hrsim;

/**
 * Change somebody's record, to provoke a mover.
 *
 * <p>Every field is optional: what is sent changes, what is omitted stays. That
 * leaves one gap, which is why the two flags exist. With omission meaning "keep",
 * there is no way to express "remove", and removing a manager or an email is
 * exactly what produces the identity-with-no-manager and the
 * correlation-cannot-resolve cases that M1 and M2 list as edge cases. A JSON
 * null cannot carry that distinction here, because an absent field and an
 * explicit null both arrive as null.
 */
record EmployeeUpdateRequest(
        String firstName,
        String lastName,
        String email,
        String department,
        String jobTitle,
        String location,
        String costCenter,
        String employmentType,
        String managerEmployeeNumber,
        Boolean clearEmail,
        Boolean clearManager) {

    // Boolean and not boolean, which is the whole reason a partial request
    // works at all. A caller sending {"department":"Risk"} omits these two, so
    // Jackson passes null to the constructor, and Jackson 3 refuses to map null
    // onto a primitive: the request failed with 400 and a parse error that
    // named neither field. Found on 2026-10-16 by sending a real partial PATCH
    // with curl, which the tests never did because they build the record in
    // Java and therefore always send all eleven fields.

    boolean clearEmailRequested() {
        return Boolean.TRUE.equals(clearEmail);
    }

    boolean clearManagerRequested() {
        return Boolean.TRUE.equals(clearManager);
    }
}
