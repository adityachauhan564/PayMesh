package com.paymesh.enums;

public enum PaymentStatus {
    SUCCESSFUL, PENDING, REJECTED;

    /** A final status is never changed again: late or redelivered results for it are ignored. */
    public boolean isFinal() {
        return this != PENDING;
    }
}
