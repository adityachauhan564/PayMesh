package com.paymesh.gateway.security;

import com.paymesh.gateway.error.ProblemDetailWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Rejects access tokens that were logged out (user_service puts their jti on the denylist until they expire).
 * Runs after authentication, so only validly signed tokens cost a Redis lookup. Fails open: if Redis is down the
 * request proceeds, since a logged-out token stays valid for at most its 15 minutes anyway.
 * Not a @Component: as a bean it would also run as a global WebFilter, outside the security chain.
 */
public class DenylistFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(DenylistFilter.class);

    static final String KEY_PREFIX = "denylist:jti:";

    private final ReactiveStringRedisTemplate redis;
    private final ProblemDetailWriter problemDetailWriter;

    public DenylistFilter(ReactiveStringRedisTemplate redis, ProblemDetailWriter problemDetailWriter) {
        this.redis = redis;
        this.problemDetailWriter = problemDetailWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(auth -> ((JwtAuthenticationToken) auth).getToken().getId())
                .flatMap(this::isDenied)
                .defaultIfEmpty(false)
                .flatMap(denied -> denied ? reject(exchange) : chain.filter(exchange));
    }

    private Mono<Boolean> isDenied(String jti) {
        return redis.hasKey(KEY_PREFIX + jti)
                .onErrorResume(e -> {
                    log.warn("Redis unavailable, skipping logout denylist check: {}", e.toString());
                    return Mono.just(false);
                });
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
        return problemDetailWriter.write(exchange, HttpStatus.UNAUTHORIZED, "This session has been logged out");
    }
}
