# Phase 6 — Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every service sends metrics, logs and traces to Grafana (Prometheus, Loki and Tempo through the `grafana/otel-lgtm` image). A payment can be followed as **one trace**:

```
gateway → transaction → outbox → Kafka → wallet → outbox → Kafka → transaction
```

Business dashboards and alerts are provisioned as code, and personal data is scrubbed before anything is stored.

**Architecture:**
- **Export:** Spring Boot 4.1's `spring-boot-starter-opentelemetry` exports traces and logs over OTLP, and Micrometer's OTLP registry exports metrics. Everything goes to a single collector endpoint, `OTLP_ENDPOINT`.
  - Export is off by default (`OTLP_ENABLED=false`), so tests and plain IDE runs make no noise. Compose turns it on.
- **Kafka:** template and listener observations are turned on.
- **The outbox hop:** the outbox stores the W3C `traceparent` from when an event was written. The relay sends the event inside a span restored from it, so the trace survives being written down and sent later by another thread.
- **Personal data:** the collector scrubs email addresses and mobile numbers from every log body and attribute, and from every span attribute, so the data never reaches storage.

**Spec:** `docs/superpowers/specs/2026-10-17-paymesh-production-design.md` §7.6. The public read-only Grafana view comes in Phase 9.

## Global Constraints

- **Versions:** from the Boot 4.1.1 BOM (Micrometer 1.17, OpenTelemetry 1.62). The logback appender is `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0`, pinned in the root `pom.xml` (property `otel-logback.version`, latest stable 2.x). The image is `grafana/otel-lgtm:0.35.0`.
- **Settings:**
  - `OTLP_ENDPOINT`, default `http://localhost:4318`, with the paths `/v1/traces`, `/v1/logs` and `/v1/metrics`.
  - `OTLP_ENABLED`, default `false`.
  - `TRACING_SAMPLING`, default `1.0`.
- **Actuator:** the services expose only `health` (with liveness and readiness probes for Phase 7). `/actuator/health/**` is public in the services; the network boundary is unchanged.
- **Metric names** (Micrometer style):

| Metric | Type | Tags |
|---|---|---|
| `paymesh.payments.completed` | counter | `status`, `type` |
| `paymesh.payments.duration` | timer, initiation → final status | `status` |
| `paymesh.outbox.backlog` | gauge | none (the service tag comes from the resource) |
| `paymesh.deadletters.new` | gauge | none |
| `paymesh.ledger.mismatches` | gauge, last reconciliation | none |

- **Commits:** one short plain line each, on `phase-6-observability`. Merge when CI is green.

## Review Focus

- **The trace must not break at the outbox:** the COMPLETED event's `traceparent` carries the same trace ID as the INITIATE that caused it. This is covered in Task 2.
- **An outbox row written outside any trace** (scheduled job, startup) must still publish, with no `traceparent`, and must not throw. This is covered in Task 2.
- **Export failures** (collector down) must never fail or slow a request. Export is batched and asynchronous. This is checked live in Task 4.
- **Personal data:** an email or mobile number in a log line or a span attribute arrives in Loki or Tempo masked. This is checked live in Task 4.
- **Cardinality:** no user IDs, transaction IDs or amounts as metric tags. This is covered in Task 3's tests, which assert on the tag keys.

---

### Task 1: Export plumbing and health in every service

**Files:**
- Modify: the root `pom.xml` (the property), the poms of `paymesh-common` and every service (`spring-boot-starter-opentelemetry`, `spring-boot-starter-actuator`, the logback appender; `api_gateway` gets the same three)
- Create: `paymesh-common/src/main/resources/paymesh-observability.properties`, holding the shared `management.*` settings, the Kafka observation flags and the export toggles
- Create: `paymesh-common/src/main/java/com/paymesh/observability/OtelLogbackInstaller.java`, which adds the OpenTelemetry logback appender to the root logger once the `OpenTelemetry` bean exists. It is conditional on that class.
- Modify: each service's `application.properties` gets `spring.config.import=optional:classpath:paymesh-observability.properties`. `api_gateway`'s `application.yml` gets the same settings inline, and its own installer copy, because it doesn't depend on common.
- Modify: `paymesh-common/.../security/SecurityConfig.java` permits `/actuator/health/**`
- Test: `transaction_service/src/test/java/com/paymesh/HealthEndpointTest.java` checks that `GET /actuator/health/liveness` returns 200 without a token, and that `/actuator/env` is not exposed.

- [ ] **Step 1: Write the failing test.**
- [ ] **Step 2: Run it and confirm it fails.** Expected: 401/404.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the test and the whole suite.** Run: `./mvnw -B verify`. Expected: green.
- [ ] **Step 5: Commit**
  - `git commit -m "Export traces, logs and metrics over OTLP"`

---

### Task 2: Traces survive the outbox

**Files:**
- Create the migrations `ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(64) NULL`:
  - `user_service/.../V8__outbox_traceparent.sql`
  - `wallet_service/.../V6__outbox_traceparent.sql`
  - `transaction_service/.../V5__outbox_traceparent.sql`
