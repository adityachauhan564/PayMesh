# PayMesh 2.0 — Roadmap + Phase 0 (Hygiene and CI) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove hard-coded secrets, fix the config gaps, make the UI tests runnable, make `docker compose up --build` work in this checkout, and add GitHub Actions CI so every later phase lands on a green pipeline.

**Architecture:**
- **Secrets:** they move out of `application.properties` and `docker-compose.yml` into a git-ignored `.env`, with a committed `.env.example`. Local IDE runs keep their development values in the existing `local` profile.
- **UI tests:** they run on the Angular 21 `@angular/build:unit-test` builder with Vitest.
- **CI:** one workflow runs three jobs: backend, UI, and image build.

**Tech Stack:** Maven wrapper with Java 21 / Spring Boot 4.0.8; Angular 21.2 with Vitest and jsdom; Docker Compose v5; GitHub Actions; Dependabot.

**Spec:** `docs/superpowers/specs/2026-10-17-paymesh-production-design.md` (§7.1 open-in-view, §7.16 CI, §8 Phase 0)

## Roadmap (all phases)

Each phase gets its own plan file, written just before it starts: `docs/superpowers/plans/YYYY-MM-DD-phase-N-<name>.md`. A phase starts only when the previous phase is green in CI.

| # | Phase | Spec § | Exit criterion |
|---|---|---|---|
| 0 | Hygiene and CI skeleton | 7.1 (part), 7.16 (part) | This plan |
| 1 | Data foundations | 7.1 | Flyway V1 = current schema; schema per service; `ddl-auto=validate`; all tests green |
| 2 | Messaging reliability | 7.2 | Retry/DLT/dead-letter store; idempotent consumers; guarded status machine; Kafka Testcontainers saga tests |
| 3 | Double-entry ledger | 7.3 | Postings for all 5 types; reconciliation job; jqwik invariant test |
| 4 | Idempotency keys | 7.4 | Replay / 422 / concurrent-duplicate tests |
| 5 | Auth hardening and 2FA | 7.5 | RS256/JWKS, refresh rotation + reuse detection, denylist, roles, TOTP + step-up, email unlock |
| 6 | Observability | 7.6 | Metrics, logs and traces in Grafana locally; trace crosses the outbox and Kafka; dashboards as code; PII scrubbing |
| 7 | Kubernetes locally | 7.14 | Helm chart runs the full stack on k3d (Windows-documented) |
| 8 | AWS + GitOps live | 7.15, 7.16 | Terraform Spot instance, Budget stop action at $20, Argo CD, multi-arch GHCR images, live smoke test |
| 9 | UI foundation + landing + demo | 7.13 | Mockups approved; Tailwind shell; sandbox-per-visitor demo; public Grafana |
| 10 | Razorpay top-ups | 7.7 | Webhook signature + reconciliation; simulated fallback without keys |
| 11 | Live updates + inbox | 7.11 | SSE stream, toasts, polling removed, in-app inbox |
| 12 | Requests and split bills | 7.9 | Request lifecycle, split rounding, ShedLock expiry |
| 13 | Risk engine | 7.8 | Kafka Streams rules; HOLD/BLOCK flow; bots exempt |
| 14 | Admin console | 7.12 | Admin APIs + UI + audit; DLQ replay; hold review |
| 15 | Rewards and cashback | 7.10 | Campaigns, scratch cards, caps |
| 16 | Analytics, QR, PDF | 7.13 | Charts, QR pay/scan, monthly PDF |
| 17 | Polish | 7.17 | k6 results, Playwright suite, README/ADRs/diagrams, demo GIF |

## Global Constraints

- Java 21, Spring Boot 4.0.8 and Spring Cloud 2025.1.3 stay as they are, and so does Angular 21.2. No version upgrades in this phase.
- **Commits:** the owner's rule is that nothing is committed or pushed unless the owner has explicitly said to, and no `Co-Authored-By` line is ever added. Every "Commit" step below is conditional on that permission. Without it, stage the files and stop there.
- **Existing tests:** all 124 backend tests must stay green. Run with `./mvnw -B verify` from the repo root, with Docker running.
- **Secrets:** no secret value may be committed anywhere except dev-only values in `application-local.*` files and `.env.example` placeholders.
- **Local profile:** the `local` profile must remain enough to run any service from the IDE against `docker compose up -d mysql kafka redis`.

