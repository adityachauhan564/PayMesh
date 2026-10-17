# Phase 3 — Double-Entry Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every successful payment writes a balanced journal entry. Each wallet's cached `balance` provably equals the sum of its postings, and a nightly reconciliation job checks that on one replica only.

**Architecture:**
- **Location:** a `com.paymesh.ledger` package in wallet_service.
- **Accounts:** `ledger_account` holds one account per user (code `USER:<id>`) plus system accounts: `PG_CLEARING`, `BANK_PAYOUT_CLEARING`, `BILLER_PAYABLE` and `OPENING_BALANCE`.
- **Entries:** `journal_entry` has one row per transaction, unique on `transaction_id`. `ledger_posting` holds the debit and credit lines.
- **Pure rules:** `LedgerEntries` turns a payment into lines, with no Spring and no database, so it can be property-tested.
- **Persistence:** the `Ledger` service saves those lines in the same transaction as the wallet's row-locked debit/credit.
- **Existing balances:** V4 posts an opening-balance entry for every wallet that already has money.
- **Reconciliation:** `ReconciliationService` compares cached balances with posting sums, and checks that all postings net to zero. It runs nightly under ShedLock.

**Tech Stack:** Spring Boot 4.1.1, MySQL 8.4 with Flyway, ShedLock 7.10.1 (`shedlock-spring` + `shedlock-provider-jdbc-template`), jqwik 1.10.2 (test).

**Spec:** `docs/superpowers/specs/2026-10-17-paymesh-production-design.md` §7.3

## Global Constraints

- **Money:** always `BigDecimal` with scale 2, and `DECIMAL(19,2)` in SQL. Never `double`.
- **Sign convention:** user accounts are credit-normal (balance = credits − debits). System accounts are just counterparties.
- **Every entry must balance:** Σ debits = Σ credits = the payment amount, with exactly 2 lines per payment.
- **Payment mapping (spec §7.3):**

| Type | Debit | Credit |
|---|---|---|
| TRANSFER | sender | receiver |
| ADD_MONEY | `PG_CLEARING` | user |
| WITHDRAW | user | `BANK_PAYOUT_CLEARING` |
| BILL_PAYMENT | user | `BILLER_PAYABLE` |
| opening balance (V4 only) | `OPENING_BALANCE` | user |

- **Rejected payments post nothing.** A redelivered payment posts nothing new, because the existing `ProcessedTransaction` check runs first and `journal_entry.transaction_id` is unique.
- **`journal_entry.type` is `VARCHAR(32)`, not a MySQL enum.** CASHBACK arrives in Phase 15 and must not need an ALTER.
- **Versions:** library versions not covered by the Boot BOM (ShedLock, jqwik) are pinned in `wallet_service/pom.xml` properties.
- **Commits:** commit and push on `phase-3-ledger`. Never add a `Co-Authored-By` line. Merge when CI is green.

## Review Focus

- **An old `INITIATE` event in flight with no `type`** (published before this deploy) must still be applied, with the type inferred: no sender means ADD_MONEY, no receiver means WITHDRAW. This is covered in Task 1, Step 1.
- **A wallet created before V4 that receives money after V4** must post against a user account backfilled by V4, with its opening balance intact. This is covered in Task 3, Step 1 (`openingBalancesAreBackfilled`) and in the random-sequence test.
- **A ledger account missing for a user** (wallet created by an event racing the migration) must be created on demand, not fail the payment. This is covered in Task 3, Step 1 (`postingCreatesAMissingUserAccount`).
- **Concurrent payments touching the same wallet** must still deadlock-free post balanced entries. Accounts are resolved after the wallets are locked in userId order; the existing lock order is unchanged. This is covered by the random-sequence test, which runs on 4 threads.
- **Reconciliation on a tampered balance** must name the user and the difference, not just say "mismatch". This is covered in Task 4, Step 1.

---

### Task 1: Payments carry their type

