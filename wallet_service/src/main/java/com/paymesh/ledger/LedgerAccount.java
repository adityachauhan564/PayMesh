package com.paymesh.ledger;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** A user's wallet account (code USER:id) or a system account (PG_CLEARING, ...). */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "ledger_account")
public class LedgerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "user_id", unique = true)
    private Long userId;

    @CreationTimestamp
    private Instant createdAt;

    static LedgerAccount forUser(long userId) {
        LedgerAccount account = new LedgerAccount();
        account.code = LedgerEntries.userAccount(userId);
        account.userId = userId;
        return account;
    }
}
