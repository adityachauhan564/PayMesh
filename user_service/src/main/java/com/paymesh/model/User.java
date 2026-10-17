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

    /** USER or ADMIN; becomes the token's roles claim. */
    @Column(nullable = false, length = 16)
    private String role = "USER";

    /** Base32 TOTP secret; set at setup, active once totpEnabled. */
    @Column(length = 64)
    private String totpSecret;

    @Column(nullable = false)
    private boolean totpEnabled;

    /** Last TOTP time step accepted: a code is valid for ~90 s and must not work twice. */
    private Long totpLastStep;

    /** SHA-256 of the one-time unlock link sent with the lockout email. */
    @Column(length = 64, columnDefinition = "char(64)")
    private String unlockTokenHash;

    private Instant unlockExpiresAt;

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

    /** The owner proved access to their email (unlock link): the lock and the link are both cleared. */
    public void unlock() {
        recordSuccessfulLogin();
        unlockTokenHash = null;
        unlockExpiresAt = null;
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