**Files:**
- Create: `paymesh-common/src/main/java/com/paymesh/enums/TransactionType.java`
- Modify: `paymesh-common/src/main/java/com/paymesh/dto/InitiateTransactionDto.java` (append `TransactionType type`)
- Modify: `transaction_service/src/main/java/com/paymesh/service/TransactionService.java` (set the type)
- Modify the 4-arg callers in tests: `wallet_service/.../WalletOperationsTest.java`, `wallet_service/.../WalletSagaIntegrationTest.java`
- Test: `paymesh-common/src/test/java/com/paymesh/enums/TransactionTypeTest.java`, `transaction_service/src/test/java/com/paymesh/service/TransactionEventTest.java`

**Interfaces:**
- Produces:
  - `public enum TransactionType { TRANSFER, ADD_MONEY, WITHDRAW, BILL_PAYMENT; public static TransactionType infer(Long fromUserId, Long toUserId) }`. `infer` returns ADD_MONEY when `from` is null, WITHDRAW when `to` is null, and TRANSFER otherwise.
  - `record InitiateTransactionDto(String transactionId, Long fromUserId, Long toUserId, BigDecimal amount, TransactionType type)`. `type` may be null on events published before this change.
  - `TransactionService.createTransaction` publishes `TransactionType.valueOf(saved.getType().name())`.

- [ ] **Step 1: Write the failing tests**
  - `TransactionTypeTest`:
    - `inferAddMoneyWithoutSender`: `infer(null, 5L)` is ADD_MONEY.
    - `inferWithdrawWithoutReceiver`: `infer(5L, null)` is WITHDRAW.
    - `inferTransferWithBoth`: `infer(5L, 6L)` is TRANSFER.
  - `TransactionEventTest` (Mockito, `@Mock TransactionRepository` whose `save` returns its argument with an id set, `@Mock Outbox`, `@InjectMocks TransactionService`):
    - `billPaymentEventCarriesItsType`: `createTransaction(1L, "Asha", new CreateTransactionRequest(...BILL_PAYMENT...))` captures `outbox.publish(eq(INITIATE_TRANSACTION_TOPIC), anyString(), captor)`, and `captor.getValue().type()` is `BILL_PAYMENT`. Read `CreateTransactionRequest`'s constructor for the argument order.

- [ ] **Step 2: Run them and confirm they fail**
  - Run: `./mvnw -B -q -pl transaction_service -am verify -Dtest='TransactionTypeTest,TransactionEventTest' -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: compile failure.

- [ ] **Step 3: Implement**
  - Add the enum, append the DTO field, set it in `createTransaction`, and give the test callers `null` or the right type.

- [ ] **Step 4: Run the tests again and confirm they pass**
  - Same command as Step 2. Expected: 4 passing.

- [ ] **Step 5: Commit**
  - `git commit -m "feat: INITIATE events carry the transaction type"`

---

### Task 2: Ledger rules as a pure function, property-tested

**Files:**
- Modify: `wallet_service/pom.xml` (add the property `jqwik.version` 1.10.2, and test dep `net.jqwik:jqwik`)
- Create: `wallet_service/src/main/java/com/paymesh/ledger/LedgerEntries.java`
- Test: `wallet_service/src/test/java/com/paymesh/ledger/LedgerEntriesProperties.java`

**Interfaces:**
- Produces:
  - `public final class LedgerEntries` with:
    - `public enum Direction { DEBIT, CREDIT }`
    - `public record Line(String accountCode, Direction direction, BigDecimal amount)`. `accountCode` is `USER:<id>` or a system code.
    - `public static String userAccount(long userId)`, which returns `"USER:" + userId`.
    - `public static final String PG_CLEARING = "PG_CLEARING", BANK_PAYOUT_CLEARING = "BANK_PAYOUT_CLEARING", BILLER_PAYABLE = "BILLER_PAYABLE", OPENING_BALANCE = "OPENING_BALANCE"`.
    - `public static List<Line> forPayment(TransactionType type, Long fromUserId, Long toUserId, BigDecimal amount)`. It returns the debit line first, then the credit line, following the mapping table.
  - It throws `IllegalArgumentException` when:
    - the amount is ≤ 0;
    - the type and parties disagree: TRANSFER needs both parties; ADD_MONEY needs only `to`; WITHDRAW and BILL_PAYMENT need only `from`;
    - it's a TRANSFER to oneself.

- [ ] **Step 1: Write the property tests** (`@Property(tries = 500)`, with `@ForAll` amounts from an `Arbitraries.bigDecimals().between(0.01, 1_000_000).ofScale(2)` provider)
  - `everyEntryBalances`: for each valid type and party combination, Σ debit = Σ credit = amount, and there are exactly 2 lines.
  - `usersMoveTheRightWay`: the sender's account only ever appears as DEBIT, and the receiver's account only ever appears as CREDIT.
  - `systemCounterpartyMatchesTheType`: ADD_MONEY → `PG_CLEARING` DEBIT; WITHDRAW → `BANK_PAYOUT_CLEARING` CREDIT; BILL_PAYMENT → `BILLER_PAYABLE` CREDIT.
  - `inconsistentPartiesAreRefused` (`@Example`s): TRANSFER with null `to`; ADD_MONEY with a sender; WITHDRAW without a sender; TRANSFER to self; amount 0. Each throws `IllegalArgumentException`.

- [ ] **Step 2: Run them and confirm they fail**
  - Run: `./mvnw -B -q -pl wallet_service -am verify -Dtest=LedgerEntriesProperties -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: compile failure.

