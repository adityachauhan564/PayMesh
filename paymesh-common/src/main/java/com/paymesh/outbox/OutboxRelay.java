package com.paymesh.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Publishes this service's outbox to Kafka, oldest first, deleting each event only after Kafka acknowledged it.
 * Stops at the first failure so order is kept, and retries on the next run. A crash between the ack and the commit
 * re-sends events, so delivery is at-least-once and consumers must be idempotent.
 */
@Slf4j
public class OutboxRelay {

    private static final int BATCH_SIZE = 100;
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transaction;
    private final String producer;

    OutboxRelay(OutboxEventRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                PlatformTransactionManager transactionManager, String producer) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.producer = producer;
        this.transaction = new TransactionTemplate(transactionManager);
        // MySQL's default REPEATABLE READ would also lock the gap after the last row, blocking every new outbox
        // insert (registrations, payments) for as long as Kafka takes to answer. READ COMMITTED locks only the rows read.
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Scheduled(initialDelayString = "${outbox.poll-interval-ms:250}", fixedDelayString = "${outbox.poll-interval-ms:250}")
    public void publishPending() {
        transaction.executeWithoutResult(status -> {
            for (OutboxEvent event : repository.lockOldest(producer, BATCH_SIZE)) {
                if (!sent(event)) {
                    return; // later events wait, so they're never published ahead of this one
                }
                repository.delete(event);
            }
        });
    }

    private boolean sent(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // Payload deliberately not logged: it carries names, emails, amounts.
            log.warn("Outbox event {} for {} not published yet, will retry: {}", event.getId(), event.getTopic(), e.toString());
            return false;
        }
    }
}
