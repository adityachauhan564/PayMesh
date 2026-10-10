package com.paymesh.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Shared token settings (env JWT_SECRET / JWT_ISSUER). Same rules as the api_gateway's own copy, which can't
 * depend on this servlet-based library. Validated at startup: no secret means no service, never a known default.
 */
@ConfigurationProperties("jwt")
public record JwtProperties(String secret, @DefaultValue("paymesh") String issuer) {

    /** HS256 requires a key of at least 256 bits. */
    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("jwt.secret (env JWT_SECRET) must be set and at least "
                    + MIN_SECRET_BYTES + " bytes long; use the 'local' profile for development");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("jwt.issuer (env JWT_ISSUER) must be set");
        }
    }

    public SecretKey signingKey() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
