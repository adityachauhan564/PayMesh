package com.paymesh.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Events published before INITIATE carried a type still have to be applied correctly. */
class TransactionTypeTest {

    @Test
    void inferAddMoneyWithoutSender() {
        assertThat(TransactionType.infer(null, 5L)).isEqualTo(TransactionType.ADD_MONEY);
    }

    @Test
    void inferWithdrawWithoutReceiver() {
        assertThat(TransactionType.infer(5L, null)).isEqualTo(TransactionType.WITHDRAW);
    }

    @Test
    void inferTransferWithBoth() {
        assertThat(TransactionType.infer(5L, 6L)).isEqualTo(TransactionType.TRANSFER);
    }
}
