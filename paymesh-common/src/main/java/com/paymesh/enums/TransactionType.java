package com.paymesh.enums;

/** What a payment is, as the wallet needs to know it to post the right ledger accounts. */
public enum TransactionType {
    TRANSFER, ADD_MONEY, WITHDRAW, BILL_PAYMENT;

    /**
     * For INITIATE events published before they carried a type. Bills and withdrawals both leave the wallet
     * system, so an untyped outgoing payment counts as a withdrawal.
     */
    public static TransactionType infer(Long fromUserId, Long toUserId) {
        if (fromUserId == null) {
            return ADD_MONEY;
        }
        return toUserId == null ? WITHDRAW : TRANSFER;
    }
}
