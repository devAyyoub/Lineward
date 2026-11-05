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

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What Lineward signs before Okta will talk to it.
 *
 * <p>No organization is needed here and that is the point of the split: these
 * are the objects, and sending them is {@code OktaTokens}. Every expectation
 * below comes from a refusal measured against {@code integrator-3500333} on
 * 2026-11-04, and the comment on each test says which one, so that a future
 * reader can tell a rule Okta imposes from a preference somebody had.
 *
 * <p>The signatures are verified rather than merely present. A test asserting
 * that a JWT has three parts would pass against a signature of the wrong bytes
 * with the wrong key, which is the failure this is here to catch.
 */
class OktaProofsTests {

    private static final String ORG = "https://integrator-3500333.okta.com";
    private static final String CLIENT_ID = "0oaTESTCLIENTID00000";
    private static final String KEY_ID = "kid-of-the-registered-public-key";

    /** Generated once: two thousand and forty eight bits is slow enough to notice per test. */
    private static KeyPair registeredKey;

    private static final Instant NOON = Instant.parse("2026-11-04T12:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OktaProofs proofs = new OktaProofs(credentials(), Clock.fixed(NOON, ZoneOffset.UTC));

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        registeredKey = generator.generateKeyPair();
    }

    @Test
    void signs_the_client_assertion_with_the_key_okta_has_registered() {
        String assertion = proofs.clientAssertion();

        assertThat(verifies(assertion, registeredKey.getPublic()))
                .as("Okta checks this signature against the public key of the application")
                .isTrue();
    }

    @Test
    void names_the_key_that_signed_the_assertion() {
        // Without this, Okta answers "The client_assertion JWT kid is invalid",
        // and so it does when the kid is the RFC 7638 thumbprint of the key:
        // the identifier is Okta's own, not a property of the key material.
        JsonNode header = header(proofs.clientAssertion());

        assertThat(header.get("kid").stringValue()).isEqualTo(KEY_ID);
        assertThat(header.get("alg").stringValue()).isEqualTo("RS256");
    }

    @Test
    void says_who_it_is_and_who_it_is_for() {
        JsonNode claims = claims(proofs.clientAssertion());

        assertThat(claims.get("iss").stringValue()).isEqualTo(CLIENT_ID);
        assertThat(claims.get("sub").stringValue()).isEqualTo(CLIENT_ID);
        assertThat(claims.get("aud").stringValue())
                .as("the audience is the token endpoint, so an assertion cannot be replayed elsewhere")
                .isEqualTo(ORG + "/oauth2/v1/token");
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(300);
    }

    @Test
    void never_signs_the_same_assertion_twice() {
        // Measured: replaying one is refused with "The client_assertion token
        // has already been used". A cached assertion works until the first
        // retry and then looks like Okta being unreliable.
        String first = claims(proofs.clientAssertion()).get("jti").stringValue();
        String second = claims(proofs.clientAssertion()).get("jti").stringValue();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void ties_a_dpop_proof_to_the_request_it_was_made_for() {
        JsonNode claims = claims(proofs.dpopProof("GET", ORG + "/api/v1/users?limit=50&after=00u1", null, null));

        assertThat(claims.get("htm").stringValue()).isEqualTo("GET");
        assertThat(claims.get("htu").stringValue())
                .as("the query string is excluded, so one proof covers every page of a read")
                .isEqualTo(ORG + "/api/v1/users");
    }

    @Test
    void signs_a_dpop_proof_with_a_key_of_its_own_and_publishes_only_its_public_half() throws Exception {
        String proof = proofs.dpopProof("GET", ORG + "/api/v1/groups", null, null);
        JsonNode header = header(proof);

        assertThat(header.get("typ").stringValue()).isEqualTo("dpop+jwt");
        assertThat(header.get("jwk").get("kty").stringValue()).isEqualTo("RSA");
        assertThat(header.get("jwk").has("d"))
                .as("a private exponent in the proof header would publish the key DPoP exists to keep")
                .isFalse();
        assertThat(verifies(proof, publicKeyOf(header.get("jwk"))))
                .as("the key announced in the header is the one that signed")
                .isTrue();
    }

    @Test
    void does_not_prepend_the_sign_byte_to_the_published_modulus() {
        // BigInteger.toByteArray is two's complement and adds a leading zero
        // whenever the top bit is set, which is about half of all RSA keys. A
        // JSON Web Key is unsigned, so leaving it in produces a key that is
        // correct half the time.
        JsonNode jwk = header(proofs.dpopProof("GET", ORG + "/api/v1/users", null, null)).get("jwk");

        byte[] modulus = Base64.getUrlDecoder().decode(jwk.get("n").stringValue());

        assertThat(modulus[0]).isNotEqualTo((byte) 0);
        assertThat(modulus).hasSize(256);
    }

    @Test
    void strips_the_sign_byte_from_a_modulus_whose_top_bit_is_set() {
        // The test above can only catch this when the generated key happens to
        // have its top bit set, which is a coin toss. This one always can.
        BigInteger topBitSet = new BigInteger(1, new byte[] {(byte) 0xFF, 0x01});

        assertThat(OktaProofs.unsigned(topBitSet)).containsExactly((byte) 0xFF, 0x01);
        assertThat(topBitSet.toByteArray())
                .as("this is what the JDK gives, and why the trim exists")
                .containsExactly((byte) 0x00, (byte) 0xFF, 0x01);
    }

    @Test
    void binds_the_proof_to_the_token_it_presents() throws Exception {
        String token = "an.access.token";

        JsonNode claims = claims(proofs.dpopProof("GET", ORG + "/api/v1/users", token, null));

        byte[] expected = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        assertThat(claims.get("ath").stringValue())
                .as("without ath a proof made for one token can be presented with another")
                .isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(expected));
    }