- [ ] **Step 3: Implement `LedgerEntries`**

- [ ] **Step 4: Run them again and confirm they pass**
  - Same command as Step 2. Expected: PASS. jqwik runs on the JUnit Platform next to Jupiter.

- [ ] **Step 5: Commit**
  - `git commit -m "feat(ledger): posting rules as a pure, property-tested function"`

---

### Task 3: Persisted ledger, wired into every payment

**Files:**
- Create in `wallet_service/src/main/java/com/paymesh/ledger/`: `LedgerAccount.java`, `JournalEntry.java`, `LedgerPosting.java`, `LedgerAccountRepository.java`, `JournalEntryRepository.java`, `Ledger.java`
- Create: `wallet_service/src/main/resources/db/migration/V4__ledger.sql`
- Modify: `wallet_service/src/main/java/com/paymesh/service/WalletOperations.java`
- Modify: `wallet_service/src/test/java/com/paymesh/service/WalletOperationsTest.java` (add `@Mock Ledger ledger`)
- Modify: `wallet_service/src/test/java/com/paymesh/SchemaMigrationTest.java`
- Test: `wallet_service/src/test/java/com/paymesh/ledger/LedgerIntegrationTest.java`

**Interfaces:**
- Consumes `LedgerEntries.forPayment` / `userAccount` and the codes (Task 2), and `TransactionType` (Task 1).
- Produces:
  - **Entities:**
    - `LedgerAccount(id, code, userId nullable, createdAt)`
    - `JournalEntry(id, transactionId, type String, createdAt)`, with `@OneToMany(cascade = ALL)` to its postings
    - `LedgerPosting(id, entry, account, direction, amount)`, where `direction` is an `@Enumerated(STRING)` `LedgerEntries.Direction`
  - **`@Service public class Ledger`:**
    - `void openAccount(long userId)`: idempotent; skips if one exists.
    - `void post(String transactionId, TransactionType type, Long fromUserId, Long toUserId, BigDecimal amount)`: `MANDATORY` transaction. It resolves each line's account, creating a missing `USER:` account, and saves one `JournalEntry`.
    - `BigDecimal balanceOf(long userId)`: credits − debits, or 0 if there's no account.
  - **`WalletOperations` changes:**
    - `createWalletForNewUser` calls `ledger.openAccount(userId)` after saving the wallet.
    - `applyTransaction` calls `ledger.post(txn.transactionId(), type, txn.fromUserId(), txn.toUserId(), txn.amount())` only when `reason == null`, where `type = txn.type() != null ? txn.type() : TransactionType.infer(from, to)`.
