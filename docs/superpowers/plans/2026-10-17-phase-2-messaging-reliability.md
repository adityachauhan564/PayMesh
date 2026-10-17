# Phase 2 — Messaging Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** No Kafka message is silently dropped or applied twice, and every message can be traced back to its request.
- Retries use backoff, and messages that still fail go to `<topic>.DLT`.
- A dead-letter store can replay them.
- Every consumer is idempotent.
- The transaction status is a guarded state machine.
- The correlation ID crosses Kafka.

**Architecture:**
- **Envelope:** every outbox row gets an `event_id` (a UUID) and a `correlation_id` (taken from the MDC when it's written). The relay sends both as Kafka headers.
- **Correlation on the consumer side:** a `RecordInterceptor` puts the header back into the MDC, so the saga's next outbox write carries it on.
- **Error handling:** one `DefaultErrorHandler` in `paymesh-common` retries 3 times with exponential backoff and skips retries for bad JSON. It then publishes to `<topic>.DLT`.
- **Dead-letter store:** wallet and transaction consume their own DLTs into a `dead_letter` table (in `paymesh-common`, JPA-only like the outbox), from which messages can be replayed through the outbox. The admin endpoints for this come in Phase 14.
- **Notifications:** deduplicated on `eventId` in Redis.

**Tech Stack:** Spring Kafka (Boot 4.0.8 BOM), Testcontainers `testcontainers-kafka` (`org.testcontainers.kafka.KafkaContainer`, image `apache/kafka:3.8.0`), MySQL and Redis Testcontainers, Awaitility (comes with the Boot test starter).

**Spec:** `docs/superpowers/specs/2026-10-17-paymesh-production-design.md` §7.2

## Global Constraints

- **Versions:** Java 21, Boot 4.0.8 and Spring Cloud 2025.1.3 stay as they are. New libraries come only from the Boot/Testcontainers BOMs, with no explicit versions.
- **DLT naming is `<originalTopic>.DLT`.** Set it explicitly; don't rely on Spring Kafka's default suffix.
- **Retries:** 3 attempts in total (2 retries), with backoff starting at 500 ms and multiplier 2. `tools.jackson.core.JacksonException` is not retried.
- **Header names:** `eventId` and `correlationId`, defined once in `com.paymesh.kafka.EventHeaders`.
- **Schema changes:** only through new Flyway migrations (`V2__…`, `V3__…`). Never edit a V1.
- **Logging:** payloads are never logged. The existing rule in `OutboxRelay` and `KafkaProducerDefaults` applies to every new log line.
- **Commits:** commit and push on `phase-2-messaging-reliability`, which stacks on `phase-1-data-foundations`. Never add a `Co-Authored-By` line.
- **Tests:** the suite stays green. The final count must equal 133 plus this phase's new tests.

## Review Focus

- **A poison message must not block its partition.** A good message sent after it, on the same topic, must still be processed. This is covered in Task 3, Step 1 (`poisonDoesNotBlockLaterMessages`).
- **Outbox rows written before V2 existed** (pending during a deploy) must get an `event_id` and still publish. This is covered in Task 1, Step 1 (`rowsWithoutEventIdGetOne`).
- **A forged `correlationId` header** (newlines, 500 characters) must be replaced, never logged as it is. This is covered in Task 2, Step 1.
- **Redis down in notification_service:** the mail must still go out (fail-open) and must never be lost silently. This is covered in Task 6, Step 1 (`redisDownStillSends`).
- **A late or duplicate COMPLETED result** for a transaction that is already final must not flip its status. This is covered in Task 5, Step 1.

---

### Task 1: Outbox events carry `eventId` and `correlationId`

**Files:**
- Create: `paymesh-common/src/main/java/com/paymesh/kafka/EventHeaders.java`
- Modify: `paymesh-common/src/main/java/com/paymesh/outbox/OutboxEvent.java`, `Outbox.java`, `OutboxRelay.java`
- Modify: `paymesh-common/src/test/java/com/paymesh/outbox/OutboxIntegrationTest.java`
- Create: `{user,wallet,transaction}_service/src/main/resources/db/migration/V2__outbox_event_headers.sql`
- Modify: `{user,wallet,transaction}_service/src/test/java/com/paymesh/SchemaMigrationTest.java`

**Interfaces:**
- Produces:
  - `EventHeaders.EVENT_ID = "eventId"` and `EventHeaders.CORRELATION_ID = "correlationId"` (`public static final String`).
  - `OutboxEvent` fields `eventId` (`String`, set to `UUID.randomUUID().toString()` in the constructor) and `correlationId` (`String`, nullable).
  - The constructor becomes `OutboxEvent(String producer, String topic, String messageKey, String payload, String correlationId)`.
  - `Outbox.publish(String topic, String key, Object payload)` keeps its signature, and now records `MDC.get(CorrelationIdLoggingFilter.MDC_KEY)`.
  - New: `Outbox.publishJson(String topic, String key, String json)`, which stores the JSON as is. Task 4 uses it for replay.
  - The relay sends `kafkaTemplate.send(ProducerRecord<String,String>)` with both headers, as UTF-8 bytes. The `correlationId` header is omitted when null.
- The migration SQL (identical in all three services):

```sql
ALTER TABLE outbox_event
  ADD COLUMN event_id CHAR(36) NOT NULL DEFAULT (UUID()),
  ADD COLUMN correlation_id VARCHAR(64) NULL;
```

- [ ] **Step 1: Write the failing tests**
  - **In `OutboxIntegrationTest`:**
    - Change the mock to `when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(sent())`, and change the existing verifies to capture `ProducerRecord`s. The order, key and value assertions stay the same.
    - Add `relaySendsEventIdAndCorrelationIdHeaders`: with `MDC.put("correlationId","corr-1")` during publish, the captured record's `eventId` header equals the row's `eventId` (36 characters), and its `correlationId` header is `"corr-1"`.
    - Add `noCorrelationIdHeaderWhenMdcEmpty`: `record.headers().lastHeader("correlationId")` is null.
    - Add `publishJsonStoresTheStringVerbatim`: `publishJson("t","k","{\"a\":1}")` gives a payload equal to `{"a":1}`, not a quoted string.
  - **In each `SchemaMigrationTest`:**
    - Change the version assertion to `containsExactly("1", "2")`.
    - Add `rowsWithoutEventIdGetOne`: `INSERT INTO outbox_event (producer, topic, message_key, payload) VALUES ('p','t','k','{}')` gives a non-null 36-character `event_id`.

- [ ] **Step 2: Run them and confirm they fail**
  - Run: `./mvnw -B -q -pl paymesh-common verify -Dtest=OutboxIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: compile errors or FAIL on the new tests.
  - Run: `./mvnw -B -q -pl user_service -am verify -Dtest=SchemaMigrationTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: FAIL (versions `["1"]`).

- [ ] **Step 3: Implement**
  - Add `EventHeaders`, the entity fields, the `Outbox` changes, and the relay's `ProducerRecord` with headers.
  - Add the three V2 migrations.

- [ ] **Step 4: Run the tests again and confirm they pass**
  - Run: `./mvnw -B -pl paymesh-common,user_service,wallet_service,transaction_service -am verify`
  - Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**
  - `git commit -m "feat(outbox): eventId and correlationId headers on every event"`

---

### Task 2: The correlation ID crosses Kafka on the consumer side

**Files:**
- Modify: `paymesh-common/src/main/java/com/paymesh/logging/CorrelationIdLoggingFilter.java` (make `SAFE_ID` package-private so it can be reused)
- Create: `paymesh-common/src/main/java/com/paymesh/logging/CorrelationIdRecordInterceptor.java`
- Test: `paymesh-common/src/test/java/com/paymesh/logging/CorrelationIdRecordInterceptorTest.java`

**Interfaces:**
- Produces a `@Component public class CorrelationIdRecordInterceptor implements RecordInterceptor<Object, Object>`.
  - `intercept(record, consumer)` puts `MDC "correlationId"` from the header when it matches `SAFE_ID`. Otherwise it puts a new UUID. It returns the record.
  - `afterRecord` / `clearThreadState` remove the MDC key.
  - Boot wires a single `RecordInterceptor` bean into the default listener container factory.

- [ ] **Step 1: Write the failing tests**
  - `copiesSafeHeaderIntoMdc`: header `corr-123` gives `MDC.get("correlationId")` = `"corr-123"` after `intercept`.
  - `replacesUnsafeHeader`: header `"evil\nINFO forged"` gives an MDC value matching `[0-9a-f-]{36}`.
  - `generatesIdWhenHeaderMissing`: the MDC value matches the UUID pattern.
  - `clearsMdcAfterRecord`: after `afterRecord(...)`, `MDC.get("correlationId")` is null.

- [ ] **Step 2: Run them and confirm they fail**
  - Run: `./mvnw -B -q -pl paymesh-common verify -Dtest=CorrelationIdRecordInterceptorTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: compile failure (the class doesn't exist).

- [ ] **Step 3: Implement the interceptor**

- [ ] **Step 4: Run the tests again and confirm they pass**
  - Same command as Step 2. Expected: 4 passing.

- [ ] **Step 5: Commit**
  - `git commit -m "feat(logging): correlation id from Kafka headers into the MDC"`

---

### Task 3: Retries with backoff, then `<topic>.DLT`

**Files:**
- Modify: `paymesh-common/pom.xml` (test scope `org.testcontainers:testcontainers-kafka`)
- Create: `paymesh-common/src/main/java/com/paymesh/kafka/KafkaErrorHandling.java`
- Test:
  - `paymesh-common/src/test/java/com/paymesh/kafka/KafkaErrorHandlingIntegrationTest.java`
  - `paymesh-common/src/test/java/com/paymesh/kafka/KafkaTestApplication.java`. This is a `@SpringBootApplication` in package `com.paymesh.kafka`, excluding `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`, `org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration` and `org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration`.

**Interfaces:**
- Produces:
  - `@Configuration public class KafkaErrorHandling` with `public static final String DLT_SUFFIX = ".DLT"`.
  - `@Bean DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template)`. Its recoverer is a `DeadLetterPublishingRecoverer` whose destination resolver returns `new TopicPartition(record.topic() + DLT_SUFFIX, -1)`.
  - The backoff is `ExponentialBackOffWithMaxRetries(2)` (initial 500 ms, multiplier 2.0).
  - `addNotRetryableExceptions(JacksonException.class)`.

- [ ] **Step 1: Write the failing integration test**
  - **Setup:**
    - A static `KafkaContainer("apache/kafka:3.8.0")`, with `spring.kafka.bootstrap-servers` from `@DynamicPropertySource`.
    - A nested `@TestConfiguration` with two `@KafkaListener`s:
      - `poison-test`: parses with `JsonMapper.readValue(message, Map.class)`, so `"not json"` throws a `JacksonException`. It counts attempts per payload and records successes.
      - `flaky-test`: always throws `IllegalStateException`, counting attempts.
    - Set `spring.kafka.consumer.auto-offset-reset=earliest`.
  - **Tests:**
    - `badJsonGoesStraightToDlt`: send `"not json"` to `poison-test`. Await (10 s) one record on `poison-test.DLT`, read with a plain `KafkaConsumer`. The attempt count is 1, and the header `kafka_dlt-exception-fqcn` contains `"Jackson"`.
    - `transientFailureIsRetriedThreeTimesThenDeadLettered`: send `"x"` to `flaky-test`. Await a record on `flaky-test.DLT`. The attempt count is exactly 3.
    - `poisonDoesNotBlockLaterMessages`: send `"not json"` then `{"ok":true}` to `poison-test`, same key. Await `{"ok":true}` appearing in the listener's successes.

- [ ] **Step 2: Run it and confirm it fails**
  - Run: `./mvnw -B -q -pl paymesh-common verify -Dtest=KafkaErrorHandlingIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: FAIL. Nothing reaches `*.DLT`, and with Spring's default handler bad JSON is retried 10 times.

