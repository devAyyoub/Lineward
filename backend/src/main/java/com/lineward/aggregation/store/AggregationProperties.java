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
package com.lineward.aggregation.store;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much of a target system to read at a time, and what it calls an employee.
 *
 * @param pageSize                how many accounts or permissions to ask for
 *                                per request, and also how many share a
 *                                transaction. A request and not a promise: a
 *                                target system is free to return fewer
 * @param employeeNumberAttribute what the target systems call the employee
 *                                number, which the first correlation rule reads
 *                                off an account. Configuration because every
 *                                system names it differently; one name for the
 *                                installation rather than one per application
 *                                because both systems this repository ships use
 *                                {@code employeeNumber}, the realm by declaring
 *                                it in its user profile and the directory by
 *                                setting the standard LDAP attribute of that
 *                                name
 * @param onStartup               runs an aggregation of every configured system
 *                                when the application starts. Off by default.
 *                                It exists so that the run can be driven end to
 *                                end against the real containers before there
 *                                is an API or a schedule to drive it, which is
 *                                the same reason the HR ingestion has one
 */
@ConfigurationProperties(prefix = "lineward.aggregation")
record AggregationProperties(int pageSize, String employeeNumberAttribute, boolean onStartup) {

    AggregationProperties {
        // Zero would turn the paging loop into one that never advances, and the
        // default of an unset int is zero.
        if (pageSize < 1) {
            throw new IllegalArgumentException(
                    "lineward.aggregation.page-size must be at least 1, got " + pageSize);
        }
        if (employeeNumberAttribute == null || employeeNumberAttribute.isBlank()) {
            throw new IllegalArgumentException("lineward.aggregation.employee-number-attribute is required");
        }
    }
}
