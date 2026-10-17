package com.paymesh.auth;

import com.paymesh.model.User;
import com.paymesh.repository.UserRepository;
import org.springframework.http.HttpStatus;
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

    private static final int RECOVERY_CODES = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RecoveryCodeRepository recoveryCodes;
    private final Clock clock;

    public TwoFactor(UserRepository users, RecoveryCodeRepository recoveryCodes, Clock clock) {
        this.users = users;
        this.recoveryCodes = recoveryCodes;
        this.clock = clock;
    }

    @Transactional
    public Setup setup(long userId) {
        User user = locked(userId);
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
        recoveryCodes.deleteAllOfUser(userId);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            byte[] bytes = new byte[5];
            RANDOM.nextBytes(bytes);
            String raw = Totp.base32(bytes).toLowerCase(Locale.ROOT);
            String formatted = raw.substring(0, 4) + "-" + raw.substring(4, 8);
            recoveryCodes.save(new RecoveryCode(userId, RefreshTokens.hash(formatted)));
            codes.add(formatted);
        }
        return codes;
    }

    /** Needs a valid code: a stolen session alone must not be able to switch two-factor off. */
    @Transactional
    public void disable(long userId, String code) {
        User user = locked(userId);
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

    private User locked(long userId) {
        return users.findLockedByUserId(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private static ResponseStatusException wrongCode() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "That code didn't match. Check the time on your phone and try again");
    }
}