- [ ] **Step 3: Implement `KafkaErrorHandling`**

- [ ] **Step 4: Run the test again and confirm it passes**
  - Same command as Step 2. Expected: 3 passing.

- [ ] **Step 5: Run the whole suite**
  - Run: `./mvnw -B verify`
  - Expected: BUILD SUCCESS. Every service now picks up the handler through component scanning.

- [ ] **Step 6: Commit**
  - `git commit -m "feat(kafka): retries with backoff, then <topic>.DLT"`

---

### Task 4: Dead-letter store with replay

**Files:**
- Create in `paymesh-common/src/main/java/com/paymesh/deadletter/`: `DeadLetter.java`, `DeadLetterRepository.java`, `DeadLetterStore.java`, `DeadLetterConfiguration.java`
- Create: `{user,wallet,transaction}_service/src/main/resources/db/migration/V3__dead_letter.sql`
- Create: `wallet_service/src/main/java/com/paymesh/consumer/DeadLetterConsumer.java`, `transaction_service/src/main/java/com/paymesh/consumer/DeadLetterConsumer.java`
- Modify: the three `SchemaMigrationTest`s (versions `"1","2","3"`)
- Test:
  - `paymesh-common/src/test/java/com/paymesh/deadletter/DeadLetterStoreIntegrationTest.java`
  - a test app in package `com.paymesh.deadletter`, with `@EntityScan` and `@EnableJpaRepositories` covering `com.paymesh.deadletter` and `com.paymesh.outbox`, and component scanning of both. Use MySQL Testcontainers, `ddl-auto=update`, and a mocked `KafkaTemplate`, like `OutboxIntegrationTest`.

