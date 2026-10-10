package com.paymesh.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class WalletTest {

    private static final LocalDate DAY1 = LocalDate.of(2026, 1, 1);

    private static Wallet walletWith(String balance) {
        Wallet w = new Wallet(1L, "A", "a@x.com");
        w.credit(new BigDecimal(balance));
        return w;
    }

    @Test
    void debitReducesBalanceAndTracksUsage() {
        Wallet w = walletWith("500");
        w.debit(new BigDecimal("120.50"), DAY1);
        assertEquals(new BigDecimal("379.50"), w.getBalance());
        assertEquals(new BigDecimal("120.50"), w.spentOn(DAY1));
        assertEquals(1, w.txnCountOn(DAY1));
    }

    @Test
    void rejectsInsufficientBalanceWithoutChangingWallet() {
        Wallet w = walletWith("100");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> w.debit(new BigDecimal("100.01"), DAY1));
        assertEquals("Insufficient balance", e.getMessage());
        assertEquals(new BigDecimal("100"), w.getBalance());
        assertEquals(0, w.txnCountOn(DAY1));
    }

    @Test
    void enforcesDailyAmountLimitAndResetsNextDay() {
        Wallet w = walletWith("50000");
        w.debit(new BigDecimal("9000"), DAY1);
        assertThrows(IllegalStateException.class, () -> w.debit(new BigDecimal("1000.01"), DAY1));
        w.debit(new BigDecimal("1000"), DAY1); // exactly at the 10000 limit is allowed
        w.debit(new BigDecimal("5000"), DAY1.plusDays(1));
        assertEquals(new BigDecimal("5000"), w.spentOn(DAY1.plusDays(1)));
    }

    @Test
    void enforcesDailyTransactionCount() {
        Wallet w = walletWith("1000");
        w.setDailyTransactionLimit(2);
        w.debit(BigDecimal.ONE, DAY1);
        w.debit(BigDecimal.ONE, DAY1);
        assertThrows(IllegalStateException.class, () -> w.debit(BigDecimal.ONE, DAY1));
        assertEquals(new BigDecimal("998"), w.getBalance());
    }
}
