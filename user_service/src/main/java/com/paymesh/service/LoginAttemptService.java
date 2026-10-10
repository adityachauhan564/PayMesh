package com.paymesh.service;

import lombok.extern.slf4j.Slf4j;
import com.paymesh.model.User;
import com.paymesh.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Per-account lockout (complements the gateway's per-IP rate limit, which can't stop guesses spread across many IPs).
 * Writes happen under a row lock in a short transaction; the slow password check runs before, outside it.
 */
@Service
@Slf4j
public class LoginAttemptService {

    private final UserRepository userRepository;
    private final LoginLockoutProperties policy;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final LockoutNotifier lockoutNotifier;

    public LoginAttemptService(UserRepository userRepository, LoginLockoutProperties policy, Clock clock,
                               PlatformTransactionManager transactionManager, LockoutNotifier lockoutNotifier) {
        this.userRepository = userRepository;
        this.policy = policy;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.lockoutNotifier = lockoutNotifier;
    }

    /** Refuses a locked account before its password is even checked, so guesses during a lock learn nothing. */
    public void ensureNotLocked(User user) {
        Instant now = clock.instant();
        if (user.isLockedAt(now)) {
            throw new AccountLockedException(Duration.between(now, user.getLockedUntil()));
        }
    }

    /**
     * Throws AccountLockedException if this failure locked the account. The owner's email is queued in the same
     * transaction as the lock, only for the attempt that caused it.
     */
    public void recordFailure(Long userId) {
        Instant now = clock.instant();
        User lockedNow = transaction.execute(status -> {
            User user = userRepository.findLockedByUserId(userId).orElse(null);
            if (user == null || user.isLockedAt(now)) {
                return null; // deleted meanwhile, or already locked by a concurrent attempt: don't extend the lock
            }
            if (!user.recordFailedLogin(now, policy.maxFailedAttempts(), policy.duration())) {
                return null;
            }
            lockoutNotifier.accountLocked(user, policy.maxFailedAttempts());
            return user;
        });
        if (lockedNow != null) {
            log.warn("User {} locked for {} after {} failed logins", userId, policy.duration(), policy.maxFailedAttempts());
            throw new AccountLockedException(policy.duration());
        }
    }

    public void recordSuccess(User user) {
        if (!user.hasLoginFailureState()) {
            return; // the common case: no write on every login
        }
        transaction.executeWithoutResult(status ->
                userRepository.findLockedByUserId(user.getUserId()).ifPresent(User::recordSuccessfulLogin));
    }
}
