package com.paymesh.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Services verify user_service's RS256 tokens against its JWKS; here a local HTTP server plays user_service. */
class SecurityConfigTest {

    private static final String ISSUER = "paymesh";
    private static final AtomicReference<JWKSet> SERVED = new AtomicReference<>();
    private static HttpServer jwksServer;
    private static RSAKey signingKey;
    private static JwtDecoder decoder;

    @BeforeAll
    static void startJwks() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("key-1").generate();
        SERVED.set(new JWKSet(signingKey.toPublicJWK()));
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext("/.well-known/jwks.json", exchange -> {
            byte[] body = SERVED.get().toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jwksServer.start();
        String uri = "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/.well-known/jwks.json";
        decoder = new SecurityConfig().jwtDecoder(new JwtProperties(uri, ISSUER));
    }

    @AfterAll
    static void stopJwks() {
        jwksServer.stop(0);
    }

    @Test
    void validRs256TokenIsAccepted() {
        assertThat(decoder.decode(token(signingKey, ISSUER, Instant.now())).getSubject()).isEqualTo("42");
    }

    @Test
    void rejectsTokenWithoutIssuer() {
        assertRejected(token(signingKey, null, Instant.now()));
    }

    @Test
    void rejectsTokenFromAnotherIssuer() {
        assertRejected(token(signingKey, "someone-else", Instant.now()));
    }

    @Test
    void rejectsExpiredToken() {
        assertRejected(token(signingKey, ISSUER, Instant.now().minus(Duration.ofHours(3))));
    }

    @Test
    void foreignKeyIsRejected() throws Exception {
        RSAKey impostor = new RSAKeyGenerator(2048).keyID("key-1").generate(); // same kid, different key
        assertRejected(token(impostor, ISSUER, Instant.now()));
    }

    @Test
    void hs256IsRejected() {
        JwtClaimsSet claims = claims(ISSUER, Instant.now());
        var secret = new SecretKeySpec("the-old-shared-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        String hs256 = new NimbusJwtEncoder(new ImmutableSecret<>(secret))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        assertRejected(hs256);
    }

    @Test
    void unknownKidRefetchesJwks() throws Exception {
        decoder.decode(token(signingKey, ISSUER, Instant.now())); // JWKS cached with key-1
        RSAKey rotated = new RSAKeyGenerator(2048).keyID("key-2").generate();
        SERVED.set(new JWKSet(List.of(signingKey.toPublicJWK(), rotated.toPublicJWK())));

        assertThat(decoder.decode(token(rotated, ISSUER, Instant.now())).getSubject()).isEqualTo("42");
    }

    @Test
    void rolesBecomeAuthorities() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("42").claim("roles", List.of("ADMIN")).build();

        assertThat(new SecurityConfig().jwtAuthenticationConverter().convert(jwt).getAuthorities())
                .extracting(GrantedAuthority::getAuthority).contains("ROLE_ADMIN");
    }

    private static void assertRejected(String token) {
        assertThatExceptionOfType(JwtException.class).isThrownBy(() -> decoder.decode(token));
    }

    private static JwtClaimsSet claims(String issuer, Instant issuedAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject("42").issuedAt(issuedAt).expiresAt(issuedAt.plus(Duration.ofHours(1)));
        if (issuer != null) {
            claims.issuer(issuer);
        }
        return claims.build();
    }

    private static String token(RSAKey key, String issuer, Instant issuedAt) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build(), claims(issuer, issuedAt)))
                .getTokenValue();
    }
}
