package com.paymesh.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Active in services that have JPA (user, wallet, transaction); notification_service has no database and skips it.
 * spring.application.name is required: it tells this service's events apart in the shared database.
 */
@Configuration
@ConditionalOnClass(name = "jakarta.persistence.EntityManager")
@EnableScheduling
public class OutboxConfiguration {

    @Bean
    public Outbox outbox(OutboxEventRepository repository, JsonMapper jsonMapper,
                         @Value("${spring.application.name}") String producer) {
        return new Outbox(repository, jsonMapper, producer);
    }

    @Bean
    public OutboxRelay outboxRelay(OutboxEventRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${spring.application.name}") String producer) {
        return new OutboxRelay(repository, kafkaTemplate, transactionManager, producer);
    }
}
