package com.paymesh.ledger;

import com.paymesh.enums.TransactionType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The posting rules: which accounts a payment debits and credits. Pure (no Spring, no database) so the
 * invariants can be property-tested. Every payment is exactly one debit and one credit of the same amount.
 * User accounts are credit-normal: a user's balance is their credits minus their debits.
 */
public final class LedgerEntries {

    public enum Direction { DEBIT, CREDIT }

    public record Line(String accountCode, Direction direction, BigDecimal amount) {
    }

    /** Money arriving from the payment gateway (add money). */
    public static final String PG_CLEARING = "PG_CLEARING";
    /** Money owed out to users' banks (withdrawals). */
    public static final String BANK_PAYOUT_CLEARING = "BANK_PAYOUT_CLEARING";
    /** Money owed to billers (bill payments). */
    public static final String BILLER_PAYABLE = "BILLER_PAYABLE";
    /** Counterpart of balances that existed before the ledger (V4 migration). */
    public static final String OPENING_BALANCE = "OPENING_BALANCE";

    private LedgerEntries() {
    }

    public static String userAccount(long userId) {
        return "USER:" + userId;
    }

    /** Debit line first, then credit. Throws IllegalArgumentException if the parties don't fit the type. */
    public static List<Line> forPayment(TransactionType type, Long fromUserId, Long toUserId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
        boolean hasFrom = fromUserId != null;
        boolean hasTo = toUserId != null;
        return switch (type) {
            case TRANSFER -> {
                require(hasFrom && hasTo && !Objects.equals(fromUserId, toUserId), type, fromUserId, toUserId);
                yield lines(userAccount(fromUserId), userAccount(toUserId), amount);
            }
            case ADD_MONEY -> {
                require(!hasFrom && hasTo, type, fromUserId, toUserId);
                yield lines(PG_CLEARING, userAccount(toUserId), amount);
            }
            case WITHDRAW -> {
                require(hasFrom && !hasTo, type, fromUserId, toUserId);
                yield lines(userAccount(fromUserId), BANK_PAYOUT_CLEARING, amount);
            }
            case BILL_PAYMENT -> {
                require(hasFrom && !hasTo, type, fromUserId, toUserId);
                yield lines(userAccount(fromUserId), BILLER_PAYABLE, amount);
            }
        };
    }

    private static List<Line> lines(String debit, String credit, BigDecimal amount) {
        return List.of(new Line(debit, Direction.DEBIT, amount), new Line(credit, Direction.CREDIT, amount));
    }

    private static void require(boolean ok, TransactionType type, Long from, Long to) {
        if (!ok) {
            throw new IllegalArgumentException(type + " can't move money from " + from + " to " + to);
        }
    }
}
