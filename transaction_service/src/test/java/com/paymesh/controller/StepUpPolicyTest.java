package com.paymesh.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.ErrorResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Large payments by 2FA users need a code entered in the last 5 minutes; nobody else is asked. */
class StepUpPolicyTest {

    private final StepUpPolicy policy = new StepUpPolicy(new BigDecimal("5000"));

    private static Jwt token(boolean mfa, List<String> amr, Instant issuedAt) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("7")
                .claim("mfa", mfa).claim("amr", amr).issuedAt(issuedAt).expiresAt(issuedAt.plus(Duration.ofMinutes(15))).build();
    }

    private void assertStepUpRequired(Jwt jwt, String amount) {
        assertThatExceptionOfType(ErrorResponseException.class).isThrownBy(() -> policy.check(jwt, new BigDecimal(amount)))
                .satisfies(e -> {
                    org.assertj.core.api.Assertions.assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    org.assertj.core.api.Assertions.assertThat(e.getBody().getType().toString())
                            .isEqualTo("https://paymesh.dev/problems/step-up-required");
                });
    }

    @Test
    void smallPaymentNeverNeedsStepUp() {
        assertThatCode(() -> policy.check(token(true, List.of("pwd"), Instant.now().minusSeconds(3600)), new BigDecimal("5000")))
                .doesNotThrowAnyException();
    }

    @Test
    void userWithout2faIsNotAsked() {
        assertThatCode(() -> policy.check(token(false, List.of("pwd"), Instant.now()), new BigDecimal("50000")))
                .doesNotThrowAnyException();
    }

    @Test
    void largePaymentBy2faUserNeedsFreshOtp() {
        assertStepUpRequired(token(true, List.of("pwd"), Instant.now()), "5000.01");
    }

    @Test
    void freshOtpTokenPasses() {
        assertThatCode(() -> policy.check(token(true, List.of("pwd", "otp"), Instant.now().minusSeconds(60)), new BigDecimal("90000")))
                .doesNotThrowAnyException();
    }

    @Test
    void staleOtpTokenIsAskedAgain() {
        assertStepUpRequired(token(true, List.of("pwd", "otp"), Instant.now().minus(Duration.ofMinutes(6))), "9000");
    }

    @Test
    void refreshedTokenIsAskedAgain() {
        assertStepUpRequired(token(true, List.of("pwd"), Instant.now().minusSeconds(5)), "9000");
    }
}
