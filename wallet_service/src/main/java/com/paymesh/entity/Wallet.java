package com.paymesh.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@Table(name = "wallet")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long walletId;

    @Column(nullable = false, unique = true)
    private Long userId;

    private String ownerName;

    private String ownerEmail;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyLimit = new BigDecimal("10000");

    @Column(nullable = false)
    private Integer dailyTransactionLimit = 10;

    /** Debit usage for usageDate; reset lazily on the first debit of a new day. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal spentToday = BigDecimal.ZERO;

    @Column(nullable = false)
    private Integer txnCountToday = 0;

    private LocalDate usageDate;

    @CreationTimestamp
    private LocalDateTime walletCreationTime;

    @UpdateTimestamp
    private LocalDateTime walletUpdationTime;

    public Wallet(Long userId, String ownerName, String ownerEmail) {
        this.userId = userId;
        this.ownerName = ownerName;
        this.ownerEmail = ownerEmail;
    }

    /** Throws IllegalStateException with a user-facing reason; leaves the wallet untouched on failure. */
    public void debit(BigDecimal amount, LocalDate today) {
        if (!today.equals(usageDate)) {
            usageDate = today;
            spentToday = BigDecimal.ZERO;
            txnCountToday = 0;
        }
        if (balance.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient balance");
        }
        if (spentToday.add(amount).compareTo(dailyLimit) > 0) {
            throw new IllegalStateException("Daily limit of Rs. " + dailyLimit.toPlainString() + " exceeded");
        }
        if (txnCountToday >= dailyTransactionLimit) {
            throw new IllegalStateException("Daily limit of " + dailyTransactionLimit + " transactions reached");
        }
        balance = balance.subtract(amount);
        spentToday = spentToday.add(amount);
        txnCountToday++;
    }

    public void credit(BigDecimal amount) {
        balance = balance.add(amount);
    }

    public BigDecimal spentOn(LocalDate day) {
        return day.equals(usageDate) ? spentToday : BigDecimal.ZERO;
    }

    public int txnCountOn(LocalDate day) {
        return day.equals(usageDate) ? txnCountToday : 0;
    }
}
