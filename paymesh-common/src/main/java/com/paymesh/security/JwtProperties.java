package com.paymesh.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where to fetch user_service's public signing keys (env JWT_JWKS_URI) and which issuer to accept (env JWT_ISSUER).
 * No secret anywhere: services only ever hold public keys. Same rules as the api_gateway's own copy, which can't
 * depend on this servlet-based library.
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
