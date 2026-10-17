package com.paymesh.kafka;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import tools.jackson.core.JacksonException;

/**
 * What every listener does with a message it can't handle (Boot applies this handler to all of them):
 * 3 attempts with backoff (0.5 s, then 1 s) for transient failures, then the message is parked on
 * {@code <topic>.DLT} with the exception in its headers, and the partition moves on. Unparseable JSON
 * can never succeed, so it goes to the DLT on the first failure.
 */
@Configuration
public class KafkaErrorHandling {

    public static final String DLT_SUFFIX = ".DLT";

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(2);
        backOff.setInitialInterval(500);
        backOff.setMultiplier(2.0);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }
}
