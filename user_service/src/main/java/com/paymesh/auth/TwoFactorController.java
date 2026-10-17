package com.paymesh.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Two-factor settings for the signed-in user. */
@RestController
@RequestMapping("/api/users/me/2fa")
public class TwoFactorController {

    public record CodeRequest(@NotBlank @Size(max = 32) String code) {
    }

    public record RecoveryCodes(List<String> recoveryCodes) {
    }

    private final TwoFactor twoFactor;

    public TwoFactorController(TwoFactor twoFactor) {
        this.twoFactor = twoFactor;
    }

    @PostMapping("/setup")
    public TwoFactor.Setup setup(@AuthenticationPrincipal Jwt jwt) {
        return twoFactor.setup(Long.parseLong(jwt.getSubject()));
    }

    @PostMapping("/enable")
    public RecoveryCodes enable(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid CodeRequest request) {
        return new RecoveryCodes(twoFactor.enable(Long.parseLong(jwt.getSubject()), request.code()));
    }

    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid CodeRequest request) {
        twoFactor.disable(Long.parseLong(jwt.getSubject()), request.code());
    }
}
