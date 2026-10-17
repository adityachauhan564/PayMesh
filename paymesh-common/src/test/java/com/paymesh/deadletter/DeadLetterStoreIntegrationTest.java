package com.paymesh.deadletter;

import com.paymesh.outbox.OutboxEvent;
import com.paymesh.outbox.OutboxEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

@SpringBootTest(properties = {
        "spring.application.name=dead-letter-test",
        "spring.jpa.hibernate.ddl-auto=update",
        "outbox.poll-interval-ms=3600000"}) // relay never runs: replays must stay visible in the outbox
class DeadLetterStoreIntegrationTest {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private DeadLetterStore store;
    @Autowired
    private DeadLetterRepository deadLetters;
    @Autowired
    private OutboxEventRepository outbox;

    @BeforeEach
    void empty() {
        deadLetters.deleteAll();
        outbox.deleteAll();
    }

    private static ConsumerRecord<String, String> dlt(long offset) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.DLT", 0, offset, "k", "{\"a\":1}");
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_TOPIC, "orders".getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_FQCN, "org.springframework.kafka.listener.ListenerExecutionFailedException".getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN, "x.Boom".getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "boom".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Test
    void recordKeepsOriginalTopicKeyPayloadAndException() {
        store.record(dlt(7));

        assertThat(deadLetters.findAll()).singleElement().satisfies(d -> {
            assertThat(d.getOriginalTopic()).isEqualTo("orders");
            assertThat(d.getMessageKey()).isEqualTo("k");
            assertThat(d.getPayload()).isEqualTo("{\"a\":1}");
            assertThat(d.getExceptionClass()).isEqualTo("x.Boom");
            assertThat(d.getExceptionMessage()).isEqualTo("boom");
            assertThat(d.getStatus()).isEqualTo(DeadLetter.Status.NEW);
        });
    }

    @Test
    void sameDltPositionIsStoredOnce() {
        store.record(dlt(7));
        store.record(dlt(7));

        assertThat(deadLetters.count()).isEqualTo(1);
    }

    @Test
    void replayRepublishesThroughTheOutboxVerbatim() {
        store.record(dlt(7));
        long id = deadLetters.findAll().getFirst().getId();

        store.replay(id);

        assertThat(outbox.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getTopic()).isEqualTo("orders");
            assertThat(e.getMessageKey()).isEqualTo("k");
            assertThat(e.getPayload()).isEqualTo("{\"a\":1}");
        });
        assertThat(deadLetters.findById(id).orElseThrow().getStatus()).isEqualTo(DeadLetter.Status.REPLAYED);
    }

    @Test
    void cannotReplayTwiceOrAfterDiscard() {
        store.record(dlt(7));
        store.record(dlt(8));
        long replayed = deadLetters.findAll().getFirst().getId();
        long discarded = deadLetters.findAll().getLast().getId();

        store.replay(replayed);
        assertThatIllegalStateException().isThrownBy(() -> store.replay(replayed));

        store.discard(discarded);
        assertThatIllegalStateException().isThrownBy(() -> store.replay(discarded));
        assertThat(outbox.findAll()).extracting(OutboxEvent::getMessageKey).containsExactly("k");
    }
}
