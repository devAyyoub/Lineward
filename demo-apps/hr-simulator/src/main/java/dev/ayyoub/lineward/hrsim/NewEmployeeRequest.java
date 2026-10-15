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

import java.time.LocalDate;

/**
 * Hire somebody, to provoke a joiner.
 *
 * <p>The employee number is not here: this service issues it, the way an HR
 * system does. Letting a caller choose it would allow a duplicate, and a
 * duplicated natural key merges two people into one identity downstream.
 *
 * <p>{@code hireDate} may be in the future, which is the future-dated joiner
 * edge case: the identity has to exist and hold no access until the date
 * arrives.
 */
record NewEmployeeRequest(
        String firstName,
        String lastName,
        String email,
        String department,
        String jobTitle,
        String location,
        String costCenter,
        String employmentType,
        LocalDate hireDate,
        String managerEmployeeNumber) {

    void validate() {
        requireText(firstName, "firstName");
        requireText(lastName, "lastName");
        requireText(department, "department");
        requireText(jobTitle, "jobTitle");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(field + " is required");
        }
    }
}
