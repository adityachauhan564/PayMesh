package com.paymesh.dto;

import jakarta.validation.constraints.*;
import com.paymesh.entity.Transaction;

import java.math.BigDecimal;

/** receiverId is required for TRANSFER, billCategory + billAccount for BILL_PAYMENT. */
public record CreateTransactionRequest(
        @NotNull Transaction.Type type,
        @NotNull @DecimalMin("1.00") @DecimalMax("100000") @Digits(integer = 6, fraction = 2) BigDecimal amount,
        Long receiverId,
        Transaction.BillCategory billCategory,
        @Size(max = 30) String billAccount,
        @Size(max = 100) String remark) {
}
