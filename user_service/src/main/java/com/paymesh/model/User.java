package com.paymesh.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "wallet_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long userId;

    @Column(nullable = false)
    private String userFullName;

    @Column(unique = true, nullable = false)
    private String userMobileNo;

    @Column(unique = true, nullable = false)
    private String userEmailId;

    private String userAddress;

    @Column(unique = true)
    private String userPan;

    @Column(nullable = false)
    private String passwordHash;

    @CreationTimestamp
    private LocalDateTime createdDate;

    @UpdateTimestamp
    private LocalDateTime updatedDate;

    /** Consecutive failed logins in the current streak (see recordFailedLogin). */
    @Column(nullable = false)
    private int failedLoginAttempts;

    private Instant lastFailedLoginAt;

    private Instant lockedUntil;

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /**
     * Counts a failed login and locks the account once the streak reaches maxAttempts.
     * A streak whose last failure is older than lockDuration starts over, so rare typos never add up to a lock.
     * Returns true if this failure locked the account.
     */
    public boolean recordFailedLogin(Instant now, int maxAttempts, Duration lockDuration) {
        boolean streakExpired = lastFailedLoginAt == null || !lastFailedLoginAt.plus(lockDuration).isAfter(now);
        failedLoginAttempts = streakExpired ? 1 : failedLoginAttempts + 1;
        lastFailedLoginAt = now;
        if (failedLoginAttempts < maxAttempts) {
            return false;
        }
        lockedUntil = now.plus(lockDuration);
        failedLoginAttempts = 0; // a full set of attempts once the lock expires
        return true;
    }

    public void recordSuccessfulLogin() {
        failedLoginAttempts = 0;
        lastFailedLoginAt = null;
        lockedUntil = null;
    }

    public boolean hasLoginFailureState() {
        return failedLoginAttempts > 0 || lockedUntil != null;
    }
}
