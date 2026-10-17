package com.paymesh;

import com.paymesh.constants.TopicConstants;
import com.paymesh.deadletter.DeadLetter;
import com.paymesh.deadletter.DeadLetterRepository;
import com.paymesh.dto.InitiateTransactionDto;
import com.paymesh.dto.UserWalletCreationRequest;
import com.paymesh.enums.TransactionType;
import com.paymesh.repository.WalletRepository;
import com.paymesh.support.MySqlTestContainer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The wallet's side of the payment saga over a real broker: apply once, answer once, park what can't be read. */
@SpringBootTest(properties = {"jwt.secret=0123456789abcdef0123456789abcdef", "outbox.poll-interval-ms=100",
        "spring.kafka.consumer.auto-offset-reset=earliest"})
class WalletSagaIntegrationTest {

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        MySqlTestContainer.register(registry);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<String, String> kafka;
    @Autowired
    private JsonMapper json;
    @Autowired
    private WalletRepository wallets;
    @Autowired
    private DeadLetterRepository deadLetters;

    @Test
    void addMoneyAppliedOnceEvenWhenRedelivered() throws Exception {
        kafka.send(TopicConstants.USER_CREATION_TOPIC, "101",
                json.writeValueAsString(new UserWalletCreationRequest(101L, "Asha", "asha@example.com"))).get();
        await().atMost(Duration.ofSeconds(20)).until(() -> wallets.findByUserId(101L).isPresent());

        String initiate = json.writeValueAsString(new InitiateTransactionDto("t-1", null, 101L, new BigDecimal("500"), TransactionType.ADD_MONEY));
        for (int i = 0; i < 2; i++) { // the second send is a redelivery
            ProducerRecord<String, String> record = new ProducerRecord<>(TopicConstants.INITIATE_TRANSACTION_TOPIC, "t-1", initiate);
            record.headers().add("correlationId", "corr-123".getBytes(StandardCharsets.UTF_8));
            kafka.send(record).get();
        }

        List<ConsumerRecord<String, String>> results = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = consumer(TopicConstants.COMPLETED_TRANSACTION_TOPIC)) {
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(results::add);
                return results.stream().anyMatch(r -> "t-1".equals(r.key()));
            });
            long until = System.currentTimeMillis() + 3000; // would a second result follow?
            while (System.currentTimeMillis() < until) {
                consumer.poll(Duration.ofMillis(500)).forEach(results::add);
            }
        }

        List<ConsumerRecord<String, String>> forT1 = results.stream().filter(r -> "t-1".equals(r.key())).toList();
        assertThat(forT1).hasSize(1);
        assertThat(forT1.getFirst().value()).contains("\"paymentStatus\":\"SUCCESSFUL\"");
        assertThat(new String(forT1.getFirst().headers().lastHeader("correlationId").value(), StandardCharsets.UTF_8))
                .isEqualTo("corr-123");
        assertThat(wallets.findByUserId(101L).orElseThrow().getBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    void poisonInitiateIsDeadLetteredAndStored() {
        kafka.send(TopicConstants.INITIATE_TRANSACTION_TOPIC, "poison-" + UUID.randomUUID(), "not json");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(deadLetters.findAll())
                .anySatisfy(d -> {
                    assertThat(d.getOriginalTopic()).isEqualTo(TopicConstants.INITIATE_TRANSACTION_TOPIC);
                    assertThat(d.getStatus()).isEqualTo(DeadLetter.Status.NEW);
                    assertThat(d.getExceptionClass()).containsIgnoringCase("jackson");
                }));
    }

    private static KafkaConsumer<String, String> consumer(String topic) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(topic));
        return consumer;
    }
}
