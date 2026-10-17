package com.paymesh.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(sent());
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
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

        assertThat(sentRecords()).extracting(r -> r.topic() + "/" + r.key() + "/" + r.value())
                .containsExactly("orders/first/\"first\"", "orders/second/\"second\"", "orders/third/\"third\"");
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void aFailedSendStopsTheBatchAndIsRetriedInOrderNextTime() {
        publishInOwnTransactions("first", "second", "third");
        when(kafkaTemplate.send(withKey("second"))).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay.publishPending();

        assertThat(sentRecords()).extracting(ProducerRecord::key).containsExactly("first", "second");
        assertThat(repository.findAll()).extracting(OutboxEvent::getMessageKey).containsExactly("second", "third");

        clearInvocations(kafkaTemplate);
        when(kafkaTemplate.send(withKey("second"))).thenReturn(sent());
        relay.publishPending();

        assertThat(repository.findAll()).isEmpty();
        assertThat(sentRecords()).extracting(ProducerRecord::key).containsExactly("second", "third"); // first not re-sent
    }

    @Test
    void anotherServicesEventsAreLeftAlone() {
        transaction.executeWithoutResult(status -> repository.save(new OutboxEvent("other-service", "orders", "x", "{}", null)));

        relay.publishPending();

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void writersAreNotBlockedWhileTheRelayWaitsOnKafka() throws Exception {
        publishInOwnTransactions("slow");
        CompletableFuture<SendResult<String, String>> kafkaAck = new CompletableFuture<>();
        when(kafkaTemplate.send(withKey("slow"))).thenReturn(kafkaAck);
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> relayRun = pool.submit(relay::publishPending); // holds its row lock until Kafka answers
            verify(kafkaTemplate, timeout(5000)).send(withKey("slow"));

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

    @Test
    void relaySendsEventIdAndCorrelationIdHeaders() {
        MDC.put("correlationId", "corr-1");
        publishInOwnTransactions("one");
        String eventId = repository.findAll().getFirst().getEventId();

        relay.publishPending();

        ProducerRecord<String, String> record = sentRecords().getFirst();
        assertThat(eventId).hasSize(36);
        assertThat(header(record, "eventId")).isEqualTo(eventId);
        assertThat(header(record, "correlationId")).isEqualTo("corr-1");
    }

    @Test
    void noCorrelationIdHeaderWhenMdcEmpty() {
        publishInOwnTransactions("one");

        relay.publishPending();

        assertThat(sentRecords().getFirst().headers().lastHeader("correlationId")).isNull();
    }

    @Test
    void publishJsonStoresTheStringVerbatim() {
        transaction.executeWithoutResult(status -> outbox.publishJson("t", "k", "{\"a\":1}"));

        assertThat(repository.findAll()).singleElement()
                .satisfies(event -> assertThat(event.getPayload()).isEqualTo("{\"a\":1}"));
    }

    @SuppressWarnings("unchecked")
    private List<ProducerRecord<String, String>> sentRecords() {
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, atLeastOnce()).send(captor.capture());
        return captor.getAllValues();
    }

    private static ProducerRecord<String, String> withKey(String key) {
        return argThat(r -> r != null && key.equals(r.key()));
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
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
