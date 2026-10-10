package com.paymesh.gateway;

import com.paymesh.gateway.support.RedisTestContainer;
import com.paymesh.gateway.support.StubUpstream;
import com.paymesh.gateway.support.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;

/** Own Spring context: user_service is down (nothing listening) and wallet_service is slower than the timeout. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jwt.secret=" + TestTokens.SECRET,
        "jwt.issuer=" + TestTokens.ISSUER,
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=300ms",
        "LOGIN_RATE_LIMIT_REPLENISH_RATE=1000",
        "LOGIN_RATE_LIMIT_BURST_CAPACITY=1000",
        "LOGIN_RATE_LIMIT_REQUESTED_TOKENS=1"})
class UpstreamFailureTest {

    private static final StubUpstream SLOW_WALLET_SERVICE = StubUpstream.start("wallet", Duration.ofSeconds(2));

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("USER_SERVICE_URL", () -> "http://localhost:" + unusedPort());
        registry.add("WALLET_SERVICE_URL", SLOW_WALLET_SERVICE::url);
        RedisTestContainer.register(registry);
    }

    @AfterAll
    static void stop() {
        SLOW_WALLET_SERVICE.close();
    }

    @Autowired
    private WebTestClient client;

    @BeforeEach
    void emptyRateLimitBuckets() {
        RedisTestContainer.flush();
    }

    @Test
    void unreachableServiceReturns503ProblemDetail() {
        client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.detail").isEqualTo("The service is temporarily unavailable, please retry")
                .jsonPath("$.instance").isEqualTo("/api/auth/login")
                .jsonPath("$.trace").doesNotExist()
                .jsonPath("$.exception").doesNotExist();
    }

    @Test
    void slowServiceReturns504ProblemDetail() {
        client.get().uri("/api/wallets/me").headers(h -> h.setBearerAuth(TestTokens.valid())).exchange()
                .expectStatus().isEqualTo(504)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("The service did not respond in time");
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
