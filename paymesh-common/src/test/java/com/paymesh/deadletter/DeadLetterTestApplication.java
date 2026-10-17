package com.paymesh.deadletter;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Boots the dead-letter store with the outbox it replays through, against a real database. */
@SpringBootApplication(scanBasePackages = {"com.paymesh.deadletter", "com.paymesh.outbox"})
@EntityScan(basePackages = {"com.paymesh.deadletter", "com.paymesh.outbox"})
@EnableJpaRepositories(basePackages = {"com.paymesh.deadletter", "com.paymesh.outbox"})
class DeadLetterTestApplication {
}
