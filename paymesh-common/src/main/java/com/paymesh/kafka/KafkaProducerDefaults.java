package com.paymesh.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.ProducerListener;

import java.time.Duration;
import java.util.Map;

/**
 * Producer settings shared by every service (picked up by component scanning, like SecurityConfig).
 * KafkaTemplate.send() waits on the caller's thread for broker metadata, by default up to 60 s. The only caller is
 * OutboxRelay (requests never touch Kafka), and during an outage this keeps each of its retries short instead of
 * stalling the relay for a minute; the event simply stays in the outbox until the next attempt.
 * A service can still choose its own value via spring.kafka.producer.properties.max.block.ms.
 */
@Configuration
public class KafkaProducerDefaults {

    /** Long enough for a healthy broker to auto-create a topic on its first ever send. */
    private static final Duration MAX_BLOCK = Duration.ofSeconds(5);

    @Bean
    public DefaultKafkaProducerFactoryCustomizer shortProducerBlocking() {
        return factory -> {
            if (!factory.getConfigurationProperties().containsKey(ProducerConfig.MAX_BLOCK_MS_CONFIG)) {
                factory.updateConfigs(Map.of(ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK.toMillis()));
            }
        };
    }

    /**
     * Replaces Boot's default listener, which logs each failed message's key and payload: here those are
     * names, emails and amounts. Topic, partition and error are enough to diagnose a failed send.
     */
    @Bean
    public ProducerListener<Object, Object> kafkaProducerListener() {
        LoggingProducerListener<Object, Object> listener = new LoggingProducerListener<>();
        listener.setIncludeContents(false);
        return listener;
    }
}
