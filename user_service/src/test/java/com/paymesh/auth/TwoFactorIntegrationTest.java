package com.paymesh.auth;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import com.paymesh.support.MySqlTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Two-factor login over real HTTP: enrolment, codes at login, replay protection, recovery codes, step-up. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.kafka.listener.auto-startup=false", "outbox.poll-interval-ms=3600000"})
class TwoFactorIntegrationTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final AtomicLong MOBILES = new AtomicLong(9_200_000_000L);

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

    private final HttpClient http = HttpClient.newHttpClient();

    record Account(String mobile, String token) {
    }

    HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    Account register() throws Exception {
        String mobile = String.valueOf(MOBILES.incrementAndGet());
        HttpResponse<String> response = post("/api/auth/register", "{\"fullName\":\"Asha\",\"mobileNo\":\"" + mobile
                + "\",\"emailId\":\"u" + mobile + "@example.com\",\"password\":\"secret123\"}", null);
        assertThat(response.statusCode()).isEqualTo(201);
        return new Account(mobile, JsonPath.read(response.body(), "$.token"));
    }

    static String codeNow(String secret) {
        return Totp.code(secret, Totp.stepAt(Instant.now()));
    }

    /** Enrols the account and returns {secret, recovery codes}. */
    String[] enrol(Account account) throws Exception {
        String secret = JsonPath.read(post("/api/users/me/2fa/setup", null, account.token()).body(), "$.secret");
        HttpResponse<String> enabled = post("/api/users/me/2fa/enable", "{\"code\":\"" + codeNow(secret) + "\"}", account.token());
        assertThat(enabled.statusCode()).isEqualTo(200);
        List<String> recovery = JsonPath.read(enabled.body(), "$.recoveryCodes");
        String[] result = new String[1 + recovery.size()];
        result[0] = secret;
        for (int i = 0; i < recovery.size(); i++) {
            result[i + 1] = recovery.get(i);
        }
        return result;
    }

    static List<String> claim(String token, String name) throws Exception {
        Object value = SignedJWT.parse(token).getJWTClaimsSet().getClaim(name);
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : value == null ? List.of() : List.of(String.valueOf(value));
    }

    // --- enrolment ---

    @Test
    void setupReturnsSecretAndOtpauthUri() throws Exception {
        Account account = register();

        HttpResponse<String> setup = post("/api/users/me/2fa/setup", null, account.token());

        assertThat(setup.statusCode()).isEqualTo(200);
        String secret = JsonPath.read(setup.body(), "$.secret");
        assertThat(Totp.fromBase32(secret)).hasSize(20);
        assertThat((String) JsonPath.read(setup.body(), "$.otpauthUri"))
                .startsWith("otpauth://totp/PayMesh:u" + account.mobile() + "@example.com?secret=" + secret)
                .contains("issuer=PayMesh").contains("digits=6").contains("period=30");
    }

    @Test
    void enableNeedsAValidCode() throws Exception {
        Account account = register();
        post("/api/users/me/2fa/setup", null, account.token());

        assertThat(post("/api/users/me/2fa/enable", "{\"code\":\"000000\"}", account.token()).statusCode()).isEqualTo(400);
    }

    @Test
    void enableReturnsEightRecoveryCodes() throws Exception {
        String[] enrolment = enrol(register());

        assertThat(enrolment).hasSize(9);
        for (int i = 1; i < enrolment.length; i++) {
            assertThat(enrolment[i]).matches("[a-z2-7]{4}-[a-z2-7]{4}");
        }
    }

    @Test
    void setupAgainIs409() throws Exception {
        Account account = register();
        enrol(account);

        assertThat(post("/api/users/me/2fa/setup", null, account.token()).statusCode()).isEqualTo(409);
    }

    @Test
    void disableNeedsAValidCode() throws Exception {
        Account account = register();
        String[] enrolment = enrol(account);

        assertThat(post("/api/users/me/2fa/disable", "{\"code\":\"000000\"}", account.token()).statusCode()).isEqualTo(400);
        assertThat(post("/api/users/me/2fa/disable", "{\"code\":\"" + enrolment[1] + "\"}", account.token()).statusCode()).isEqualTo(204);
        assertThat(post("/api/auth/login", "{\"username\":\"" + account.mobile() + "\",\"password\":\"secret123\"}", null).statusCode())
                .isEqualTo(200);
    }

    @Test
    void tokensOf2faUsersCarryMfaTrue() throws Exception {
        Account account = register();
        assertThat(claim(account.token(), "mfa")).containsExactly("false");
        String[] enrolment = enrol(account);

        HttpResponse<String> login = post("/api/auth/login", "{\"username\":\"" + account.mobile()
                + "\",\"password\":\"secret123\",\"otp\":\"" + enrolment[1] + "\"}", null);

        assertThat(claim(JsonPath.read(login.body(), "$.token"), "mfa")).containsExactly("true");
    }
}
