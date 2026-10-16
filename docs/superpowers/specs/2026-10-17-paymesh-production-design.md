# PayMesh 2.0 — Production-Grade Portfolio Design

Date: 2026-10-17 · Status: draft for approval

## 1. Intent

PayMesh is a portfolio project for Java backend roles (Spring Boot, microservices, Kafka, AWS). It is **not** a real-money product: no real funds, no RBI licence, no real KYC. Payment gateways run in test mode.

The goal is a live link a recruiter can open, a polished app they can click through in 30 seconds without registering, and a 10-minute interview walkthrough that shows real engineering depth. Each feature exists because it gives a strong interview talking point *and* something visible to demo.

**Priority set by the owner:** backend depth first, then visual polish. All three tiers are in scope: foundations, depth features and UI, plus Kubernetes, rewards/cashback and two-factor login.

## 2. Starting point (as of commit d5b20dc)

**What exists:**
- **Services:** Spring Boot 4.0.8 on Java 21, with virtual threads. Five services: user (8081), wallet (8091), transaction (8086), notification (8082) and api_gateway (8080, Spring Cloud Gateway on WebFlux). Shared code lives in `paymesh-common`.
- **UI:** Angular 21 `wallet-ui`.
- **Data:** one MySQL 8.4 database (`paymesh`) shared by all services, with `ddl-auto=update`. Redis is used only for the login rate limit.
- **Saga:** choreography over Kafka with a transactional outbox (`paymesh-common/.../outbox`).
- **Wallet model:** wallet rows are locked pessimistically in ascending userId order. Each wallet stores a balance column, and payments are deduplicated through `ProcessedTransaction`.
- **Auth:** JWT with HS256 and a shared secret, 24 h expiry, no refresh token. The JWT is checked at both the gateway and each service.
- **Tests:** 124 passing (JUnit 5, Mockito, Testcontainers for MySQL and Redis). There are no Kafka tests and no UI tests.
- **README:** 731 lines.

