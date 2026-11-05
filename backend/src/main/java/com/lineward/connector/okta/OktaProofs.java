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

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two signed objects every call to Okta carries, and the only place that
 * knows how they are built.
 *
 * <p><b>Authenticating against Okta is three mechanisms stacked, not one</b>,
 * and all three were found by asking the real organization on 2026-11-04
 * rather than by reading about the endpoints:
 *
 * <ol>
 * <li><b>The client assertion</b> ({@code private_key_jwt}): the application
 *     proves who it is by signing a short lived JWT with its registered key.
 *     The header has to name the key with the {@code kid} Okta assigned, and
 *     Okta's {@code kid} is not the RFC 7638 thumbprint of the key: computing
 *     the thumbprint and sending it was refused.
 * <li><b>The DPoP proof</b>: a second JWT, signed with a key that belongs to
 *     this process and never leaves it, which binds the access token to that
 *     key. A token stolen off the wire or out of a log is useless without it,
 *     which is exactly the property worth having in a component that holds
 *     administrative reach over an identity provider.
 * <li><b>Freshness</b>: the assertion's {@code jti} is single use, measured by
 *     replaying one and being told {@code The client_assertion token has
 *     already been used}. Okta also refuses the first token request on purpose
 *     to hand back a {@code nonce} that the next proof has to carry.
 * </ol>
 *
 * <p>Point three has a consequence that outlives this class and is the reason
 * the nonce retry lives in {@link OktaTokens} and not in a generic retry
 * wrapper: <b>a retry has to mint new credentials, not resend the ones that
 * failed.</b> Resending is the natural thing to write and is precisely what
 * turns a recoverable refusal into an intermittent failure.
 *
 * <p>No HTTP here on purpose, which is the same split
 * {@code KeycloakDocuments} makes against {@code KeycloakAdminApi}: building a
 * proof and sending one are different jobs, and only the second needs a server.
 * Everything below is therefore checked by {@code OktaProofsTests} without an
 * organization, by verifying the signatures and reading the claims back.
 *
 * <p>The DPoP key is generated per instance and kept in memory. It is
 * deliberately not persisted: losing it on a restart costs one token exchange,
 * while writing it down would recreate the stealable credential that DPoP
 * exists to remove. RSA with SHA-256 because that is what this organization
 * was seen to accept; ECDSA is lighter and is not yet measured.
 */
final class OktaProofs {

    /** Short enough that a stolen assertion is useless, long enough to survive a slow network. */
    private static final Duration ASSERTION_LIFETIME = Duration.ofMinutes(5);

    private static final String RSA_SIGNATURE = "SHA256withRSA";

    private final OktaCredentials credentials;
    private final Clock clock;
    private final KeyPair dpopKey;
    private final Map<String, Object> dpopPublicKey;
    private final JsonMapper json = JsonMapper.builder().build();

    OktaProofs(OktaCredentials credentials, Clock clock) {
        this.credentials = credentials;
        this.clock = clock;
        this.dpopKey = generateDpopKey();
        this.dpopPublicKey = describe((RSAPublicKey) dpopKey.getPublic());
    }

    /**
     * A fresh assertion that says "I am this application" to the token endpoint.
     *
     * <p>Fresh every time, never cached, because of the single use {@code jti}.
     * Caching one would work until the first retry and then fail in a way that
     * looks like Okta being flaky.
     */
    String clientAssertion() {
        long now = clock.instant().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", credentials.clientId());
        claims.put("sub", credentials.clientId());
        claims.put("aud", credentials.tokenUrl());
        claims.put("iat", now);
        claims.put("exp", now + ASSERTION_LIFETIME.toSeconds());
        claims.put("jti", UUID.randomUUID().toString());
        return signed(Map.of("alg", "RS256", "typ", "JWT", "kid", credentials.keyId()), claims, credentials.signingKey());
    }

    /**
     * A proof that this process holds the key the token is bound to.
     *
     * <p>One per request, and tied to the request: the method and the URL are
     * inside it, so a proof captured from a read cannot be replayed against a
     * write. The URL is taken without its query string, which is what the DPoP
     * specification asks for and what lets one proof cover a paged read's
     * cursor without being reissued per page.
     *
     * @param method      the HTTP method this proof is for
     * @param url         the full URL, query string included and stripped here
     * @param accessToken the token being presented, or {@code null} when asking
     *                    for one. Present, its hash goes in {@code ath}, which
     *                    is what stops a proof for one token being reused with
     *                    another
     * @param nonce       the value Okta last handed back, or {@code null} the
     *                    first time, when Okta has not asked for one yet
     */
    String dpopProof(String method, String url, String accessToken, String nonce) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("htm", method);
        claims.put("htu", withoutQuery(url));
        claims.put("iat", clock.instant().getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        if (accessToken != null) {
            claims.put("ath", base64url(sha256(accessToken)));
        }
        if (nonce != null) {
            claims.put("nonce", nonce);
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("typ", "dpop+jwt");
        header.put("alg", "RS256");
        header.put("jwk", dpopPublicKey);
        return signed(header, claims, dpopKey.getPrivate());
    }

    private String signed(Map<String, Object> header, Map<String, Object> claims, PrivateKey key) {
        String signingInput = base64url(json.writeValueAsBytes(header)) + "." + base64url(json.writeValueAsBytes(claims));
        try {
            Signature signature = Signature.getInstance(RSA_SIGNATURE);
            signature.initSign(key);
            signature.update(signingInput.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return signingInput + "." + base64url(signature.sign());
        } catch (GeneralSecurityException cannotSign) {
            throw new IllegalStateException("the Okta connector could not sign with its configured key", cannotSign);
        }
    }

    /**
     * The public half of the DPoP key, as the proof header carries it.
     *
     * <p>{@code BigInteger.toByteArray} is two's complement and prepends a zero
     * byte whenever the top bit is set, which is about half of all RSA moduli.
     * A JSON Web Key holds unsigned big endian, so that byte has to go: leaving
     * it in produces a key that is right half the time, which is the worst kind
     * of right.
     */
    private static Map<String, Object> describe(RSAPublicKey key) {
        return Map.of(
                "kty", "RSA",
                "n", base64url(unsigned(key.getModulus())),
                "e", base64url(unsigned(key.getPublicExponent())));
    }

    /**
     * Package private so a test can hand it a value whose top bit is set.
     *
     * <p>Through the public surface this is only exercised by whatever key the
     * JVM happened to generate, so a test reaching it that way catches the bug
     * about half the time, which is the kind of guard that reports success for
     * years and then does not.
     */
    static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private static KeyPair generateDpopKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("this JVM cannot generate RSA keys, so DPoP is not possible", impossible);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("this JVM has no SHA-256", impossible);
        }
    }

    private static String withoutQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static String base64url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
