package com.paymesh.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class JwtPropertiesTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"too-short", "0123456789abcdef0123456789abcde"})
    void servicesRefuseToStartWithoutAStrongEnoughSecret(String secret) {
        assertThatIllegalStateException().isThrownBy(() -> new JwtProperties(secret, "paymesh"))
                .withMessageContaining("JWT_SECRET");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void requiresAnIssuer(String issuer) {
        assertThatIllegalStateException().isThrownBy(() -> new JwtProperties("0123456789abcdef0123456789abcdef", issuer));
    }
}
