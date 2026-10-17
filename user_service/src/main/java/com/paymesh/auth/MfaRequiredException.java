package com.paymesh.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.net.URI;

/** 401 with a ProblemDetail type the web app recognises: the password was right, now ask for the 6-digit code. */
public class MfaRequiredException extends ErrorResponseException {

    public static final URI TYPE = URI.create("https://paymesh.dev/problems/mfa-required");

    public MfaRequiredException() {
        super(HttpStatus.UNAUTHORIZED, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Enter the 6-digit code from your authenticator app");
        problem.setType(TYPE);
        return problem;
    }
}
