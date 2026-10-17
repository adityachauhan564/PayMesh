package com.paymesh.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.paymesh.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Mints the short-lived RS256 access tokens every other service verifies through JWKS. */
@Component
public class TokenIssuer {

    private final JwtKeys keys;
    private final AuthProperties auth;
    private final String issuer;
    private final JwtEncoder encoder;

    public TokenIssuer(JwtKeys keys, AuthProperties auth, @Value("${jwt.issuer:paymesh}") String issuer) {
        this.keys = keys;
        this.auth = auth;
        this.issuer = issuer;
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingKey())));
    }

    /** amr (RFC 8176): how the user authenticated, e.g. ["pwd"] or ["pwd", "otp"]. jti makes the token revocable. */
    public String issueAccess(User user, List<String> amr) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(user.getUserId().toString())
                .claim("name", user.getUserFullName())
                .claim("roles", List.of(user.getRole()))
                .claim("amr", amr)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(auth.accessTtl()))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.signingKey().getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** user_service checks its own tokens against the key in memory instead of fetching its own JWKS over HTTP. */
    public JwtDecoder localDecoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.signingKey().toRSAPublicKey()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
            return decoder;
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
