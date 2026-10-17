package com.paymesh.ledger;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** One side of a journal entry: an amount debited or credited to one account. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "ledger_posting")
public class LedgerPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    private JournalEntry entry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private LedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LedgerEntries.Direction direction;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    LedgerPosting(JournalEntry entry, LedgerAccount account, LedgerEntries.Direction direction, BigDecimal amount) {
        this.entry = entry;
        this.account = account;
        this.direction = direction;
        this.amount = amount;
    }
}
