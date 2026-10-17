package com.paymesh.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real Kafka: what matters here (redelivery, seeking past a failed record, the DLT publish) is broker behaviour. */
@SpringBootTest(properties = "spring.kafka.consumer.auto-offset-reset=earliest")
class KafkaErrorHandlingIntegrationTest {

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @TestConfiguration
    static class Listeners {

        final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        final List<String> processed = new CopyOnWriteArrayList<>();
        final JsonMapper json = JsonMapper.builder().build();

        @Bean
        Listeners listeners() {
            return this;
        }

        @KafkaListener(topics = "poison-test", groupId = "poison-test")
        void poison(String message) {
            attempts.computeIfAbsent("poison:" + message, k -> new AtomicInteger()).incrementAndGet();
            json.readValue(message, Map.class); // "not json" -> JacksonException
            processed.add(message);
        }

        @KafkaListener(topics = "flaky-test", groupId = "flaky-test")
        void flaky(String message) {
            attempts.computeIfAbsent("flaky:" + message, k -> new AtomicInteger()).incrementAndGet();
            throw new IllegalStateException("downstream unavailable");
        }
    }

    @Autowired
    private KafkaTemplate<String, String> template;

    @Autowired
    private Listeners listeners;

    @Test
    void badJsonGoesStraightToDlt() {
        String payload = "not json " + UUID.randomUUID();
        template.send("poison-test", "k", payload);

        ConsumerRecord<String, String> dead = awaitOn("poison-test.DLT", payload);

        assertThat(listeners.attempts.get("poison:" + payload)).hasValue(1);
        assertThat(new String(dead.headers().lastHeader("kafka_dlt-exception-cause-fqcn").value(), StandardCharsets.UTF_8))
                .containsIgnoringCase("jackson");
    }

    @Test
    void transientFailureIsRetriedThreeTimesThenDeadLettered() {
        String payload = "x-" + UUID.randomUUID();
        template.send("flaky-test", "k", payload);

        awaitOn("flaky-test.DLT", payload);

        assertThat(listeners.attempts.get("flaky:" + payload)).hasValue(3);
    }

    @Test
    void poisonDoesNotBlockLaterMessages() {
        String good = "{\"ok\":\"" + UUID.randomUUID() + "\"}";
        template.send("poison-test", "same-key", "not json at all");
        template.send("poison-test", "same-key", good);

        await().atMost(Duration.ofSeconds(20)).until(() -> listeners.processed.contains(good));
    }

    private static ConsumerRecord<String, String> awaitOn(String topic, String value) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> seen = new ArrayList<>();
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(seen::add);
                return seen.stream().anyMatch(r -> value.equals(r.value()));
            });
            return seen.stream().filter(r -> value.equals(r.value())).findFirst().orElseThrow();
        }
    }
}
