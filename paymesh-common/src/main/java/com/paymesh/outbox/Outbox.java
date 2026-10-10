package com.paymesh.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use instead of KafkaTemplate.send for anything that must not be lost: the message is stored with the
 * caller's data change and published by OutboxRelay after commit, at least once.
 */
public class Outbox {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final String producer;

    Outbox(OutboxEventRepository repository, ObjectMapper objectMapper, String producer) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.producer = producer;
    }

    /**
     * MANDATORY: outside a transaction there would be nothing to be atomic with, so that's a bug at the call site
     * and fails loudly instead of quietly reintroducing lost events.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String topic, String key, Object payload) {
        repository.save(new OutboxEvent(producer, topic, key, toJson(payload)));
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload is not serializable: " + payload.getClass(), e);
        }
    }
}
