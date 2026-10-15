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

import dev.ayyoub.lineward.datagen.SyntheticEmployee;

import java.time.Instant;
import java.time.LocalDate;

/**
 * An employee as this HR system publishes them.
 *
 * <p>Shaped like an HR record and not like a Lineward identity, which is the
 * whole discipline of this service: no organization id, no identity id, no
 * status field. Lineward derives its own status from the dates, and a source
 * that handed over governance concepts would be doing Lineward's job for it and
 * hiding the work that M1 exists to do.
 *
 * <p>{@code lastModifiedAt} is here so a caller can ask for only what changed.
 */
record EmployeeView(
        String employeeNumber,
        String firstName,
        String lastName,
        String email,
        String department,
        String jobTitle,
        String location,
        String costCenter,
        String employmentType,
        LocalDate hireDate,
        LocalDate terminationDate,
        String managerEmployeeNumber,
        Instant lastModifiedAt) {

    static EmployeeView of(SyntheticEmployee employee) {
        return new EmployeeView(
                employee.employeeNumber(), employee.firstName(), employee.lastName(), employee.email(),
                employee.department(), employee.jobTitle(), employee.location(), employee.costCenter(),
                employee.employmentType(), employee.hireDate(), employee.terminationDate(),
                employee.managerEmployeeNumber(), employee.lastModifiedAt());
    }
}
