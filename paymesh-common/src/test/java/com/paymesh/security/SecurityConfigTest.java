package com.paymesh.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class SecurityConfigTest {

    private static final String SECRET = "library-test-secret-at-least-32-bytes";
    private static final String ISSUER = "paymesh";

    private final JwtDecoder decoder = new SecurityConfig().jwtDecoder(new JwtProperties(SECRET, ISSUER));

    @Test
    void acceptsTokenSignedWithTheSharedSecretFromTheConfiguredIssuer() {
        assertThat(decoder.decode(token(SECRET, ISSUER, Instant.now())).getSubject()).isEqualTo("42");
    }

    @Test
    void rejectsTokenWithoutIssuer() {
        assertRejected(token(SECRET, null, Instant.now()));
    }

    @Test
    void rejectsTokenFromAnotherIssuer() {
        assertRejected(token(SECRET, "someone-else", Instant.now()));
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        assertRejected(token("another-secret-that-is-also-32-bytes-long", ISSUER, Instant.now()));
    }

    @Test
    void rejectsExpiredToken() {
        assertRejected(token(SECRET, ISSUER, Instant.now().minus(Duration.ofHours(3))));
    }

    private void assertRejected(String token) {
        assertThatExceptionOfType(JwtException.class).isThrownBy(() -> decoder.decode(token));
    }

    private static String token(String secret, String issuer, Instant issuedAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject("42").issuedAt(issuedAt).expiresAt(issuedAt.plus(Duration.ofHours(1)));
        if (issuer != null) {
            claims.issuer(issuer);
        }
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }
}
