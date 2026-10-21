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

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Turns the manager the source names into a responsibility row.
 *
 * <p>Separate pass, and it has to be: the source names a manager by employee
 * number, and that person may not have been read yet when the record that points
 * at them arrives. Resolving it inline would make the result depend on the order
 * the pages happen to come in.
 *
 * <p>Writes into {@code identity_responsibility}, which is one table for
 * manager, owner and sponsor by decision 5 of ADR-013. That is what makes the
 * leaver cascade a single piece of logic instead of two that drift apart, and it
 * is the reason this class only ever touches rows whose origin is
 * {@code ASSERTED}. A sponsor assigned by a person inside Lineward is not the
 * HR system's to overwrite, and with a single {@code manager_id} column there
 * would be no way to tell the two apart.
 *
 * <p>History rather than overwriting: a change closes the old row with
 * {@code valid_to} and opens a new one. That is what later answers "who was
 * their manager when this access was approved", which is a question an auditor
 * asks and a mutable column cannot answer.
 */
class ManagerReconciliation {

    private static final String ASSERTED = "ASSERTED";

    private final JdbcClient jdbc;

    ManagerReconciliation(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What one batch of manager links did. */
    record Outcome(int linked, int unlinked, int unresolved, int preserved) {

        Outcome plus(Outcome other) {
            return new Outcome(
                    linked + other.linked,
                    unlinked + other.unlinked,
                    unresolved + other.unresolved,
                    preserved + other.preserved);
        }

        static Outcome none() {
            return new Outcome(0, 0, 0, 0);
        }
    }

    private record ExternalIdentity(String externalId, UUID identityId) {}

    private record CurrentManager(UUID rowId, UUID subjectIdentityId, UUID responsibleIdentityId, String origin) {}

    /**
     * @param assertedManagers subject employee number to the manager's employee
     *     number, or to {@code null} when the source asserts no manager. The
     *     null entries matter as much as the others: a manager removed at the
     *     source has to close the row here, and an identity nobody answers for
     *     is the finding M1 lists, not a missing entry to skip
     */
    Outcome reconcile(UUID orgId, UUID sourceSystemId, Map<String, String> assertedManagers) {
        if (assertedManagers.isEmpty()) {
            return Outcome.none();
        }

        Set<String> involved = new LinkedHashSet<>(assertedManagers.keySet());
        assertedManagers.values().stream().filter(java.util.Objects::nonNull).forEach(involved::add);

        Map<String, UUID> identityIds = resolveIdentityIds(orgId, sourceSystemId, involved);
        Map<UUID, CurrentManager> currentBySubject = loadCurrentManagers(orgId, identityIds.values());

        int linked = 0;
        int unlinked = 0;
        int unresolved = 0;
        int preserved = 0;

        for (Map.Entry<String, String> entry : assertedManagers.entrySet()) {
            UUID subjectId = identityIds.get(entry.getKey());
            if (subjectId == null) {
                // The subject was written moments ago in this same run, so this
                // means the source changed under us. Counted rather than
                // thrown: the next run reads it again.
                unresolved++;
                continue;
            }

            CurrentManager current = currentBySubject.get(subjectId);
            if (current != null && !ASSERTED.equals(current.origin())) {
                preserved++;
                continue;
            }

            String managerExternalId = entry.getValue();
            UUID managerId = managerExternalId == null ? null : identityIds.get(managerExternalId);

            if (managerExternalId != null && managerId == null) {
                // The manager exists at the source but has not been read yet,
                // which an incremental read does routinely. The next full read
                // closes the gap.
                unresolved++;
                continue;
            }
            if (managerId != null && managerId.equals(subjectId)) {
                // The source says somebody manages themselves. The schema
                // forbids it, and letting the insert fail would abort the whole
                // batch over one contradictory record.
                unresolved++;
                continue;
            }

            boolean unchanged = current != null
                    && managerId != null
                    && current.responsibleIdentityId().equals(managerId);
            if (unchanged) {
                continue;
            }

            // Close before opening: the unique index allows one current holder
            // of a role per identity, so the order is not a preference.
            if (current != null) {
                close(orgId, current.rowId());
                if (managerId == null) {
                    unlinked++;
                }
            }
            if (managerId != null) {
                open(orgId, subjectId, managerId);
                linked++;
            }
        }

        return new Outcome(linked, unlinked, unresolved, preserved);
    }

    private Map<String, UUID> resolveIdentityIds(
            UUID orgId, UUID sourceSystemId, Collection<String> externalIds) {
        List<ExternalIdentity> rows = jdbc.sql("""
                SELECT external_id, identity_id
                  FROM source_record
                 WHERE org_id = :orgId
                   AND source_system_id = :sourceSystemId
                   AND external_id IN (:externalIds)
                """)
                .param("orgId", orgId)
                .param("sourceSystemId", sourceSystemId)
                .param("externalIds", externalIds)
                .query((rs, row) -> new ExternalIdentity(
                        rs.getString("external_id"), rs.getObject("identity_id", UUID.class)))
                .list();

        return rows.stream().collect(
                Collectors.toMap(ExternalIdentity::externalId, ExternalIdentity::identityId));
    }

    private Map<UUID, CurrentManager> loadCurrentManagers(UUID orgId, Collection<UUID> subjectIds) {
        if (subjectIds.isEmpty()) {
            return Map.of();
        }
        List<CurrentManager> rows = jdbc.sql("""
                SELECT id, subject_identity_id, responsible_identity_id, origin
                  FROM identity_responsibility
                 WHERE org_id = :orgId
                   AND role = 'MANAGER'
                   AND valid_to IS NULL
                   AND subject_identity_id IN (:subjectIds)
                """)
                .param("orgId", orgId)
                .param("subjectIds", subjectIds)
                .query((rs, row) -> new CurrentManager(
                        rs.getObject("id", UUID.class),
                        rs.getObject("subject_identity_id", UUID.class),
                        rs.getObject("responsible_identity_id", UUID.class),
                        rs.getString("origin")))
                .list();

        return rows.stream().collect(
                Collectors.toMap(CurrentManager::subjectIdentityId, Function.identity()));
    }

    /**
     * Closes with the database's clock rather than the application's, so that
     * {@code valid_to} is always after the {@code valid_from} the same clock
     * produced. The period check on the table rejects anything else, and two
     * clocks a few microseconds apart is exactly the kind of failure that only
     * shows up in production.
     */
    private void close(UUID orgId, UUID rowId) {
        jdbc.sql("UPDATE identity_responsibility SET valid_to = now() WHERE org_id = :orgId AND id = :rowId")
                .param("orgId", orgId)
                .param("rowId", rowId)
                .update();
    }

    private void open(UUID orgId, UUID subjectId, UUID managerId) {
        jdbc.sql("""
                INSERT INTO identity_responsibility (
                        org_id, subject_identity_id, responsible_identity_id, role, origin)
                VALUES (:orgId, :subjectId, :managerId, 'MANAGER', 'ASSERTED')
                """)
                .param("orgId", orgId)
                .param("subjectId", subjectId)
                .param("managerId", managerId)
                .update();
    }
}
