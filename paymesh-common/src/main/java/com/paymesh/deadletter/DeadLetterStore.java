package com.paymesh.deadletter;

import com.paymesh.kafka.KafkaErrorHandling;
import com.paymesh.outbox.Outbox;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Keeps this service's dead letters and puts them back on their original topic on request. */
@Slf4j
public class DeadLetterStore {

    private static final int MAX_MESSAGE = 1000;

    private final DeadLetterRepository repository;
    private final Outbox outbox;

    DeadLetterStore(DeadLetterRepository repository, Outbox outbox) {
        this.repository = repository;
        this.outbox = outbox;
    }

    /** Idempotent: a DLT record redelivered at the same position is stored once. */
    @Transactional
    public void record(ConsumerRecord<String, String> dltRecord) {
        if (repository.existsByDltTopicAndDltPartitionAndDltOffset(dltRecord.topic(), dltRecord.partition(), dltRecord.offset())) {
            return;
        }
        String originalTopic = header(dltRecord, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        if (originalTopic == null && dltRecord.topic().endsWith(KafkaErrorHandling.DLT_SUFFIX)) {
            originalTopic = dltRecord.topic().substring(0, dltRecord.topic().length() - KafkaErrorHandling.DLT_SUFFIX.length());
        }
        // The fqcn header is Spring's ListenerExecutionFailedException wrapper; the cause says what actually failed.
        String exceptionClass = header(dltRecord, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
        if (exceptionClass == null) {
            exceptionClass = header(dltRecord, KafkaHeaders.DLT_EXCEPTION_FQCN);
        }
        String message = header(dltRecord, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        if (message != null && message.length() > MAX_MESSAGE) {
            message = message.substring(0, MAX_MESSAGE);
        }
        DeadLetter saved = repository.save(new DeadLetter(dltRecord.topic(), dltRecord.partition(), dltRecord.offset(),
                originalTopic == null ? dltRecord.topic() : originalTopic, dltRecord.key(), dltRecord.value(),
                exceptionClass, message));
        // Payload deliberately not logged: it carries names, emails, amounts.
        log.error("Dead letter {} from {}: {}", saved.getId(), saved.getOriginalTopic(), exceptionClass);
    }

    /** Republishes through the outbox, in the same transaction as the status change: never lost, never doubled. */
    @Transactional
    public void replay(long id) {
        DeadLetter deadLetter = find(id);
        deadLetter.markReplayed();
        outbox.publishJson(deadLetter.getOriginalTopic(), deadLetter.getMessageKey(), deadLetter.getPayload());
    }

    @Transactional
    public void discard(long id) {
        find(id).markDiscarded();
    }

    public List<DeadLetter> findNew() {
        return repository.findByStatusOrderById(DeadLetter.Status.NEW);
    }

    private DeadLetter find(long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("No dead letter " + id));
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
