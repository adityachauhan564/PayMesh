package com.paymesh;

import com.paymesh.constants.TopicConstants;
import com.paymesh.model.User;
import com.paymesh.repository.UserRepository;
import com.paymesh.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Real HTTP + real MySQL. The mocked controller tests passed while lockout silently did nothing in production:
 * with open-in-view, the read-only login query left the User read-only in the request's session, so the
 * failure count was never written. Only a real persistence layer shows that, and the row lock under concurrency.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jwt.secret=user-service-integration-secret-32-bytes",
        "login.lockout.max-failed-attempts=3",
        "login.lockout.duration=15m"})
class LoginLockoutIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MySqlTestContainer.register(registry);
    }

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @LocalServerPort
    private int port;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private String mobile;

    @BeforeEach
    void registeredUser() {
        mobile = "9" + String.format("%09d", System.nanoTime() % 1_000_000_000L);
        User user = new User();
        user.setUserFullName("Lock Test");
        user.setUserMobileNo(mobile);
        user.setUserEmailId(mobile + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("secret123"));
        userRepository.save(user);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void repeatedWrongPasswordsAreStoredAndLockTheAccount() {
        assertThat(login("wrong-1").statusCode()).isEqualTo(401);
        assertThat(userRepository.findByUserMobileNo(mobile).orElseThrow().getFailedLoginAttempts())
                .as("the failure must be persisted").isEqualTo(1);

        login("wrong-2");
        HttpResponse<String> third = login("wrong-3");

        assertThat(third.statusCode()).isEqualTo(429);
        assertThat(third.headers().firstValue("Retry-After")).hasValue("900");
        assertThat(login("secret123").statusCode()).as("correct password while locked").isEqualTo(429);
    }

    @Test
    void concurrentGuessesCannotSlipPastTheLock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Integer>> guesses = IntStream.range(0, 12)
                    .<Callable<Integer>>mapToObj(i -> () -> login("guess-" + i).statusCode())
                    .toList();
            List<Integer> statuses = pool.invokeAll(guesses).stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(statuses).containsOnly(401, 429).contains(429);
        } finally {
            pool.shutdown();
        }

        User stored = userRepository.findByUserMobileNo(mobile).orElseThrow();
        assertThat(stored.isLockedAt(Instant.now())).isTrue();
        assertThat(login("secret123").statusCode()).isEqualTo(429);
        // Goes out via the outbox relay (polls every 250 ms): wait for it, then make sure no second one follows.
        verify(kafkaTemplate, timeout(5000).times(1))
                .send(eq(TopicConstants.SEND_NOTIFICATION_TOPIC), eq(mobile + "@example.com"), anyString());
        verify(kafkaTemplate, after(1000).times(1).description("one lock, one email, despite 12 parallel guesses"))
                .send(eq(TopicConstants.SEND_NOTIFICATION_TOPIC), eq(mobile + "@example.com"), anyString());
    }

    // JDK client: HttpURLConnection (TestRestTemplate's default) can't read a 401 carrying WWW-Authenticate.
    private HttpResponse<String> login(String password) {
        String body = "{\"username\":\"" + mobile + "\",\"password\":\"" + password + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
