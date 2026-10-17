package com.paymesh.logging;

import com.paymesh.kafka.EventHeaders;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The Kafka side of CorrelationIdLoggingFilter: puts the event's correlationId header into the MDC while it is
 * handled, so its logs line up with the original request and the next outbox event carries the same id on.
 * Boot wires a single RecordInterceptor bean into every listener container.
 */
@Component
public class CorrelationIdRecordInterceptor implements RecordInterceptor<Object, Object> {

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        Header header = record.headers().lastHeader(EventHeaders.CORRELATION_ID);
        String incoming = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        boolean safe = incoming != null && CorrelationIdLoggingFilter.SAFE_ID.matcher(incoming).matches();
        MDC.put(CorrelationIdLoggingFilter.MDC_KEY, safe ? incoming : UUID.randomUUID().toString());
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        MDC.remove(CorrelationIdLoggingFilter.MDC_KEY);
    }

    @Override
    public void clearThreadState(Consumer<?, ?> consumer) {
        MDC.remove(CorrelationIdLoggingFilter.MDC_KEY);
    }
}