**Interfaces:**
- Consumes `Outbox.publishJson(String, String, String)` (Task 1) and `KafkaErrorHandling.DLT_SUFFIX` (Task 3).
- Produces:
  - `DeadLetter` entity, table `dead_letter`, with these columns:
    - `id` BIGINT AUTO_INCREMENT PK
    - `dlt_topic` VARCHAR(255) NOT NULL, `dlt_partition` INT NOT NULL, `dlt_offset` BIGINT NOT NULL, unique together as `uk_dead_letter_position`
    - `original_topic` VARCHAR(255) NOT NULL
    - `message_key` VARCHAR(255) NULL
    - `payload` MEDIUMTEXT NOT NULL
    - `exception_class` VARCHAR(255) NULL
    - `exception_message` VARCHAR(1000) NULL (truncated)
    - `status` `enum('NEW','REPLAYED','DISCARDED')` NOT NULL
    - `created_at` / `updated_at` DATETIME(6)
  - `public enum DeadLetter.Status { NEW, REPLAYED, DISCARDED }`
  - `DeadLetterStore` methods:
    - `void record(ConsumerRecord<String, String> dltRecord)`. The original topic comes from header `KafkaHeaders.DLT_ORIGINAL_TOPIC`, falling back to `dltRecord.topic()` minus `.DLT`. The exception comes from `KafkaHeaders.DLT_EXCEPTION_FQCN` / `DLT_EXCEPTION_MESSAGE`. If the same DLT position already exists, it is a no-op.
    - `void replay(long id)`. Only `NEW` can be replayed: it calls `outbox.publishJson(originalTopic, key, payload)` and sets the status to `REPLAYED` in the same transaction. Anything else throws `IllegalStateException`.
    - `void discard(long id)`. Only `NEW` can be discarded; otherwise it throws `IllegalStateException`.
    - `List<DeadLetter> findNew()`.
  - `DeadLetterConfiguration` is `@ConditionalOnClass(name = "jakarta.persistence.EntityManager")`, like `OutboxConfiguration`.
  - Listeners:
    - wallet: `@KafkaListener(topics = {<its 4 topics> + DLT_SUFFIX}, groupId = "wallet_service.deadLetters")`
    - transaction: `{COMPLETED_TRANSACTION_TOPIC + DLT_SUFFIX}`, group `"transaction_service.deadLetters"`
    - Each listener calls `store.record(record)`.
    - `// ponytail: one consumer service per topic today; filter on the kafka_dlt-original-consumer-group header if a topic gains a second consumer`
