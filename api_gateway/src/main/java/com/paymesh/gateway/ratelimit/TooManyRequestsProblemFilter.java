package com.paymesh.gateway.ratelimit;

import com.paymesh.gateway.error.ProblemDetailWriter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The built-in RequestRateLimiter answers 429 with an empty body. This fills that body with the same ProblemDetail
 * every other gateway error uses and adds Retry-After (seconds until one attempt has refilled).
 * Only body-less 429s are touched; a 429 a service sends with its own body passes through unchanged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // just after CorrelationIdFilter, so the 429 carries the correlation id
public class TooManyRequestsProblemFilter implements WebFilter {

    // Set by RedisRateLimiter on every limited request
    private static final String REQUESTED_TOKENS_HEADER = "X-RateLimit-Requested-Tokens";
    private static final String REPLENISH_RATE_HEADER = "X-RateLimit-Replenish-Rate";

    private final ProblemDetailWriter problemDetailWriter;

    public TooManyRequestsProblemFilter(ProblemDetailWriter problemDetailWriter) {
        this.problemDetailWriter = problemDetailWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange.mutate().response(new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> setComplete() {
                if (!HttpStatus.TOO_MANY_REQUESTS.equals(getStatusCode()) || isCommitted()) {
                    return super.setComplete();
                }
                addRetryAfter(getHeaders());
                return problemDetailWriter.write(exchange.mutate().response(this).build(),
                        HttpStatus.TOO_MANY_REQUESTS, "Too many requests, please try again later");
            }
        }).build());
    }

    private static void addRetryAfter(HttpHeaders headers) {
        String requestedTokens = headers.getFirst(REQUESTED_TOKENS_HEADER);
        String replenishRate = headers.getFirst(REPLENISH_RATE_HEADER);
        if (requestedTokens == null || replenishRate == null || Long.parseLong(replenishRate) <= 0) {
            return;
        }
        long tokens = Long.parseLong(requestedTokens);
        long perSecond = Long.parseLong(replenishRate);
        long seconds = (tokens + perSecond - 1) / perSecond; // round up
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(seconds, 1)));
    }
}
