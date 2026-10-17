package com.paymesh.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Step-up for large payments: a user who turned on two-factor login must have entered a code in the last 5 minutes
 * (token amr contains "otp", iat fresh) to pay more than the threshold. Users without two-factor aren't asked.
 * 403, not 401: the web app refreshes on 401, and a refreshed token (amr ["pwd"]) would never satisfy this.
 */
@Component
public class StepUpPolicy {

    static final URI TYPE = URI.create("https://paymesh.dev/problems/step-up-required");
    private static final Duration FRESHNESS = Duration.ofMinutes(5);

    private final BigDecimal threshold;

    public StepUpPolicy(@Value("${payments.step-up-threshold:5000}") BigDecimal threshold) {
        this.threshold = threshold;
    }

    public void check(Jwt jwt, BigDecimal amount) {
        if (amount.compareTo(threshold) <= 0 || !Boolean.TRUE.equals(jwt.getClaimAsBoolean("mfa"))) {
            return;
        }
        List<String> amr = jwt.getClaimAsStringList("amr");
        Instant issuedAt = jwt.getIssuedAt();
        boolean freshOtp = amr != null && amr.contains("otp") && issuedAt != null
                && issuedAt.isAfter(Instant.now().minus(FRESHNESS));
        if (!freshOtp) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                    "Payments over Rs. " + threshold.toPlainString() + " need the code from your authenticator app");
            problem.setType(TYPE);
            throw new ErrorResponseException(HttpStatus.FORBIDDEN, problem, null);
        }
    }
}