- The V3 SQL is the `CREATE TABLE` matching the entity above, identical in all three services. user_service needs it too, because the entity is scanned everywhere JPA is.

- [ ] **Step 1: Write the failing tests in `DeadLetterStoreIntegrationTest`**
  - `recordKeepsOriginalTopicKeyPayloadAndException`: given a `ConsumerRecord("orders.DLT", 0, 7L, "k", "{\"a\":1}")` with the headers original topic `orders`, exception fqcn `x.Boom` and message `boom`, the stored row has `originalTopic` `orders`, key `k`, payload `{"a":1}`, `exceptionClass` `x.Boom` and status `NEW`.
  - `sameDltPositionIsStoredOnce`: recording it twice gives `count()` = 1.
  - `replayRepublishesThroughTheOutboxVerbatim`: `replay(id)` leaves one `outbox_event` row with topic `orders`, key `k` and payload `{"a":1}`, and the status becomes `REPLAYED`.
  - `cannotReplayTwiceOrAfterDiscard`: a second `replay` throws `IllegalStateException`. A `discard` on a different NEW row followed by `replay` on it also throws.

- [ ] **Step 2: Run them and confirm they fail**
  - Run: `./mvnw -B -q -pl paymesh-common verify -Dtest=DeadLetterStoreIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: compile failure.

- [ ] **Step 3: Implement**
  - Add the entity, repository, store, configuration and V3 migrations. Bump the SchemaMigrationTest versions.
  - Add both listener classes. Each one's end-to-end check is in Task 7.

- [ ] **Step 4: Run the tests again and confirm they pass**
  - Run: `./mvnw -B verify`
  - Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**
  - `git commit -m "feat: dead-letter store with replay through the outbox"`

---

### Task 5: Guarded transaction status

**Files:**
- Modify: `paymesh-common/src/main/java/com/paymesh/enums/PaymentStatus.java` (add `public boolean isFinal()` = `this != PENDING`)
- Modify: `transaction_service/src/main/java/com/paymesh/service/TransactionService.java` (`updateTransactionStatus`)
- Test: `transaction_service/src/test/java/com/paymesh/service/TransactionStatusGuardTest.java` (Mockito: `@Mock TransactionRepository`, `@InjectMocks TransactionService`)

**Interfaces:**
- `updateTransactionStatus(TransactionResultDto)` becomes `@Transactional`.
  - If the stored status `isFinal()`, it logs at `info` "Ignoring result {status} for transaction {id}: already {stored}" and returns without saving.
  - Phase 13 adds `ON_HOLD`, which is non-final.

- [ ] **Step 1: Write the failing tests**
  - `pendingBecomesSuccessful`: stored PENDING plus a SUCCESSFUL result gives status SUCCESSFUL, and `save` is called once.
  - `finalStatusIsNeverOverwritten`: stored SUCCESSFUL plus a REJECTED result ("late") keeps status SUCCESSFUL and the failureReason null, and `save` is never called.
  - `duplicateResultIsIgnored`: stored REJECTED plus a REJECTED result means `save` is never called.

- [ ] **Step 2: Run them and confirm they fail**
  - Run: `./mvnw -B -q -pl transaction_service -am verify -Dtest=TransactionStatusGuardTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: `finalStatusIsNeverOverwritten` and `duplicateResultIsIgnored` FAIL.

