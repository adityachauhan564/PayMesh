package com.paymesh.auth;

import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.SendMailNotification;
import com.paymesh.model.User;
import com.paymesh.outbox.Outbox;
import com.paymesh.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Optional TOTP two-factor login. Setup stores a secret; enabling proves the authenticator app has it (one valid code)
 * and hands out 8 single-use recovery codes. Codes are checked under the user's row lock, so a code works once.
 */
@Service
public class TwoFactor {

    public record Setup(String secret, String otpauthUri) {
    }

    /** A wrong code; the controller counts it toward the account lockout (outside this transaction). */
    public static class WrongCodeException extends ResponseStatusException {
        WrongCodeException() {
            super(HttpStatus.BAD_REQUEST, "That code didn't match. Check the time on your phone and try again");
        }
    }

    private static final int RECOVERY_CODES = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RecoveryCodeRepository recoveryCodes;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final Outbox outbox;
    private final Clock clock;

    public TwoFactor(UserRepository users, RecoveryCodeRepository recoveryCodes, RefreshTokenRepository refreshTokens,
                     PasswordEncoder passwordEncoder, Outbox outbox, Clock clock) {
        this.users = users;
        this.recoveryCodes = recoveryCodes;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Needs the password again: a stolen session alone must not be able to put its own authenticator on the account. */
    @Transactional
    public Setup setup(long userId, String password) {
        User user = locked(userId);
        requirePassword(user, password);
        if (user.isTotpEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Two-factor login is already on");
        }
        String secret = Totp.newSecret();
        user.setTotpSecret(secret);
        user.setTotpLastStep(null);
        String label = URLEncoder.encode("PayMesh:" + user.getUserEmailId(), StandardCharsets.UTF_8).replace("%3A", ":").replace("%40", "@");
        return new Setup(secret, "otpauth://totp/" + label + "?secret=" + secret + "&issuer=PayMesh&algorithm=SHA1&digits=6&period=30");
    }

    /** Returns the recovery codes, shown to the user exactly once. */
    @Transactional
    public List<String> enable(long userId, String code) {
        User user = locked(userId);
        if (user.isTotpEnabled() || user.getTotpSecret() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Start two-factor setup first");
        }
        long step = Totp.matchingStep(user.getTotpSecret(), code, Totp.stepAt(clock.instant()));
        if (step < 0) {
            throw wrongCode();
        }
        user.setTotpEnabled(true);
        user.setTotpLastStep(step);
        endOtherSessionsAndTell(user, "turned on");
        recoveryCodes.deleteAllOfUser(userId);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            byte[] bytes = new byte[10]; // 80 bits: unsalted SHA-256 is fine at this entropy, unlike for passwords
            RANDOM.nextBytes(bytes);
            String raw = Totp.base32(bytes).toLowerCase(Locale.ROOT);
            String formatted = String.join("-", raw.substring(0, 4), raw.substring(4, 8), raw.substring(8, 12), raw.substring(12, 16));
            recoveryCodes.save(new RecoveryCode(userId, RefreshTokens.hash(formatted)));
            codes.add(formatted);
        }
        return codes;
    }

    /** Needs the password and a valid code: a stolen session alone must not be able to switch two-factor off. */
    @Transactional
    public void disable(long userId, String password, String code) {
        User user = locked(userId);
        requirePassword(user, password);
        if (!user.isTotpEnabled()) {
            return;
        }
        if (!verify(user, code)) {
            throw wrongCode();
        }
        user.setTotpEnabled(false);
        user.setTotpSecret(null);
        user.setTotpLastStep(null);
        recoveryCodes.deleteAllOfUser(userId);
        endOtherSessionsAndTell(user, "turned off");
    }

    /** Every refresh session ends (they must log in again, with the new setting), and the owner hears about it. */
    private void endOtherSessionsAndTell(User user, String change) {
        refreshTokens.revokeAllOfUser(user.getUserId(), clock.instant());
        SendMailNotification mail = new SendMailNotification(user.getUserEmailId(), "PayMesh: two-factor login " + change,
                String.format("Hi %s,%n%nTwo-factor login was %s for your PayMesh account, and every device was signed out.%n%n"
                        + "If this wasn't you, reset your password and contact support right away.%n%nThanks & Regards,%nPayMesh Team",
                        user.getUserFullName(), change));
        outbox.publish(TopicConstants.SEND_NOTIFICATION_TOPIC, mail.receiverMailId(), mail);
    }

    private void requirePassword(User user, String password) {
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Password is incorrect");
        }
    }

    /**
     * A current TOTP code not used before, or an unused recovery code (which is then spent). The caller must hold
     * the user's row lock in the surrounding transaction, so two requests can't both spend the same code.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean verify(User user, String code) {
        if (!user.isTotpEnabled() || code == null) {
            return false;
        }
        long step = Totp.matchingStep(user.getTotpSecret(), code, Totp.stepAt(clock.instant()));
        if (step >= 0) {
            if (user.getTotpLastStep() != null && step <= user.getTotpLastStep()) {
                return false; // replay
            }
            user.setTotpLastStep(step);
            return true;
        }
        String normalised = code.trim().toLowerCase(Locale.ROOT);
        return recoveryCodes.findFirstByUserIdAndCodeHashAndUsedAtIsNull(user.getUserId(), RefreshTokens.hash(normalised))
                .map(recovery -> {
                    recovery.markUsed(clock.instant());
                    return true;
                })
                .orElse(false);
    }

    /** verify() under the user's row lock, for callers without a transaction of their own (login, step-up). */
    @Transactional
    public boolean verifyForUser(long userId, String code) {
        return verify(locked(userId), code);
    }

    private User locked(long userId) {
        return users.findLockedByUserId(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private static ResponseStatusException wrongCode() {
        return new WrongCodeException();
    }
}