- **V4 SQL**, in this order:
  1. Create `ledger_account`: `id` BIGINT AI PK, `code` VARCHAR(64) NOT NULL UNIQUE `uk_ledger_account_code`, `user_id` BIGINT NULL UNIQUE `uk_ledger_account_user`, `created_at` DATETIME(6).
  2. Insert the 4 system codes.
  3. Insert `USER:<user_id>` for every row in `wallet`.
  4. Create `journal_entry`: `id` BIGINT AI PK, `transaction_id` VARCHAR(255) NOT NULL UNIQUE `uk_journal_entry_txn`, `type` VARCHAR(32) NOT NULL, `created_at` DATETIME(6).
  5. Create `ledger_posting`: `id` BIGINT AI PK, `entry_id` BIGINT NOT NULL FK → journal_entry, `account_id` BIGINT NOT NULL FK → ledger_account, `direction` ENUM('CREDIT','DEBIT') NOT NULL, `amount` DECIMAL(19,2) NOT NULL, KEY `idx_ledger_posting_account (account_id)`.
  6. For every `wallet` with `balance > 0`: a `journal_entry('opening-<wallet_id>', 'OPENING_BALANCE', NOW(6))` plus 2 postings, DEBIT `OPENING_BALANCE` and CREDIT the user, both for `balance`.
  - Use `INSERT … SELECT` throughout; no procedural code.

- [ ] **Step 1: Write the failing tests**
  - **`WalletOperationsTest`:** add `@Mock Ledger ledger`.
    - `successfulPaymentIsPostedToTheLedger`: an ADD_MONEY to an existing wallet calls `verify(ledger).post("t-1", ADD_MONEY, null, 7L, amount)`.
    - `rejectedPaymentPostsNothing`: insufficient balance means `verify(ledger, never()).post(...)`.
    - `untypedLegacyEventIsInferred`: a DTO with `type` null and `from` null posts as ADD_MONEY.
  - **`SchemaMigrationTest` (wallet):** versions become `"1","2","3","4"`.
    - `openingBalancesAreBackfilled` (NOT_SUPPORTED, with the same clean→target pattern as Phase 2, `target("3")`): insert a wallet (`user_id` 900, `balance` 250.00, with the NOT NULL columns filled), migrate. Then `ledger_account` has `USER:900`, and `journal_entry` `opening-<wallet_id>` has postings DEBIT `OPENING_BALANCE` 250.00 and CREDIT `USER:900` 250.00.
  - **`LedgerIntegrationTest`:**
    - Setup: `@SpringBootTest(properties = {"jwt.secret=0123456789abcdef0123456789abcdef", "spring.kafka.listener.auto-startup=false", "outbox.poll-interval-ms=3600000"})`, plus `@MockitoBean KafkaTemplate<String,String>` and `MySqlTestContainer`.
    - `randomPaymentsKeepEveryBalanceEqualToItsPostings`:
      1. Create wallets for users 1..5 through `walletOperations.createWalletForNewUser`.
      2. Run 200 operations from `new Random(42)`, on 4 threads, through `walletOperations.applyTransaction`. Each operation is a random type with random parties and amounts from 1 to 3000. Some get rejected (insufficient balance, daily limits); raise every wallet's daily limits in setup with `updateLimits(id, 1_000_000, 1_000)` so most pass.
      3. Then, for every user, `walletRepository.findByUserId(u).getBalance()` equals `ledger.balanceOf(u)`. The global sum (Σ DEBIT − Σ CREDIT) over all postings is 0, and every `journal_entry` has exactly 2 postings with equal debit and credit sums (checked in SQL).
    - `redeliveryPostsOnce`: applying the same ADD_MONEY DTO twice gives one `journal_entry` for that id.
    - `postingCreatesAMissingUserAccount`: delete user 5's `ledger_account` row (with no postings), then apply an ADD_MONEY to user 5. It succeeds, and `ledger.balanceOf(5)` equals the amount.

