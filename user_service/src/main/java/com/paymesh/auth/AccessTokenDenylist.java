package com.paymesh.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Logged-out access tokens, by jti, until they would have expired anyway. The gateway rejects them.
 * Fails open: if Redis is down, the token simply lives out its 15 minutes.
 */
@Component
@Slf4j
public class AccessTokenDenylist {

    public static final String KEY_PREFIX = "denylist:jti:";

    private final StringRedisTemplate redis;

    public AccessTokenDenylist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void deny(String jti, Instant expiresAt) {
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        if (jti == null || remaining.isNegative() || remaining.isZero()) {
            return;
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + jti, "1", remaining);
        } catch (RuntimeException e) {
            log.warn("Redis unavailable: logged-out token {} stays valid until it expires: {}", jti, e.toString());
        }
    }
}
