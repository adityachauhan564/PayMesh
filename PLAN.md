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
| Spring Boot | 3.2.2 (services) / 3.2.12 (gateway) | **3.5.16**, one version everywhere | 3.2 is EOL; 3.5 is the latest 3.x. Boot 4 is a larger migration (Jackson 3, Security 7), deferred |
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

## Deliberately skipped

- Spring Boot 4 migration: say so if you want it.
- Renaming `user_service` etc. directories: cosmetic churn.
- Containerising each service: compose still runs infra only.
