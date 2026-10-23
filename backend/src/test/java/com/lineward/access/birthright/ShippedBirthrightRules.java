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
package com.lineward.access.birthright;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Loads {@code birthright-rules.yaml} the way the application loads it.
 *
 * <p>Shared by the test that checks the file binds and the one that checks every
 * grant in it exists, because both have to be reading the rules this build
 * ships rather than a copy written for a test. A copy is how a file and its test
 * drift apart.
 */
final class ShippedBirthrightRules {

    private ShippedBirthrightRules() {}

    static BirthrightRules load() {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            new YamlPropertySourceLoader()
                    .load("birthright-rules", new ClassPathResource("birthright-rules.yaml"))
                    .forEach(environment.getPropertySources()::addLast);
        } catch (IOException e) {
            throw new UncheckedIOException("birthright-rules.yaml is not on the test classpath", e);
        }

        BirthrightProperties properties = Binder.get(environment)
                .bind("lineward.access.birthright", BirthrightProperties.class)
                .orElseThrow(() -> new AssertionError(
                        "birthright-rules.yaml did not bind: no rules under lineward.access.birthright"));

        return new BirthrightRules(properties.rules());
    }
}
