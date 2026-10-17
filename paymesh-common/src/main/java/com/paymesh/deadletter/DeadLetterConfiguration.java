package com.paymesh.deadletter;

import com.paymesh.outbox.Outbox;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Like OutboxConfiguration: only services with a database keep dead letters (notification_service has none). */
@Configuration
@ConditionalOnClass(name = "jakarta.persistence.EntityManager")
public class DeadLetterConfiguration {

    /** Messages given up on and not yet replayed or discarded: anything above zero needs a look (alert). */
    @Bean
    Gauge newDeadLetters(MeterRegistry registry, DeadLetterRepository repository) {
        return Gauge.builder("paymesh.deadletters.new", repository, r -> r.countByStatus(DeadLetter.Status.NEW))
                .description("Dead letters waiting for replay or discard").register(registry);
    }

    @Bean
    public DeadLetterStore deadLetterStore(DeadLetterRepository repository, Outbox outbox) {
        return new DeadLetterStore(repository, outbox);
    }
}
