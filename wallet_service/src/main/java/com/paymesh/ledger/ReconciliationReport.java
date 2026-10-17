package com.paymesh.ledger;

import java.math.BigDecimal;
import java.util.List;

/** Result of comparing every cached wallet balance with its postings, and of checking all postings net to zero. */
public record ReconciliationReport(List<Mismatch> mismatches, BigDecimal netOfAllPostings) {

    public record Mismatch(long userId, BigDecimal walletBalance, BigDecimal ledgerBalance) {
        @Override
        public String toString() {
            return "user " + userId + ": wallet " + walletBalance.toPlainString() + ", ledger " + ledgerBalance.toPlainString()
                    + " (off by " + walletBalance.subtract(ledgerBalance).toPlainString() + ")";
        }
    }

    public boolean clean() {
        return mismatches.isEmpty() && netOfAllPostings.signum() == 0;
    }
}
