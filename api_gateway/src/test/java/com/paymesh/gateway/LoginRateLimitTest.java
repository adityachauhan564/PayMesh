package com.paymesh.gateway;

import com.paymesh.gateway.support.GatewayTestSupport;
import com.paymesh.gateway.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tight limit: 3 attempts at once, then 1 per minute (bucket of 180 tokens, each login costs 60, refills 1/s).
 * Refill is too slow to matter within a test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jwt.secret=" + TestTokens.SECRET,
        "jwt.issuer=" + TestTokens.ISSUER,
        "LOGIN_RATE_LIMIT_REPLENISH_RATE=1",
        "LOGIN_RATE_LIMIT_BURST_CAPACITY=180",
        "LOGIN_RATE_LIMIT_REQUESTED_TOKENS=60"})
class LoginRateLimitTest extends GatewayTestSupport {

    private static final int ALLOWED_BURST = 3;

    @Test
    void blocksLoginAttemptsBeyondTheBurstWithProblemDetailAndRetryAfter() throws InterruptedException {
        for (int attempt = 1; attempt <= ALLOWED_BURST; attempt++) {
            login().expectStatus().isOk();
        }

        login()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "60")
                // Counts tokens, which refill 1/s, so it may read 1 or 2: just fewer than one attempt (60) costs.
                .expectHeader().value("X-RateLimit-Remaining", remaining -> assertThat(Integer.parseInt(remaining)).isLessThan(60))
                .expectHeader().exists("X-Correlation-Id")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.detail").isEqualTo("Too many requests, please try again later")
                .jsonPath("$.instance").isEqualTo("/api/auth/login");

        for (int attempt = 1; attempt <= ALLOWED_BURST; attempt++) {
            assertThat(USER_SERVICE.takeRequest()).isNotNull();
        }
        assertThat(USER_SERVICE.receivedNothing()).as("blocked attempt must not reach user_service").isTrue();
    }

    @Test
    void onlyLoginIsLimited() {
        for (int attempt = 0; attempt <= ALLOWED_BURST; attempt++) {
            login();
        }
        login().expectStatus().isEqualTo(429);

        client.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isOk();
        client.get().uri("/api/wallets/me").headers(h -> h.setBearerAuth(TestTokens.valid())).exchange()
                .expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec login() {
        return client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"9876543210\",\"password\":\"guess\"}")
                .exchange();
    }
}