- [ ] **Step 2: Run them and confirm they fail**
  - Run: `./mvnw -B -q -pl wallet_service -am verify -Dtest='WalletOperationsTest,SchemaMigrationTest,LedgerIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: compile failure (no `Ledger`).

- [ ] **Step 3: Implement**
  - Add the entities, repositories, `Ledger`, V4, and the `WalletOperations` wiring.

- [ ] **Step 4: Run the tests again and confirm they pass**
  - Same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**
  - `git commit -m "feat(ledger): every payment posts a balanced journal entry"`

---

### Task 4: Reconciliation, nightly under ShedLock

**Files:**
- Modify: `wallet_service/pom.xml` (add the property `shedlock.version` 7.10.1, plus deps `shedlock-spring` and `shedlock-provider-jdbc-template`)
- Create: `wallet_service/src/main/resources/db/migration/V5__shedlock.sql`. This is ShedLock's MySQL table: `shedlock(name VARCHAR(64) PK, lock_until TIMESTAMP(3) NOT NULL, locked_at TIMESTAMP(3) NOT NULL, locked_by VARCHAR(255) NOT NULL)`.
- Create: `wallet_service/src/main/java/com/paymesh/ledger/ReconciliationService.java`, `wallet_service/src/main/java/com/paymesh/config/SchedulerLockConfig.java`
- Modify: `wallet_service/src/main/resources/application.properties` (`ledger.reconcile-cron = ${LEDGER_RECONCILE_CRON:0 30 2 * * *}`)
- Modify: `wallet_service/src/test/java/com/paymesh/SchemaMigrationTest.java` (versions `"1"`..`"5"`)
- Test: add to `LedgerIntegrationTest`

**Interfaces:**
- Consumes the Task 3 tables.
- Produces:
  - `public record ReconciliationReport(List<Mismatch> mismatches, BigDecimal netOfAllPostings) { public boolean clean() }`.
  - `public record Mismatch(long userId, BigDecimal walletBalance, BigDecimal ledgerBalance)`, which states the difference in its `toString`.
  - `ReconciliationService.reconcile()` returns the report, using two native queries through `JdbcTemplate`: wallets LEFT JOIN their account's posting sums, `HAVING` a difference; and Σ(DEBIT) − Σ(CREDIT) over all postings.
  - `@Scheduled(cron = "${ledger.reconcile-cron}") @SchedulerLock(name = "ledger-reconcile", lockAtMostFor = "PT10M") void nightly()` logs `info` when clean, and `error` listing each mismatch and the net when not.
  - `SchedulerLockConfig`: `@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")` plus `@Bean LockProvider` = `new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().build())`.

- [ ] **Step 1: Write the failing tests (in `LedgerIntegrationTest`)**
  - `cleanLedgerReconciles`: after a few payments, `reconcile().clean()` is true and `netOfAllPostings` is 0.
  - `tamperedBalanceIsNamed`: `jdbc.update("UPDATE wallet SET balance = balance + 1 WHERE user_id = 3")` makes `reconcile()` return exactly one mismatch, with `userId` 3, and `walletBalance − ledgerBalance` equal to 1.00. Put the balance back afterwards.
  - `SchemaMigrationTest`: versions `"1"`..`"5"`.

- [ ] **Step 2: Run them and confirm they fail**
  - Run: `./mvnw -B -q -pl wallet_service -am verify -Dtest='LedgerIntegrationTest,SchemaMigrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: compile failure.

- [ ] **Step 3: Implement V5, the ShedLock config, `ReconciliationService` and the property**

- [ ] **Step 4: Run them again and confirm they pass**
  - Same command as Step 2. Expected: PASS.

- [ ] **Step 5: Run the whole suite**
  - Run: `./mvnw -B verify`
  - Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**
  - `git commit -m "feat(ledger): nightly reconciliation under ShedLock"`

---

### Task 5: README

- [ ] **Step 1: Update the README**
  - Add a "Double-entry ledger" section: why a balance column alone isn't auditable, the accounts, the mapping table, the sign convention, opening balances, and reconciliation (nightly, ShedLock, what a mismatch log looks like).
  - Data model: add `ledger_account`, `journal_entry` and `ledger_posting` to the ER diagram.
  - Kafka catalogue: the INITIATE payload gains `type`.
  - Design decisions:
    - "Double-entry ledger with a cached balance": O(1) reads, auditable, reconcilable.
    - "Ledger rules as a pure function": property-tested.
  - Configuration table: `LEDGER_RECONCILE_CRON`.

- [ ] **Step 2: Commit**
  - `git commit -m "docs: double-entry ledger"`

---

## Phase 3 done when

- `./mvnw -B verify` is green.
- CI is green.
- The final review's Critical and Important findings are fixed.
- The PR is merged.
