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
package com.lineward.connector.okta;

import com.lineward.datagen.HrPopulationGenerator;
import com.lineward.datagen.model.HrPopulationSpec;
import com.lineward.datagen.model.SyntheticEmployee;
import com.lineward.datagen.model.SyntheticHrPopulation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which people the Okta demo organization holds, and why each one is there.
 *
 * <p><b>The shape of this is the decision, not the list.</b> Every other target
 * system in this project holds the whole company: the HR simulator, Keycloak
 * and OpenLDAP all serve five thousand people, because they cost nothing and we
 * host them. Okta's free plan caps an organization at ten active users, read
 * from the panel on 2026-11-04, so the obvious plan of mirroring the population
 * was never going to work. Rather than fight the cap, the split is inverted:
 * <b>volume lives where it is free and ours, and Okta holds a chosen slice</b>,
 * one account per thing the demo has to show. For a demonstration, nine
 * accounts that prove three findings are worth more than five thousand that
 * prove the same three.
 *
 * <p><b>The people are not invented.</b> They are grown from the same seed the
 * HR simulator uses, so the person behind an Okta account is the same person
 * Lineward ingested from HR, down to the employee number that correlation
 * matches on. Inventing a parallel set would make every Okta account an orphan
 * for an uninteresting reason.
 *
 * <p><b>What is deliberately not here: drift.</b> A drifted grant is one the
 * target system has and birthright does not expect, and expressing that needs a
 * rule naming the entitlement in advance. LDAP and Keycloak allow it, because
 * their native values are a distinguished name and a path that a person
 * chooses. Okta's are identifiers it generates when the group is created, which
 * {@link OktaDocuments} picks on purpose so that a rename cannot break a
 * membership, and which no rule file can name before the group exists. So in
 * Okta every membership would read as unexpected and the finding would be
 * trivially true. Drift is demonstrated against the two systems where the
 * comparison means something, decided on 2026-11-22.
 */
public final class OktaDemoSlice {

    /**
     * The fixture of section 10.2, matching {@code hr-simulator/application.yaml}.
     *
     * <p>Copied rather than read from that service: the slice has to be
     * computable without anything running, and a seed is part of the fixture
     * rather than a setting. If the simulator's seed ever changes, the test
     * below fails and says so.
     */
    public static final HrPopulationSpec SPEC =
            new HrPopulationSpec(20261016L, 5_000, LocalDate.parse("2026-10-15"), 0.03);

    /** Okta's free plan ceiling, read from the panel and not from documentation. */
    public static final int ACTIVE_USER_CAP = 10;

    /** The organization's own administrator, who holds one of the ten. */
    public static final int SEATS_ALREADY_TAKEN = 1;

    private OktaDemoSlice() {}

    /** What an account in the slice is there to demonstrate. */
    public enum Case {
        /** Nothing wrong with it. Without these the findings have nothing to stand against. */
        MATCHED,
        /** Never signed in, which is the only form of dormancy that can be seeded. */
        DORMANT,
        /** HR says this person left and the account is still alive. */
        LEAVER_STILL_ENABLED,
        /** No employee number, so no rule can find an owner for it. */
        UNOWNED_SERVICE_ACCOUNT
    }

    /**
     * One account to create.
     *
     * @param employeeNumber the number HR asserts, or {@code null} for an
     *                       account that belongs to nobody on purpose
     */
    public record SeedAccount(
            String login,
            String email,
            String firstName,
            String lastName,
            String employeeNumber,
            String department,
            Case demonstrates) {

        /** Whether correlation should be able to find an owner for this one. */
        public boolean shouldMatch() {
            return employeeNumber != null;
        }
    }

    /**
     * The slice, in the order it is created.
     *
     * <p>Six people out of the generated company plus one account that
     * deliberately has no person behind it.
     */
    public static List<SeedAccount> accounts() {
        SyntheticHrPopulation population = HrPopulationGenerator.generate(SPEC);
        List<SeedAccount> slice = new ArrayList<>();
        slice.add(from(population, "E-0002", Case.MATCHED));
        slice.add(from(population, "E-0003", Case.MATCHED));
        slice.add(from(population, "E-0004", Case.MATCHED));
        slice.add(from(population, "E-0005", Case.DORMANT));
        slice.add(from(population, "E-3894", Case.LEAVER_STILL_ENABLED));
        slice.add(from(population, "E-3293", Case.LEAVER_STILL_ENABLED));
        slice.add(new SeedAccount(
                "svc-reporting@lineward.example",
                "svc-reporting@lineward.example",
                "Reporting",
                "Service",
                null,
                "Engineering",
                Case.UNOWNED_SERVICE_ACCOUNT));
        return List.copyOf(slice);
    }

    /** The groups the slice needs, derived from it rather than listed twice. */
    public static Set<String> groups() {
        Set<String> names = new LinkedHashSet<>();
        for (SeedAccount account : accounts()) {
            names.add(groupOf(account));
        }
        return Set.copyOf(names);
    }

    /** Which group an account belongs in. One per department present in the slice. */
    public static String groupOf(SeedAccount account) {
        return "dept-" + account.department().toLowerCase().replace(' ', '-');
    }

    private static SeedAccount from(SyntheticHrPopulation population, String employeeNumber, Case demonstrates) {
        Optional<SyntheticEmployee> found = population.find(employeeNumber);
        SyntheticEmployee employee = found.orElseThrow(() -> new IllegalStateException(
                "the slice names " + employeeNumber + ", which this seed does not produce."
                        + " Either the seed changed or the number is wrong, and both are worth stopping for"));
        if (employee.email() == null) {
            throw new IllegalStateException(
                    employeeNumber + " has no email, so it cannot be an Okta login. Pick another one");
        }
        return new SeedAccount(
                employee.email(),
                employee.email(),
                employee.firstName(),
                employee.lastName(),
                employee.employeeNumber(),
                employee.department(),
                demonstrates);
    }
}
