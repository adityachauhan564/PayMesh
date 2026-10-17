package com.paymesh.deadletter;

import com.paymesh.outbox.Outbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Like OutboxConfiguration: only services with a database keep dead letters (notification_service has none). */
@Configuration
@ConditionalOnClass(name = "jakarta.persistence.EntityManager")
public class DeadLetterConfiguration {

    @Bean
    public DeadLetterStore deadLetterStore(DeadLetterRepository repository, Outbox outbox) {
        return new DeadLetterStore(repository, outbox);
    }
}
