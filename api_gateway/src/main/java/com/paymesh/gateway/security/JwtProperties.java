package com.paymesh.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where user_service publishes its public signing keys (env JWT_JWKS_URI) and the issuer to accept (env JWT_ISSUER).
 * The gateway holds no secret: it can verify tokens but never mint one.
 */
@ConfigurationProperties("jwt")
public record JwtProperties(@DefaultValue("http://localhost:8081/.well-known/jwks.json") String jwksUri,
                            @DefaultValue("paymesh") String issuer) {

    public JwtProperties {
        if (jwksUri == null || jwksUri.isBlank()) {
            throw new IllegalStateException("jwt.jwks-uri (env JWT_JWKS_URI) must be set");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("jwt.issuer (env JWT_ISSUER) must be set");
        }
    }
}
