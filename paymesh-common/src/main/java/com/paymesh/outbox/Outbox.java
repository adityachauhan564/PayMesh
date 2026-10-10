package com.paymesh.outbox;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use instead of KafkaTemplate.send for anything that must not be lost: the message is stored with the
 * caller's data change and published by OutboxRelay after commit, at least once.
 */
public class Outbox {

    private final OutboxEventRepository repository;
    private final JsonMapper jsonMapper;
    private final String producer;

    Outbox(OutboxEventRepository repository, JsonMapper jsonMapper, String producer) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
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
            return jsonMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("Outbox payload is not serializable: " + payload.getClass(), e);
        }
    }
}
