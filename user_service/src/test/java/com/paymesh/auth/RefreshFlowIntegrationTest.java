package com.paymesh.auth;

import com.jayway.jsonpath.JsonPath;
import com.paymesh.support.MySqlTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP, real cookies, real MySQL and Redis: the session lifecycle as a browser lives it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.kafka.listener.auto-startup=false", "outbox.poll-interval-ms=3600000"})
class RefreshFlowIntegrationTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final AtomicLong MOBILES = new AtomicLong(9_100_000_000L);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        MySqlTestContainer.register(registry);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Session(String accessToken, String refreshCookie, HttpResponse<String> response) {
    }

    private HttpResponse<String> post(String path, String body, String cookie, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) {
            request.header("Cookie", "paymesh_refresh=" + cookie);
        }
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String setCookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("paymesh_refresh=")).findFirst().orElse(null);
    }

    private static String cookieValue(HttpResponse<?> response) {
        String header = setCookie(response);
        return header == null ? null : header.substring("paymesh_refresh=".length(), header.indexOf(';'));
    }

    private Session register() throws Exception {
        long mobile = MOBILES.incrementAndGet();
        HttpResponse<String> response = post("/api/auth/register", "{\"fullName\":\"Asha\",\"mobileNo\":\"" + mobile
                + "\",\"emailId\":\"u" + mobile + "@example.com\",\"password\":\"secret123\"}", null, null);
        assertThat(response.statusCode()).isEqualTo(201);
        return new Session(JsonPath.read(response.body(), "$.token"), cookieValue(response), response);
    }

    @Test
    void loginSetsAnHttpOnlyStrictCookie() throws Exception {
        String cookie = setCookie(register().response());

        assertThat(cookie).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/auth")
                .contains("Max-Age=604800").contains("Secure");
    }

    @Test
    void refreshRotatesTheCookieAndReturnsANewAccessToken() throws Exception {
        Session session = register();

        HttpResponse<String> refreshed = post("/api/auth/refresh", null, session.refreshCookie(), null);

        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertThat(cookieValue(refreshed)).isNotBlank().isNotEqualTo(session.refreshCookie());
        assertThat((String) JsonPath.read(refreshed.body(), "$.token")).isNotEqualTo(session.accessToken());
        assertThat((Integer) JsonPath.read(refreshed.body(), "$.user.userId")).isPositive();
    }

    @Test
    void expiredRefreshTokenIs401() throws Exception {
        String cookie = register().refreshCookie();
        jdbc.update("UPDATE refresh_token SET expires_at = NOW(6) - INTERVAL 1 DAY");

        assertThat(post("/api/auth/refresh", null, cookie, null).statusCode()).isEqualTo(401);
    }

    @Test
    void logoutRevokesAndDenylists() throws Exception {
        Session session = register();
        String jti = com.nimbusds.jwt.SignedJWT.parse(session.accessToken()).getJWTClaimsSet().getJWTID();

        HttpResponse<String> logout = post("/api/auth/logout", null, session.refreshCookie(), session.accessToken());

        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(setCookie(logout)).contains("Max-Age=0");
        assertThat(post("/api/auth/refresh", null, session.refreshCookie(), null).statusCode()).isEqualTo(401);
        Long ttl = redis.getExpire("denylist:jti:" + jti);
        assertThat(ttl).isBetween(1L, 900L);
    }

    @Test
    void logoutWithoutCookieOrWithAnExpiredTokenIs204() throws Exception {
        assertThat(post("/api/auth/logout", null, null, null).statusCode()).isEqualTo(204);
        assertThat(post("/api/auth/logout", null, "unknown", "not-a-jwt").statusCode()).isEqualTo(204);
    }

    @Test
    void refreshWithoutCookieIs401() throws Exception {
        assertThat(post("/api/auth/refresh", null, null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void rotationKeepsOneLiveTokenPerFamily() throws Exception {
        String a = register().refreshCookie();
        String b = cookieValue(post("/api/auth/refresh", null, a, null));
        cookieValue(post("/api/auth/refresh", null, b, null));

        List<Long> live = jdbc.queryForList("SELECT COUNT(*) FROM refresh_token WHERE family_id = "
                + "(SELECT family_id FROM (SELECT family_id FROM refresh_token WHERE token_hash = SHA2(?, 256)) f) "
                + "AND used_at IS NULL AND revoked_at IS NULL", Long.class, a);
        assertThat(live).containsExactly(1L);
    }

    /** Two tabs refreshing at the same moment share one cookie: the slower one must not log both out. */
    @Test
    void secondTabRefreshingAtTheSameMomentDoesNotRevokeTheSession() throws Exception {
        String shared = register().refreshCookie();

        HttpResponse<String> first = post("/api/auth/refresh", null, shared, null);
        HttpResponse<String> second = post("/api/auth/refresh", null, shared, null); // same cookie, a moment later

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(401);
        assertThat(post("/api/auth/refresh", null, cookieValue(first), null).statusCode()).isEqualTo(200); // session survives
    }

    /** A used token presented again later is a copy (theft): the whole family goes, the real holder included. */
    @Test
    void reusedRefreshTokenRevokesTheFamily() throws Exception {
        String a = register().refreshCookie();
        String b = cookieValue(post("/api/auth/refresh", null, a, null));
        jdbc.update("UPDATE refresh_token SET used_at = used_at - INTERVAL 1 MINUTE WHERE token_hash = SHA2(?, 256)", a);

        HttpResponse<String> replay = post("/api/auth/refresh", null, a, null);

        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(setCookie(replay)).contains("Max-Age=0");
        assertThat(post("/api/auth/refresh", null, b, null).statusCode()).isEqualTo(401);
    }
}
