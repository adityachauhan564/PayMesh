# Phase 7 — Kubernetes Locally Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One Helm chart, `deploy/helm/paymesh`, runs the whole of PayMesh on a local k3d cluster with one script:

```
sh scripts/k3d-up.sh   →   http://localhost:8080
```

It's the same chart that Phase 8 deploys to k3s on AWS.

**Architecture:**
- **Spring services:** one shared Deployment+Service template ranges over a `services:` values map. Each service gets:
  - actuator probes;
  - requests and limits;
  - a non-root user with a read-only root filesystem and a `/tmp` emptyDir.
- **In-cluster infrastructure:**
  - Kafka is a Strimzi `Kafka` with a `KafkaNodePool`, 1 KRaft node.
  - MySQL is a StatefulSet with a PVC, plus the schema init script from a ConfigMap.
  - Redis is a Deployment.
  - LGTM is the `grafana/otel-lgtm` image with the collector config, dashboards and alerts from ConfigMaps.
- **Networking:**
  - Traefik (bundled with k3s) routes the Ingress to wallet-ui, whose nginx proxies `/api` to the gateway.
  - Ingress NetworkPolicies:
    - default deny;
    - only Traefik can reach the UI, and only the UI can reach the gateway;
    - the gateway (and the services, for JWKS) can reach the services;
    - only the services can reach the data stores.

**Tech Stack:** k3d 5.9.0 (k3s v1.35), Helm 4.3.0, Strimzi 1.2.0 (CRD API `kafka.strimzi.io/v1`), the images already built by `scripts/build-images.sh`.

**Spec:** `docs/superpowers/specs/2026-10-17-paymesh-production-design.md` §7.14.

## Global Constraints

- **Memory:** fits Docker Desktop's 8 GB.
  - Each JVM gets a 512Mi limit with `-XX:+UseSerialGC -XX:MaxRAMPercentage=60 -Xss512k`.
  - Kafka gets 1Gi, MySQL 768Mi, LGTM 1Gi and Redis 128Mi.
- **Pod security:** `runAsNonRoot`, `readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`, drop ALL capabilities, seccomp `RuntimeDefault`.
- **Names:** Service names match compose (`user-service:8081`, `wallet-service:8091`, `transaction-service:8086`, `notification-service:8082`, `api-gateway:8080`, `wallet-ui:8080`, `mysql`, `redis`, `lgtm`), so the apps' URLs don't change. Kafka is `paymesh-kafka-bootstrap:9092`.
- **Secrets:** locally, `paymesh-secrets` is created from `.env` by the script. The chart only references it.
- **Commits:** one short plain line each, on `phase-7-kubernetes`. Merge when CI is green.

## Out of scope here (owned by later phases)

- Phase 8:
  - cert-manager/TLS;
  - External Secrets with SSM;
  - Argo CD;
  - the S3 backup CronJob.
- Phase 9: the demo-reset CronJob, which needs demo mode.

## Review Focus

- **Probes must not flood Tempo.** Kubelet probes every 10 s on 5 services must not create root traces, including Spring Security's own observations. This is checked live.
- **A pod restart must not lose data:** MySQL keeps its data across pod deletion (PVC). This is checked live.
- **NetworkPolicies must not break the app.** Probes, JWKS fetches, OTLP export and the Strimzi operator to Kafka still work. A pod outside the allowed set can't reach MySQL. This is checked live.
- **The rate-limit client IP is right behind two proxies.** Traefik replaces any client-sent X-Forwarded-For, and nginx appends Traefik's address, so the gateway trusts 2 hops.
- **wallet-service rolls out with Recreate**, never two versions at once against the ledger.

---

### Task 1: Probe traces dropped

- **Modify:**
  - `paymesh-common/.../observability/ObservationFilters.java`;
  - the gateway's copy.
- **What changes:** also drop `spring.security.*` observations that have no parent observation. A probe's request observation is already dropped, so its security observations would otherwise become root traces. Security spans inside real requests keep their parent and stay.
- **Test:** `ObservationFiltersTest.dropsParentlessSecurityObservations`, plus `keepsSecurityObservationsInsideARequest`.
- **Commit:** `Drop probe security traces`

### Task 2: The chart

- **Create** `deploy/helm/paymesh/`:
  - `Chart.yaml`, `values.yaml`;
  - templates: `_helpers.tpl`, `services.yaml`, `hpa.yaml`, `ingress.yaml`, `networkpolicies.yaml`, `mysql.yaml`, `redis.yaml`, `kafka.yaml`, `lgtm.yaml`, `wallet-ui-config.yaml`.
- **Move:**
  - `deploy/mysql/init/` → `deploy/helm/paymesh/files/mysql-init/`;
  - `deploy/observability/` → `deploy/helm/paymesh/files/observability/`.
  - Reason: Helm, and Argo CD later, can only read files inside the chart. Update the compose mounts and the README paths.
- **Service settings:**
  - wallet-service uses `strategy: Recreate`.
  - transaction-service gets an HPA (CPU 70%, 1–3 replicas).
  - The gateway gets `TRUSTED_PROXY_COUNT=2`.
  - wallet-ui's nginx config comes from a ConfigMap: a plain `proxy_pass http://api-gateway:8080` (the ClusterIP is stable, and Docker's resolver 127.0.0.11 doesn't exist in Kubernetes).
- **Check:** `helm lint` and `helm template` render cleanly, and `docker compose config -q` passes.
- **Commit:** `Add Helm chart for the whole stack`

### Task 3: k3d script and live check

- **Create:** `scripts/k3d-up.sh`, which runs these steps in order:
  1. Create the cluster `paymesh` (`-p 8080:80@loadbalancer`), if it's missing.
  2. Install the Strimzi operator (Helm, OCI chart, 1.2.0) into `paymesh`.
  3. Build the images, then `k3d image import`.
  4. Create `paymesh-secrets` from `.env`.
  5. `helm upgrade --install`, then wait for the rollouts.
- **Live check:**
  1. Register, add money and transfer through `http://localhost:8080`.
  2. Grafana (via port-forward) has the trace, and has no probe traces.
  3. Delete the mysql pod; the data survives.
  4. A throwaway pod can't reach `mysql:3306`.
  5. The HPA reports CPU.
- **Commit:** `Run the stack on k3d with one script`

### Task 4: CI and README

- **CI:** a `helm` job runs `helm lint` and `helm template`, and fails on a render error.
- **README:** a "Kubernetes (local)" section covering prerequisites, the script, what runs where, memory, and teardown (`k3d cluster delete paymesh`).
- **Commit:** `Lint the chart in CI and document Kubernetes`
