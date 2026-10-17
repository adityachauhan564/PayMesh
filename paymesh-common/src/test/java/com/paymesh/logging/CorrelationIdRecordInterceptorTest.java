package com.paymesh.logging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdRecordInterceptorTest {

    private static final String UUID_PATTERN = "[0-9a-f-]{36}";

    private final CorrelationIdRecordInterceptor interceptor = new CorrelationIdRecordInterceptor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static ConsumerRecord<Object, Object> record(String correlationId) {
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("topic", 0, 0L, "key", "value");
        if (correlationId != null) {
            record.headers().add("correlationId", correlationId.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Test
    void copiesSafeHeaderIntoMdc() {
        interceptor.intercept(record("corr-123"), null);
        assertThat(MDC.get("correlationId")).isEqualTo("corr-123");
    }

    @Test
    void replacesUnsafeHeader() {
        interceptor.intercept(record("evil\nINFO forged"), null);
        assertThat(MDC.get("correlationId")).matches(UUID_PATTERN);
    }

    @Test
    void generatesIdWhenHeaderMissing() {
        interceptor.intercept(record(null), null);
        assertThat(MDC.get("correlationId")).matches(UUID_PATTERN);
    }

    @Test
    void clearsMdcAfterRecord() {
        ConsumerRecord<Object, Object> record = record("corr-123");
        interceptor.intercept(record, null);
        interceptor.afterRecord(record, null);
        assertThat(MDC.get("correlationId")).isNull();
    }
}
