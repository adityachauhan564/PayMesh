package com.paymesh.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Real MySQL: the guarantees here (atomicity, locking, isolation) are exactly what mocks can't show. */
@SpringBootTest(properties = {
        "spring.application.name=outbox-test",
        "spring.jpa.hibernate.ddl-auto=update",
        "outbox.poll-interval-ms=3600000"}) // relay runs only when a test calls it
class OutboxIntegrationTest {

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
    private Outbox outbox;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private OutboxEventRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void emptyOutbox() {
        transaction = new TransactionTemplate(transactionManager);
        repository.deleteAll();
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(sent());
    }

    @Test
    void publishingOutsideATransactionIsRefused() {
        assertThatExceptionOfType(IllegalTransactionStateException.class)
                .isThrownBy(() -> outbox.publish("topic", "key", Map.of("a", 1)));
    }

    @Test
    void anEventIsStoredOnlyIfTheSurroundingTransactionCommits() {
        transaction.executeWithoutResult(status -> {
            outbox.publish("orders", "1", Map.of("id", 1));
            status.setRollbackOnly();
        });
        assertThat(repository.findAll()).isEmpty();

        transaction.executeWithoutResult(status -> outbox.publish("orders", "2", Map.of("id", 2)));

        assertThat(repository.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getTopic()).isEqualTo("orders");
            assertThat(event.getMessageKey()).isEqualTo("2");
            assertThat(event.getPayload()).isEqualTo("{\"id\":2}");
            assertThat(event.getProducer()).isEqualTo("outbox-test");
        });
    }

    @Test
    void storesRealisticallyLargePayloads() {
        String emailBody = "x".repeat(20_000); // emails run to hundreds of bytes; leave lots of headroom

        transaction.executeWithoutResult(status -> outbox.publish("mails", "1", Map.of("message", emailBody)));

        assertThat(repository.findAll()).singleElement()
                .satisfies(event -> assertThat(event.getPayload()).contains(emailBody));
    }

    @Test
    void relayPublishesInWriteOrderThenRemovesTheEvents() {
        publishInOwnTransactions("first", "second", "third");

        relay.publishPending();

        InOrder order = inOrder(kafkaTemplate);
        order.verify(kafkaTemplate).send("orders", "first", "\"first\"");
        order.verify(kafkaTemplate).send("orders", "second", "\"second\"");
        order.verify(kafkaTemplate).send("orders", "third", "\"third\"");
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void aFailedSendStopsTheBatchAndIsRetriedInOrderNextTime() {
        publishInOwnTransactions("first", "second", "third");
        when(kafkaTemplate.send("orders", "second", "\"second\"")).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay.publishPending();

        verify(kafkaTemplate, never()).send("orders", "third", "\"third\"");
        assertThat(repository.findAll()).extracting(OutboxEvent::getMessageKey).containsExactly("second", "third");

        when(kafkaTemplate.send("orders", "second", "\"second\"")).thenReturn(sent());
        relay.publishPending();

        assertThat(repository.findAll()).isEmpty();
        verify(kafkaTemplate, times(1)).send("orders", "first", "\"first\""); // not re-sent
    }

    @Test
    void anotherServicesEventsAreLeftAlone() {
        transaction.executeWithoutResult(status -> repository.save(new OutboxEvent("other-service", "orders", "x", "{}")));

        relay.publishPending();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void writersAreNotBlockedWhileTheRelayWaitsOnKafka() throws Exception {
        publishInOwnTransactions("slow");
        CompletableFuture<SendResult<String, String>> kafkaAck = new CompletableFuture<>();
        when(kafkaTemplate.send("orders", "slow", "\"slow\"")).thenReturn(kafkaAck);
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> relayRun = pool.submit(relay::publishPending); // holds its row lock until Kafka answers
            verify(kafkaTemplate, timeout(5000)).send("orders", "slow", "\"slow\"");

            long started = System.nanoTime();
            publishInOwnTransactions("meanwhile");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));

            kafkaAck.complete(null);
            relayRun.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(repository.findAll()).extracting(OutboxEvent::getMessageKey).containsExactly("meanwhile");
    }

    private void publishInOwnTransactions(String... keys) {
        for (String key : List.of(keys)) {
            transaction.executeWithoutResult(status -> outbox.publish("orders", key, key));
        }
    }

    private static CompletableFuture<SendResult<String, String>> sent() {
        return CompletableFuture.completedFuture(null);
    }
}
