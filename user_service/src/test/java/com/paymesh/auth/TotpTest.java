package com.paymesh.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** RFC 6238 appendix B vectors (SHA1), cut to 6 digits as authenticator apps show them. */
class TotpTest {

    private static final String RFC_SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesRfc6238Vectors() {
        assertThat(Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1234567890)))).isEqualTo("005924");
    }

    @Test
    void acceptsOneStepOfClockDriftEitherWay() {
        long now = 1000;
        for (long step = now - 1; step <= now + 1; step++) {
            assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step), now)).isEqualTo(step);
        }
    }

    @Test
    void rejectsCodesTwoStepsAway() {
        long now = 1000;
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, now - 2), now)).isEqualTo(-1);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, now + 2), now)).isEqualTo(-1);
    }

    @Test
    void rejectsMalformedCodes() {
        assertThat(Totp.matchingStep(RFC_SECRET, "12345", 1000)).isEqualTo(-1);
        assertThat(Totp.matchingStep(RFC_SECRET, "abcdef", 1000)).isEqualTo(-1);
        assertThat(Totp.matchingStep(RFC_SECRET, null, 1000)).isEqualTo(-1);
    }

    @Test
    void base32RoundTripsAndSecretsAre160Bits() {
        byte[] bytes = {0, 1, 2, (byte) 250, (byte) 255, 42, 7};
        assertThat(Totp.fromBase32(Totp.base32(bytes))).isEqualTo(bytes);
        assertThat(Totp.fromBase32(Totp.newSecret())).hasSize(20);
    }
}
