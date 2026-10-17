package com.paymesh.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh tokens: 32 random bytes the browser keeps in an HttpOnly cookie; only their SHA-256 is stored. Each use
 * returns a new token and marks the old one used. Presenting a used token again means it was copied (or a stale
 * tab): the whole family is revoked, so whoever holds the newest token is logged out too.
 */
@Service
@Slf4j
public class RefreshTokens {

    public record Rotation(long userId, String newToken) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Two tabs share one cookie and may refresh at the same moment: the slower one presents the token the faster one
     * just used. Within this window that's a race, not theft: refused, but the session is not revoked.
     */
    static final Duration REUSE_GRACE = Duration.ofSeconds(10);

    private final RefreshTokenRepository repository;
    private final AuthProperties auth;

    public RefreshTokens(RefreshTokenRepository repository, AuthProperties auth) {
        this.repository = repository;
        this.auth = auth;
    }

    @Transactional
    public String issue(long userId) {
        return create(userId, UUID.randomUUID().toString());
    }

    /** noRollbackFor: a detected reuse must keep its family revocation even though the request fails. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Rotation rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw unauthorized();
        }
        RefreshToken token = repository.findLockedByHash(hash(rawToken)).orElseThrow(RefreshTokens::unauthorized);
        Instant now = Instant.now();
        if (token.getRevokedAt() != null || token.getExpiresAt().isBefore(now)) {
            throw unauthorized();
        }
        if (token.getUsedAt() != null && token.getUsedAt().isAfter(now.minus(REUSE_GRACE))) {
            throw unauthorized();
        }
        if (token.getUsedAt() != null) {
            log.warn("Refresh token reuse for user {}: revoking session family {}", token.getUserId(), token.getFamilyId());
            repository.revokeFamily(token.getFamilyId(), now);
            throw unauthorized();
        }
        token.markUsed(now);
        return new Rotation(token.getUserId(), create(token.getUserId(), token.getFamilyId()));
    }

    @Transactional
    public void revokeFamilyOf(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByTokenHash(hash(rawToken)).ifPresent(t -> repository.revokeFamily(t.getFamilyId(), Instant.now()));
    }

    private String create(long userId, String familyId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.save(new RefreshToken(userId, familyId, hash(raw), Instant.now().plus(auth.refreshTtl())));
        return raw;
    }

    public static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired, please log in again");
    }
}
