package com.paymesh.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A Kafka message waiting to be published. Written in the same transaction as the data change it describes,
 * deleted once Kafka has acknowledged it. Each service has its own schema; the producer column is kept so a relay
 * never picks up another service's rows if schemas are ever shared again.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "outbox_event", indexes = @Index(name = "idx_outbox_producer_id", columnList = "producer, id"))
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String producer;

    @Column(nullable = false)
    private String topic;

    @Column(name = "message_key", nullable = false)
    private String messageKey;

    /** Without a length, Hibernate maps this to MySQL TINYTEXT (255 bytes): too small for an email. 1 MiB -> MEDIUMTEXT. */
    @Lob
    @Column(nullable = false, length = 1_048_576)
    private String payload;

    @CreationTimestamp
    private Instant createdAt;

    /** Sent as the eventId header; unique per event, so consumers can drop redeliveries. */
    @Column(name = "event_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String eventId;

    /** The request's correlation id when the event was written; null outside a request or consumed event. */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    OutboxEvent(String producer, String topic, String messageKey, String payload, String correlationId) {
        this.producer = producer;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.correlationId = correlationId;
        this.eventId = UUID.randomUUID().toString();
    }
}