- [ ] **Step 3: Implement the guard and `@Transactional`**

- [ ] **Step 4: Run the tests again and confirm they pass**
  - Same command as Step 2. Expected: 3 passing.

- [ ] **Step 5: Commit**
  - `git commit -m "fix(txn): final statuses can't be overwritten by late or duplicate results"`

---

### Task 6: Notifications deduplicated by `eventId`; failed mails retried

**Files:**
- Modify: `notification_service/pom.xml` (add `spring-boot-starter-data-redis`, plus test scope `org.testcontainers:testcontainers`)
- Modify: `notification_service/src/main/resources/application.properties` (`spring.data.redis.host=${REDIS_HOST:localhost}`, `port=${REDIS_PORT:6379}`, `password=${REDIS_PASSWORD:}`, `connect-timeout=500ms`, `timeout=500ms`)
- Modify: `notification_service/src/main/java/com/paymesh/KafkaConsumer.java`
- Modify: `docker-compose.yml` (notification-service: `depends_on` redis, and `REDIS_HOST: redis`)
- Test: `notification_service/src/test/java/com/paymesh/NotificationDedupTest.java`

**Interfaces:**
- Consumes `EventHeaders.EVENT_ID` (Task 1).
- Produces:
  - The constructor `KafkaConsumer(JsonMapper, JavaMailSender, StringRedisTemplate, @Value("${spring.mail.username:}") String mailFrom)`.
  - The listener `sendNotification(String message, @Header(name = EventHeaders.EVENT_ID, required = false) String eventId)`.
