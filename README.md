# PayMesh: Event-Driven Digital Wallet Platform

[![CI](https://github.com/adityachauhan564/PayMesh/actions/workflows/ci.yml/badge.svg)](https://github.com/adityachauhan564/PayMesh/actions/workflows/ci.yml)

A secure, event-driven digital wallet built as **Spring Boot 4 microservices** on **Java 21**: centralized API routing through **Spring Cloud Gateway**, **JWT** authentication with **Spring Security**, asynchronous **Apache Kafka** messaging for transaction processing and notifications, **MySQL** storage, and an **Angular** web app.

Users can register, add money, send money to other users by mobile number, pay bills and recharges, withdraw to a bank, set daily limits, and see their full history, with an email for every money movement.

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-6DB33F)
![Spring Cloud Gateway](https://img.shields.io/badge/Spring%20Cloud%20Gateway-5.0-6DB33F)
![Angular](https://img.shields.io/badge/Angular-21-DD0031)
![Kafka](https://img.shields.io/badge/Apache%20Kafka-3.8-231F20)
![MySQL](https://img.shields.io/badge/MySQL-8.4-4479A1)
![Redis](https://img.shields.io/badge/Redis-7.4-DC382D)

---

## Contents

1. [Features](#features)
2. [Architecture at a glance](#architecture-at-a-glance)
3. [Services](#services)
4. [How a request travels](#how-a-request-travels)
5. [Core flows](#core-flows): registration, payments, login
6. [Reliability: the transactional outbox](#reliability-the-transactional-outbox)
7. [Double-entry ledger](#double-entry-ledger)
8. [Data model](#data-model)
9. [Kafka event catalogue](#kafka-event-catalogue)
10. [Security model](#security-model)
11. [API reference](#api-reference)
12. [Getting started](#getting-started)
13. [Configuration](#configuration)
14. [Testing](#testing)
15. [Project structure](#project-structure)
16. [Design decisions](#design-decisions)
17. [Known limitations and roadmap](#known-limitations-and-roadmap)
18. [Troubleshooting](#troubleshooting)

---

## Features

| | Feature | Details |
|---|---|---|
| 👤 | **Accounts** | Register with mobile, email and password (BCrypt); log in with mobile **or** email; view, edit and delete profile |
| 💰 | **Wallet** | Created automatically on sign-up; balance; daily spend limit (default ₹10,000) and daily payment count (default 10), both user-configurable |
| ➕ | **Add money** | Top up the wallet (bank side simulated) |
| ↗️ | **Send money** | Find the receiver by mobile number, confirm their name, pay |
| ⚡ | **Recharge and bills** | Mobile, DTH, electricity, water, gas, broadband |
| ⬇️ | **Withdraw** | Move money back to the bank (simulated) |
| 📜 | **History** | Paginated, with full details per transaction |
| ✉️ | **Email notifications** | Welcome, every debit and credit, failed payments, account locked |
| 🛡️ | **Security** | JWT auth at gateway **and** service, per-IP login rate limit, per-account lockout, ownership checks everywhere |
| 🔁 | **Reliability** | No event is ever lost: works through Kafka outages and catches up automatically |

---

## Architecture at a glance

```mermaid
flowchart TB
    user(["User's browser"]) --> ui["wallet-ui · Angular 21 · :4200"]
    ui -- "/api/** with Bearer JWT" --> gw["api_gateway · Spring Cloud Gateway · :8080"]
    gw <-- "login rate-limit buckets" --> redis[("Redis")]

    gw --> us["user_service · :8081"]
    gw --> ws["wallet_service · :8091"]
    gw --> ts["transaction_service · :8086"]

    us --> db[("MySQL · paymesh")]
    ws --> db
    ts --> db

    us <-. "events (outbox)" .-> kafka{{"Apache Kafka"}}
    ws <-.-> kafka
    ts <-.-> kafka
    kafka -.-> ns["notification_service · :8082"]
    ns --> smtp[["SMTP server"]]
    ns <-- "eventId claims (no duplicate emails)" --> redis
```

Solid arrows are synchronous HTTP or database calls; dotted arrows are asynchronous Kafka events. Which events flow
where (every arrow is a Kafka topic, published through the [outbox](#reliability-the-transactional-outbox)):

```mermaid
flowchart LR
    us["user_service"]
    ts["transaction_service"]
    ws["wallet_service"]
    ns["notification_service"]
    us -- "user created / updated / deleted" --> ws
    ts -- "payment requests" --> ws
    ws -- "payment results" --> ts
    us -- "lockout emails" --> ns
    ws -- "welcome and payment emails" --> ns
```

**The big ideas**

- **One public door.** The browser only ever talks to the gateway. It routes requests, checks the JWT, rate-limits logins, adds a correlation ID and returns errors in one consistent format.
- **Services own their data and talk through events.** No service calls another over HTTP. A payment is a *saga*: `transaction_service` records the request, `wallet_service` moves the money atomically, and the result flows back as an event.
- **The wallet is the source of truth for money.** Every balance change happens in one database transaction, with row locks, balance and daily-limit checks, and duplicate protection.
- **Nothing is lost.** Events are written to a database *outbox* together with the data they describe, then relayed to Kafka. Kafka can be down and the system still accepts requests and catches up afterwards.

---

## Services

| Module | Port | Responsibility | Owns (tables) | Publishes | Consumes |
|---|---|---|---|---|---|
| **`api_gateway`** | 8080 | Single entry point: routing, JWT validation, login rate limiting, correlation IDs, uniform errors | (uses Redis) | n/a | n/a |
| **`user_service`** | 8081 | Register and login (issues JWT), profile CRUD, receiver lookup, account lockout | `wallet_user` | user created / updated / deleted, lockout email | n/a |
| **`wallet_service`** | 8091 | Wallets, balances, daily limits; **applies every payment atomically** | `wallet`, `wallet_processed_transaction` | payment result, welcome and payment emails | user events, payment requests |
| **`transaction_service`** | 8086 | Create payments, history, details; tracks status | `wallet_transactions` | payment request | payment result |
| **`notification_service`** | 8082 | Sends emails (logs them if SMTP isn't configured), once per event | none (uses Redis for `eventId` claims) | n/a | emails |
| **`paymesh-common`** | n/a | Shared code: event DTOs, topics, JWT validation, error handling, log correlation, **transactional outbox**, Kafka retries and dead letters | `outbox_event`, `dead_letter` (in each service's schema) | n/a | n/a |
| **`wallet-ui`** | 4200 | Angular app: login and register, dashboard, pay, history, profile | n/a | n/a | n/a |

All services share **one MySQL database** for simplicity, but each touches only its own tables and none uses another's, so they can be split into separate databases without code changes.

---

## How a request travels

Every API call passes through the same gateway pipeline before it reaches a service, and the service checks the token again.

```mermaid
flowchart TD
    req["Request from browser"] --> cid["1 · CorrelationIdFilter<br/>reuse a safe X-Correlation-Id or create one"]
    cid --> sec{"2 · JWT valid?<br/>signature, expiry, issuer"}
    sec -- "public route: register, login, health" --> route
    sec -- "missing or invalid" --> e401["401 ProblemDetail"]
    sec -- "valid" --> route{"3 · Route match?"}
    route -- "no" --> e404["404 ProblemDetail"]
    route -- "POST /api/auth/login" --> rl{"4 · Under per-IP<br/>rate limit? (Redis)"}
    rl -- "no" --> e429["429 ProblemDetail + Retry-After"]
    rl -- "yes" --> fwd
    route -- "other routes" --> fwd["5 · Forward unchanged to the service"]
    fwd --> svc["Service: validates the JWT again,<br/>takes the user ID only from the token's sub,<br/>enforces ownership"]
    fwd -- "service down" --> e503["503 ProblemDetail"]
    fwd -- "service too slow" --> e504["504 ProblemDetail"]
```

Every response, including errors, carries `X-Correlation-Id`, and every service log line shows it (`INFO [e2e-trace-001] ...`), so one request can be followed through all logs.

---

## Core flows

### 1. Registration: account first, wallet follows

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant GW as api_gateway
    participant US as user_service
    participant DB as MySQL
    participant K as Kafka
    participant WS as wallet_service
    participant NS as notification_service

    U->>GW: POST /api/auth/register
    GW->>US: forward (public route)
    rect rgb(235, 245, 255)
    note over US,DB: one database transaction
    US->>DB: INSERT wallet_user (password BCrypt-hashed)
    US->>DB: INSERT outbox_event "user created"
    end
    US-->>U: 201 Created + JWT (logged in immediately)
    note over US,K: OutboxRelay, about 250 ms later, or once Kafka is back
    US->>K: user-service.userWalletCreation
    K->>WS: user created
    rect rgb(235, 245, 255)
    note over WS,DB: one database transaction
    WS->>DB: INSERT wallet (balance 0, limits ₹10,000 / 10 per day)
    WS->>DB: INSERT outbox_event "welcome email"
    end
    WS->>K: SEND_NOTIFICATION
    K->>NS: welcome email
    NS-->>U: "Welcome to PayMesh"
```

Until the wallet exists, `GET /api/wallets/me` answers *"Wallet not found yet, it is being created"*. The dashboard waits and retries.

### 2. Payments: a saga with the wallet as the source of truth

All four payment types use the same flow. Only which wallet is debited and which is credited changes:

| Type | Debits | Credits | Example |
|---|---|---|---|
| `TRANSFER` | sender | receiver | Pay a friend |
| `ADD_MONEY` | n/a (bank) | you | Top up |
| `WITHDRAW` | you | n/a (bank) | Cash out |
| `BILL_PAYMENT` | you | n/a (biller) | Mobile recharge, electricity, … |

```mermaid
sequenceDiagram
    autonumber
    actor U as User (Angular)
    participant GW as api_gateway
    participant TS as transaction_service
    participant K as Kafka
    participant WS as wallet_service
    participant NS as notification_service

    U->>GW: POST /api/transactions {type: TRANSFER, amount, receiverId}
    GW->>TS: forward (JWT checked)
    rect rgb(235, 245, 255)
    note over TS: one transaction: payment saved as PENDING + outbox "payment request"
    end
    TS-->>U: 202 Accepted {transactionId, status: PENDING}

    loop every second until no longer PENDING
        U->>GW: GET /api/transactions/{id}
    end

    TS->>K: INITIATE_TRANSACTION_TOPIC (outbox relay)
    K->>WS: payment request
    rect rgb(235, 245, 255)
    note over WS: one transaction
    WS->>WS: already processed? then ignore (idempotent)
    WS->>WS: lock both wallets (SELECT ... FOR UPDATE, by userId order: no deadlocks)
    WS->>WS: check balance, daily amount and daily count, then debit and credit
    WS->>WS: record as processed + outbox: result + emails
    end
    WS->>K: COMPLETED_TRANSACTION_TOPIC (SUCCESSFUL or REJECTED + reason)
    K->>TS: result, so status is updated
    WS->>K: SEND_NOTIFICATION
    K->>NS: "₹250 debited" / "₹250 credited" / "payment failed"
    U->>GW: GET /api/transactions/{id}
    GW-->>U: SUCCESSFUL (or REJECTED: "Insufficient balance")
```

**Payment status lifecycle**

```mermaid
stateDiagram-v2
    [*] --> PENDING: POST /api/transactions
    PENDING --> SUCCESSFUL: wallet applied it
    PENDING --> REJECTED: insufficient balance, daily limit,<br/>daily count, or wallet not found
    SUCCESSFUL --> [*]
    REJECTED --> [*]
```

Why the money stays correct:

- **No double spending.** Balance is checked and changed under a row lock, in the same transaction. In a test, 8 parallel ₹300 sends from a ₹1,200.50 balance gave exactly 4 successes and left ₹0.50.
- **No double applying.** Each `transactionId` is recorded in `wallet_processed_transaction` in the same transaction, so a redelivered message is ignored.
- **No lost payments.** The request and the result both go through the outbox.
- **Exact amounts.** Money is `BigDecimal` / `DECIMAL(19,2)` end to end.

### 3. Login: two independent brute-force defences

```mermaid
flowchart TD
    start(["POST /api/auth/login"]) --> ip{"Gateway: this IP under<br/>5 attempts/min? (Redis)"}
    ip -- "no" --> r1["429 · Retry-After"]
    ip -- "yes, or Redis down (fail-open)" --> find{"user_service:<br/>account exists?"}
    find -- "no" --> r401["401 Invalid username or password"]
    find -- "yes" --> locked{"Account locked?"}
    locked -- "yes" --> r2["429 · 'Try again in N minutes'<br/>even with the correct password"]
    locked -- "no" --> pw{"Password matches?<br/>(BCrypt)"}
    pw -- "yes" --> ok["Reset failure count<br/>200 + JWT (24 h)"]
    pw -- "no" --> count["Count failure under a row lock"]
    count --> fifth{"5th failure in a row?"}
    fifth -- "no" --> r401
    fifth -- "yes" --> lock["Lock for 15 min<br/>+ queue 'account locked' email<br/>(same transaction)"]
    lock --> r2
```

| Defence | Where | Stops | Default |
|---|---|---|---|
| **Per-IP rate limit** | gateway + Redis | one machine hammering many accounts | 5 attempts, then 1 every 12 s |
| **Per-account lockout** | user_service + MySQL | many machines hammering one account | 5 wrong passwords, then 15 min lock |

---

## Reliability: the transactional outbox

Writing to the database and then publishing to Kafka are **two separate systems**. If the process crashes between them, or Kafka is down, an event is lost: a user with no wallet, or a payment stuck at PENDING forever. The outbox makes the event part of the database transaction.

```mermaid
sequenceDiagram
    autonumber
    participant S as Service code
    participant DB as MySQL
    participant R as OutboxRelay (every 250 ms)
    participant K as Kafka

    rect rgb(235, 245, 255)
    note over S,DB: one database transaction
    S->>DB: business change (user, payment, balance)
    S->>DB: INSERT outbox_event (topic, key, JSON payload)
    end
    S->>DB: COMMIT: both saved, or neither

    loop every 250 ms
        R->>DB: SELECT oldest 100 FOR UPDATE (READ COMMITTED)
        R->>K: send event, wait for acknowledgement
        alt acknowledged
            R->>DB: DELETE the event
        else Kafka down or timed out
            R->>R: stop here, retry next run (order kept)
        end
    end
```

| Situation | What happens |
|---|---|
| Kafka is down | Requests still succeed **instantly**; events wait in `outbox_event`; the relay retries; everything completes once Kafka is back (verified live) |
| Service crashes after commit | Events are already in the table; the relay sends them after restart |
| Crash after Kafka ack, before delete | The event is sent **again**: delivery is *at-least-once*, so consumers are idempotent (below) |
| Two instances of one service | Rows are locked while being sent: no concurrent double publishing, order kept |
| Kafka slow to respond | Writers are **not** blocked: the relay reads at `READ COMMITTED`, so MySQL takes no gap locks |

**Every consumer tolerates duplicates:**

| Consumer | Why a duplicate is harmless |
|---|---|
| Create wallet | Skips if the wallet already exists (no second welcome email) |
| Update or delete wallet owner | Setting the same values again changes nothing |
| Apply payment | `wallet_processed_transaction` records each `transactionId` |
| Update payment status | Only a `PENDING` payment takes a result; a late or repeated result for a final payment is ignored |
| Send email | The event's `eventId` is claimed in Redis (`notification:sent:<eventId>`, 7 days) before sending; a claimed id is skipped |

`Outbox.publish(...)` runs only inside an existing transaction (`Propagation.MANDATORY`). Calling it anywhere else is a programming error and fails immediately.

**Every event carries two headers**, set by the relay from the outbox row:

| Header | Purpose |
|---|---|
| `eventId` | Unique per event (UUID); consumers without a natural business key dedupe on it |
| `correlationId` | The request's `X-Correlation-Id`; consumers put it back into the log context, and the events they write carry it on, so one payment can be followed through every service's logs |

### When a message can't be handled

1. **Retry:** every listener gets 3 attempts, 0.5 s then 1 s apart (one `DefaultErrorHandler` in `paymesh-common`). Unparseable JSON can never succeed, so it skips the retries.
2. **Park:** the message goes to `<topic>.DLT` with the exception in its headers, and the partition moves on: one bad message never blocks the ones behind it.
3. **Keep:** wallet_service and transaction_service consume their own DLTs into a `dead_letter` table (topic, key, payload, exception, status `NEW` / `REPLAYED` / `DISCARDED`). If storing fails (database down), the DLT consumer retries every 2 s until it works rather than dead-lettering a dead letter; only DLT partitions wait.
4. **Replay or discard:** `DeadLetterStore.replay(id)` puts the payload back on its original topic **through the outbox**, in the same transaction as the status change, so a replay is never lost or doubled. (Admin endpoints for this arrive with the admin console.)

notification_service has no database, so its failures stay on `SEND_NOTIFICATION.DLT`; replay them with the Kafka CLI.

---

## Double-entry ledger

A balance column says *what* a wallet holds, never *why*. Every successful payment therefore also writes a **journal entry** in wallet_service: one debit and one credit of the same amount, in the same database transaction as the row-locked balance change. `wallet.balance` stays as a cached balance (O(1) reads), and the ledger can prove it.

| Payment | Debit | Credit |
|---|---|---|
| Transfer | sender | receiver |
| Add money | `PG_CLEARING` (money arriving from the payment gateway) | user |
| Withdraw | user | `BANK_PAYOUT_CLEARING` (owed to the user's bank) |
| Bill payment | user | `BILLER_PAYABLE` (owed to the biller) |
| Balance from before the ledger | `OPENING_BALANCE` | user |

- **Accounts:** one per user (`USER:<id>`, opened with the wallet) plus the system accounts above. User accounts are credit-normal: **balance = credits − debits**.
- **Rules as a pure function:** `LedgerEntries.forPayment(...)` turns a payment into lines with no Spring and no database, so jqwik checks its invariants over thousands of generated payments (always balanced, users move the right way, the right counterparty).
- **Exactly once:** rejected payments post nothing; `journal_entry.transaction_id` is unique, behind the existing `wallet_processed_transaction` check.
- **Opening balances:** the migration that introduced the ledger posted every existing balance against `OPENING_BALANCE`, so history starts consistent. Roll it out with **every wallet_service replica stopped first** (Kubernetes `Recreate`): an old replica still running after the migration would change balances without postings, and reconciliation would flag those wallets from then on.
- **Reconciliation:** `ReconciliationService` runs nightly (`LEDGER_RECONCILE_CRON`, default 02:30) on **one replica only** (ShedLock row lock, using the database clock). It checks every wallet's cached balance against its postings and that all postings net to zero; a failure is logged at ERROR naming each user and the difference, e.g. `user 3: wallet 101.00, ledger 100.00 (off by 1.00)`.

---

## Data model

Each service owns its schema on one MySQL server (`user_db`, `wallet_db`, `txn_db`), with its own MySQL user and its own Flyway migrations. **No foreign keys or queries cross service boundaries**: a service user can't even read another schema. The dotted lines are logical references by ID.

```mermaid
erDiagram
    WALLET_USER {
        bigint user_id PK
        string user_full_name
        string user_mobile_no UK
        string user_email_id UK
        string user_pan UK
        string user_address
        string password_hash "BCrypt"
        int failed_login_attempts
        datetime last_failed_login_at
        datetime locked_until
        datetime created_date
        datetime updated_date
    }
    WALLET {
        bigint wallet_id PK
        bigint user_id UK
        string owner_name "copy, for emails"
        string owner_email "copy, for emails"
        decimal balance "DECIMAL(19,2)"
        decimal daily_limit "default 10000"
        int daily_transaction_limit "default 10"
        decimal spent_today
        int txn_count_today
        date usage_date
    }
    WALLET_PROCESSED_TRANSACTION {
        string transaction_id PK "idempotency"
        string status
        string reason
        string from_name
        string to_name
    }
    WALLET_TRANSACTIONS {
        string transaction_id PK "UUID"
        string type "TRANSFER, ADD_MONEY, WITHDRAW, BILL_PAYMENT"
        bigint sender_id "null for ADD_MONEY"
        bigint receiver_id "null for WITHDRAW, BILL_PAYMENT"
        string sender_name
        string receiver_name
        decimal amount "DECIMAL(19,2)"
        string status "PENDING, SUCCESSFUL, REJECTED"
        string failure_reason
        string remark
        string bill_category
        string bill_account
        datetime payment_initiation_date
    }
    LEDGER_ACCOUNT {
        bigint id PK
        string code UK "USER:id or PG_CLEARING, ..."
        bigint user_id UK "null for system accounts"
    }
    JOURNAL_ENTRY {
        bigint id PK
        string transaction_id UK "one entry per payment"
        string type "TRANSFER, ADD_MONEY, ..., OPENING_BALANCE"
    }
    LEDGER_POSTING {
        bigint id PK
        bigint entry_id FK
        bigint account_id FK
        string direction "DEBIT, CREDIT"
        decimal amount "DECIMAL(19,2)"
    }
    OUTBOX_EVENT {
        bigint id PK
        string producer "user-service, wallet-service, transaction-service"
        string topic
        string message_key
        text payload "JSON, MEDIUMTEXT"
        datetime created_at
    }

    WALLET_USER ||..o| WALLET : "owns (by user_id)"
    WALLET_USER ||..o{ WALLET_TRANSACTIONS : "sends or receives"
    WALLET_TRANSACTIONS ||..o| WALLET_PROCESSED_TRANSACTION : "applied as"
    JOURNAL_ENTRY ||--|{ LEDGER_POSTING : "debit + credit"
    LEDGER_ACCOUNT ||--o{ LEDGER_POSTING : "posted to"
    WALLET ||..|| LEDGER_ACCOUNT : "balance = credits - debits"
```

Schemas and users are created once, with the MySQL volume, by `deploy/mysql/init/01-service-schemas.sh`. Each service's tables come from Flyway migrations in `src/main/resources/db/migration`, and Hibernate only checks them (`ddl-auto=validate`), so a mismatch stops the service at startup instead of silently altering tables.

**Schema changes:** add `V<n>__<what_changed>.sql` next to the service's existing migrations. Never edit a migration that has already run; Flyway checksums it. Enum columns are MySQL `enum(...)`, so a new enum constant needs an `ALTER TABLE ... MODIFY` migration. Once a service runs more than one replica, make changes **expand then contract**: a migration must keep working for the previous version still running during a rollout (add a column as nullable or with a default, backfill, and tighten it in a later release).

---

## Kafka event catalogue

| Topic | Producer | Consumer(s) | Key | Payload |
|---|---|---|---|---|
| `user-service.userWalletCreation` | user_service | wallet_service | userId | `{userId, userName, userEmailId}` |
| `user-service.userUpdation` | user_service | wallet_service | userId | `{userId, userName, userEmailId}` |
| `user-service.userDeletion` | user_service | wallet_service | userId | `userId` (deletes the wallet only if its balance is 0) |
| `INITIATE_TRANSACTION_TOPIC` | transaction_service | wallet_service | transactionId | `{transactionId, fromUserId, toUserId, amount, type}` (`type` picks the ledger accounts) |
| `COMPLETED_TRANSACTION_TOPIC` | wallet_service | transaction_service | transactionId | `{transactionId, paymentStatus, reason, fromName, toName}` |
| `SEND_NOTIFICATION` | user_service, wallet_service | notification_service | recipient email | `{receiverMailId, subject, message}` |

Keys keep ordering per user and per transaction. Payload classes live in `paymesh-common/.../dto`. Every topic has a `<topic>.DLT` twin for messages that failed all retries, and every message carries `eventId` and `correlationId` headers (see [Reliability](#reliability-the-transactional-outbox)).

---

## Security model

**Two layers, no shortcuts.** The gateway rejects bad tokens early. Each service validates the **same token again** and takes the user's identity only from its `sub` claim, never from a request field. A `senderId` or `userId` sent in a request body is ignored. The gateway adds no identity headers, and no service trusts any.

| Area | Implementation |
|---|---|
| **Tokens** | HS256 JWT issued by user_service: `sub` = userId, `name`, `iss` = `paymesh`, 24 h expiry. Signature, expiry and issuer are checked by the gateway and every service. |
| **Secrets** | `JWT_SECRET` must be set (≥ 32 bytes, same everywhere). **Services refuse to start without it**; a development value exists only in the `local` profile. No credentials are in code. |
| **Public endpoints** | Only `POST /api/auth/register`, `POST /api/auth/login` and `GET /actuator/health` (status only). Everything else needs a token; unknown paths are denied. |
| **Ownership** | Each user sees and changes only their own profile, wallet and transactions; other people's transactions return **404**. |
| **Brute force** | Per-IP rate limit at the gateway (Redis) and per-account lockout in user_service ([details](#3-login-two-independent-brute-force-defences)) |
| **Passwords** | BCrypt; never logged or returned |
| **Errors** | `application/problem+json` everywhere; a 401 never says *why* a token failed; no stack traces reach clients |
| **Logs** | No tokens, passwords, emails or message payloads. Failed Kafka sends log the topic and error only. |
| **Spoofing** | `X-Forwarded-For` is ignored unless `TRUSTED_PROXY_COUNT` says your own proxy added it; correlation IDs from clients are reused only if they match `[A-Za-z0-9-]{1,64}` |

### Production network boundary

```mermaid
flowchart LR
    internet([Internet]) --> lb["Load balancer / TLS"]
    lb --> gw["api_gateway"]
    lb --> static["Angular static files<br/>(same origin)"]
    subgraph private["Private network (no public ports)"]
        gw --> svcs["user / wallet / transaction services"]
        gw --> data[("MySQL · Kafka · Redis")]
        svcs --> data
        notif["notification_service"] --> data
    end
```

Only the gateway (and the static UI) may be reachable from the internet. Use no published ports in Docker, `ClusterIP` in Kubernetes, or security groups that admit only the gateway. Behind a load balancer, set `TRUSTED_PROXY_COUNT=1`, otherwise all users share the load balancer's rate-limit bucket. Locally, services also listen on `localhost` ports for debugging.

---

## API reference

Base URL: `http://localhost:8080` (the gateway). All bodies are JSON. 🔒 = needs `Authorization: Bearer <token>`.

### Auth and users (user_service)

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/register` | public | Create account; returns `{token, user}` |
| POST | `/api/auth/login` | public | `{username: mobile or email, password}`; returns `{token, user}` |
| GET | `/api/users/me` | 🔒 | My profile |
| PUT | `/api/users/me` | 🔒 | Update `{fullName, emailId, address}` |
| DELETE | `/api/users/me` | 🔒 | Delete account (withdraw your balance first) |
| GET | `/api/users/lookup?mobile=98…` | 🔒 | Find a receiver: `{userId, fullName}` |

### Wallet (wallet_service)

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/wallets/me` | 🔒 | `{walletId, balance, dailyLimit, dailyTransactionLimit, spentToday, transactionsToday}` |
| PUT | `/api/wallets/me/limits` | 🔒 | `{dailyLimit: 100–100000, dailyTransactionLimit: 1–50}` |

### Transactions (transaction_service)

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/transactions` | 🔒 | Start a payment; returns `202` with `status: PENDING` |
| GET | `/api/transactions?page=0&size=20` | 🔒 | My history, newest first |
| GET | `/api/transactions/{id}` | 🔒 | Details (404 if I'm not sender or receiver) |

**Idempotency-Key (optional, the web app always sends one):** send any unique string (≤ 255 chars, e.g. a UUID) with `POST /api/transactions`, and a retry of the same request — double click, timeout, flaky network — returns the **original** response with `Idempotent-Replayed: true` instead of creating a second payment. Reusing a key with a different body is `422`. Keys are per user and remembered for 24 h. Only successful creations are remembered, so a request rejected by validation can be fixed and retried with the same key.

**Validation rules:** mobile `[6-9]` + 9 digits · password 8–64 chars · PAN `ABCDE1234F` (optional) · amount ₹1–₹1,00,000 with at most 2 decimals · remark up to 100 chars.

### Examples

```bash
# Register (you're logged in straight away)
curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"fullName":"Asha Verma","mobileNo":"9876543210","emailId":"asha@example.com","password":"secret123"}'

# Log in, keep the token
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"9876543210","password":"secret123"}' | jq -r .token)

# Add money
curl -s -X POST localhost:8080/api/transactions -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"ADD_MONEY","amount":1000}'

# Safe to retry: the same Idempotency-Key never pays twice
curl -s -X POST localhost:8080/api/transactions -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json'   -H 'Idempotency-Key: 6f1c2a9e-topup-1' -d '{"type":"ADD_MONEY","amount":1000}'

# Send money (find the receiver's userId with /api/users/lookup first)
curl -s -X POST localhost:8080/api/transactions -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"TRANSFER","amount":250.50,"receiverId":2,"remark":"Dinner"}'

# Mobile recharge
curl -s -X POST localhost:8080/api/transactions -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"BILL_PAYMENT","amount":299,"billCategory":"MOBILE_RECHARGE","billAccount":"9876543210"}'
```

`billCategory` is one of `MOBILE_RECHARGE`, `DTH`, `ELECTRICITY`, `WATER`, `GAS`, `BROADBAND`.

### Error format

Every error from the gateway and from services looks like this:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "mobileNo must be a valid 10 digit mobile number, password size must be between 8 and 64",
  "instance": "/api/auth/register"
}
```

| Status | Meaning |
|---|---|
| 400 | Validation failed (`detail` names the fields) |
| 401 | Missing, invalid or expired token, or wrong credentials |
| 404 | Not found, or not yours |
| 409 | Mobile, email or PAN already registered |
| 429 | Too many login attempts (IP) or account locked; see `Retry-After` |
| 503 / 504 | A service is down / too slow (from the gateway) |

---

## Getting started

### Prerequisites

| Tool | Version | Used for |
|---|---|---|
| Docker | any recent | everything in containers; MySQL, Kafka, Redis and the integration tests otherwise |
| Java | 21+ | only to run or test services outside Docker (Maven wrapper included) |
| Node.js | 20.19+ or 22.12+ | only to run the Angular dev server |

### Option A: everything in Docker (one command)

```bash
cd paymesh
cp .env.example .env            # then edit the secrets; compose refuses to start without them
docker compose up -d --build     # first build takes a few minutes
# Folder path has non-ASCII characters? Use: sh scripts/build-images.sh && docker compose up -d
```

Open **http://localhost:4200**. That one port serves the app and the API (`/api/**`, proxied by nginx to the gateway), so
`curl http://localhost:4200/api/...` works too. Services and the gateway are not published; MySQL `:3306`, Kafka `:9092`
and Redis `:6379` are, on localhost only.

| Container | Built from | Notes |
|---|---|---|
| `wallet-ui` | `wallet-ui/Dockerfile` | nginx: Angular app + `/api` proxy; the only way in |
| `api-gateway`, `user-service`, `wallet-service`, `transaction-service`, `notification-service` | root `Dockerfile` with `SERVICE=<module>` | JRE 21, non-root |
| `mysql`, `kafka`, `redis` | upstream images | health-checked; services wait for MySQL and Kafka |

Settings come from your shell or the `.env` file next to `docker-compose.yml` (start from `.env.example`): `MYSQL_ROOT_PASSWORD`,
`USER_DB_PASSWORD`, `WALLET_DB_PASSWORD`, `TXN_DB_PASSWORD` and `JWT_SECRET` are **required**, and `MAIL_USERNAME` / `MAIL_PASSWORD` (unset = mails are logged by `notification-service`).

```bash
docker compose logs -f notification-service    # the emails
docker compose down                            # stop; add -v to also wipe the database
```

### Option B: develop locally (services from your IDE)

#### 1. Start the infrastructure

```bash
cp .env.example .env                    # its placeholder DB password matches the local profile default
docker compose up -d mysql kafka redis    # MySQL :3306, Kafka :9092, Redis :6379
```

The `local` profile connects as `user_svc` / `wallet_svc` / `txn_svc` to `user_db` / `wallet_db` / `txn_db`, which compose's MySQL creates on its first start.

> Already have MySQL on 3306? Start only the rest (`docker compose up -d kafka redis`), run `deploy/mysql/init/01-service-schemas.sh`'s SQL against yours (or create the three schemas and users yourself), and point the services at it with `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`.

#### 2. Build and start the backend

```bash
./mvnw -q install -DskipTests            # Windows: mvnw.cmd -q install -DskipTests
```

Then start each app in its own terminal, or run the main classes from your IDE with the `local` profile:

```bash
java -jar user_service/target/user_service-0.0.1-SNAPSHOT.jar                 --spring.profiles.active=local
java -jar wallet_service/target/wallet_service-0.0.1-SNAPSHOT.jar             --spring.profiles.active=local
java -jar transaction_service/target/transaction_service-0.0.1-SNAPSHOT.jar   --spring.profiles.active=local
java -jar notification_service/target/notification_service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
java -jar api_gateway/target/api_gateway-0.0.1-SNAPSHOT.jar                   --spring.profiles.active=local
```

The `local` profile supplies a **development-only** JWT secret. Anywhere else, set `JWT_SECRET` instead (same value on all five).

#### 3. Start the web app

```bash
cd wallet-ui
npm install
npm start                    # http://localhost:4200, proxies /api to the gateway on :8080
```

### Try it

1. Open http://localhost:4200 and **create an account**. The dashboard shows "being created" for a moment, then your ₹0.00 wallet.
2. **Add money**, then register a second user in a private window.
3. **Send money** to the second user's mobile number, watch the status go from *Processing* to *Payment successful*.
4. Try to **withdraw** more than you have to see a clean rejection, and check **History** for details.
5. Emails appear in the `notification_service` log (or in your inbox, if SMTP is configured).

---

## Configuration

All settings come from environment variables with local-friendly defaults.

| Variable | Default | Used by | Purpose |
|---|---|---|---|
| `JWT_SECRET` | **required** (`local` profile has a dev value) | all | HS256 signing key, ≥ 32 bytes, identical everywhere |
| `JWT_ISSUER` | `paymesh` | all | Expected `iss` claim |
| `DB_URL` | `jdbc:mysql://localhost:3306/user_db` (`wallet_db`, `txn_db`) | user, wallet, transaction | The service's own schema |
| `DB_USERNAME` / `DB_PASSWORD` | **required** (`local` profile: `user_svc` / `wallet_svc` / `txn_svc`, password `change-me-local-only`) | user, wallet, transaction | The service's own MySQL user |
| `USER_DB_PASSWORD` / `WALLET_DB_PASSWORD` / `TXN_DB_PASSWORD` | **required** (compose only) | mysql init, services | Passwords for the three service users |
| `KAFKA_SERVERS` | `localhost:9092` | all services | Kafka bootstrap servers |
| `IDEMPOTENCY_RETENTION` | `PT24H` | transaction | How long `Idempotency-Key` responses are remembered |
| `LEDGER_RECONCILE_CRON` | `0 30 2 * * *` (02:30 daily) | wallet | When the ledger reconciliation runs (Spring cron, one replica via ShedLock) |
| `USER_SERVICE_URL` / `WALLET_SERVICE_URL` / `TRANSACTION_SERVICE_URL` | `http://localhost:8081` / `:8091` / `:8086` | gateway | Route targets |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / none | gateway | Rate-limit store |
| `LOGIN_RATE_LIMIT_BURST_CAPACITY` / `…_REQUESTED_TOKENS` / `…_REPLENISH_RATE` | `60` / `12` / `1` | gateway | Token bucket: 5 attempts at once, then 1 every 12 s |
| `TRUSTED_PROXY_COUNT` | `0` | gateway | Proxies in front of the gateway (set `1` behind a load balancer) |
| `LOGIN_LOCKOUT_MAX_FAILED_ATTEMPTS` / `LOGIN_LOCKOUT_DURATION` | `5` / `15m` | user | Account lockout policy |
| `MAIL_HOST` / `MAIL_PORT` | `smtp.gmail.com` / `587` | notification | SMTP server |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | unset, so emails are only logged | notification | SMTP login (Gmail: use an App Password) |

Advanced (Spring properties): `outbox.poll-interval-ms` (default `250`), `spring.kafka.producer.properties.max.block.ms` (default `5000`).

---

## Testing

```bash
./mvnw test        # Docker must be running: integration tests use Testcontainers
```

The suite runs against **real** infrastructure wherever correctness depends on it: MySQL for locking, isolation and column sizes, and Redis for rate limiting. Several bugs that mocked tests missed were caught this way.

| Module | What's covered |
|---|---|
| `api_gateway` | Routing with unchanged paths; public allowlist; rejected tokens (missing, malformed, expired, wrong signature, wrong issuer); 404/503/504 ProblemDetails; correlation IDs (generated, reused, malicious values replaced); startup secret check; login rate limit (429 + `Retry-After`, only login limited, spoofed `X-Forwarded-For` ignored, works when Redis hangs) |
| `paymesh-common` | Shared JWT validation; secret checks; log correlation filter; Kafka producer defaults; payload-free failure logging; **outbox**: refuses calls outside a transaction, all-or-nothing with the data, in-order relay, stops at the first failure, ignores other services' rows, large payloads, never blocks writers |
| `user_service` | Token issuance (`iss`, `sub`); ownership; registration queues wallet creation; lockout rules, 429 + `Retry-After`, correct password refused while locked, exactly one email per lock; real MySQL: 12 parallel guesses give one lock and one email; Kafka outage keeps the registration event |
| `wallet_service` | Debit and limit rules; payment results and emails queued with the change; duplicates ignored; ownership |
| `transaction_service` | Sender taken from the token, never the body; payment queued with its event; not-yours gives 404; history scoped to the caller |

---

## Project structure

```
paymesh/
├── api_gateway/                 Spring Cloud Gateway (reactive)
│   └── src/main/java/com/paymesh/gateway/
│       ├── security/            JWT validation, 401 ProblemDetail entry point
│       ├── ratelimit/           client-IP key resolver, 429 ProblemDetail filter
│       ├── correlation/         X-Correlation-Id filter
│       └── error/               503/504/404 ProblemDetail handler
├── paymesh-common/       shared jar (component-scanned by every service)
│   └── src/main/java/com/paymesh/
│       ├── dto/  constants/  enums/   event payloads and topic names
│       ├── security/            SecurityConfig (JWT), JwtProperties, ApiExceptionHandler
│       ├── outbox/              Outbox, OutboxRelay, OutboxEvent (transactional outbox)
│       ├── kafka/               producer defaults (5 s max block, payload-free logging)
│       └── logging/             correlation ID → log context
├── user_service/                accounts, JWT issuing, lockout (Flyway: src/main/resources/db/migration)
├── wallet_service/              wallets, balances, applying payments (Flyway as above)
├── transaction_service/         payments, history (Flyway as above)
├── notification_service/        emails
├── wallet-ui/                   Angular 21 app
│   └── src/app/
│       ├── api.ts               HTTP client, JWT interceptor, auth guard, types
│       └── pages/               auth · dashboard · pay · history · profile · txn-list
├── deploy/mysql/init/           creates one schema + user per service on a new MySQL volume
├── scripts/build-images.sh      builds compose images one at a time (non-ASCII path workaround)
├── .env.example                 secrets template for compose (copy to .env)
├── Dockerfile                   one image per Spring Boot service (--build-arg SERVICE=<module>)
├── docker-compose.yml           whole stack, or infra only for IDE runs
└── Requirements                 original project brief
```

---

## Design decisions

| Decision | Why |
|---|---|
| **Saga over Kafka instead of synchronous HTTP between services** | Services stay independent; a slow or down service doesn't cascade; payments resume after outages |
| **Wallet service decides, not transaction service** | The balance check and the debit happen in *one* transaction under row locks. Checking in one service and debiting in another allowed double spending in the original design. |
| **Transactional outbox** | DB-then-Kafka loses events on crashes and outages; the outbox makes events part of the commit |
| **JWT validated at gateway *and* services** | Defence in depth: bypassing the gateway grants nothing; services never trust headers |
| **HS256 shared secret** | Simple for one issuer inside one trust boundary; easy to move to RS256/JWKS if third parties ever verify tokens |
| **Schema per service, Flyway migrations, `ddl-auto=validate`** | Services can't couple through tables; every schema change is reviewed SQL with a history; a drifted schema fails fast at startup |
| **Double-entry ledger plus a cached balance** | Reads stay O(1); every rupee is explained by balanced postings, and a nightly reconciliation proves cache and ledger agree |
| **Ledger rules as a pure function** | The invariants (balanced, right accounts) are property-tested over thousands of generated payments, independent of Spring and MySQL |
| **Idempotency key stored in the payment's own transaction** | Key, payment and outbox event commit together; concurrent duplicates serialise on the unique `(user, key)` index and replay the winner's response, with no lock service |
| **`BigDecimal` for money** | `double` can't represent ₹0.10 exactly |
| **ProblemDetail (RFC 7807) everywhere** | One error shape for the UI, from the gateway and every service |
| **`spring.jpa.open-in-view=false` in user_service** | With it on, the row-locked read returned a stale cached copy and concurrent failed logins overwrote each other's count |
| **Gateway on its own Boot parent** | The root POM adds servlet `spring-boot-starter-webmvc` to every module; the gateway must be reactive |
| **Java 21 virtual threads** (`spring.threads.virtual.enabled`) | Every service blocks on JDBC, Kafka or SMTP; virtual threads make that cheap without going reactive |
| **Fail-open rate limiting** | A Redis outage must not lock every user out; the per-account lockout still applies |

---

## Known limitations and roadmap

- **Emails are at-most-once.** Duplicates are suppressed by `eventId`; a crash between claiming the id and sending can lose one email (by design: a duplicate payment email is worse than a missing one). If Redis is down, mail is sent without the duplicate check.
- **Notification dead letters** stay on `SEND_NOTIFICATION.DLT` (notification_service has no database); replay them with the Kafka CLI.
- **Tokens can't be revoked** before their 24 h expiry. Next step: short access tokens plus refresh tokens.
- **Lockout can be abused**: someone who knows a mobile number can keep that account locked, 15 minutes at a time. Mitigations: CAPTCHA after failures, or unlock by email.
- **Users behind one shared IP** (office, mobile carrier NAT) share a login rate-limit bucket.
- **The UI keeps the token in `localStorage`**, which an XSS bug could read. An HttpOnly cookie would be safer.
- **Simulated bank**: add money and withdraw don't call a real payment gateway.

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `jwt.secret (env JWT_SECRET) must be set and at least 32 bytes` at startup | Intended. Run with `--spring.profiles.active=local`, or set `JWT_SECRET` |
| `Access denied for user 'user_svc'` (or `wallet_svc`, `txn_svc`) or `Unknown database 'user_db'` after upgrading | Your `mysql-data` volume predates the per-service schemas, and init scripts only run on a new volume. `docker compose down -v` (this **wipes local data**), then start again |
| `Access denied for user '...'@'localhost'` from the IDE | Your MySQL has different credentials: set `DB_USERNAME` / `DB_PASSWORD` (and `DB_URL` if not on 3306) |
| Wallet says *"being created"* for a long time | Kafka or wallet_service isn't running. Nothing is lost: pending events sit in `outbox_event` and go out once both are up |
| Payment stuck at *Processing* | Same as above: check Kafka and wallet_service |
| Login returns **429** | Too many attempts from your IP, or the account is locked; wait for `Retry-After` seconds |
| `docker compose up --build` fails with *header key "x-docker-expose-session-sharedkey" contains value with non-printable ASCII characters* | A Docker Compose/BuildKit bug when the folder path has non-ASCII characters (e.g. an em dash) and several images build at once. Run `sh scripts/build-images.sh` (builds them one at a time), then `docker compose up -d` |
| Tests fail with a Docker error | Start Docker (Testcontainers runs MySQL and Redis for the integration tests) |
| `npm start` fails with a Node version error | Angular 21 needs Node 20.19+ or 22.12+ |
| UI shows *"Server is unreachable"* | The gateway (`:8080`) isn't running, or the UI isn't served through `npm start` (which proxies `/api`) |
| No emails arrive | Without `MAIL_USERNAME` / `MAIL_PASSWORD` they're printed in the notification_service log instead |