## Review Focus

- **Missing `.env`:** `docker compose up` without a `.env` must fail fast, naming the missing variable, rather than starting MySQL with an empty password. This is covered in Task 1, Step 2.
- **IDE run without the `local` profile:** the service must fail at startup with a clear datasource or JWT error, rather than silently using `Abc@1234`. This is covered in Task 1, Step 5.
- **`.env` in the Docker build context:** it must never end up in an image layer. This is covered in Task 1, Step 3 (`.dockerignore`).
- **Login failure versus session expiry:** a 401 on a failed login (no token yet) must not trigger logout or navigation; only a 401 on a request that carried a token should. This is covered in Task 3's tests.
- **CI on Linux:** `mvnw` is committed without its executable bit (mode 100644), so CI would fail with "permission denied". This is covered in Task 5, Step 1.

---

### Task 1: Move secrets out of code and compose

**Files:**
- Create: `.env.example`
- Modify: `.gitignore`; `.dockerignore`; `docker-compose.yml` (the `x-service-env` block, the `mysql` env, and the three `JWT_SECRET` lines)
- Modify: `user_service/src/main/resources/application.properties`, `wallet_service/…/application.properties`, `transaction_service/…/application.properties` (the datasource lines)
- Modify: `user_service/…/application-local.properties`, `wallet_service/…/application-local.properties`, `transaction_service/…/application-local.properties`
- Modify: `README.md` (the configuration table row for `DB_USERNAME` / `DB_PASSWORD` at line 623, the `.env` paragraph at line 560, and Getting Started)

**Interfaces:**
- Produces the environment variable names that later phases rely on: `MYSQL_ROOT_PASSWORD`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `MAIL_USERNAME`, `MAIL_PASSWORD`.

- [ ] **Step 1: Create `.env.example`**
  - It holds `MYSQL_ROOT_PASSWORD=change-me-local-only`, `DB_USERNAME=root`, `DB_PASSWORD=change-me-local-only`, `JWT_SECRET=change-me-to-at-least-32-bytes-of-random-text`, `MAIL_USERNAME=` and `MAIL_PASSWORD=`.
  - Each line gets a one-line comment.
  - Add `.env` to `.gitignore` and to `.dockerignore`.

- [ ] **Step 2: Make compose fail without `.env`**
  - In `docker-compose.yml`, replace the literal values with required variables:
    - `MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?set it in .env (copy .env.example)}`
    - `DB_USERNAME: ${DB_USERNAME:-root}`
    - `DB_PASSWORD: ${DB_PASSWORD:?…}`
    - every `JWT_SECRET: ${JWT_SECRET:?…}`, with the dev-secret fallback removed
  - Verify with the `.env` temporarily moved away: `docker compose config > /dev/null` must exit non-zero and print `MYSQL_ROOT_PASSWORD`.
  - Then `cp .env.example .env` and run `docker compose config > /dev/null`, which must exit 0.

- [ ] **Step 3: Check that `.env` is excluded from the build context**
  - Run `docker build --no-cache --target build --build-arg SERVICE=paymesh-common -q . ` followed by `docker run --rm <id> test ! -f /src/.env`. It must exit 0.
  - If `paymesh-common` can't be built standalone that way, use `SERVICE=user_service`.

- [ ] **Step 4: Move the database defaults into the `local` profile**
  - In the three `application.properties` files, change the datasource lines to `spring.datasource.username = ${DB_USERNAME}` and `spring.datasource.password = ${DB_PASSWORD}`. The URL keeps its localhost default.
  - Add `DB_USERNAME`/`DB_PASSWORD` defaults to the three `application-local.properties` files, under the existing "Development only" comment:

```properties
spring.datasource.username = ${DB_USERNAME:root}
spring.datasource.password = ${DB_PASSWORD:Abc@1234}
```

