package com.paymesh.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * A Kafka message waiting to be published. Written in the same transaction as the data change it describes,
 * deleted once Kafka has acknowledged it. All services share one database, so each row records its producer.
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

    OutboxEvent(String producer, String topic, String messageKey, String payload) {
        this.producer = producer;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
    }
}
