package com.paymesh.gateway.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Rate-limit bucket per client IP. X-Forwarded-For is only honoured for the number of proxies we run in front of
 * the gateway (env TRUSTED_PROXY_COUNT); otherwise a client could send a fresh fake address with every attempt.
 * Behind a load balancer set it to 1, or every user shares the load balancer's bucket.
 */
@Component
public class ClientAddressKeyResolver implements KeyResolver {

    private final RemoteAddressResolver addressResolver;

    public ClientAddressKeyResolver(@Value("${TRUSTED_PROXY_COUNT:0}") int trustedProxyCount) {
        if (trustedProxyCount < 0) {
            throw new IllegalArgumentException("TRUSTED_PROXY_COUNT must be 0 or more");
        }
        this.addressResolver = trustedProxyCount == 0
                ? new RemoteAddressResolver() { } // the TCP peer address
                : XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxyCount);
    }

    /** An empty key makes the rate limiter deny the request (deny-empty-key), so unknown clients are not exempt. */
    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        return Mono.justOrEmpty(addressResolver.resolve(exchange)).map(address -> address.getHostString());
    }
}