    @Test
    void leaves_out_the_token_hash_when_there_is_no_token_yet() {
        // The request that asks for a token cannot hash one. Sending an empty
        // ath instead of none is the kind of helpfulness that gets rejected.
        assertThat(claims(proofs.dpopProof("POST", ORG + "/oauth2/v1/token", null, null)).has("ath"))
                .isFalse();
    }

    @Test
    void carries_the_nonce_once_okta_has_asked_for_one() {
        // Okta refuses the first token request on purpose to hand one back.
        JsonNode claims = claims(proofs.dpopProof("POST", ORG + "/oauth2/v1/token", null, "the-nonce"));

        assertThat(claims.get("nonce").stringValue()).isEqualTo("the-nonce");
    }

    @Test
    void reads_the_key_okta_shows_once() {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(registeredKey.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        OktaCredentials credentials = OktaCredentials.fromPem(ORG, CLIENT_ID, KEY_ID, pem);

        assertThat(credentials.signingKey().getModulus())
                .isEqualTo(((RSAPrivateKey) registeredKey.getPrivate()).getModulus());
    }

    @Test
    void refuses_anything_that_is_not_a_pkcs8_key() {
        assertThatThrownBy(() -> OktaCredentials.fromPem(ORG, CLIENT_ID, KEY_ID, "-----BEGIN RSA PRIVATE KEY-----"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BEGIN PRIVATE KEY");
    }

    @Test
    void never_prints_the_key() {
        // A record prints every component by default and a PrivateKey is free
        // to print its own material.
        assertThat(credentials().toString()).contains("<redacted>").doesNotContain("Private");
    }

    private static OktaCredentials credentials() {
        return new OktaCredentials(ORG, CLIENT_ID, KEY_ID, (RSAPrivateKey) registeredKey.getPrivate());
    }

    private static JsonNode header(String jwt) {
        return part(jwt, 0);
    }

    private static JsonNode claims(String jwt) {
        return part(jwt, 1);
    }

    private static JsonNode part(String jwt, int index) {
        return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[index]));
    }

    private static boolean verifies(String jwt, PublicKey key) {
        try {
            String[] parts = jwt.split("\\.");
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return signature.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (Exception cannotVerify) {
            throw new IllegalStateException(cannotVerify);
        }
    }

    private static PublicKey publicKeyOf(JsonNode jwk) throws Exception {
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("n").stringValue()));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("e").stringValue()));
        return java.security.KeyFactory.getInstance("RSA")
                .generatePublic(new java.security.spec.RSAPublicKeySpec(modulus, exponent));
    }
}
