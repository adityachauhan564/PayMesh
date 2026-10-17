package com.paymesh.gateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.sun.net.httpserver.HttpServer;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Mints RS256 tokens shaped like user_service's (sub, name, roles, amr, jti, iss), and plays user_service's JWKS
 * endpoint on a local port for the whole test JVM.
 */
public final class TestTokens {

    public static final String ISSUER = "paymesh";
    public static final String JWKS_URI;
    private static final RSAKey KEY;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] jwks = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                exchange.getResponseBody().write(jwks);
                exchange.close();
            });
            server.setExecutor(null);
            server.start();
            JWKS_URI = "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json";
        } catch (JOSEException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private TestTokens() {
    }

    public static String valid() {
        return withRole("USER");
    }

    public static String withRole(String role) {
        return sign(KEY, claims(ISSUER, Instant.now(), Duration.ofMinutes(15), role, UUID.randomUUID().toString()));
    }

    public static String withJti(String jti) {
        return sign(KEY, claims(ISSUER, Instant.now(), Duration.ofMinutes(15), "USER", jti));
    }

    public static String expired() {
        return sign(KEY, claims(ISSUER, Instant.now().minus(Duration.ofHours(2)), Duration.ofMinutes(15), "USER", UUID.randomUUID().toString()));
    }

    public static String withIssuer(String issuer) {
        return sign(KEY, claims(issuer, Instant.now(), Duration.ofMinutes(15), "USER", UUID.randomUUID().toString()));
    }

    public static String withoutIssuer() {
        return withIssuer(null);
    }

    public static String signedWithOtherKey() {
        try {
            RSAKey impostor = new RSAKeyGenerator(2048).keyID("test-key").generate(); // same kid, different key
            return sign(impostor, claims(ISSUER, Instant.now(), Duration.ofMinutes(15), "USER", UUID.randomUUID().toString()));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The old shared-secret format: must no longer be accepted. */
    public static String hs256() {
        var secret = new SecretKeySpec("the-old-shared-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new NimbusJwtEncoder(new ImmutableSecret<>(secret))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                        claims(ISSUER, Instant.now(), Duration.ofMinutes(15), "USER", UUID.randomUUID().toString())))
                .getTokenValue();
    }

    private static JwtClaimsSet claims(String issuer, Instant issuedAt, Duration lifetime, String role, String jti) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .subject("42")
                .claim("name", "Test User")
                .claim("roles", List.of(role))
                .claim("amr", List.of("pwd"))
                .id(jti)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(lifetime));
        if (issuer != null) {
            builder.issuer(issuer);
        }
        return builder.build();
    }

    private static String sign(RSAKey key, JwtClaimsSet claims) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build(), claims))
                .getTokenValue();
    }
}
