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

import java.security.KeyFactory;
import java.security.GeneralSecurityException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Where an Okta organization is and which service application reads it.
 *
 * <p><b>There is no secret here, and that is the design rather than an
 * omission.</b> Okta will issue tokens carrying rights over its own
 * administration API only to a service application that authenticates by
 * signing an assertion with a private key, measured against
 * {@code integrator-3500333} on 2026-11-04: the same application configured
 * with a shared secret is refused with {@code invalid_client}. So the
 * credential that reaches an identity provider is a key that never travels,
 * instead of a string that travels on every request and sits in whatever logs
 * it passed through. That is the opposite of the Keycloak connector, which has
 * a client secret because Keycloak accepts one, and it is worth noticing which
 * of the two is the exception.
 *
 * <p>The key arrives parsed rather than as text, so that an unreadable one is
 * refused when the connector is built and not on the first read, hours later,
 * in the middle of an aggregation run.
 *
 * <p>{@link #toString()} is overridden, and here it is a control and not
 * tidiness. A record prints every component, and a {@code PrivateKey} is free
 * to print its own material: some providers do. One log line built by
 * concatenation would publish the key that reaches the organization.
 *
 * @param orgUrl    where the organization serves, with no trailing slash, for
 *                  example {@code https://integrator-3500333.okta.com}. Not the
 *                  {@code -admin} host, which serves the console and not the API
 * @param clientId  the service application that reads, holding the four read
 *                  scopes and the read only administrator role and nothing else
 * @param keyId     the identifier Okta gave the registered public key. Required
 *                  rather than optional: an assertion that does not name its key
 *                  is refused with {@code The client_assertion JWT kid is
 *                  invalid}, and so is one naming the RFC 7638 thumbprint of the
 *                  key, which is what Okta does not use. Both measured on
 *                  2026-11-04
 * @param signingKey the private half of that registered key. Never written to a
 *                  log, an exception or a {@code ConnectionCheck}
 */
public record OktaCredentials(String orgUrl, String clientId, String keyId, RSAPrivateKey signingKey) {

    public OktaCredentials {
        require(orgUrl, "orgUrl");
        require(clientId, "clientId");
        require(keyId, "keyId");
        if (signingKey == null) {
            throw new IllegalArgumentException("an Okta connector cannot be built without a signing key");
        }
        orgUrl = orgUrl.endsWith("/") ? orgUrl.substring(0, orgUrl.length() - 1) : orgUrl;
    }

    /**
     * Builds a credential from the PEM text Okta shows once, when the key is
     * generated.
     *
     * <p>PKCS#8, which is what {@code -----BEGIN PRIVATE KEY-----} announces and
     * what Okta's PEM export produces. The older {@code BEGIN RSA PRIVATE KEY}
     * form is PKCS#1 and is refused here rather than converted: accepting both
     * would mean guessing at a parse failure, and the operator who pasted the
     * wrong half of a key is better served by being told so.
     */
    public static OktaCredentials fromPem(String orgUrl, String clientId, String keyId, String privateKeyPem) {
        return new OktaCredentials(orgUrl, clientId, keyId, parse(privateKeyPem));
    }

    private static RSAPrivateKey parse(String pem) {
        if (pem == null || !pem.contains("BEGIN PRIVATE KEY")) {
            throw new IllegalArgumentException(
                    "the Okta signing key has to be a PKCS#8 PEM, the one that starts with BEGIN PRIVATE KEY."
                            + " Okta shows it once, when the key pair is generated, under the PEM tab");
        }
        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException unreadable) {
            // Deliberately without the cause's message in the text: a parse
            // failure of key material can quote the material.
            throw new IllegalArgumentException("the Okta signing key could not be read as a PKCS#8 RSA key");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("an Okta connector cannot be built without " + name);
        }
    }

    /** Where client credentials are exchanged for an access token. */
    String tokenUrl() {
        return orgUrl + "/oauth2/v1/token";
    }

    /** An administration API URL, for example {@code /api/v1/users?limit=50}. */
    String apiUrl(String path) {
        return orgUrl + path;
    }

    /** Everything but the key, so that printing this cannot leak it. */
    @Override
    public String toString() {
        return "OktaCredentials[orgUrl=" + orgUrl + ", clientId=" + clientId + ", keyId=" + keyId
                + ", signingKey=<redacted>]";
    }
}