- Modify: `OutboxEvent` (a `traceparent` field), `Outbox.publishJson` (captures the current context through `ObjectProvider<Tracer>` and `ObjectProvider<Propagator>`), `OutboxRelay.publishPending` (for each event with a `traceparent`, extracts it, starts a span named `outbox relay <topic>` and sends inside it; otherwise it sends as before)
- Modify: the `SchemaMigrationTest`s (the new versions)
- Test: add `traceContinuesThroughTheOutbox` to `wallet_service/src/test/java/com/paymesh/WalletSagaIntegrationTest.java`
  - Send INITIATE with the header `traceparent=00-<32 hex>-<16 hex>-01`.
  - The COMPLETED record's `traceparent` header has the same 32-hex trace ID.
  - Test properties: `management.tracing.sampling.probability=1.0`; export stays off.

- [ ] **Step 1: Write the failing test.**
- [ ] **Step 2: Run it and confirm it fails.** Expected: no `traceparent`, or a different trace ID.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the test and the whole suite.** Expected: green, including `OutboxIntegrationTest`, where no tracer is present and so no `traceparent` is stored.
- [ ] **Step 5: Commit**
  - `git commit -m "Carry the trace through the outbox"`

---

### Task 3: Business metrics

**Files:**
- Modify: `TransactionService.updateTransactionStatus`, which counts `paymesh.payments.completed` and times `paymesh.payments.duration` (from `paymentInitiationDate` to now) when a payment becomes final
- Create: `paymesh-common/.../outbox/OutboxMetrics.java`, which registers the `paymesh.outbox.backlog` gauge (a count of this producer's rows) when JPA and a MeterRegistry are present
- Create: `paymesh-common/.../deadletter/DeadLetterMetrics.java`, the `paymesh.deadletters.new` gauge
- Modify: `ReconciliationService.nightly`, which sets `paymesh.ledger.mismatches`
- Test:
  - `TransactionStatusGuardTest` gets a `SimpleMeterRegistry` and checks that a final status counts exactly once, with the tags `status` and `type` only.
  - `OutboxIntegrationTest` checks the backlog gauge.
  - `DeadLetterStoreIntegrationTest` checks the NEW gauge.
  - `LedgerIntegrationTest` checks that the gauge reads 1 after a tampered reconciliation.

- [ ] **Step 1: Write the failing tests.**
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the tests and the suite.** Expected: green.
- [ ] **Step 5: Commit**
  - `git commit -m "Add payment, outbox, dead-letter and ledger metrics"`

---

### Task 4: Grafana stack, scrubbing, dashboards, alerts; live check

**Files:**
- Create: `deploy/observability/otelcol-config.yaml`. It is the image's default config plus a `transform/scrub` processor in all three pipelines, replacing:
  - emails (`[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}`) with `***@***`;
  - 10-digit mobiles starting 6–9 (`\b[6-9]\d{9}\b`) with `**********`;
  - in log bodies, log attributes and span attributes.
- Create: `deploy/observability/dashboards/{payments,messaging,jvm}.json` and `deploy/observability/dashboards.yaml` (provisioning)
- Create: `deploy/observability/alerts.yaml`, with Grafana alert rules:
  - outbox backlog > 100 for 5 min;
  - dead letters NEW > 0;
  - ledger mismatches > 0.
- Modify: `docker-compose.yml`:
  - An `lgtm` service (`grafana/otel-lgtm:0.35.0`) with mounts for the collector config, the dashboards and the alerts, publishing `127.0.0.1:3000` for Grafana.
  - Every Spring service gets `OTLP_ENABLED: "true"` and `OTLP_ENDPOINT: http://lgtm:4318`.
- Modify: `notification_service/.../KafkaConsumer.java`. Without SMTP it logs only the subject and a masked recipient at INFO; the body goes at DEBUG. Mail content is personal data and must not leave the box by default.

- [ ] **Step 1: Make the edits.** Expected: `docker compose config -q` passes.
- [ ] **Step 2: Live check**, with the stack up (the infra-ports override):
  1. Register 2 users, add money, and transfer.
  2. Through Grafana's HTTP API (`localhost:3000`, admin/admin):
     - **(a) Tempo:** search `service.name=transaction-service` and find a trace containing spans from `api-gateway`, `transaction-service` and `wallet-service`, and a Kafka send/receive.
     - **(b) Prometheus:** `paymesh_payments_completed_total` > 0.
     - **(c) Loki:** `{service_name="wallet-service"}` returns lines carrying a `trace_id`.
     - **(d) Scrubbing:** a log line containing the test email shows `***@***`.
  3. Stop `lgtm`, then pay. The request still succeeds quickly.
- [ ] **Step 3: Commit**
  - `git commit -m "Add Grafana stack, dashboards, alerts and PII scrubbing"`

---

### Task 5: README

- [ ] **Step 1: Write the README section**
  - "Observability": what goes where, opening Grafana (`localhost:3000`), the dashboards, following a payment's trace, the metrics table, the alerts, and personal-data scrubbing.
  - Configuration rows: `OTLP_ENABLED`, `OTLP_ENDPOINT`, `TRACING_SAMPLING`.
  - Remove the "correlation IDs only" wording where tracing now covers it.
- [ ] **Step 2: Commit**
  - `git commit -m "Document observability"`
