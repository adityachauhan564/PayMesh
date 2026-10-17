package com.paymesh.auth;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A single-use backup code for two-factor login (by hash). */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "recovery_code")
public class RecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "code_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String codeHash;

    @Column(name = "used_at")
    private Instant usedAt;

    RecoveryCode(long userId, String codeHash) {
        this.userId = userId;
        this.codeHash = codeHash;
    }

    void markUsed(Instant now) {
        usedAt = now;
    }
}
