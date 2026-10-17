package com.paymesh.kafka;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/**
 * What every listener does with a message it can't handle (Boot applies this handler to all of them):
 * 3 attempts with backoff (0.5 s, then 1 s) for transient failures, then the message is parked on
 * {@code <topic>.DLT} with the exception in its headers, and the partition moves on. Unparseable JSON
 * can never succeed, so it goes to the DLT on the first failure.
 * <p>
 * DLT consumers (which store dead letters) are the exception: they retry every 2 s until they succeed, since
 * dead-lettering a dead letter would park it on a {@code .DLT.DLT} topic nobody reads. Only DLT partitions wait.
 */
@Configuration
public class KafkaErrorHandling {

    public static final String DLT_SUFFIX = ".DLT";

    private static final long DLT_CONSUMER_RETRY_MS = 2000;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(2);
        backOff.setInitialInterval(500);
        backOff.setMultiplier(2.0);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(JacksonException.class);
        handler.setBackOffFunction((record, exception) -> record.topic().endsWith(DLT_SUFFIX)
                ? new FixedBackOff(DLT_CONSUMER_RETRY_MS, FixedBackOff.UNLIMITED_ATTEMPTS)
                : null); // null = the exponential backoff above
        return handler;
    }
}
