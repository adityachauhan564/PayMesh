package com.paymesh.ledger;

import com.paymesh.enums.TransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Writes payments to the double-entry ledger. Called inside the wallet's own transaction, after the wallet rows
 * are locked, so the postings commit (or roll back) together with the cached balance they explain.
 */
@Service
public class Ledger {

    private final LedgerAccountRepository accounts;
    private final JournalEntryRepository entries;

    public Ledger(LedgerAccountRepository accounts, JournalEntryRepository entries) {
        this.accounts = accounts;
        this.entries = entries;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void openAccount(long userId) {
        if (!accounts.existsByUserId(userId)) {
            accounts.save(LedgerAccount.forUser(userId));
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void post(String transactionId, TransactionType type, Long fromUserId, Long toUserId, BigDecimal amount) {
        JournalEntry entry = new JournalEntry(transactionId, type.name());
        for (LedgerEntries.Line line : LedgerEntries.forPayment(type, fromUserId, toUserId, amount)) {
            entry.add(account(line.accountCode()), line.direction(), line.amount());
        }
        entries.save(entry);
    }

    public BigDecimal balanceOf(long userId) {
        return accounts.balanceOfUser(userId);
    }

    /** System accounts are seeded by V4; a missing user account (wallet event raced the migration) is created. */
    private LedgerAccount account(String code) {
        return accounts.findByCode(code).orElseGet(() -> {
            if (!code.startsWith("USER:")) {
                throw new IllegalStateException("System ledger account " + code + " is missing");
            }
            return accounts.save(LedgerAccount.forUser(Long.parseLong(code.substring("USER:".length()))));
        });
    }
}
