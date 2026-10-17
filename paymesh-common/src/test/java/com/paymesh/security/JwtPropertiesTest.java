package com.paymesh.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class JwtPropertiesTest {

    @ParameterizedTest
    @NullAndEmptySource
    void requiresAJwksUri(String jwksUri) {
        assertThatIllegalStateException().isThrownBy(() -> new JwtProperties(jwksUri, "paymesh"))
                .withMessageContaining("JWT_JWKS_URI");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void requiresAnIssuer(String issuer) {
        assertThatIllegalStateException().isThrownBy(() -> new JwtProperties("http://user-service:8081/.well-known/jwks.json", issuer));
    }
}
