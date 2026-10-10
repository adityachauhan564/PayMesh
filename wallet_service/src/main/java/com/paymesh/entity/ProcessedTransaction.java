package com.paymesh.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.paymesh.dto.TransactionResultDto;
import com.paymesh.enums.PaymentStatus;

/** Idempotency record: Kafka may redeliver, a transaction must be applied to wallets exactly once. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "wallet_processed_transaction")
public class ProcessedTransaction {

    @Id
    private String transactionId;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    private String reason;

    private String fromName;

    private String toName;

    public ProcessedTransaction(TransactionResultDto r) {
        this.transactionId = r.transactionId();
        this.status = r.paymentStatus();
        this.reason = r.reason();
        this.fromName = r.fromName();
        this.toName = r.toName();
    }
}