- [ ] **Step 5: Check that a run without the `local` profile and without env vars fails clearly**
  - Run: `./mvnw -q -pl wallet_service -am spring-boot:run` with no profile and no environment variables.
  - Expected: startup fails with `Could not resolve placeholder 'DB_USERNAME'` (or the existing JWT secret error, which comes first). Either one counts as passing.
  - Stop the process.

- [ ] **Step 6: Run the whole backend suite**
  - Run: `./mvnw -B verify`
  - Expected: `BUILD SUCCESS` with 124 tests and 0 failures. Testcontainers tests set their own datasource through `@DynamicPropertySource` or `@ServiceConnection`.

- [ ] **Step 7: Update the README**
  - Getting Started: add `cp .env.example .env` before `docker compose up`.
  - Configuration table, `DB_USERNAME`/`DB_PASSWORD` row: the default becomes "**required** (`local` profile has dev values)".
  - Line 560: `JWT_SECRET` is now required, and no longer "defaults to the dev secret".

- [ ] **Step 8: Commit** (only with the owner's permission)
  - `git add .env.example .gitignore .dockerignore docker-compose.yml */src/main/resources/application*.properties README.md`
  - `git commit -m "chore: move secrets out of code and compose into .env"`

---

### Task 2: Config hygiene (open-in-view, notification service identity)

**Files:**
- Modify: `wallet_service/src/main/resources/application.properties`, `transaction_service/src/main/resources/application.properties`
- Modify: `notification_service/src/main/resources/application.properties`

**Interfaces:** none new.

- [ ] **Step 1: Turn off open-in-view**
  - Add to the wallet and transaction `application.properties`, after `ddl-auto`:

```properties
# Off: sessions end with the service transaction; nothing lazy-loads in controllers or views
spring.jpa.open-in-view = false
```

- [ ] **Step 2: Give the notification service a name and a correlation-aware log pattern**
  - Add to `notification_service/…/application.properties`:
    - `spring.application.name = notification-service`
    - `logging.pattern.level = %5p [%X{correlationId:-}]` (the same line and comment as the other services)

- [ ] **Step 3: Run the suite and confirm the OSIV warning is gone**
  - Run `./mvnw -B verify`. Expected: `BUILD SUCCESS`, 0 failures.
  - Then run `./mvnw -B -q -pl wallet_service,transaction_service verify 2>&1 | grep -c "spring.jpa.open-in-view is enabled by default"`. Expected: `0`.

- [ ] **Step 4: Commit** (only with the owner's permission)
  - `git commit -am "chore: disable open-in-view everywhere, name notification-service"`

---

### Task 3: Runnable UI unit tests

**Files:**
- Modify: `wallet-ui/package.json` (devDependencies: `vitest`, `jsdom`; script `"test": "ng test"` stays)
- Modify: `wallet-ui/angular.json` (add an `architect.test` target)
- Create: `wallet-ui/src/app/api.spec.ts`
- Modify: `wallet-ui/README.md`, replacing the CLI boilerplate with 10 lines: install, start (with the proxy), test, build

**Interfaces:**
- Consumes from `wallet-ui/src/app/api.ts`: `Api` (`session` signal, `logout()`), `authInterceptor`, `authGuard` and `errorMessage(e: unknown): string`. None of these change.
- Produces: `npm test -- --watch=false`, which exits 0. CI (Task 5) calls exactly this.

- [ ] **Step 1: Add the test target and dependencies**
  - Run `cd wallet-ui && npm i -D vitest@^4 jsdom@^27`. Use the versions that `@angular/build@21.2` declares as peers. If npm reports a peer conflict, pin to that peer range.
  - Add to `angular.json` under `projects.wallet-ui.architect`:

```json
"test": {
  "builder": "@angular/build:unit-test",
  "options": { "tsConfig": "tsconfig.spec.json", "runner": "vitest", "buildTarget": "::development" }
}
```

- [ ] **Step 2: Write the failing spec `wallet-ui/src/app/api.spec.ts`**
  - **Setup:**
    - TestBed with `provideHttpClient(withInterceptors([authInterceptor]))`, `provideHttpClientTesting()` and `provideRouter([])`.
    - Clear `localStorage` in `beforeEach`.
    - Seed a session with `localStorage.setItem('paymesh.session', JSON.stringify({ token: 't1', user: {...} }))` *before* `TestBed.inject(Api)`.
  - **Tests:**
    - `adds Bearer header when a session exists`: expect `req.request.headers.get('Authorization')` toBe `'Bearer t1'`.
    - `sends no Authorization header without a session`: expect `req.request.headers.has('Authorization')` toBe `false`.
    - `401 on an authenticated request logs out`: flush a 401. Expect `api.session()` toBeNull, `localStorage.getItem('paymesh.session')` toBeNull, and `router.navigate` (spy) called with `['/login']`.
    - `401 on login (no token) does not log out or navigate`: with no session, POST `/api/auth/login` and flush a 401. Expect the navigate spy not to have been called.
    - `authGuard redirects to /login without a session`: `TestBed.runInInjectionContext(() => authGuard(...))` returns a `UrlTree` that serializes to `'/login'`.
    - `errorMessage maps status 0 to unreachable`: expect it toBe `'Server is unreachable. Is the backend running?'`.
    - `errorMessage returns ProblemDetail detail`: given `new HttpErrorResponse({ status: 400, error: { detail: 'Insufficient balance' } })`, expect `'Insufficient balance'`.

- [ ] **Step 3: Run the tests**
  - Run: `cd wallet-ui && npx ng test --watch=false`
  - Expected: 7 passing. These tests pin existing behaviour, so they should pass as soon as the runner works. If any fails, that's a real bug: stop and report it rather than editing `api.ts`.
  - To prove the tests aren't empty, change `'Bearer '` to `'Bearer'` in `api.ts`, check that the first test fails, then revert.

- [ ] **Step 4: Check the production build still passes**
  - Run: `cd wallet-ui && npx ng build`
  - Expected: success, with no budget errors.

- [ ] **Step 5: Commit** (only with the owner's permission)
  - `git add wallet-ui/package.json wallet-ui/package-lock.json wallet-ui/angular.json wallet-ui/src/app/api.spec.ts wallet-ui/README.md`
  - `git commit -m "test(ui): vitest runner and api/auth specs"`

---

### Task 4: `docker compose up --build` works in this checkout

**Files:**
- Modify: `README.md` (troubleshooting row at line 727, and Getting Started if a workaround is needed)
- Possibly create: `scripts/build-images.sh`, only if Step 2 fails

**Interfaces:**
- Produces: the command CI's image job runs (Task 5). It is either `docker compose build`, or `scripts/build-images.sh` if the bug still reproduces.

- [ ] **Step 1: Try to reproduce on the current toolchain**
  - Compose is now v5.5.0 and Engine 29.7.2, newer than when the README row was written.
  - Run: `cp .env.example .env` (if Task 1 didn't already create it), then `docker compose build`.
  - Record whether it fails with `non-printable ASCII characters`.

- [ ] **Step 2: Decide based on the result**
  - **If the build succeeds:** delete the troubleshooting row and go to Step 3.
  - **If it fails, try `COMPOSE_BAKE=true docker compose build`:**
    - If that works, set `COMPOSE_BAKE=true` in `.env.example`, since compose reads `.env` for its own settings. Update the README row to "fixed by `COMPOSE_BAKE=true` in `.env`".
    - If that also fails, create `scripts/build-images.sh`. It loops over `user_service wallet_service transaction_service notification_service api_gateway`, runs `docker build --build-arg SERVICE=$s -t paymesh-${s//_/-} .`, and finishes with `docker build -t paymesh-wallet-ui wallet-ui`.
      - Add `image: paymesh-<name>` to each compose service, next to `build:`, so `docker compose up -d` (without `--build`) uses those images.
      - Update the README row.

- [ ] **Step 3: Smoke-test the whole stack**
  - Run `docker compose up -d`, then wait until `docker compose ps` shows mysql and kafka healthy.
  - Then `curl -s -o /dev/null -w "%{http_code}" -X POST localhost:4200/api/auth/register -H "Content-Type: application/json" -d '{"fullName":"Smoke Test","mobileNo":"9000000001","emailId":"smoke@example.com","password":"Passw0rd!"}'`
  - Expected: `201` (or `200`, whatever the controller returns). Then `docker compose down`.
  - If the register body's field names differ, read them from `user_service/src/main/java/com/paymesh/dto/UserDtos.java`.

- [ ] **Step 4: Commit** (only with the owner's permission)
  - `git commit -am "build: compose builds in non-ASCII paths"` (also `git add scripts/` if it was created)

---

### Task 5: GitHub Actions CI and Dependabot

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/dependabot.yml`
- Modify: git file mode of `mvnw`
- Modify: `README.md` (CI badge under the title)

**Interfaces:**
- Consumes: `./mvnw -B verify` (Task 1), `npm test -- --watch=false` and `npx ng build` (Task 3), and the image build command (Task 4).
- Produces: the workflow name `CI` and the job ids `backend`, `ui`, `images`. Phase 8 adds a `publish` job that `needs: [backend, ui, images]`.

- [ ] **Step 1: Make `mvnw` executable in git**
  - Run: `git update-index --chmod=+x mvnw`, then `git ls-files -s mvnw`.
  - Expected: mode `100755`.

- [ ] **Step 2: Write `.github/workflows/ci.yml`**
  - **Triggers:** `push` to `main`, and `pull_request`.
  - **`concurrency`:** group `ci-${{ github.ref }}`, with `cancel-in-progress: true`.
  - **Permissions:** `contents: read`.
  - **Job `backend`:**
    - `ubuntu-latest`, `actions/checkout@v4`, `actions/setup-java@v4` (temurin, 21, `cache: maven`).
    - Run `./mvnw -B verify`. Docker is available on ubuntu runners, so Testcontainers works.
    - On failure, upload `**/target/surefire-reports` with `actions/upload-artifact@v4`.
  - **Job `ui`:**
    - `actions/setup-node@v4` (node 22, `cache: npm`, `cache-dependency-path: wallet-ui/package-lock.json`).
    - `working-directory: wallet-ui`, then `npm ci`, `npm test -- --watch=false`, `npx ng build`.
  - **Job `images`:**
    - `cp .env.example .env`, then the Task 4 build command.
    - No push yet; publishing comes in Phase 8.

- [ ] **Step 3: Write `.github/dependabot.yml`**
  - Version 2, weekly updates, `open-pull-requests-limit: 5` each, for these ecosystems:
    - `maven` (`/`)
    - `npm` (`/wallet-ui`)
    - `github-actions` (`/`)
    - `docker` (`/` and `/wallet-ui`)
  - Group Spring updates (`org.springframework*`) and Angular updates (`@angular/*`) into one PR each.

- [ ] **Step 4: Validate the workflow locally**
  - Run: `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest`
  - Expected: no output and exit 0.

- [ ] **Step 5: Add the CI badge**
  - Add under the README title: `[![CI](https://github.com/adityachauhan564/PayMesh/actions/workflows/ci.yml/badge.svg)](https://github.com/adityachauhan564/PayMesh/actions/workflows/ci.yml)`

- [ ] **Step 6: Commit and verify on GitHub** (only with the owner's permission to commit *and* push)
  - `git add .github mvnw README.md`
  - `git commit -m "ci: GitHub Actions build/test/images and Dependabot"`
  - Push a branch, open a PR, then run `gh pr checks --watch`.
  - Expected: `backend`, `ui` and `images` all pass.
  - Without push permission, stop after actionlint and report that the GitHub run is still pending.

---

## Phase 0 done when

- `./mvnw -B verify` passes, `npm test -- --watch=false` passes, and `docker compose up` works from this em-dash path.
- A `git grep` for `Abc@1234` finds it only in `application-local.properties` files.
- CI is green on GitHub, or this is reported as pending if pushing isn't authorized.
