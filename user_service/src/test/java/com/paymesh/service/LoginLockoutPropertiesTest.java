package com.paymesh.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class LoginLockoutPropertiesTest {

    @Test
    void needsAtLeastOneAttempt() {
        assertThatIllegalStateException().isThrownBy(() -> new LoginLockoutProperties(0, Duration.ofMinutes(15)));
    }

    @Test
    void needsAPositiveLockDuration() {
        assertThatIllegalStateException().isThrownBy(() -> new LoginLockoutProperties(5, Duration.ZERO));
        assertThatIllegalStateException().isThrownBy(() -> new LoginLockoutProperties(5, null));
    }
}