- The algorithm:
  1. If `eventId` isn't null, call `setIfAbsent("notification:sent:" + eventId, "1", Duration.ofDays(7))`.
     - `false` means skip with an info log.
     - A Redis exception means a warn log, then carry on and send (fail-open).
  2. Send the mail, or log it when `mailFrom` is blank, as today.
  3. On `MailException`: delete the key if this call set it, then rethrow. The Task 3 handler retries the message and then dead-letters it.

- [ ] **Step 1: Write the failing tests**
  - **Setup:**
    - A static `GenericContainer("redis:7.4-alpine")` with port 6379 exposed.
    - A real `StringRedisTemplate` over a `LettuceConnectionFactory` pointed at it.
    - A mocked `JavaMailSender`, `mailFrom = "noreply@paymesh.test"`, and a `JsonMapper` for the `SendMailNotification` JSON.
  - **Tests:**
    - `sameEventIdSendsOnce`: two calls with `eventId` `e1` mean `send` is called once.
    - `differentEventIdsBothSend`: `e1` and `e2` mean 2 sends.
    - `missingEventIdAlwaysSends`: two calls with null mean 2 sends.
    - `failedMailReleasesTheKeyAndRethrows`: the first send throws `MailSendException`, so the call throws. A second call with the same `eventId` sends, so `send` is called twice in total.
    - `redisDownStillSends`: with a template pointed at an unused port (`localhost:1`, 500 ms timeout), the call sends once and throws nothing.

