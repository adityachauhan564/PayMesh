# Phase 1 — Data Foundations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every stateful service owns its schema through Flyway migrations, uses its own MySQL schema and user, and runs with `ddl-auto=validate`.

**Architecture:**
- **Baseline:** each of `user_service`, `wallet_service` and `transaction_service` gets `src/main/resources/db/migration/V1__baseline.sql`. It is generated from today's Hibernate schema, so it matches exactly.
- **Schemas and users:** one MySQL server holds `user_db`, `wallet_db` and `txn_db`. Each schema has its own user (`user_svc`, `wallet_svc`, `txn_svc`) with grants only on that schema. A compose init script creates them.
- **Outbox:** each service's own V1 contains its own `outbox_event` table.
- **Later schemas:** `risk_db` comes in Phase 13 with risk_service (YAGNI until then).

**Tech Stack:** Spring Boot 4.0.8 with `spring-boot-starter-flyway` (Flyway 11.14.1, from the Boot BOM) and `org.flywaydb:flyway-mysql`; MySQL 8.4; Testcontainers MySQL.

**Spec:** `docs/superpowers/specs/2026-10-17-paymesh-production-design.md` §7.1

## Global Constraints

- **Versions:** Java 21, Spring Boot 4.0.8 and Spring Cloud 2025.1.3 stay as they are. Take Flyway's version from the Boot BOM; never pin it.
- **V1 must match today's schema exactly.** Hibernate `validate` passing on a Flyway-built schema is the proof.
- **After this phase, `spring.jpa.hibernate.ddl-auto = validate`** in all three services. No `update` or `create` anywhere in `src/main`.
- **Grants:** each service user gets `ALL PRIVILEGES` on its own schema only (Flyway needs DDL), and nothing on the other schemas.
- **Existing local data may be dropped.** README documents `docker compose down -v` for anyone upgrading.
- **Commits:** commit and push on branch `phase-1-data-foundations`, which stacks on `phase-0-hygiene-ci`. Never add a `Co-Authored-By` line.
- **Tests:** all existing tests stay green (`./mvnw -B verify`, Docker running).

## Review Focus

