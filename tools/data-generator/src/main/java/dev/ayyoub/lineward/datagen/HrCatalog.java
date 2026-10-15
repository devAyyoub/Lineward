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
package dev.ayyoub.lineward.datagen;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * The vocabulary the generated population is drawn from.
 *
 * <p>Everything here is invented. Section 3.2 allows synthetic data only, and
 * section 3.1 forbids anything traceable to a real employer, so the departments
 * are the generic ones any mid-sized company has and the names are common given
 * names and surnames combined at random rather than taken from anywhere.
 *
 * <p>The email domain ends in {@code .example}, which RFC 2606 reserves for
 * exactly this. A plausible-looking real domain in a dataset that gets
 * published, exported or emailed eventually reaches somebody's inbox.
 *
 * <p>Two departments are here on purpose and not for variety: Procurement
 * creates suppliers and Finance approves payments to them, which is the textbook
 * segregation of duties conflict M6 will need a population to detect.
 */
final class HrCatalog {

    private HrCatalog() {}

    static final String EMAIL_DOMAIN = "lineward.example";

    /**
     * A department, its cost center prefix, how much of the headcount it takes,
     * and the four job titles that make up its career ladder.
     *
     * <p>The ladder is ordered from junior to head, and the generator maps it
     * onto the hierarchy: one head, some managers, and individual contributors
     * below them. A real org chart is messier, but the shape that matters for
     * identity governance is that somebody answers for everybody.
     */
    record Department(String name, String costCenterPrefix, int weight, List<String> ladder) {

        String titleFor(Level level) {
            return ladder.get(level.ordinal());
        }
    }

    /** Where somebody sits in their department's ladder. */
    enum Level {
        JUNIOR, SENIOR, MANAGER, HEAD
    }

    static final List<Department> DEPARTMENTS = List.of(
            new Department("Engineering", "ENG", 26,
                    List.of("Software Engineer", "Senior Software Engineer", "Engineering Manager", "Head of Engineering")),
            new Department("Operations", "OPS", 16,
                    List.of("Operations Analyst", "Operations Specialist", "Operations Manager", "Head of Operations")),
            new Department("Treasury", "TRE", 10,
                    List.of("Treasury Analyst", "Senior Treasury Analyst", "Treasury Manager", "Head of Treasury")),
            new Department("Risk", "RSK", 10,
                    List.of("Risk Analyst", "Credit Risk Analyst", "Risk Manager", "Head of Risk")),
            new Department("Finance", "FIN", 9,
                    List.of("Accountant", "Financial Controller", "Finance Manager", "Head of Finance")),
            new Department("Procurement", "PRC", 7,
                    List.of("Buyer", "Procurement Specialist", "Procurement Manager", "Head of Procurement")),
            new Department("Information Security", "SEC", 7,
                    List.of("Security Analyst", "Security Engineer", "Security Manager", "Head of Information Security")),
            new Department("Human Resources", "HRS", 6,
                    List.of("HR Specialist", "HR Business Partner", "HR Manager", "Head of People")),
            new Department("Legal and Compliance", "LGL", 5,
                    List.of("Compliance Officer", "Legal Counsel", "Compliance Manager", "Head of Compliance")),
            new Department("Internal Audit", "AUD", 4,
                    List.of("Auditor", "Senior Auditor", "Audit Manager", "Head of Internal Audit")));

    static final List<String> LOCATIONS =
            List.of("Madrid", "Barcelona", "Valencia", "Sevilla", "Bilbao", "Zaragoza", "Remote");

    /**
     * Contractors and interns matter to identity governance out of proportion to
     * their number: they are the population whose access most often outlives
     * them, because their end date is known in advance and nobody acts on it.
     */
    static final List<String> EMPLOYMENT_TYPES = List.of(
            "EMPLOYEE", "EMPLOYEE", "EMPLOYEE", "EMPLOYEE", "EMPLOYEE",
            "EMPLOYEE", "EMPLOYEE", "EMPLOYEE", "CONTRACTOR", "INTERN");

    static final List<String> GIVEN_NAMES = List.of(
            "Lucia", "Hugo", "Martina", "Mateo", "Sofia", "Leo", "Jimena", "Daniel", "Valeria", "Pablo",
            "Carmen", "Alvaro", "Alba", "Adrian", "Noa", "Diego", "Emma", "Javier", "Vega", "Marcos",
            "Elena", "Sergio", "Irene", "Ruben", "Nerea", "Ivan", "Claudia", "Oscar", "Marta", "Raul",
            "Ainara", "Gonzalo", "Rocio", "Nicolas", "Paula", "Andres", "Lola", "Felipe", "Nuria", "Tomas",
            "Amina", "Yusuf", "Salma", "Karim", "Laia", "Biel", "Uxia", "Brais", "Ane", "Kepa");

    static final List<String> SURNAMES = List.of(
            "Garcia", "Rodriguez", "Gonzalez", "Fernandez", "Lopez", "Martinez", "Sanchez", "Perez",
            "Gomez", "Martin", "Jimenez", "Ruiz", "Hernandez", "Diaz", "Moreno", "Alvarez", "Munoz",
            "Romero", "Alonso", "Gutierrez", "Navarro", "Torres", "Dominguez", "Vazquez", "Ramos",
            "Gil", "Ramirez", "Serrano", "Blanco", "Molina", "Morales", "Suarez", "Ortega", "Delgado",
            "Castro", "Ortiz", "Rubio", "Marin", "Sanz", "Iglesias", "Nunez", "Medina", "Garrido",
            "Cortes", "Castillo", "Santos", "Lozano", "Guerrero", "Cano", "Prieto");

    /**
     * Builds the local part of an email the way a corporate convention does.
     *
     * <p>Accents are stripped rather than kept, because every directory and
     * every target system does something different with them and the resulting
     * mismatch is a correlation failure that has nothing to teach. The one
     * correlation failure in this dataset is seeded on purpose
     * ({@link AnomalyKind#DUPLICATE_EMAIL}), so the accidental kind is removed.
     */
    static String emailLocalPart(String givenName, String surname) {
        return stripAccents(givenName).toLowerCase(Locale.ROOT)
                + "."
                + stripAccents(surname).toLowerCase(Locale.ROOT);
    }

    private static String stripAccents(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^A-Za-z]", "");
    }
}
