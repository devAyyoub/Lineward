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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lineward.connector.ConnectorException;
import com.lineward.connector.ConnectorType;
import com.lineward.connector.ReadCursor;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import org.junit.jupiter.api.Test;

/**
 * What the Okta connector decides before it has spoken to anybody.
 *
 * <p>Every test here runs against an organization that does not exist, at an
 * address nothing is listening on, and none of them is slow. That is the
 * point: a cursor this connector did not issue has to be refused before a
 * request is made, so refusing it cannot depend on a server being reachable.
 * If one of these ever starts taking seconds, the refusal has moved behind the
 * network and the guarantee has quietly gone.
 */
class OktaConnectorTests {

    private final OktaConnector connector = new OktaConnector(credentials());

    @Test
    void speaks_for_okta() {
        assertThat(connector.type()).isEqualTo(ConnectorType.OKTA);
    }

    @Test
    void refuses_a_cursor_it_did_not_issue() {
        // Reading an unknown cursor as "start from the beginning" is how a
        // resumed read reports a part of the catalogue as all of it, and the
        // next phase then treats everything missing as taken away.
        assertThatThrownBy(() -> connector.readEntitlements(new ReadCursor("00g1g3xk4nQQ1P7Rd697"), 10))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("not a cursor the Okta connector issued");
    }

    @Test
    void refuses_a_population_it_does_not_have() {
        assertThatThrownBy(() -> connector.readEntitlements(new ReadCursor("PEOPLE:00g1"), 10))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("no catalogue population called 'PEOPLE'");
    }

    @Test
    void refuses_before_it_asks_anybody() {
        // The address is unroutable, so anything that reached the network would
        // hang or fail with a connection error rather than this message.
        long started = System.currentTimeMillis();

        assertThatThrownBy(() -> connector.readEntitlements(new ReadCursor("nonsense"), 10))
                .isInstanceOf(ConnectorException.class);

        assertThat(System.currentTimeMillis() - started).isLessThan(1_000);
    }

    private static OktaCredentials credentials() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return new OktaCredentials(
                    "https://nothing.listens.here.invalid",
                    "0oaTESTCLIENTID00000",
                    "kid-of-the-registered-public-key",
                    (RSAPrivateKey) generator.generateKeyPair().getPrivate());
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
