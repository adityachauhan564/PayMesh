package com.paymesh.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ClientAddressKeyResolverTest {

    @Test
    void withoutTrustedProxiesTheConnectionAddressIsTheKeyAndXForwardedForCannotSpoofIt() {
        MockServerWebExchange exchange = exchangeFrom("203.0.113.7", "1.2.3.4");

        assertThat(new ClientAddressKeyResolver(0).resolve(exchange).block()).isEqualTo("203.0.113.7");
    }

    @Test
    void behindOneTrustedProxyTheAddressThatProxySawIsTheKey() {
        // Client forged "1.2.3.4"; our load balancer (10.0.0.5) appended the real client address.
        MockServerWebExchange exchange = exchangeFrom("10.0.0.5", "1.2.3.4, 198.51.100.9");

        assertThat(new ClientAddressKeyResolver(1).resolve(exchange).block()).isEqualTo("198.51.100.9");
    }

    @Test
    void rejectsNegativeProxyCount() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ClientAddressKeyResolver(-1));
    }

    private static MockServerWebExchange exchangeFrom(String remoteIp, String xForwardedFor) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new InetSocketAddress(remoteIp, 50000))
                .header("X-Forwarded-For", xForwardedFor));
    }
}
