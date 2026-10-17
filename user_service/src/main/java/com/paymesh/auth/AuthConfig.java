package com.paymesh.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Configuration
public class AuthConfig {

    /** Primary: verifies against the key in memory rather than fetching this service's own JWKS over HTTP. */
    @Bean
    @Primary
    public JwtDecoder localJwtDecoder(TokenIssuer tokenIssuer) {
        return tokenIssuer.localDecoder();
    }
}
