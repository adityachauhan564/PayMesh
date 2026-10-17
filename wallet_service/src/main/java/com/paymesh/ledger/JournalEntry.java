package com.paymesh.ledger;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One payment in the ledger: its balanced postings. Unique per transaction, so a payment can't post twice. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "journal_entry")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private String transactionId;

    /** A string, not a MySQL enum: new payment types must not need a schema change. */
    @Column(nullable = false, length = 32)
    private String type;

    @CreationTimestamp
    private Instant createdAt;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.ALL)
    private List<LedgerPosting> postings = new ArrayList<>();

    JournalEntry(String transactionId, String type) {
        this.transactionId = transactionId;
        this.type = type;
    }

    void add(LedgerAccount account, LedgerEntries.Direction direction, java.math.BigDecimal amount) {
        postings.add(new LedgerPosting(this, account, direction, amount));
    }
}
