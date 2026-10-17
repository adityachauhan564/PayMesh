package com.paymesh;

import com.paymesh.constants.TopicConstants;
import com.paymesh.outbox.OutboxEvent;
import com.paymesh.outbox.OutboxEventRepository;
import com.paymesh.support.MySqlTestContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka is unreachable (real KafkaTemplate, nothing listening). Registration must still answer promptly, and its
 * wallet-creation event must be kept in the outbox for when Kafka is back, not lost.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class KafkaUnavailableTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        MySqlTestContainer.register(registry);
        registry.add("KAFKA_SERVERS", () -> "localhost:" + unusedPort());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Test
    void registrationAnswersPromptlyAndKeepsItsEventUntilKafkaIsBack(CapturedOutput output) throws Exception {
        String mobile = "8" + String.format("%09d", System.nanoTime() % 1_000_000_000L);
        String body = "{\"fullName\":\"Kafka Down\",\"mobileNo\":\"" + mobile + "\",\"emailId\":\"" + mobile
                + "@example.com\",\"password\":\"secret123\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();

        long started = System.nanoTime();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(took).as("blocked on Kafka for %s", took).isLessThan(Duration.ofSeconds(15));
        long userId = new JsonMapper().readTree(response.body()).at("/user/userId").asLong();
        assertThat(outboxEvents.findAll())
                .filteredOn(event -> event.getTopic().equals(TopicConstants.USER_CREATION_TOPIC))
                .extracting(OutboxEvent::getMessageKey)
                .contains(String.valueOf(userId));
        assertThat(output).as("message payloads (names, emails) must not be logged").doesNotContain(mobile + "@example.com");
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