**Known gaps:**
- **Data:** no migrations, and one database is shared by every service.
- **Kafka:** no retries or dead-letter topic. Notifications aren't deduplicated. `TransactionService.updateTransactionStatus` is not transactional and can overwrite a final state.
- **Missing infrastructure:** no CI, no metrics or tracing, no Kubernetes.
- **Secrets:** hard-coded; the DB password is `Abc@1234`.
- **UI:** the test setup is broken (it references vitest, which isn't installed), and the UI uses `alert()`.
- **Bugs:**
  - `docker compose up --build` fails on paths with non-ASCII characters, and this repo's folder name contains an em dash.
  - `open-in-view` is still on in the wallet and transaction services.

## 3. Goals

1. **Correct money movement**, explainable on a whiteboard:
   - a double-entry ledger, with a reconciliation job that proves balance = sum of postings
   - idempotent APIs and idempotent consumers
   - guarded state machines
2. **Reliable messaging:** retries with backoff, dead-letter topics, a dead-letter store that an admin can replay, and every message traceable.
3. **Production operations:**
   - database migrations and a database per service
   - metrics, logs and traces in Grafana
   - CI/CD
   - Kubernetes (Helm) on AWS, provisioned with Terraform
   - secrets kept out of git
4. **Feature set that demos well:**
   - Razorpay test-mode top-ups
   - a real-time fraud/risk engine (Kafka Streams)
   - request money and split bills
   - live updates (SSE)
   - an admin console
   - rewards/cashback with scratch cards
   - TOTP two-factor login and step-up
   - spending analytics, QR pay, PDF statements
5. **An attractive UI and a zero-friction demo:**
   - a public landing page
   - "Try the demo" one-click login as seeded users
   - a "View trace" link that shows the recruiter the distributed trace of the payment they just made

## 4. Non-goals

- **No real money:** no real KYC, no real bank payouts, no UPI. Withdraw stays simulated, because Razorpay payouts need business KYC.
- **No native mobile app.** The web UI is responsive and mobile-first.
- **No multi-node or multi-region HA, and no managed AWS data services** (RDS, MSK, ElastiCache, EKS). They cost too much on the budget. The README names them as the production upgrade path.
- **No new service for every feature.** Exactly one new service is added (risk). Everything else goes into the existing services.

## 5. Anti-goals (failure even if it "works")

- The live link is down, slow, or out of memory when a recruiter clicks it.
- The owner can't explain a piece of it in an interview. Every component needs a one-paragraph "why" in the README.
- Existing behaviour or tests regress.
- The UI looks like a tutorial template.
- The AWS bill exceeds the credits without warning.

## 6. Constraints

- **Hosting budget (owner's decision):** AWS **Spot**, with a hard cap of **$20 a month**. It must be paid entirely from AWS credits; nothing comes out of the owner's pocket.
  - Everything must run on **one node with 8 GB RAM**, so the memory budget is a hard constraint.
  - When the credits run out, the instance is stopped and the README falls back to the demo video and local setup. Moving to Hetzner is a documented option for the owner, not automatic.
- **ARM:** the AWS instance is ARM (Graviton). All images must be multi-arch (amd64 + arm64).
- **Development machine:** Windows 11, with the repo path containing an em dash. Local tooling must work there.
- **Versions:** keep Java 21, Spring Boot 4, Spring Cloud 2025.1 and Angular 21.
- **Git (owner's rule):** Claude never commits or pushes unless explicitly told. There is no Co-Authored-By line.

## 7. Design

### 7.1 Data foundations
- **Flyway** in each stateful service. The V1 baseline matches today's schema exactly. After that, `ddl-auto=validate` everywhere.
- **Database per service:** one MySQL server holds the schemas `user_db`, `wallet_db`, `txn_db` and `risk_db`. Each service gets its own MySQL user with grants only on its own schema.
  - The outbox and dead-letter tables live inside each service's schema.
  - A one-off migration note explains how to move existing local data. Dropping local dev data is acceptable.
- `spring.jpa.open-in-view=false` everywhere.

### 7.2 Messaging reliability
- **Event envelope:** every event carries an `eventId` (a UUID) and an event type and version in Kafka headers. It also carries `traceparent` and the correlation ID, which are stored on the outbox row so tracing survives the outbox hop.
- **Retries:** a `DefaultErrorHandler` with exponential backoff (3 attempts) is configured once in `paymesh-common`.
  - Non-retryable errors go straight to the DLT: deserialization failures and validation errors.
  - After retries, messages go to `<topic>.DLT`.
- **Dead-letter store:** a shared consumer in `paymesh-common` reads each service's DLTs and records the messages in a `dead_letter` table: payload, headers, exception, and status (NEW, REPLAYED or DISCARDED). Admins can list, replay (republish through the outbox) or discard them.
- **Idempotent consumers:**
  - The wallet keeps `ProcessedTransaction`.
  - The notification service deduplicates on `eventId` using Redis `SET NX` with a 7-day TTL.
  - Transaction status updates become a guarded state machine: only PENDING → final and ON_HOLD → final. Late or duplicate results are ignored and logged.
- **Tests:** Kafka integration tests use Testcontainers Kafka. They cover the full saga, a redelivered message, poison messages reaching the DLT, and the outbox surviving a Kafka outage.

### 7.3 Double-entry ledger (wallet_service)
- **Tables:**
  - `ledger_account`: user wallets plus system accounts `PG_CLEARING` (Razorpay), `BANK_PAYOUT_CLEARING`, `BILLER_PAYABLE` and `REWARDS_EXPENSE`.
  - `journal_entry`: one per transaction, unique on `transactionId`.
  - `ledger_posting`: rows of (entry, account, amount, DEBIT/CREDIT). The postings in each entry must sum to zero, which is checked in code and covered by a property test.
- **Balance:** `Wallet.balance` stays as a cached balance updated in the same database transaction as the postings, so reads stay O(1).
- **Payment mapping:**
  - TRANSFER: debit the sender, credit the receiver.
  - ADD_MONEY: debit PG_CLEARING, credit the user.
  - WITHDRAW: debit the user, credit BANK_PAYOUT_CLEARING.
  - BILL_PAYMENT: debit the user, credit BILLER_PAYABLE.
  - CASHBACK: debit REWARDS_EXPENSE, credit the user.
- **Reconciliation:** a nightly scheduled job (ShedLock, so only one replica runs it) checks that every wallet's balance equals the sum of its postings and that all postings net to zero globally. Mismatches raise a metric and alert, and show up in the admin console.
- **Existing balances:** a migration posts an opening-balance entry for each existing wallet.

### 7.4 Idempotency keys (transaction_service)
- `POST /api/transactions`, request creation and top-up creation accept an `Idempotency-Key` header. It is required from the UI and optional for curl.
- **Table** `idempotency_key`: `userId`, `key`, a request hash, the response status and body, and `createdAt`. The table is unique on `(userId, key)` and rows expire after 24 h.
  - The same key with the same body replays the stored response.
  - The same key with a different body returns 422.
  - Concurrent duplicates are settled by the unique constraint.

### 7.5 Auth hardening (user_service and gateway)
- **Signing:** switch from HS256 to **RS256**. user_service publishes `/.well-known/jwks.json`, and the gateway and services validate against that JWKS, so there's no shared secret. The key pair comes from secrets, with support for key rotation through `kid`.
- **Access token:** lives for 15 minutes and is held in memory in the UI, never in localStorage. Claims: `sub`, `name`, `roles`, `amr`, `jti`.
- **Refresh token:**
  - Opaque, with only its SHA-256 hash stored in `refresh_token`.
  - Rotated on every use. Reusing an old token revokes the whole token family.
  - Sent as an `HttpOnly; Secure; SameSite=Strict` cookie, scoped to `/api/auth`.
- **Logout:** revokes the token family and puts the access token's `jti` on a Redis denylist (TTL = remaining lifetime), which the gateway checks.
- **Roles:** `USER` and `ADMIN`. The admin is seeded from config or secrets. `/api/admin/**` requires ADMIN at both the gateway and the service.
- **TOTP 2FA:**
  - Optional per user. Enrolment shows a QR code and gives 8 hashed recovery codes.
  - Login with 2FA enabled is two steps: password, then a 6-digit code.
  - **Step-up:** payments above ₹5,000 (configurable) need a token whose `amr` includes `otp` and was issued within the last 5 minutes. Otherwise the API returns 401 with a `step_up_required` problem type, and the UI prompts for the code.
- **Lockout abuse fix:** a locked account can be unlocked through a one-time email link, which closes the README's "lockout can be abused" item.

### 7.6 Observability
- Actuator, Prometheus metrics and health probes on every service.
- **Tracing:** OpenTelemetry through Micrometer Tracing, with Kafka observation turned on for both the template and the listeners, and the `traceparent` carried through the outbox (7.2).
- **Logs:** structured JSON logs (Spring Boot's built-in structured logging) that include `traceId`.
- **Stack:** Grafana with Prometheus, Tempo and Loki. Locally this is the single `grafana/otel-lgtm` image behind a compose profile. In Kubernetes it's a slim deployment of the same stack.
- **Dashboards as code:**
  - Payments: throughput, success/rejected/held rate, and saga latency p50/p95/p99.
  - Kafka: consumer lag and outbox backlog.
  - DLQ count.
  - JVM.
  - Ledger reconciliation status.
- **Alerts:** outbox backlog growing, DLQ above 0, reconciliation mismatch, and a pod restarting repeatedly.
- **Public demo view:** an anonymous read-only Grafana org exposes the dashboards and Tempo. The UI's transaction detail page shows a "View trace" link to that payment's trace.

### 7.7 Razorpay top-ups (transaction_service)
- **Flow:**
  1. `POST /api/topups` creates a Razorpay order through the REST API using test keys, plus an ADD_MONEY transaction in `AWAITING_PAYMENT`.
  2. The UI opens Razorpay Checkout.
  3. The `POST /api/webhooks/razorpay` webhook is public, and its HMAC signature is verified. It handles `payment.captured`, deduplicates by Razorpay event ID, moves the transaction to PENDING and emits it through the existing saga.
- **Fallback:** the client callback also verifies the payment signature, so things still work if the webhook is late.
- **Reconciliation:** a ShedLock job polls Razorpay for orders stuck in `AWAITING_PAYMENT` for more than 15 minutes, and marks them captured or expired.
- **Without keys:** if no Razorpay keys are configured, the service falls back to today's simulated top-up, so local runs need no account.

### 7.8 Risk engine (new `risk_service`, Kafka Streams)
- **Saga change:**
  1. transaction_service emits `payment.initiated`.
  2. risk_service evaluates it and emits `payment.assessed` with a decision of APPROVE, HOLD or BLOCK and the reasons.
  3. The wallet applies only APPROVE.
  4. transaction_service marks BLOCK as REJECTED and HOLD as a new `ON_HOLD` status.
- **Rules:**
  - Velocity: more than 5 payments per sender in 1 minute, using a windowed count in a state store.
  - Fan-out: more than 4 distinct receivers in 10 minutes.
  - Amount anomaly: more than 3× the sender's rolling 30-day average and over ₹2,000.
  - New payee with a high amount.
  - A static blocklist.
  - Thresholds come from config. Each rule is a small class implementing one interface, which is justified here because there are 5 rules and more will come.
- **Holds:** a held payment shows up in the admin review queue. When an admin approves, the service emits `payment.assessed` with APPROVE (manual), and the saga continues. Rejecting gives REJECTED.
- **Availability:** if risk_service is down, payments wait in PENDING; Kafka buffers them. The service fails closed but only adds delay. This is documented.
- **Storage:** `risk_db` stores decisions for the audit trail and the admin UI.

### 7.9 Request money and split bills (transaction_service)
- **`payment_request` table:** requester, payer, amount, note, status (PENDING, PAID, DECLINED, CANCELLED, EXPIRED), `expiresAt` (7 days), and an optional `splitGroupId`.
  - Paying a request creates a TRANSFER through the normal saga, with an idempotency key derived from the request ID.
  - A ShedLock job expires old requests.
- **Split bill:** one call creates N requests in a group, split equally or by custom amounts, with paise rounding to the requester. The requester sees per-person status.

### 7.10 Rewards and cashback (module inside transaction_service)
- **Campaigns** are defined in config: first top-up, bill payments, and every 10th transfer.
- After a SUCCESSFUL payment, the rules engine may create a `reward` in state `SCRATCH_PENDING`, with the amount decided at creation.
- Scratching it in the UI creates a CASHBACK transaction through the saga (ledger: REWARDS_EXPENSE → user).
- **Abuse guards:** caps per user per day, no rewards on CASHBACK itself, and no rewards for self-loops between the same two users.

### 7.11 Live updates (notification_service)
- **Endpoint:** `GET /api/events/stream` uses SSE, authenticated with a Bearer token. The UI uses fetch-based SSE because the native EventSource can't send headers.
- **Delivery:** each notification pod consumes payment and request events with a pod-unique consumer group (`notification-sse-${HOSTNAME}`), so every pod sees every event, and pushes them to that user's open connections.
  - Events must carry the user IDs; `TransactionResultDto` gains sender and receiver IDs.
- **Uses:** live balance updates, a toast when a payment is received, request updates, and polling removed from the UI.
- **Gateway:** routes are set up so SSE responses aren't buffered or timed out (heartbeat every 15 s).
- **notification_service** still sends emails, and now has a real reason to run a web server.
- **In-app inbox:** every notification is also stored in a per-user in-app inbox (Redis list, last 50, with a bell icon in the UI).
- **No real email in production.** The live site runs with outbound email off (log-only), so the inbox is how visitors see "the email they would have got". Email over SMTP remains a config switch for local use.
- **Open registration in production** is rate-limited per IP, and new accounts are labelled "public". Logs and traces must never include email, mobile or names: a log/trace scrubbing filter replaces them with the userId before the public Grafana view goes live.

### 7.12 Admin console
- **API:** `/api/admin/**` endpoints in each service:
  - user: list and search users, lock/unlock
  - wallet: freeze/unfreeze (a frozen wallet rejects debits)
  - wallet: reconciliation report
  - transaction: search all transactions
  - risk: review queue (approve or reject)
  - all: dead-letter list, replay or discard
- **UI:** an `/admin` area in the same Angular app, lazy-loaded and guarded by role.
- **Audit:** every admin action is written to an `admin_audit` table in the service that performs it.

### 7.13 UI and demo experience
- **Tech (builder's call, recorded):**
  - Tailwind CSS v4 + Angular CDK (dialogs, overlays) + Lucide icons
  - Chart.js for charts
  - `qrcode` for generating QR codes, plus the zxing browser library for scanning them
- **Design:** a fintech look with a refined version of today's navy/sky palette and light/dark themes. It uses toasts, skeleton loaders, subtle motion that respects `prefers-reduced-motion`, and an empty state on every list.
  - Target WCAG AA contrast and full keyboard navigation.
  - Mockups go to the owner for a reaction before this phase is built.
- **Pages:**
  - Public landing page: hero, feature highlights, architecture diagram, "Try the demo".
  - Dashboard: balance, quick actions, recent activity, rewards.
  - Pay, Scan & Pay (QR), My QR, Requests / Split, History with filters and search, Transaction detail with a timeline and a "View trace" link.
  - Analytics: spend by category and month, top payees.
  - Statements: a monthly PDF generated server-side with OpenPDF in transaction_service.
  - Profile & Security: 2FA, sessions, limits.
  - Admin.
- **Demo mode: a sandbox per visitor.**
  - **What the visitor gets:** "Try the demo" creates a fresh sandbox user for that visitor, with ₹10,000, 30 days of seeded history, two pending requests and one unscratched reward.
  - **Counterparties:** the visitor's counterparties are the shared bot users Asha, Ravi and Meera, each with a large balance.
    - The bots auto-accept requests and occasionally send money back, which drives the live SSE toasts.
    - The bots are exempt from risk velocity and fan-out rules and from reward caps. Sandbox users are *not* exempt, so visitors can trigger a hold on purpose; the landing page explains how.
  - **Lifetime:**
    - Sandboxes expire after 24 h and a CronJob deletes them, with their ledger entries going to a closing entry.
    - At most 200 live sandboxes; after that, the oldest is recycled.
    - Creating a sandbox is rate-limited per IP.
  - **Restrictions:** sandbox users can't change their password. 2FA can be enrolled, which demonstrates step-up.
  - **One-click admin:** a separate read-only "demo admin" login shows the admin console, with actions limited to sandbox data.
- **Tests:** fix the vitest setup, add unit tests for services and guards, and add Playwright end-to-end tests for the golden paths: demo login, transfer, top-up (simulated), request/split, and the admin hold review.

### 7.14 Kubernetes
- **Cluster:** **k3s** on a single node. Locally the same chart runs on k3d, documented for Windows.
- **Charts:**
  - One Helm chart, `deploy/helm/paymesh`, with a shared deployment template and a values block per service.
  - Each service gets readiness and liveness probes from actuator groups, resource requests and limits sized to the memory budget, and runs as non-root with a read-only root filesystem.
- **Infrastructure in-cluster:**
  - Kafka uses the Strimzi operator in KRaft mode with 1 broker.
  - MySQL and Redis use plain StatefulSets with PVCs (local-path).
  - LGTM observability as in 7.6.
- **Networking and security:**
  - Ingress: Traefik (bundled with k3s) plus cert-manager with Let's Encrypt.
  - NetworkPolicies: only the gateway can reach the services, and only services can reach the data stores.
  - Secrets: External Secrets Operator reading **AWS SSM Parameter Store**. For local k3d, a plain Secret is created from `.env`.
- **Scaling and jobs:**
  - An HPA on transaction-service (CPU), demoable with k6.
  - CronJobs: demo reset, and DB backup to S3 (mysqldump, 7-day retention).
- **GitOps:** **Argo CD** syncs from `deploy/` in the repo.
- **Memory budget** for an 8 GB node:
  - k3s ~0.6 GB
  - 7 JVMs at ~350 MB each (`-XX:+UseSerialGC`, `MaxRAMPercentage`, a capped thread stack)
  - Kafka 1 GB, MySQL 0.6 GB, Redis 0.1 GB
  - LGTM ~1 GB, Argo CD ~0.4 GB, nginx/UI small
  - If measurements show it doesn't fit, the order of fallbacks is: shrink LGTM retention, run Argo CD's server only when needed, then move up to 16 GB.

### 7.15 AWS with Terraform
- **Layout:** `deploy/terraform/`.
- **State:** stored in S3 with S3-native locking.
- **Compute:** one `t4g.large` EC2 instance (Graviton, 8 GB) as a **persistent Spot request**, with `instance_interruption_behavior = "stop"`.
  - If AWS reclaims capacity, the instance stops and starts again on its own, keeping its disk and Elastic IP. There's no Auto Scaling group and no volume-reattach logic.
  - All pods come back through k3s, and data on the PVCs survives.
  - Disk: gp3 20 GB.
- **Cost estimate:** about $13–16 Spot + $1.60 EBS + $3.65 public IPv4, roughly **$18–21 a month**, which is right at the cap.
  - The fallback if Spot prices rise is `t4g.medium` (4 GB) running a slim profile: no LGTM, no Argo CD UI, and smaller JVM heaps.
  - A documented "slim" values file exists for that case.
- **Network:** a security group allowing only 80/443 inbound. Admin access goes through SSM Session Manager; there's no SSH port or key.
- **IAM:** an instance role that can read SSM parameters under `/paymesh/*` and write the S3 backup bucket.
- **Bootstrap:** user_data installs k3s, then Argo CD, then the app-of-apps.
- **Cost controls (hard guardrail):**
  - AWS Budgets with a **$20 monthly cap**, with email alerts at 50% and 80%, and a forecast alert at 100%.
  - A **Budget action at 100% of actual spend stops the EC2 instance automatically**, so overspending isn't possible.
  - A separate alert fires when the remaining credits drop below $20.
  - A documented `stop`/`start` script pauses the box between interview seasons.
- **DNS:** use the owner's domain if there is one; otherwise `<ip>.sslip.io` with Let's Encrypt.
- **Moving to a cheap VM later:** the same k3s + Argo CD bootstrap script runs on Hetzner; only the Terraform provider module changes.

### 7.16 CI/CD (GitHub Actions)
- **PR checks:**
  - Maven build and tests, including Testcontainers.
  - UI lint, test and build.
  - Playwright end-to-end against compose.
  - CodeQL.
  - Trivy scan of the images.
  - Dependabot for Maven, npm and Actions.
- **Main branch:**
  - Build multi-arch images with buildx and push them to GHCR tagged with the git SHA.
  - A bot commit bumps the image tags in `deploy/helm/paymesh/values-prod.yaml`, and Argo CD rolls them out.
  - A smoke test hits the live `/actuator/health` and a synthetic login.
- **Fix:** the compose build bug on non-ASCII paths is fixed (or worked around and documented) so that `docker compose up --build` works in this checkout.

### 7.17 Quality and docs
- **Tests:**
  - Every phase keeps all existing tests green and adds tests for its own logic.
  - The ledger invariant gets a property test (jqwik).
- **Load testing:** a k6 script and recorded results (throughput, p95) go in the README.
- **Docs:**
  - The README is rewritten around the demo: live link, a 2-minute GIF, architecture diagrams (C4 context and container, the saga sequence, the ledger), a "why" for each component, and an interview cheat-sheet.
  - Architecture decision records go in `docs/adr/`, one per big call: ledger, RS256/JWKS, risk inline vs async, k3s vs EKS, and Argo CD.
  - "Known limitations" is updated to match what was built.

## 8. Delivery phases

Each phase is shippable on its own: tests green, README updated, nothing half-built. Each phase gets its own implementation plan, written just before the work starts.

| # | Phase | Covers |
|---|---|---|
| 0 | Hygiene and CI skeleton | Compose path fix, secrets to `.env`, open-in-view, UI test setup, GitHub Actions build+test |
| 1 | Data foundations | 7.1 |
| 2 | Messaging reliability | 7.2 |
| 3 | Double-entry ledger | 7.3 |
| 4 | Idempotency keys | 7.4 |
| 5 | Auth hardening and 2FA | 7.5 |
| 6 | Observability | 7.6 (except the public demo view, which comes in phase 9) |
| 7 | Kubernetes locally | 7.14 on k3d |
| 8 | AWS + GitOps live | 7.15, 7.16 → **live link exists from here** |
| 9 | UI redesign foundation + landing page + demo mode | 7.13 shell, design system, demo users, public Grafana |
| 10 | Razorpay top-ups | 7.7 |
| 11 | Live updates | 7.11 |
| 12 | Requests and split bills | 7.9 |
| 13 | Risk engine | 7.8 |
| 14 | Admin console | 7.12 |
| 15 | Rewards and cashback | 7.10 |
| 16 | Analytics, QR, PDF statements | Rest of 7.13 |
| 17 | Polish | k6, Playwright suite, README/ADRs/diagrams, demo GIF |

Every feature phase from 10 onwards includes its UI on the new design system.

## 9. Left to the builder

- Package layout and class names within the existing conventions.
- Exact Flyway script contents, as long as V1 matches the current schema.
- Exact risk thresholds and reward amounts; config defaults are fine.
- Grafana dashboard layouts.
- Helm template structure.
- Exact Tailwind tokens, as long as they stay within the palette direction and the owner approves the mockups.

## 10. Calls Claude made (owner to check)

- **Hosting:** k3s on one EC2 t4g.large instead of EKS, which costs about $73 a month just for the control plane.
- **Spot interruptions:** a persistent Spot request with stop-on-interrupt, rather than an Auto Scaling group with volume reattachment. A Budget action stops the instance at $20.
- **Data:** a schema per service on one MySQL server, not separate servers.
- **Signing:** RS256/JWKS instead of a shared HS256 secret.
- **Risk:** checked inline before the wallet (it can hold payments), not after the fact.
- **Placement:** rewards, requests and Razorpay go in transaction_service, so there's only one new service (risk).
- **UI stack:** Tailwind + Angular CDK, not Angular Material or PrimeNG.
- **Infrastructure tools:** Argo CD for deploys, Strimzi for Kafka, External Secrets with AWS SSM.
- **Withdraw:** stays simulated.
- **Demo:** a sandbox per visitor, with shared bot counterparties, instead of everyone sharing three seeded accounts (which would trip risk rules and reward caps).
- **Production email:** off, replaced by an in-app inbox. Open registration is rate-limited, and PII is scrubbed from logs and traces.
