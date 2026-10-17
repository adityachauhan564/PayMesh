# Phase 4 — Idempotency Keys Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retrying `POST /api/transactions` with the same `Idempotency-Key` never creates a second payment, whether the retry comes from a double click, a network timeout, or concurrent duplicates.

**Architecture:**
- **Where the key is recorded:** an `idempotency_key` table in `txn_db` (V4), unique on `(user_id, idem_key)`. The key row is inserted **in the same database transaction** as the payment and its outbox event.
- **Concurrent duplicates:** a duplicate request blocks on the unique index until the first commits, then hits a duplicate-key error and replays the stored response. Without a key, behaviour is unchanged.
- **Expiry:** rows older than 24 h are deleted hourly. The delete is idempotent, so every replica may run it and no lock is needed.
- **UI:** the web app sends a key per payment attempt and keeps it across network-error retries.

**Tech Stack:** Spring Boot 4.1.1 (webmvc), MySQL with Flyway, Angular 21.

**Spec:** `docs/superpowers/specs/2026-10-17-paymesh-production-design.md` §7.4

## Global Constraints

- **Header name:** `Idempotency-Key`, 1–255 characters. Anything longer is a 400 ProblemDetail.
- **Same key, same user, same body:** replay the stored status (202) and body byte for byte, plus the response header `Idempotent-Replayed: true`.
- **Same key, different body:** 422 ProblemDetail with detail `"Idempotency-Key was already used with a different request"`.
- **Body comparison:** SHA-256 (hex) of the request re-serialised with the context's `JsonMapper`. Field order and whitespace don't matter.
- **Scope:** keys are per user. Two users using the same key never collide.
- **What gets stored:** only successful creations (202). Validation failures aren't stored and can be retried with the same key.
- **Retention:** 24 h (`idempotency.retention`, default `PT24H`), cleaned by `@Scheduled(fixedDelayString = "PT1H")`.
- **Commits:** on `phase-4-idempotency-keys`; never add a `Co-Authored-By` line; merge when CI is green.

## Review Focus

- **Ten identical requests in parallel** create exactly one payment, and all ten responses carry the same `transactionId`. This is covered in Task 1 (`concurrentDuplicatesCreateOnePayment`).
- **A retry after the first attempt failed validation (400)** must be processed, not replayed as 400. This is covered in Task 1 (`failedValidationIsNotRemembered`).
- **A replay must not publish a second INITIATE event** (the outbox row count stays 1). This is covered in Task 1 (`sameKeySameBodyReplays`).
- **The UI must keep the key across a network failure and change it after any answer from the server.** This is covered in Task 2.
- **An expired key behaves as new.** This is covered in Task 1 (`expiredKeysAreDeleted`).

---

### Task 1: Idempotent payment creation

**Files:**
- Create: `transaction_service/src/main/resources/db/migration/V4__idempotency_key.sql`
- Create in `transaction_service/src/main/java/com/paymesh/idempotency/`: `IdempotencyKey.java` (entity), `IdempotencyKeyRepository.java`, `IdempotentPayments.java`
- Modify: `transaction_service/src/main/java/com/paymesh/controller/TransactionController.java`
- Modify: `transaction_service/src/main/resources/application.properties` (`idempotency.retention = ${IDEMPOTENCY_RETENTION:PT24H}`)
- Modify: `transaction_service/src/test/java/com/paymesh/SchemaMigrationTest.java` (versions `"1"`..`"4"`)
- Test: `transaction_service/src/test/java/com/paymesh/idempotency/IdempotencyIntegrationTest.java`

**Interfaces:**
- **V4:** `idempotency_key`:
  - `id` BIGINT AI PK
  - `user_id` BIGINT NOT NULL
  - `idem_key` VARCHAR(255) NOT NULL
  - `request_hash` CHAR(64) NOT NULL
  - `response_status` INT NOT NULL
  - `response_body` MEDIUMTEXT NOT NULL
  - `created_at` DATETIME(6) NOT NULL
  - UNIQUE `uk_idempotency_user_key (user_id, idem_key)`, KEY `idx_idempotency_created (created_at)`
- **`IdempotentPayments`** (`@Service`):
  - `public record Response(int status, String body, boolean replayed)`
  - `@Transactional public Response create(long userId, String userName, String key, CreateTransactionRequest request)`:
    1. If a stored row exists for `(userId, key)` and its hash matches, return it with `replayed = true`. If the hash differs, throw `ResponseStatusException(422, …)`.
    2. Otherwise call `transactionService.createTransaction(...)`, serialise the result with `JsonMapper`, `saveAndFlush` the key row, and return `Response(202, body, false)`.
  - `public Response replayAfterRace(long userId, String key, CreateTransactionRequest request)`: a fresh read-only lookup used after a `DataIntegrityViolationException`, with the same hash rule.
  - `@Scheduled(fixedDelayString = "PT1H") @Transactional public int deleteExpired()`: deletes rows with `created_at < now − retention` and returns the count.