- [ ] **Step 2: Run them and confirm they fail**
  - Run: `./mvnw -B -q -pl notification_service -am verify -Dtest=NotificationDedupTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: compile failure (no such constructor).

- [ ] **Step 3: Implement and update compose**

- [ ] **Step 4: Run the tests again and confirm they pass**
  - Same command as Step 2. Expected: 5 passing.

- [ ] **Step 5: Commit**
  - `git commit -m "feat(notification): dedupe by eventId in Redis, retry failed mails"`

---

### Task 7: Wallet saga over real Kafka

**Files:**
- Modify: `wallet_service/pom.xml` (test scope `org.testcontainers:testcontainers-kafka`)
- Test: `wallet_service/src/test/java/com/paymesh/WalletSagaIntegrationTest.java`

**Interfaces:**
- Consumes everything above: the topics in `TopicConstants`, the headers in `EventHeaders`, `DeadLetterRepository`, `WalletRepository`, and `MySqlTestContainer`.
- Test setup:
  - `@SpringBootTest(properties = {"jwt.secret=0123456789abcdef0123456789abcdef", "outbox.poll-interval-ms=100"})`
  - A static `KafkaContainer("apache/kafka:3.8.0")`. Register `spring.kafka.bootstrap-servers`, plus `MySqlTestContainer.register`.
  - Send raw strings with headers through the context's `KafkaTemplate`.
  - Read results with a plain `KafkaConsumer<String,String>` (group `test-<uuid>`, earliest) subscribed to `COMPLETED_TRANSACTION_TOPIC`.

- [ ] **Step 1: Write the tests**
  - `addMoneyAppliedOnceEvenWhenRedelivered`:
    1. Send `UserWalletCreationRequest(101, "Asha", "asha@example.com")` JSON to `USER_CREATION_TOPIC`, then await the wallet for 101.
    2. Send `InitiateTransactionDto("t-1", null, 101, 500)` JSON twice, keyed `t-1`, with header `correlationId=corr-123`.
    3. Await (15 s) a COMPLETED record for `t-1` with `paymentStatus` SUCCESSFUL, and wallet 101's balance equal to `500.00`.
    4. Poll 3 s more. Exactly one COMPLETED record for `t-1` arrived, and its `correlationId` header is `corr-123`.
  - `poisonInitiateIsDeadLetteredAndStored`: send `"not json"` to `INITIATE_TRANSACTION_TOPIC`. Await (15 s) one `DeadLetterRepository` row with `originalTopic` = `INITIATE_TRANSACTION_TOPIC`, status `NEW`, and `exceptionClass` containing `"Jackson"`.

- [ ] **Step 2: Run the tests**
  - Run: `./mvnw -B -q -pl wallet_service -am verify -Dtest=WalletSagaIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: PASS. Tasks 1–4 already built the behaviour, so this test proves the wiring.
  - Show it can fail: temporarily remove `wallet_service`'s `DeadLetterConsumer` `@KafkaListener` annotation. `poisonInitiateIsDeadLetteredAndStored` must FAIL. Then restore it.

- [ ] **Step 3: Run the whole suite**
  - Run: `./mvnw -B verify`
  - Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**
  - `git commit -m "test(wallet): saga, redelivery and dead-lettering over real Kafka"`

---

### Task 8: README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the reliability section and the Kafka event catalogue**
  - The envelope headers (`eventId`, `correlationId`).
  - Retries (3 attempts, backoff 0.5 s → 1 s), with bad JSON not retried, then `<topic>.DLT`.
  - The `dead_letter` table and replay (`DeadLetterStore.replay`; admin endpoints in Phase 14).
  - The idempotency table, one row per consumer:
    - wallet payments → `wallet_processed_transaction`
    - wallet creation → `existsByUserId`
    - status updates → final-state guard
    - notifications → Redis `notification:sent:<eventId>` for 7 days

- [ ] **Step 2: Update Known limitations**
  - Remove "Correlation IDs stop at Kafka".
  - Change the emails line to: "Duplicate emails are suppressed by eventId; a crash between claiming the eventId and sending can lose one email (at-most-once by design: a duplicate payment email is worse than a missing one)."
  - Add: "Notification DLT messages stay on `SEND_NOTIFICATION.DLT` (notification_service has no database); replay them with the Kafka CLI."
  - Add the Redis dependency of notification-service to the architecture/services table.

- [ ] **Step 3: Commit**
  - `git commit -m "docs: messaging reliability"`

---

## Phase 2 done when

- `./mvnw -B verify` is green with 133 + 3 + 4 + 3 + 4 + 3 + 5 + 2 = 157 tests. That counts Task 1's 3 OutboxIntegrationTest tests and 3 SchemaMigrationTest tests together as 6, so recount from the actual runs.
- CI is green.
- The final review's Critical and Important findings are fixed.
