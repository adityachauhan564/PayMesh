package com.paymesh.gateway.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class JwtPropertiesTest {

    private static final String SECRET_32_BYTES = "0123456789abcdef0123456789abcdef";

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"too-short", "0123456789abcdef0123456789abcde"})
    void refusesToStartWithoutAStrongEnoughSecret(String secret) {
        assertThatIllegalStateException().isThrownBy(() -> new JwtProperties(secret, "paymesh"))
                .withMessageContaining("JWT_SECRET");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void requiresAnIssuer(String issuer) {
        assertThatIllegalStateException().isThrownBy(() -> new JwtProperties(SECRET_32_BYTES, issuer));
    }

    @Test
    void acceptsA256BitSecret() {
        assertThat(new JwtProperties(SECRET_32_BYTES, "paymesh").signingKey().getAlgorithm()).isEqualTo("HmacSHA256");
    }
}
