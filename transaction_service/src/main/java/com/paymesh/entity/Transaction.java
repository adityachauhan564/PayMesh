package com.paymesh.entity;

import jakarta.persistence.*;
import lombok.*;
import com.paymesh.enums.PaymentStatus;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@ToString
@Table(name = "wallet_transactions", indexes = {
        @Index(columnList = "senderId"),
        @Index(columnList = "receiverId")
})
public class Transaction {

    public enum Type { TRANSFER, ADD_MONEY, WITHDRAW, BILL_PAYMENT }

    public enum BillCategory { MOBILE_RECHARGE, DTH, ELECTRICITY, WATER, GAS, BROADBAND }

    @Id
    @UuidGenerator
    private String transactionId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Type type;

    /** null for ADD_MONEY (money comes from bank). */
    private Long senderId;

    /** null for WITHDRAW and BILL_PAYMENT (money leaves the wallet system). */
    private Long receiverId;

    private String senderName;

    private String receiverName;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    private String remark;

    private String failureReason;

    @Enumerated(EnumType.STRING)
    private BillCategory billCategory;

    private String billAccount;

    @CreationTimestamp
    private LocalDateTime paymentInitiationDate;

    @UpdateTimestamp
    private LocalDateTime paymentUpdationDate;
}
