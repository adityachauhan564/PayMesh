package com.paymesh.outbox;

import com.paymesh.logging.CorrelationIdLoggingFilter;
import org.slf4j.MDC;
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
    private final OutboxTracing tracing;

    Outbox(OutboxEventRepository repository, JsonMapper jsonMapper, String producer, OutboxTracing tracing) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.producer = producer;
        this.tracing = tracing;
    }

    /**
     * MANDATORY: outside a transaction there would be nothing to be atomic with, so that's a bug at the call site
     * and fails loudly instead of quietly reintroducing lost events.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String topic, String key, Object payload) {
        publishJson(topic, key, toJson(payload));
    }

    /** For payloads that already are JSON (e.g. replaying a dead letter): stored as is, not re-encoded. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishJson(String topic, String key, String json) {
        OutboxEvent event = new OutboxEvent(producer, topic, key, json, MDC.get(CorrelationIdLoggingFilter.MDC_KEY));
        event.traceparent = tracing.currentTraceparent();
        repository.save(event);
    }

    private String toJson(Object payload) {
        try {
            return jsonMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("Outbox payload is not serializable: " + payload.getClass(), e);
        }
    }
}
