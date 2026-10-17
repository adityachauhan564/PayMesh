package com.paymesh.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
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
 * spring.application.name is required: it is written to, and filtered on, every outbox row (see OutboxEvent).
 */
@Configuration
@ConditionalOnClass(name = "jakarta.persistence.EntityManager")
@EnableScheduling
public class OutboxConfiguration {

    /** Events still waiting to be published: a growing number means Kafka (or the relay) is behind. */
    @Bean
    Gauge outboxBacklog(MeterRegistry registry, OutboxEventRepository repository, @Value("${spring.application.name}") String producer) {
        return Gauge.builder("paymesh.outbox.backlog", repository, r -> r.countByProducer(producer))
                .description("Outbox events not yet published to Kafka").register(registry);
    }

    @Bean
    OutboxTracing outboxTracing(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        return new OutboxTracing(tracer, propagator);
    }

    @Bean
    public Outbox outbox(OutboxEventRepository repository, JsonMapper jsonMapper,
                         @Value("${spring.application.name}") String producer, OutboxTracing tracing) {
        return new Outbox(repository, jsonMapper, producer, tracing);
    }

    @Bean
    public OutboxRelay outboxRelay(OutboxEventRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${spring.application.name}") String producer, OutboxTracing tracing) {
        return new OutboxRelay(repository, kafkaTemplate, transactionManager, producer, tracing);
    }
}
