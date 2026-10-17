package com.paymesh.gateway.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

/**
 * Boots the real gateway against three stub services. Subclasses share one Spring context and one set of stubs;
 * the stubs live for the whole test JVM (the cached context keeps pointing at them).
 * The login rate limit is effectively off here; LoginRateLimitTest covers it with tight limits.
 */
@AutoConfigureWebTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jwt.issuer=" + TestTokens.ISSUER,
        "LOGIN_RATE_LIMIT_REPLENISH_RATE=1000",
        "LOGIN_RATE_LIMIT_BURST_CAPACITY=1000",
        "LOGIN_RATE_LIMIT_REQUESTED_TOKENS=1"})
public abstract class GatewayTestSupport {

    protected static final StubUpstream USER_SERVICE = StubUpstream.start("user");
    protected static final StubUpstream WALLET_SERVICE = StubUpstream.start("wallet");
    protected static final StubUpstream TRANSACTION_SERVICE = StubUpstream.start("transaction");
    protected static final Map<String, StubUpstream> UPSTREAMS =
            Map.of("user", USER_SERVICE, "wallet", WALLET_SERVICE, "transaction", TRANSACTION_SERVICE);

    @DynamicPropertySource
    static void pointRoutesAtStubs(DynamicPropertyRegistry registry) {
        registry.add("jwt.jwks-uri", () -> TestTokens.JWKS_URI);
        registry.add("USER_SERVICE_URL", USER_SERVICE::url);
        registry.add("WALLET_SERVICE_URL", WALLET_SERVICE::url);
        registry.add("TRANSACTION_SERVICE_URL", TRANSACTION_SERVICE::url);
        RedisTestContainer.register(registry);
    }

    @Autowired
    protected WebTestClient client;

    /** Stubs and rate-limit buckets are shared across test classes, so every test starts from a clean slate. */
    @BeforeEach
    void resetSharedState() {
        UPSTREAMS.values().forEach(StubUpstream::reset);
        RedisTestContainer.flush();
    }

    protected static boolean nothingForwarded() {
        return UPSTREAMS.values().stream().allMatch(StubUpstream::receivedNothing);
    }
}