- **Controller:** `create(..., @RequestHeader(name = "Idempotency-Key", required = false) String key)`:
  - With no key, it works exactly as today.
  - A key longer than 255 characters (or blank) gives 400.
  - Otherwise it calls `idempotentPayments.create`. On `DataIntegrityViolationException` it calls `replayAfterRace`.
  - It returns `ResponseEntity.status(r.status()).contentType(APPLICATION_JSON)`, adding the header `Idempotent-Replayed: true` when replayed, with the body `r.body()`.

- [ ] **Step 1: Write the failing tests in `IdempotencyIntegrationTest`**
  - **Setup:** `@SpringBootTest(properties = {"jwt.secret=…32+…", "spring.kafka.listener.auto-startup=false", "outbox.poll-interval-ms=3600000"})` plus `@AutoConfigureMockMvc`, `MySqlTestContainer`, and `@MockitoBean KafkaTemplate`. Requests use `jwt().jwt(j -> j.subject("<id>").claim("name","Asha"))`. The body is `{"type":"ADD_MONEY","amount":100}`.
  - **Tests:**
    - `sameKeySameBodyReplays`: two POSTs with key `k1` both return 202 with an equal `transactionId`. The second has `Idempotent-Replayed: true`. The DB has 1 `wallet_transactions` row and 1 INITIATE `outbox_event` row for that user's payment.
    - `sameKeyDifferentBodyIs422`: the second POST, with amount 200, returns 422 and a ProblemDetail `detail` containing `"different request"`.
    - `bodyComparisonIgnoresFieldOrder`: `{"amount":100,"type":"ADD_MONEY"}` replays the earlier `{"type":"ADD_MONEY","amount":100}` with the same key.
    - `concurrentDuplicatesCreateOnePayment`: 10 threads POST the same key at once. All return 202 with a single distinct `transactionId`, and the DB has 1 payment for that key's user.
    - `noKeyBehavesAsBefore`: two POSTs without a key create 2 payments.
    - `keysAreScopedPerUser`: users 7 and 8 with the same key get 2 different payments.
    - `failedValidationIsNotRemembered`: a POST with `amount` 0 and key `k9` returns 400. The same key with a valid body then returns 202, without the replayed header.
    - `overlongKeyIs400`: a 256-character key returns 400.
    - `expiredKeysAreDeleted`: insert a row through JDBC with `created_at = NOW(6) - INTERVAL 25 HOUR` and one with `NOW(6)`. `deleteExpired()` returns 1, and the fresh row remains.

- [ ] **Step 2: Run them and confirm they fail**
  - Run: `./mvnw -B -q -pl transaction_service -am verify -Dtest='IdempotencyIntegrationTest,SchemaMigrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: compile failure (no `IdempotentPayments`).

- [ ] **Step 3: Implement V4, the entity, repository, service, controller change and property**

- [ ] **Step 4: Run them again and confirm they pass**
  - Same command as Step 2. Expected: PASS.

- [ ] **Step 5: Run the module's whole suite**
  - Run: `./mvnw -B -pl transaction_service -am verify`
  - Expected: BUILD SUCCESS. `TransactionControllerTest` (`@WebMvcTest`) needs `@MockitoBean IdempotentPayments`.

- [ ] **Step 6: Commit**
  - `git commit -m "feat(txn): Idempotency-Key on payment creation"`

---

### Task 2: The web app sends a key per payment attempt

**Files:**
- Modify: `wallet-ui/src/app/api.ts`: `createTxn(body: object, idempotencyKey: string)` sends the header.
- Modify: `wallet-ui/src/app/pages/pay.ts`
- Test: `wallet-ui/src/app/api.spec.ts`

**Interfaces:**
- `pay.ts` keeps `private idempotencyKey = crypto.randomUUID()`.
  - It is used for every submit.
  - It is replaced with a new UUID after a successful creation, or after an error whose HTTP status is 400–499.
  - It is kept after status 0 or 5xx, which means "maybe it got through": a retry then replays instead of paying twice.

- [ ] **Step 1: Write the failing test** `createTxn sends the Idempotency-Key header`: `api.createTxn({}, 'k-1')` gives `req.request.headers.get('Idempotency-Key')` = `'k-1'`.

- [ ] **Step 2: Run it and confirm it fails**
  - Run: `cd wallet-ui && npx ng test --watch=false`
  - Expected: a compile/type error or a FAIL.

- [ ] **Step 3: Implement `api.ts` and `pay.ts`**

- [ ] **Step 4: Run the tests and the build**
  - Run: `npx ng test --watch=false && npx ng build`
  - Expected: 8 tests passing, and the build succeeds.

- [ ] **Step 5: Commit**
  - `git commit -m "feat(ui): one Idempotency-Key per payment attempt"`

---

### Task 3: README

- [ ] **Step 1: Update the README**
  - API reference: the `Idempotency-Key` header on `POST /api/transactions`, with replay, 422 and the `Idempotent-Replayed` header.
  - A curl example.
  - Design decisions row: "Idempotency key in the payment's own transaction": concurrent duplicates serialise on the unique index; no lock service.
  - Configuration: `IDEMPOTENCY_RETENTION`.

- [ ] **Step 2: Commit**
  - `git commit -m "docs: idempotency keys"`
