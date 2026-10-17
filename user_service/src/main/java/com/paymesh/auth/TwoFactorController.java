package com.paymesh.auth;

import com.paymesh.repository.UserRepository;
import com.paymesh.service.LoginAttemptService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.function.Supplier;

/**
 * Two-factor settings for the signed-in user. Wrong codes and passwords count toward the same lockout as failed
 * logins, and a locked account can't try more, so neither can be brute-forced through these endpoints either.
 */
@RestController
@RequestMapping("/api/users/me/2fa")
public class TwoFactorController {

    public record CodeRequest(@NotBlank @Size(max = 32) String code) {
    }

    public record PasswordRequest(@NotBlank @Size(max = 64) String password) {
    }

    public record PasswordAndCodeRequest(@NotBlank @Size(max = 64) String password, @NotBlank @Size(max = 32) String code) {
    }

    public record RecoveryCodes(List<String> recoveryCodes) {
    }

    private final TwoFactor twoFactor;
    private final LoginAttemptService loginAttempts;
    private final UserRepository users;

    public TwoFactorController(TwoFactor twoFactor, LoginAttemptService loginAttempts, UserRepository users) {
        this.twoFactor = twoFactor;
        this.loginAttempts = loginAttempts;
        this.users = users;
    }

    @PostMapping("/setup")
    public TwoFactor.Setup setup(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid PasswordRequest request) {
        long userId = userId(jwt);
        return countingWrongSecrets(userId, () -> twoFactor.setup(userId, request.password()));
    }

    @PostMapping("/enable")
    public RecoveryCodes enable(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid CodeRequest request) {
        long userId = userId(jwt);
        return new RecoveryCodes(countingWrongSecrets(userId, () -> twoFactor.enable(userId, request.code())));
    }

    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid PasswordAndCodeRequest request) {
        long userId = userId(jwt);
        countingWrongSecrets(userId, () -> {
            twoFactor.disable(userId, request.password(), request.code());
            return null;
        });
    }

    /** Fast refusal for a locked account; TwoFactor re-checks under the row lock and counts failures itself. */
    private <T> T countingWrongSecrets(long userId, Supplier<T> action) {
        loginAttempts.ensureNotLocked(users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));
        return action.get();
    }

    private static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
