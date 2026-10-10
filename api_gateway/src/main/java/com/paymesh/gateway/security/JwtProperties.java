package com.paymesh.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Must match what user_service signs with. Validated at startup so a missing or weak secret
 * fails fast instead of silently accepting forgeable tokens.
 */
@ConfigurationProperties("jwt")
public record JwtProperties(String secret, String issuer) {

    /** HS256 requires a key of at least 256 bits. */
    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("jwt.secret (env JWT_SECRET) must be set and at least "
                    + MIN_SECRET_BYTES + " bytes long");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("jwt.issuer (env JWT_ISSUER) must be set");
        }
    }

    public SecretKey signingKey() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
