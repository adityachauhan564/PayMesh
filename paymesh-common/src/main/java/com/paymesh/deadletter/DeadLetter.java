package com.paymesh.deadletter;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A Kafka message this service gave up on (it reached {@code <topic>.DLT}), kept so it can be inspected and
 * replayed or discarded. Unique on its DLT position, so a redelivered DLT record is stored once.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "dead_letter", uniqueConstraints = @UniqueConstraint(name = "uk_dead_letter_position",
        columnNames = {"dlt_topic", "dlt_partition", "dlt_offset"}))
public class DeadLetter {

    public enum Status { NEW, REPLAYED, DISCARDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dlt_topic", nullable = false)
    private String dltTopic;

    @Column(name = "dlt_partition", nullable = false)
    private int dltPartition;

    @Column(name = "dlt_offset", nullable = false)
    private long dltOffset;

    @Column(name = "original_topic", nullable = false)
    private String originalTopic;

    @Column(name = "message_key")
    private String messageKey;

    /** 1 MiB -> MEDIUMTEXT, like the outbox payload it may be replayed into. */
    @Lob
    @Column(nullable = false, length = 1_048_576)
    private String payload;

    @Column(name = "exception_class")
    private String exceptionClass;

    @Column(name = "exception_message", length = 1000)
    private String exceptionMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    DeadLetter(String dltTopic, int dltPartition, long dltOffset, String originalTopic, String messageKey,
               String payload, String exceptionClass, String exceptionMessage) {
        this.dltTopic = dltTopic;
        this.dltPartition = dltPartition;
        this.dltOffset = dltOffset;
        this.originalTopic = originalTopic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.exceptionClass = exceptionClass;
        this.exceptionMessage = exceptionMessage;
        this.status = Status.NEW;
    }

    void markReplayed() {
        requireNew();
        status = Status.REPLAYED;
    }

    void markDiscarded() {
        requireNew();
        status = Status.DISCARDED;
    }

    private void requireNew() {
        if (status != Status.NEW) {
            throw new IllegalStateException("Dead letter " + id + " is already " + status);
        }
    }
}