- **Fresh volume:** `docker compose up` on a fresh volume must create the 3 schemas and 3 users, and each service must migrate and start. A payment between two users must still reach SUCCESSFUL across the services. This is covered in Task 4, Step 5.
- **Isolation:** a service user must be denied when it reads another service's schema. This is covered in Task 4, Step 6.
- **Stale volume:** someone upgrading with an old `mysql-data` volume (init scripts don't rerun) gets a clear failure (`Access denied for user 'user_svc'` or `Unknown database`), and the README troubleshooting row tells them to run `down -v`. This is covered in Task 4, Step 7.
- **Hibernate MySQL `enum` columns:** Hibernate 7 maps `@Enumerated(STRING)` to MySQL `enum(...)`. V1 must keep those exact definitions, or `validate` fails. Later phases that add enum values (ON_HOLD, CASHBACK) must `ALTER` the column. This is covered by each `SchemaMigrationTest`.
- **Re-running migrations:** starting a service twice on the same database must not re-apply V1. Flyway's history makes this a no-op. This is covered in Task 1, Step 6.

---

### Task 1: Flyway baseline for user_service

**Files:**
- Modify: `user_service/pom.xml` (add `spring-boot-starter-flyway`, `org.flywaydb:flyway-mysql`, and test scope `spring-boot-starter-data-jpa-test`)
- Create: `user_service/src/main/resources/db/migration/V1__baseline.sql`
- Modify: `user_service/src/main/resources/application.properties` (`ddl-auto = validate`)
- Test: `user_service/src/test/java/com/paymesh/SchemaMigrationTest.java`

**Interfaces:**
- Consumes the existing `com.paymesh.support.MySqlTestContainer.register(DynamicPropertyRegistry)`. It sets `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`.
- Produces the **V1 generation procedure** that Tasks 2 and 3 repeat:
  1. Start `docker run -d --name pm-schema -e MYSQL_ROOT_PASSWORD=gen -p 127.0.0.1:3399:3306 mysql:8.4` and wait until `mysqladmin ping` answers.
  2. Package the service, then run its jar with:
     - env: `DB_URL=jdbc:mysql://localhost:3399/gen?createDatabaseIfNotExist=true`, `DB_USERNAME=root`, `DB_PASSWORD=gen`, `JWT_SECRET=<32+ chars>`, `KAFKA_SERVERS=localhost:1`
     - args: `--spring.jpa.hibernate.ddl-auto=create --spring.flyway.enabled=false`
  3. Stop it once the log shows `Started`.
  4. Run `docker exec pm-schema mysqldump -uroot -pgen --no-data --skip-comments --skip-add-drop-table --skip-set-charset gen`. Strip the `/*!…*/` lines and the `AUTO_INCREMENT=<n>` table options, and save the result as `V1__baseline.sql`.
  5. Drop the `gen` database before the next service.

- [ ] **Step 1: Write the failing test `SchemaMigrationTest`**

```java
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SchemaMigrationTest {
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) { MySqlTestContainer.register(r); }

    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayBuildsTheSchemaHibernateExpects() {
        // context start = Flyway migrate + Hibernate validate
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = 1", String.class))
                .containsExactly("1");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**
  - Run: `./mvnw -B -q -pl user_service -am verify -Dtest=SchemaMigrationTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: FAIL. Either the context fails with `Schema-validation: missing table [outbox_event]` / `[wallet_user]`, or the test fails because `flyway_schema_history` doesn't exist.

- [ ] **Step 3: Add the dependencies and generate `V1__baseline.sql`**
  - Add the dependencies and set `ddl-auto = validate`.
  - Generate V1 with the procedure in Interfaces.
  - It must contain exactly two tables: `wallet_user` and `outbox_event`.

- [ ] **Step 4: Run the test again and confirm it passes**
  - Same command as Step 2. Expected: PASS.

- [ ] **Step 5: Run the module's whole suite**
  - Run: `./mvnw -B -pl user_service -am verify`
  - Expected: BUILD SUCCESS. `LoginLockoutIntegrationTest` and `KafkaUnavailableTest` now run on Flyway-built schemas.

- [ ] **Step 6: Check that a second start doesn't re-apply V1**
  - Add `@Test void secondMigrateIsNoOp(@Autowired Flyway flyway) { assertThat(flyway.migrate().migrationsExecuted).isZero(); }` to `SchemaMigrationTest`.
  - Run it. Expected: PASS.

- [ ] **Step 7: Commit**
  - `git add user_service && git commit -m "feat(user): Flyway baseline, ddl-auto=validate"`

---

### Task 2: Flyway baseline for wallet_service

**Files:**
- Modify: `wallet_service/pom.xml` (the same three dependencies as Task 1, plus test scope `org.testcontainers:testcontainers-mysql`, matching user_service's coordinates)
- Create: `wallet_service/src/test/java/com/paymesh/support/MySqlTestContainer.java` (a copy of user_service's)
- Create: `wallet_service/src/main/resources/db/migration/V1__baseline.sql`
- Modify: `wallet_service/src/main/resources/application.properties` (`ddl-auto = validate`)
- Test: `wallet_service/src/test/java/com/paymesh/SchemaMigrationTest.java` (the same two tests as Task 1)

**Interfaces:**
- Consumes the V1 generation procedure from Task 1.
- Expected tables: `wallet`, `wallet_processed_transaction`, `outbox_event`.

- [ ] **Step 1: Copy `MySqlTestContainer` and write `SchemaMigrationTest`**
  - The test has both test methods from Task 1.

- [ ] **Step 2: Run it and confirm it fails**
  - Run: `./mvnw -B -q -pl wallet_service -am verify -Dtest=SchemaMigrationTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: FAIL, as in Task 1.

- [ ] **Step 3: Add the dependencies, set validate, and generate V1**
  - It should contain exactly the 3 tables listed above.

- [ ] **Step 4: Run the test again and confirm it passes**
  - Same command as Step 2. Expected: PASS (2 tests).

- [ ] **Step 5: Run the module's whole suite**
  - Run: `./mvnw -B -pl wallet_service -am verify`
  - Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**
  - `git add wallet_service && git commit -m "feat(wallet): Flyway baseline, ddl-auto=validate"`

---

### Task 3: Flyway baseline for transaction_service

**Files:**
- The same set as Task 2, under `transaction_service/`.

**Interfaces:**
- Consumes the V1 generation procedure from Task 1.
- Expected tables: `wallet_transactions` (with its `senderId` and `receiverId` indexes), `outbox_event`.

- [ ] **Step 1: Copy `MySqlTestContainer` and write `SchemaMigrationTest`**
  - Use both test methods, and add an index check: `jdbc.queryForList("SELECT DISTINCT column_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'wallet_transactions' AND index_name <> 'PRIMARY'", String.class)` containsExactlyInAnyOrder the sender and receiver columns, using the names Hibernate generates (`sender_id`, `receiver_id`).

- [ ] **Step 2: Run it and confirm it fails**
  - Run: `./mvnw -B -q -pl transaction_service -am verify -Dtest=SchemaMigrationTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: FAIL.

- [ ] **Step 3: Add the dependencies, set validate, and generate V1**

- [ ] **Step 4: Run the test again and confirm it passes**
  - Same command as Step 2. Expected: PASS (3 tests).

- [ ] **Step 5: Run the module's whole suite**
  - Run: `./mvnw -B -pl transaction_service -am verify`
  - Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**
  - `git add transaction_service && git commit -m "feat(txn): Flyway baseline, ddl-auto=validate"`

---

### Task 4: A schema and a user per service

**Files:**
- Create: `deploy/mysql/init/01-service-schemas.sh`
- Modify: `docker-compose.yml`
  - mysql: mount `./deploy/mysql/init:/docker-entrypoint-initdb.d:ro`, and pass `USER_DB_PASSWORD`, `WALLET_DB_PASSWORD` and `TXN_DB_PASSWORD`.
  - The `x-service-env` anchor loses the `DB_*` variables, and each stateful service sets its own `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`.
- Modify: `.env.example` (drop `DB_USERNAME`/`DB_PASSWORD`; add `USER_DB_PASSWORD`, `WALLET_DB_PASSWORD`, `TXN_DB_PASSWORD`, all `change-me-local-only`)
- Modify: the three `application.properties` files: the `DB_URL` default becomes `jdbc:mysql://localhost:3306/<schema>`, without `createDatabaseIfNotExist`.
- Modify: the three `application-local.properties` files: the `DB_USERNAME` default becomes `<svc>_svc`, and the `DB_PASSWORD` default stays `change-me-local-only`.
- Modify: `paymesh-common/src/main/java/com/paymesh/outbox/OutboxEvent.java` and `OutboxConfiguration.java` (Javadoc only)
- Modify: `README.md` (Data model intro at line 332, the configuration table, the Known limitations "Shared database" line at 719, Option B, and Troubleshooting)

**Interfaces:**
- Produces this mapping between schemas, users and variables. Phases 7 and 8 reuse it for k8s Secrets and SSM:

| Service | Schema | User | Password variable |
|---|---|---|---|
| user-service | `user_db` | `user_svc` | `USER_DB_PASSWORD` |
| wallet-service | `wallet_db` | `wallet_svc` | `WALLET_DB_PASSWORD` |
| transaction-service | `txn_db` | `txn_svc` | `TXN_DB_PASSWORD` |

- [ ] **Step 1: Write `01-service-schemas.sh`**
  - Make it executable and use LF endings; `.gitattributes` already covers `*.sh`.
  - It uses `set -eu` and runs one `mysql -uroot -p"$MYSQL_ROOT_PASSWORD"` heredoc. For each row of the table above it runs `CREATE DATABASE IF NOT EXISTS <schema>;`, `CREATE USER IF NOT EXISTS '<user>'@'%' IDENTIFIED BY '<pw>';` and `GRANT ALL PRIVILEGES ON <schema>.* TO '<user>'@'%';`.
  - It fails if any of the three password variables is empty, because `set -u` covers that.

- [ ] **Step 2: Update compose and `.env.example`**
  - The mysql service no longer needs `MYSQL_DATABASE: paymesh`; remove it.
  - Each service's password uses `${…_DB_PASSWORD:?set it in .env (copy .env.example)}`.
  - Verify: `docker compose config -q` exits 0 with the new `.env`. With `TXN_DB_PASSWORD` removed from `.env`, it fails and names the variable.

- [ ] **Step 3: Update the properties defaults**
  - Change the URL and local-profile defaults as listed under Files.
  - Update the `OutboxEvent` Javadoc: "Each service has its own schema; the producer column is kept so a relay never picks up another service's rows if schemas are ever shared again."

- [ ] **Step 4: Run the backend suite**
  - Run: `./mvnw -B verify`
  - Expected: BUILD SUCCESS, with the total test count = 124 + the new SchemaMigrationTest tests (2 + 2 + 3 = 131).

- [ ] **Step 5: Run the whole stack end to end on a fresh volume**
  - Build: `sh scripts/build-images.sh`.
  - Start: `docker compose down -v`, then `docker compose up -d`. On a machine where a native MySQL holds :3306, add a scratch override that resets the infra `ports`, as in the Phase 0 ruling.
  - Test:
    1. Register two users (mobiles `9000000001` and `9000000002`) through `localhost:4200/api/auth/register`.
    2. As user 1, `POST /api/transactions` with `{"type":"ADD_MONEY","amount":500}`, then `{"type":"TRANSFER","receiverId":<user2 id>,"amount":100}`.
    3. Poll `GET /api/transactions/{id}` until it's no longer PENDING.
  - Expected: both reach `SUCCESSFUL`. `GET /api/wallets/me` for user 2 shows `balance` 100.

- [ ] **Step 6: Check isolation**
  - Run: `docker compose exec mysql mysql -uuser_svc -p"$USER_DB_PASSWORD" -e "SELECT COUNT(*) FROM wallet_db.wallet"`
  - Expected: `ERROR 1142 (42000): SELECT command denied`.
  - `docker compose exec mysql mysql -uroot -p… -e "SHOW DATABASES"` lists `user_db`, `wallet_db`, `txn_db`, and no `paymesh`.

- [ ] **Step 7: Update the README**
  - Data model intro: "Each service owns its schema (`user_db`, `wallet_db`, `txn_db`) on one MySQL server, with its own user and Flyway migrations; no foreign keys or queries cross service boundaries."
  - Configuration table: per-service `DB_*` variables and the 3 `*_DB_PASSWORD` compose variables.
  - Delete the "Shared database" known limitation.
  - Option B: the services use the local-profile users, and the database must be created by compose (the init script), not by the app.
  - Troubleshooting row: `Access denied for user 'user_svc'` or `Unknown database 'user_db'` after upgrading means an old `mysql-data` volume. Run `docker compose down -v` (this wipes local data).
  - New subsection "Schema changes": add `V<n>__<what>.sql` under the service's `db/migration`; never edit an applied migration.

- [ ] **Step 8: Commit**
  - `git add deploy docker-compose.yml .env.example */src/main/resources paymesh-common README.md && git commit -m "feat: schema and user per service"`

---

## Phase 1 done when

- `./mvnw -B verify` is green with 131 tests.
- CI is green on the PR.
- A fresh-volume compose run moves money end to end.
- Cross-schema reads are denied.
- `grep -rn "ddl-auto" */src/main` shows only `validate`.
