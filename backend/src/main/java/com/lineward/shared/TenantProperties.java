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
package com.lineward.shared;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which organization this installation serves.
 *
 * <p>Configuration and not a lookup, and the reason is a consequence of
 * ADR-011 worth stating: the policy on the {@code organization} table
 * discriminates on its primary key, so a query that would find the row by code
 * needs {@code app.current_org} set first, which needs the id. The id cannot be
 * discovered at runtime by design, because being able to discover it would mean
 * being able to enumerate the other tenants.
 *
 * <p>It has to match the organization inserted by the migration
 * {@code V002__installation_organization_and_hr_source.sql}. One organization
 * until multi-tenancy exists, which section 2.3 says is not a goal: the
 * discriminator is there so that adding tenants later does not mean rewriting
 * every table and every query.
 *
 * @param orgId the organization every row of this installation belongs to
 */
@ConfigurationProperties(prefix = "lineward.tenant")
public record TenantProperties(UUID orgId) {

    public TenantProperties {
        if (orgId == null) {
            throw new IllegalArgumentException("lineward.tenant.org-id is required");
        }
    }
}
