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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * A stand-in for the HR system that owns the truth about who works here.
 *
 * <p>It exists because the authoritative source has to be somewhere Lineward
 * cannot reach except over the network. The point of M1 is reading an external
 * system and working out what changed, and that is untestable if the data
 * already sits in Lineward's own tables.
 *
 * <p>What it offers beyond serving a population is the ability to <em>provoke</em>
 * lifecycle events on demand: hire somebody, move them, terminate them with a
 * date in the past. Those are the three events of a joiner, mover and leaver
 * cycle, and the mover is the one implementations get wrong, because granting
 * the new access is remembered and removing the old one is not.
 */
@SpringBootApplication
@EnableConfigurationProperties(HrSimulatorProperties.class)
public class HrSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(HrSimulatorApplication.class, args);
    }
}
