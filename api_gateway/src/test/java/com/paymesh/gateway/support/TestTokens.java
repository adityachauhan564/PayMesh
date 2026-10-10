package com.paymesh.gateway.support;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/** Mints HS256 tokens shaped like the ones user_service issues (sub = userId, name, iss). */
public final class TestTokens {

    public static final String SECRET = "gateway-test-secret-at-least-32-bytes-long";
    public static final String ISSUER = "paymesh";

    private TestTokens() {
    }

    public static String valid() {
        return sign(SECRET, claims(ISSUER, Instant.now(), Duration.ofHours(1)));
    }

    public static String expired() {
        return sign(SECRET, claims(ISSUER, Instant.now().minus(Duration.ofHours(2)), Duration.ofHours(1)));
    }

    public static String withIssuer(String issuer) {
        return sign(SECRET, claims(issuer, Instant.now(), Duration.ofHours(1)));
    }

    public static String withoutIssuer() {
        return sign(SECRET, claims(null, Instant.now(), Duration.ofHours(1)));
    }

    public static String signedWithOtherSecret() {
        return sign("some-other-secret-that-is-also-32-bytes-long", claims(ISSUER, Instant.now(), Duration.ofHours(1)));
    }

    private static JwtClaimsSet claims(String issuer, Instant issuedAt, Duration lifetime) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .subject("42")
                .claim("name", "Test User")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(lifetime));
        if (issuer != null) {
            builder.issuer(issuer);
        }
        return builder.build();
    }

    private static String sign(String secret, JwtClaimsSet claims) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
