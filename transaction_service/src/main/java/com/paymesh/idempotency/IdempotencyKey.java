package com.paymesh.idempotency;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A response remembered under (user, Idempotency-Key), so a retried request gets it back instead of paying twice. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "idempotency_key")
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idem_key", nullable = false)
    private String idemKey;

    /** SHA-256 (hex) of the request, to tell a retry from a different request reusing the key. */
    @Column(name = "request_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String requestHash;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Lob
    @Column(name = "response_body", nullable = false, length = 1_048_576)
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    IdempotencyKey(long userId, String idemKey, String requestHash, int responseStatus, String responseBody) {
        this.userId = userId;
        this.idemKey = idemKey;
        this.requestHash = requestHash;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.createdAt = Instant.now();
    }
}
