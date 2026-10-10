package com.paymesh.dto;

import java.math.BigDecimal;

/** fromUserId == null means money comes from outside (add money), toUserId == null means it leaves (withdraw, bills). */
public record InitiateTransactionDto(String transactionId, Long fromUserId, Long toUserId, BigDecimal amount) {
}
