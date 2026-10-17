-- Double-entry ledger (see com.paymesh.ledger). wallet.balance stays as a cached balance; postings explain it.

CREATE TABLE ledger_account (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL,
  user_id BIGINT DEFAULT NULL,
  created_at DATETIME(6) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ledger_account_code (code),
  UNIQUE KEY uk_ledger_account_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO ledger_account (code, created_at) VALUES
  ('PG_CLEARING', NOW(6)), ('BANK_PAYOUT_CLEARING', NOW(6)), ('BILLER_PAYABLE', NOW(6)), ('OPENING_BALANCE', NOW(6));

INSERT INTO ledger_account (code, user_id, created_at)
SELECT CONCAT('USER:', user_id), user_id, NOW(6) FROM wallet;

CREATE TABLE journal_entry (
  id BIGINT NOT NULL AUTO_INCREMENT,
  transaction_id VARCHAR(255) NOT NULL,
  type VARCHAR(32) NOT NULL,
  created_at DATETIME(6) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_journal_entry_txn (transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ledger_posting (
  id BIGINT NOT NULL AUTO_INCREMENT,
  entry_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  direction ENUM('CREDIT','DEBIT') NOT NULL,
  amount DECIMAL(19,2) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_ledger_posting_account (account_id),
  CONSTRAINT fk_ledger_posting_entry FOREIGN KEY (entry_id) REFERENCES journal_entry (id),
  CONSTRAINT fk_ledger_posting_account FOREIGN KEY (account_id) REFERENCES ledger_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Balances that existed before the ledger: one entry each, OPENING_BALANCE -> user.
INSERT INTO journal_entry (transaction_id, type, created_at)
SELECT CONCAT('opening-', wallet_id), 'OPENING_BALANCE', NOW(6) FROM wallet WHERE balance > 0;

INSERT INTO ledger_posting (entry_id, account_id, direction, amount)
SELECT e.id, (SELECT id FROM ledger_account WHERE code = 'OPENING_BALANCE'), 'DEBIT', w.balance
FROM wallet w JOIN journal_entry e ON e.transaction_id = CONCAT('opening-', w.wallet_id);

INSERT INTO ledger_posting (entry_id, account_id, direction, amount)
SELECT e.id, a.id, 'CREDIT', w.balance
FROM wallet w
JOIN journal_entry e ON e.transaction_id = CONCAT('opening-', w.wallet_id)
JOIN ledger_account a ON a.user_id = w.user_id;
