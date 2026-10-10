package com.paymesh.dto;

import com.paymesh.enums.PaymentStatus;

public record TransactionResultDto(String transactionId, PaymentStatus paymentStatus, String reason,
                                   String fromName, String toName) {
}
