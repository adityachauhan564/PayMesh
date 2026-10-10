package com.paymesh.service;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.time.Duration;

/** 429 ProblemDetail with Retry-After. ErrorResponseException (unlike ResponseStatusException) can carry headers. */
public class AccountLockedException extends ErrorResponseException {

    public AccountLockedException(Duration remaining) {
        super(HttpStatus.TOO_MANY_REQUESTS, problem(remaining), null);
        getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(remaining.toSeconds(), 1)));
    }

    private static ProblemDetail problem(Duration remaining) {
        long minutes = Math.max((remaining.toSeconds() + 59) / 60, 1); // round up
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many failed login attempts. Try again in " + minutes + (minutes == 1 ? " minute." : " minutes."));
    }
}
