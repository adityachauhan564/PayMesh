# PayMesh: Master Plan

Goal: make the codebase match its pitch.

> **PayMesh: Event-Driven Digital Wallet Platform**
> Java 21, Spring Boot, Spring Cloud Gateway, Apache Kafka, Spring Security, JWT, MySQL, Angular

The architecture already matches (gateway, JWT at gateway + services, Kafka saga, outbox, MySQL, Angular).
What didn't: the name (still "JBDL Wallet"), Java 17, and an end-of-life Spring Boot 3.2.

## Decisions

| Area | Before | After | Why |
|---|---|---|---|
| Java | 17 | **21** (LTS) | Required; enables virtual threads |
| Spring Boot | 3.2.2 (services) / 3.2.12 (gateway) | **3.5.16**, one version everywhere (superseded by phase 2: 4.0.8) | 3.2 is EOL |
| Spring Cloud | 2023.0.6 | **2025.0.3** | Train for Boot 3.5; gateway moves to `spring-cloud-starter-gateway-server-webflux` |
| Testcontainers | pinned 1.21.4 override | Boot-managed | Boot 3.5 already ships a Docker 29 compatible version |
| Threads | platform | **virtual threads** (`spring.threads.virtual.enabled`) | Java 21 win for blocking JDBC/Kafka services, one line each |
| Name | JBDL Wallet | **PayMesh** | Branding in UI, emails, JWT issuer, DB name, README |
| Maven coords | `com.jbdl63:jbdl_wallet`, `org.example:*` | `com.paymesh:paymesh`, `com.paymesh:*` | |
| Shared lib | `jbdl_wallet_libraries` | `paymesh-common` | |
| Java packages | `org.example` | `com.paymesh` | |

## Steps (all done)

1. Baseline: run the full test suite on the current code.
2. Rename: module dir, Maven coordinates, packages, branding strings, DB name, JWT issuer, UI titles.
3. Upgrade: Java 21, Boot 3.5.16, Spring Cloud 2025.0.3, gateway starter + property prefix, drop redundant per-module compiler/testcontainers properties.
4. Virtual threads on in every service.
5. Verify: full backend test suite (Testcontainers MySQL/Redis) + Angular production build.
6. Docs: README (name, badges, versions), this plan.

Result: 123/123 backend tests pass (same as baseline), 0 deprecation warnings (`@MockBean` replaced by `@MockitoBean`), Angular production build OK.

## Phase 2: Spring Boot 4 (done)

**Versions:** Spring Boot **4.0.8** + Spring Cloud **2025.1.3** (Gateway 5.0), one version everywhere.
Boot 4.1 exists, but no Spring Cloud train supports it yet; Gateway's compatibility check would refuse it.

**Constraint:** no behaviour or API change. Same 123 tests must pass unchanged in intent.

| Boot 4 change | Impact on PayMesh | Action |
|---|---|---|
| Auto-configuration split into per-technology modules | `spring-kafka` alone no longer auto-configures `KafkaTemplate`; `@WebMvcTest` lives in its own test module | `spring-boot-starter-webmvc`, `spring-boot-starter-kafka`, matching `*-test` starters |
| Jackson 3 is the default (`tools.jackson`) | Gateway error writer, outbox, 3 Kafka consumers, 2 tests use `ObjectMapper` | Move to `JsonMapper`; `JacksonException` is unchecked, so checked-exception plumbing goes away. No `spring-boot-jackson2` shim (deprecated on arrival) |
| Classes moved to new packages | `@WebMvcTest`, `ErrorWebExceptionHandler`, `DefaultKafkaProducerFactoryCustomizer` | Import changes only |
| Testcontainers 2.x | New artifact names and `org.testcontainers.mysql` package | Rename deps and imports |
| Spring Cloud Gateway 5 | Old starter and `spring.cloud.gateway.*` prefix removed | Already on `gateway-server-webflux` + `server.webflux` prefix since phase 1 |
| Security 7, Hibernate 7, Kafka 4 client | Code already uses lambda DSL, `StringSerializer`, plain JPA | Expect none; fix what the compiler/tests surface |

**Commit sequence** (each one builds and passes tests on its own):
1. `docs(plan)`: this section.
2. `build(gateway)`: the gateway has its own Boot parent, so it migrates on its own.
3. `build`: `paymesh-common` + services. Starters, Jackson 3 and Testcontainers 2 must land together (Boot 4 no longer brings Jackson 2).
4. `docs`: README versions and badges.

**Result:** 124/124 tests (123 + one regression test), no deprecation warnings, Angular build OK.
End-to-end smoke run of all five apps through the gateway: register, wallet created over Kafka, add money,
transfer, insufficient-balance rejection, notification emails.

Surprises along the way:
- `@WebMvcTest` slices lost their `HttpSecurity` bean: Boot 4 moved the slice's security auto-configuration
  into `spring-boot-security-test`, so the root POM uses `spring-boot-starter-security-test`.
- The smoke run showed `X-Correlation-Id` returned twice (gateway + service echo). Pre-existing on Boot 3.5;
  fixed in its own commit with a regression test.

## Phase 3: containers (done)

`docker compose up -d --build` runs the whole stack; `docker compose up -d mysql kafka redis` keeps the IDE workflow.

- One root `Dockerfile` for all Spring Boot services (`--build-arg SERVICE=<module>`), JRE 21, non-root.
- `wallet-ui` in nginx is the only published app port (4200): app + `/api` proxy. The gateway trusts that one
  proxy hop for client IPs and is deliberately unpublished, so `X-Forwarded-For` cannot be forged around nginx.
- Kafka has two listeners: `kafka:19092` in the compose network, `localhost:9092` for host-run apps.
- Verified: full money flow through nginx, host-run service publishing to containerised consumers, forged
  `X-Forwarded-For` still rate-limited, recreated gateway picked up by nginx without a restart.
- Known: Docker Compose multi-service builds fail when the checkout path contains non-ASCII characters
  (BuildKit session header); documented in the README's troubleshooting.

## Deliberately skipped

- Kubernetes manifests, image registry, CI pipeline, actuator health checks on the four services.
- Renaming `user_service` etc. directories: cosmetic churn.
