package com.paymesh.controller;

import jakarta.validation.Valid;
import com.paymesh.auth.AccessTokenDenylist;
import com.paymesh.auth.AuthProperties;
import com.paymesh.auth.RefreshTokens;
import com.paymesh.dto.UserDtos.*;
import com.paymesh.service.UserOperationsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

@RestController
@RequestMapping("/api")
public class UserController {

    /** HttpOnly (scripts can't read it), SameSite=Strict (other sites can't send it), only sent to /api/auth. */
    static final String REFRESH_COOKIE = "paymesh_refresh";

    @Autowired
    private UserOperationsService userOperationsService;

    @Autowired
    private RefreshTokens refreshTokens;

    @Autowired
    private AccessTokenDenylist denylist;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private AuthProperties authProperties;

    @PostMapping("/auth/register")
    public ResponseEntity<AuthResponse> register(@RequestBody @Valid RegisterRequest request) {
        return withNewSession(HttpStatus.CREATED, userOperationsService.register(request));
    }

    @PostMapping("/auth/login")
    public ResponseEntity<AuthResponse> login(@RequestBody @Valid LoginRequest request) {
        return withNewSession(HttpStatus.OK, userOperationsService.login(request));
    }

    /** Swaps the refresh cookie for a new one plus a fresh access token; how the web app restores a session. */
    @PostMapping("/auth/refresh")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        try {
            RefreshTokens.Rotation rotation = refreshTokens.rotate(refreshToken);
            return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(rotation.newToken(), authProperties.refreshTtl()))
                    .body(userOperationsService.sessionFor(rotation.userId()));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO)).build();
        }
    }

    /** Ends the session: the refresh family is revoked and the current access token denylisted. Always 204. */
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
                                       @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        refreshTokens.revokeFamilyOf(refreshToken);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                Jwt jwt = jwtDecoder.decode(authorization.substring("Bearer ".length()));
                denylist.deny(jwt.getId(), jwt.getExpiresAt());
            } catch (JwtException expiredOrInvalid) {
                // nothing to revoke: it no longer works anyway
            }
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO)).build();
    }

    private ResponseEntity<AuthResponse> withNewSession(HttpStatus status, AuthResponse auth) {
        String refreshToken = refreshTokens.issue(auth.user().userId());
        return ResponseEntity.status(status).header(HttpHeaders.SET_COOKIE, cookie(refreshToken, authProperties.refreshTtl())).body(auth);
    }

    private String cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value).httpOnly(true).secure(authProperties.cookieSecure())
                .sameSite("Strict").path("/api/auth").maxAge(maxAge).build().toString();
    }

    @PostMapping("/users/me/step-up")
    public AuthResponse stepUp(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid com.paymesh.auth.TwoFactorController.CodeRequest request) {
        return userOperationsService.stepUp(Long.parseLong(jwt.getSubject()), request.code());
    }

    @GetMapping("/users/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.of(userOperationsService.getUser(Long.valueOf(jwt.getSubject())));
    }

    @PutMapping("/users/me")
    public UserResponse update(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid UpdateUserRequest request) {
        return UserResponse.of(userOperationsService.updateUser(Long.valueOf(jwt.getSubject()), request));
    }

    @DeleteMapping("/users/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt) {
        userOperationsService.deleteUser(Long.valueOf(jwt.getSubject()));
    }

    @GetMapping("/users/lookup")
    public UserLookupResponse lookup(@RequestParam String mobile) {
        return userOperationsService.lookupByMobile(mobile);
    }
}
