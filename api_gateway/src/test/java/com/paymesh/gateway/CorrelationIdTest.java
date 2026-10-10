package com.paymesh.gateway;

import com.paymesh.gateway.support.GatewayTestSupport;
import com.paymesh.gateway.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdTest extends GatewayTestSupport {

    private static final String HEADER = "X-Correlation-Id";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Test
    void generatesAnIdAndSendsItUpstreamAndBack() throws InterruptedException {
        String returned = client.get().uri("/api/wallets/me").headers(h -> h.setBearerAuth(TestTokens.valid())).exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches(HEADER, UUID_PATTERN)
                .returnResult(String.class).getResponseHeaders().getFirst(HEADER);

        assertThat(WALLET_SERVICE.takeRequest().header(HEADER)).isEqualTo(returned);
    }

    @Test
    void propagatesAWellFormedIncomingId() throws InterruptedException {
        client.get().uri("/api/wallets/me")
                .headers(h -> {
                    h.setBearerAuth(TestTokens.valid());
                    h.set(HEADER, "web-7f3a9c-01");
                })
                .exchange()
                .expectHeader().valueEquals(HEADER, "web-7f3a9c-01");

        assertThat(WALLET_SERVICE.takeRequest().header(HEADER)).isEqualTo("web-7f3a9c-01");
    }

    @ParameterizedTest
    @ValueSource(strings = {"<script>alert(1)</script>", "has spaces", "ends-with-newline%0d%0aX-Evil: 1",
            "0123456789012345678901234567890123456789012345678901234567890123456789"})
    void replacesUnsafeOrOversizedIds(String unsafe) throws InterruptedException {
        client.get().uri("/api/wallets/me")
                .headers(h -> {
                    h.setBearerAuth(TestTokens.valid());
                    h.set(HEADER, unsafe);
                })
                .exchange()
                .expectHeader().valueMatches(HEADER, UUID_PATTERN);

        assertThat(WALLET_SERVICE.takeRequest().header(HEADER)).matches(UUID_PATTERN);
    }

    @Test
    void errorResponsesCarryTheIdToo() {
        client.get().uri("/api/wallets/me").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueMatches(HEADER, UUID_PATTERN);

        client.get().uri("/api/unknown").headers(h -> h.setBearerAuth(TestTokens.valid())).exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueMatches(HEADER, UUID_PATTERN);
    }
}
