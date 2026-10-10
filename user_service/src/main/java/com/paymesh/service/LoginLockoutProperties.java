package com.paymesh.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** env LOGIN_LOCKOUT_MAX_FAILED_ATTEMPTS / LOGIN_LOCKOUT_DURATION (e.g. 15m). */
@ConfigurationProperties("login.lockout")
public record LoginLockoutProperties(@DefaultValue("5") int maxFailedAttempts,
                                     @DefaultValue("15m") Duration duration) {

    public LoginLockoutProperties {
        if (maxFailedAttempts < 1) {
            throw new IllegalStateException("login.lockout.max-failed-attempts must be at least 1");
        }
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException("login.lockout.duration must be positive");
        }
    }
}
