package com.paymesh.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserLockoutTest {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCK = Duration.ofMinutes(15);
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private final User user = new User();

    @Test
    void locksOnTheFifthConsecutiveFailureForTheLockDuration() {
        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            assertThat(user.recordFailedLogin(T0, MAX_ATTEMPTS, LOCK)).isFalse();
        }

        assertThat(user.recordFailedLogin(T0, MAX_ATTEMPTS, LOCK)).isTrue();
        assertThat(user.isLockedAt(T0.plus(LOCK).minusSeconds(1))).isTrue();
        assertThat(user.isLockedAt(T0.plus(LOCK))).isFalse();
    }

    @Test
    void anOldFailureStreakStartsOver() {
        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            user.recordFailedLogin(T0, MAX_ATTEMPTS, LOCK);
        }

        Instant muchLater = T0.plus(LOCK).plusSeconds(1);
        assertThat(user.recordFailedLogin(muchLater, MAX_ATTEMPTS, LOCK)).isFalse();
        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
    }

    @Test
    void successfulLoginClearsTheStreak() {
        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            user.recordFailedLogin(T0, MAX_ATTEMPTS, LOCK);
        }

        user.recordSuccessfulLogin();

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.recordFailedLogin(T0, MAX_ATTEMPTS, LOCK)).isFalse();
    }

    @Test
    void afterTheLockExpiresTheUserGetsAFullSetOfAttempts() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            user.recordFailedLogin(T0, MAX_ATTEMPTS, LOCK);
        }

        Instant afterLock = T0.plus(LOCK);
        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            assertThat(user.recordFailedLogin(afterLock, MAX_ATTEMPTS, LOCK)).isFalse();
        }
        assertThat(user.isLockedAt(afterLock)).isFalse();
    }

    @Test
    void neverLockedUserIsNotLocked() {
        assertThat(user.isLockedAt(T0)).isFalse();
    }
}
