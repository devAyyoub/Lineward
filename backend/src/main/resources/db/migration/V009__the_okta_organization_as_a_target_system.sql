-- Copyright 2026 Ayyoub Amjahed Abed
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     https://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- The Okta organization, as the third target system Lineward reads.
--
-- V008 taught the schema that OKTA is a connector type. This is the row that
-- uses it, and until now it has lived by hand in one developer's database,
-- which is why the Okta half of KAN-9 could be verified once and not repeated.
--
-- Why it is seeded here and the Keycloak and LDAP rows were seeded in V003:
-- those two point at containers this repository starts, so every checkout has
-- the system behind the row. An Okta organization belongs to somebody, so this
-- row names a system that most checkouts cannot reach. That is deliberate and
-- it is the same shape as the connector configuration, which application.yaml
-- leaves commented out for the same reason: the row describes what Lineward
-- would govern, and the configuration decides whether it tries. A target
-- system with no connector configured is simply never read.
--
-- No entitlements are seeded with it, which is the difference that matters
-- against V003. Okta names a group by an identifier it generates when the
-- group is created, picked by the connector on purpose so a rename cannot
-- break a membership. Nothing can know those values before the organization
-- exists, so the Okta catalogue is discovered by aggregation rather than
-- asserted here. A seeded guess would reappear as drift against every real
-- group, which is exactly what V004 had to undo for Keycloak.

-- Which organization this statement is for, as V003 and V004 also declare.
-- Not boilerplate: application carries FORCE ROW LEVEL SECURITY and its policy
-- reads this setting, so a migration that forgets it is refused rather than
-- quietly writing a row into nobody's organization. The third argument makes
-- it local to the transaction.
SELECT set_config('app.current_org', '00000000-0000-7000-8000-000000000001', true);

INSERT INTO application (org_id, code, name, connector_type, criticality)
VALUES ('00000000-0000-7000-8000-000000000001', 'okta-directory',
        'Okta directory', 'OKTA', 'HIGH');
