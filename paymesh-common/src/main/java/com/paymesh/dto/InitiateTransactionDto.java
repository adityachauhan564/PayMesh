package com.paymesh.dto;

import com.paymesh.enums.TransactionType;

import java.math.BigDecimal;

/**
 * fromUserId == null means money comes from outside (add money), toUserId == null means it leaves (withdraw, bills).
 * type is null on events published before it was added; see TransactionType.infer.
 */
public record InitiateTransactionDto(String transactionId, Long fromUserId, Long toUserId, BigDecimal amount,
                                     TransactionType type) {
}
