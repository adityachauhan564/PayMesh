# Phase 5a — RS256 Tokens, Refresh Sessions, Logout, Roles

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the shared HS256 secret and the 24 h token kept in localStorage with:
- RS256 access tokens that last 15 minutes and are verified through JWKS;
- rotating refresh tokens in an httpOnly cookie;
- logout that actually revokes;
- `USER` / `ADMIN` roles.

Phase 5b adds TOTP, step-up and the email unlock link.

**Architecture:**
- **Signing:** user_service is the only service holding a private key. It publishes `/.well-known/jwks.json`. The gateway and the other services verify tokens against that endpoint, which is fetched lazily and cached, so there is no shared secret and no startup ordering.
- **Refresh tokens:** opaque, with only their SHA-256 hash stored. They rotate on every use, and reusing an old one revokes its whole family.
- **Logout:** revokes the refresh family and puts the access token's `jti` on a Redis denylist, which the gateway checks.
- **Web app:** keeps the access token in memory only, restores the session through `POST /api/auth/refresh`, and refreshes once when it gets a 401.

**Tech Stack:** Spring Security OAuth2 Resource Server and JOSE (Nimbus), Spring Data Redis (user_service), and Spring Cloud Gateway's reactive Redis, which is already present.

**Spec:** `docs/superpowers/specs/2026-10-17-paymesh-production-design.md` §7.5

## Global Constraints

- **Access token:**
  - RS256, 15 minutes long, with `kid` = the RFC 7638 thumbprint of the key.
  - Claims: `iss` (`jwt.issuer`, default `paymesh`), `sub` (userId), `name`, `roles` (a JSON array: `["USER"]` or `["ADMIN"]`), `amr` (`["pwd"]`), `jti` (a UUID), `iat`, `exp`.
- **Refresh token:**
  - 32 random bytes, base64url; only its SHA-256 hex is stored.
  - Lives 7 days and rotates on every `POST /api/auth/refresh`.
  - Reusing an already-rotated token revokes every token in its `family_id`.
  - Cookie: `paymesh_refresh`, `HttpOnly`, `SameSite=Strict`, `Path=/api/auth`, `Max-Age=604800`. `Secure` comes from `auth.cookie-secure` (env `AUTH_COOKIE_SECURE`, default `true`); browsers treat localhost as secure.
- **Private key:** comes from `jwt.private-key` (env `JWT_PRIVATE_KEY`, PKCS#8 PEM). If it's unset, user_service generates an RSA-2048 key at startup and logs a WARN that sessions won't survive a restart.
- **Verifiers:** `jwt.jwks-uri` (env `JWT_JWKS_URI`, default `http://localhost:8081/.well-known/jwks.json`). Verifiers accept RS256 only.
- **Denylist key:** `denylist:jti:<jti>`, with TTL = the token's remaining lifetime.
  - user_service writes it on logout.
  - The gateway checks every authenticated request against it.
  - Both fail open when Redis is down, with a WARN, because access tokens expire in 15 minutes anyway.
- **Admins:** `/api/admin/**` requires `ROLE_ADMIN` at both the gateway and the services. Admins are listed in `auth.admin-emails` (env `ADMIN_EMAILS`, comma-separated). Those users get `role = ADMIN` when they register, and existing users are promoted at startup.
- **`JWT_SECRET` and `jwt.secret` disappear everywhere:** code, tests, compose, `.env.example`, `application-local.*` and README.
- **Commits:** one short plain-English line each, on `phase-5a-tokens-and-sessions`. Merge when CI is green.

## Review Focus

- **A refresh token used twice** (stolen and replayed) must revoke the family, so the legitimate holder's next refresh fails too. This is covered in Task 3 (`reusedRefreshTokenRevokesTheFamily`).
- **An access token used after logout** is rejected at the gateway with a 401 ProblemDetail, even though its signature is still valid. This is covered in Task 4 (`deniedTokenIsRejected`).
- **A token signed by another RSA key carrying a valid-looking `kid`**, or an HS256 token signed with the old secret, must be rejected. This is covered in Task 2 (`foreignKeyIsRejected`, `hs256IsRejected`) and Task 4.
- **The page reloads:** the web app must restore the session from the cookie, without localStorage. A 401 mid-session refreshes once and retries the request, and a failed refresh logs out without looping. This is covered in Task 6.
- **user_service restarts without `JWT_PRIVATE_KEY`:** old tokens fail verification cleanly (401, never 500), and users log in again. Verifiers refetch JWKS when they see an unknown `kid`. This is covered in Task 2 (`unknownKidRefetchesJwks`).

---

### Task 1: user_service signs RS256 tokens and publishes JWKS

**Files:**
- Create: `user_service/src/main/java/com/paymesh/auth/JwtKeys.java`, `user_service/src/main/java/com/paymesh/auth/TokenIssuer.java`, `user_service/src/main/java/com/paymesh/auth/JwksController.java`, `user_service/src/main/java/com/paymesh/auth/AuthProperties.java`
- Create: `user_service/src/main/resources/db/migration/V4__user_role.sql`, which adds `role VARCHAR(16) NOT NULL DEFAULT 'USER'` to `wallet_user`
- Modify: `user_service/src/main/java/com/paymesh/model/User.java` (add a `role` String field, default `"USER"`), `UserService.java` (drop the HMAC `jwtEncoder` bean), `UserOperationsService.java` (use `TokenIssuer`, assign the role at registration)
- Test: `user_service/src/test/java/com/paymesh/auth/JwtKeysTest.java`, `user_service/src/test/java/com/paymesh/auth/TokenIssuerTest.java`

**Interfaces:**
- `@Component JwtKeys`:
  - `RSAKey signingKey()` holds the private key, with `kid` set.
  - `JWKSet publicJwks()`.
  - The constructor takes `@Value("${jwt.private-key:}") String pem`. It parses PKCS#8 PEM, or generates a key when the PEM is blank.
- `@ConfigurationProperties("auth") record AuthProperties(@DefaultValue("PT15M") Duration accessTtl, @DefaultValue("P7D") Duration refreshTtl, @DefaultValue("true") boolean cookieSecure, @DefaultValue("") List<String> adminEmails)`.
- `@Component TokenIssuer`:
  - `String issueAccess(User user, List<String> amr)`.
  - `@Bean JwtEncoder` (`NimbusJwtEncoder` over `new ImmutableJWKSet<>(new JWKSet(signingKey))`).
  - `@Bean JwtDecoder` built from the public key with an issuer validator. This one replaces the common JWKS decoder inside user_service.
- `JwksController`: `GET /.well-known/jwks.json` returns `publicJwks().toJSONObject()`.
- Startup `ApplicationRunner` in `UserOperationsService` or a small `AdminSeeder`: promotes users whose email is in `adminEmails`.

- [ ] **Step 1: Write the failing tests**
  - `JwtKeysTest`:
    - `generatesAKeyWhenNoneConfigured`: `new JwtKeys("")` gives an RSA key with a non-blank `kid` that equals the computed thumbprint, and the public JWKS has no private parts (`publicJwks().getKeys().get(0).isPrivate()` is false).
    - `loadsAConfiguredPkcs8Key`: generate a key pair in the test, PEM-encode the private key, and `new JwtKeys(pem).signingKey().toRSAPublicKey()` equals the generated public key.
  - `TokenIssuerTest`:
    - `accessTokenCarriesRolesAmrJtiAndExpiresIn15Minutes`: decode with `NimbusJwtDecoder.withPublicKey(keys.signingKey().toRSAPublicKey())`. Check `roles == ["USER"]`, `amr == ["pwd"]`, `jti` is a UUID, `exp − iat == 900 s`, and the header `kid` equals the key's `kid`.

- [ ] **Step 2: Run them and confirm they fail**
  - Run: `./mvnw -B -q -pl user_service -am verify -Dtest='JwtKeysTest,TokenIssuerTest' -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: compile failure.

- [ ] **Step 3: Implement**
  - Add the classes and V4. Bump `user_service`'s `SchemaMigrationTest` versions to `"1".."4"`.

- [ ] **Step 4: Run them again and confirm they pass**
  - Run the same command. Expected: PASS.

- [ ] **Step 5: Commit**
  - `git commit -m "Sign tokens with RS256 and publish JWKS"`

---

### Task 2: Services verify through JWKS, with roles

**Files:**
- Modify: `paymesh-common/src/main/java/com/paymesh/security/JwtProperties.java` (becomes `record JwtProperties(@DefaultValue("http://localhost:8081/.well-known/jwks.json") String jwksUri, @DefaultValue("paymesh") String issuer)`)
- Modify: `paymesh-common/src/main/java/com/paymesh/security/SecurityConfig.java`:
  - an RS256 JWKS decoder, `@ConditionalOnMissingBean`;
  - a converter mapping the `roles` claim to `ROLE_*`;
  - `/api/admin/**` `hasRole("ADMIN")`;
  - `/.well-known/jwks.json` permitted;
  - the `SecretKey` bean removed.
- Modify: `paymesh-common/src/test/java/com/paymesh/security/SecurityConfigTest.java`, `JwtPropertiesTest.java`
- Modify: every test or properties file that sets `jwt.secret`. Remove the property, and use `jwt()` post-processors with `authorities` where roles matter.
- Modify: `{user,wallet,transaction,notification}_service/src/main/resources/application-local.properties` (drop `jwt.secret`)

**Interfaces:**
- `SecurityConfig.jwtAuthenticationConverter()` maps `roles` to `ROLE_<role>`, with `sub` as the principal name.

- [ ] **Step 1: Write the failing tests in `SecurityConfigTest`**
  - Setup: serve a JWKS with the JDK `HttpServer`, from a test RSA key.
  - `validRs256TokenIsAccepted`.
  - `foreignKeyIsRejected`: signed by another RSA key with the same `kid`.
  - `hs256IsRejected`.
  - `wrongIssuerIsRejected`.
  - `unknownKidRefetchesJwks`: rotate the served key, then a token with the new `kid` is accepted.
  - `rolesBecomeAuthorities`: `["ADMIN"]` → `ROLE_ADMIN`.

- [ ] **Step 2: Run them and confirm they fail**
  - Run: `./mvnw -B -q -pl paymesh-common verify -Dtest=SecurityConfigTest`
  - Expected: FAIL or compile failure.

- [ ] **Step 3: Implement, then remove `jwt.secret` everywhere**
  - Check with `grep -rn "jwt.secret\|JWT_SECRET" --include=*.java --include=*.properties --include=*.yml .`, which must find nothing outside docs and the gateway (Task 4).

- [ ] **Step 4: Run the suite for the changed modules**
  - Run: `./mvnw -B -pl paymesh-common,user_service,wallet_service,transaction_service,notification_service -am verify`
  - Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**
  - `git commit -m "Verify tokens through JWKS and map roles"`

---

### Task 3: Refresh tokens, rotation, logout

**Files:**
- Create: `user_service/src/main/resources/db/migration/V5__refresh_token.sql`, with `refresh_token`:
  - `id` BIGINT AI PK
  - `user_id` BIGINT NOT NULL
  - `family_id` CHAR(36) NOT NULL
  - `token_hash` CHAR(64) NOT NULL UNIQUE
  - `expires_at` DATETIME(6) NOT NULL
  - `used_at` DATETIME(6) NULL
  - `revoked_at` DATETIME(6) NULL
  - `created_at` DATETIME(6) NOT NULL
  - KEY on `(family_id)`
- Create: `user_service/src/main/java/com/paymesh/auth/RefreshToken.java`, `RefreshTokenRepository.java`, `RefreshTokens.java`, `AccessTokenDenylist.java`
- Modify: `user_service/pom.xml` (add `spring-boot-starter-data-redis`), `application.properties` (redis host/port/password/timeouts, as in notification_service)
- Modify: `UserController.java`: register and login set the cookie; add `POST /api/auth/refresh` and `POST /api/auth/logout`
- Test: `user_service/src/test/java/com/paymesh/auth/RefreshFlowIntegrationTest.java`, using MySQL plus Redis `GenericContainer`, `@SpringBootTest(webEnvironment=RANDOM_PORT)`, and the JDK `HttpClient` so cookies are real

**Interfaces:**
- `RefreshTokens`:
  - `String issue(long userId)`: a new family.
  - `Rotation rotate(String raw)`, where `record Rotation(long userId, String newRaw)`. It throws `ResponseStatusException(401)` when the token is unknown, expired or revoked. A token whose `used_at` is already set means reuse: revoke the family, then 401.
  - `void revokeFamilyOf(String raw)`: a no-op when unknown.
- `AccessTokenDenylist.deny(String jti, Instant expiresAt)` writes the Redis key with its TTL, and fails open.
- `POST /api/auth/refresh` (cookie) returns 200 `AuthResponse(token, user)` plus a new cookie, or 401 with the cookie cleared (`Max-Age=0`).
- `POST /api/auth/logout` (cookie, optional Bearer) returns 204 and clears the cookie. It revokes the family and denylists the bearer's `jti` when the bearer is valid.

- [ ] **Step 1: Write the failing tests**
  - `loginSetsAnHttpOnlyStrictCookie`: check the `Set-Cookie` attributes against the Global Constraints.
  - `refreshRotatesTheCookieAndReturnsANewAccessToken`.
  - `reusedRefreshTokenRevokesTheFamily`: refresh with cookie A gives B; refresh with A again gives 401; then refresh with B gives 401.
  - `expiredRefreshTokenIs401`: set `expires_at` in the past through the repository.
  - `logoutRevokesAndDenylists`: after logout, refreshing with the cookie gives 401, and Redis has `denylist:jti:<jti>` with a TTL between 1 and 900 s.
  - `logoutWithoutCookieIs204`.

- [ ] **Step 2: Run them and confirm they fail**
  - Run: `./mvnw -B -q -pl user_service -am verify -Dtest=RefreshFlowIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: FAIL or compile failure.

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run them again and confirm they pass**
  - Run the same command, then `./mvnw -B -pl user_service -am verify`.
  - Expected: PASS.

- [ ] **Step 5: Commit**
  - `git commit -m "Add rotating refresh tokens and real logout"`

---

### Task 4: Gateway — JWKS, roles, denylist

**Files:**
- Modify: `api_gateway/src/main/java/com/paymesh/gateway/security/JwtProperties.java` (`jwksUri`, `issuer`), `GatewaySecurityConfig.java` (RS256 JWKS decoder, roles converter, `/api/admin/**` ADMIN, public `POST /api/auth/refresh` and `/api/auth/logout`)
- Create: `api_gateway/src/main/java/com/paymesh/gateway/security/DenylistFilter.java`. This is a `WebFilter` placed after authentication: for an authenticated `Jwt`, Redis `hasKey(denylist:jti:<jti>)` true gives a 401 ProblemDetail. It fails open.
- Modify: `api_gateway/src/main/resources/application.yml` (drop the `jwt.secret` comment, add `jwks-uri`)
- Modify the tests: `support/TestTokens.java` (an RSA key plus a JDK `HttpServer` JWKS endpoint), `GatewayTestSupport`, `LoginRateLimitTest`, `RateLimiterFailOpenTest`, `UpstreamFailureTest`, `security/JwtPropertiesTest`
- Test: add to `GatewaySecurityTest`:
  - `deniedTokenIsRejected`
  - `refreshAndLogoutArePublic`
  - `adminPathNeedsAdminRole`: 403 for a USER, routed for an ADMIN
  - `hs256TokenIsRejected`

- [ ] **Step 1: Write the failing tests**

- [ ] **Step 2: Run them and confirm they fail**
  - Run: `./mvnw -B -q -pl api_gateway verify -Dtest=GatewaySecurityTest`

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run the gateway suite**
  - Run: `./mvnw -B -pl api_gateway verify`
  - Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**
  - `git commit -m "Check JWKS, roles and the logout denylist at the gateway"`

---

### Task 5: Compose, env, README; live check

**Files:**
- Modify: `docker-compose.yml`:
  - Remove `JWT_SECRET`.
  - Services and the gateway get `JWT_JWKS_URI: http://user-service:8081/.well-known/jwks.json`.
  - user-service gets `JWT_PRIVATE_KEY: ${JWT_PRIVATE_KEY:-}`, `REDIS_HOST: redis`, `ADMIN_EMAILS: ${ADMIN_EMAILS:-}`, `AUTH_COOKIE_SECURE: ${AUTH_COOKIE_SECURE:-true}`, and `depends_on` redis.
- Modify: `.env.example`: drop `JWT_SECRET`; add `JWT_PRIVATE_KEY=` with a comment (empty means a fresh key per start; generate one with `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`) and `ADMIN_EMAILS=`.
- Modify: `README.md`: the security model (RS256/JWKS, the token lifecycle diagram, refresh rotation and reuse detection, logout and the denylist, roles), the API reference (refresh, logout, cookie), configuration, and remove the known limitations about revocation and localStorage.

- [ ] **Step 1: Make the edits**
  - Then run `docker compose config -q`. Expected: exit 0.

- [ ] **Step 2: Live check**
  - Build with `sh scripts/build-images.sh`, then `docker compose up -d` with the infra-ports override.
  - Then, with curl and a cookie jar:
    - register → 201 + `Set-Cookie`;
    - `GET /api/users/me` with the token → 200;
    - `POST /api/auth/refresh` → 200 + a new token;
    - `POST /api/auth/logout` → 204;
    - `GET /api/users/me` with the old token → **401**;
    - refresh → **401**.

- [ ] **Step 3: Commit**
  - `git commit -m "Wire JWKS and sessions into compose and docs"`

---

### Task 6: Web app — in-memory token, silent refresh

**Files:**
- Modify: `wallet-ui/src/app/api.ts`, `wallet-ui/src/app/app.config.ts`, `wallet-ui/src/app/api.spec.ts`

**Interfaces:**
- **Session:** `Api.session` (`{token, user}`) is a signal and is never persisted.
- **`Api.restore(): Promise<void>`** calls `POST /api/auth/refresh` once. On success it sets the session; on 401 it leaves the session null. It runs through `provideAppInitializer(() => inject(Api).restore())`.
- **`authInterceptor`:**
  - It attaches the Bearer token.
  - On a 401 for a request that carried a token, and only for a non-`/api/auth/` URL, it calls refresh once, shared by concurrent 401s, then retries with the new token.
  - If the refresh fails, it calls `logout()` and rethrows.
- **`logout()`** calls `POST /api/auth/logout` (errors ignored), clears the session and navigates to `/login`.
- **Cleanup:** remove `localStorage` use entirely.

- [ ] **Step 1: Write the failing tests**
  - Replace the localStorage-based setup in `api.spec.ts` with a session set through `api.session.set(...)`.
  - `restoreUsesTheRefreshCookie`: `POST /api/auth/refresh` returning 200 sets the session.
  - `restoreWithoutCookieStaysLoggedOut`.
  - `a401RefreshesOnceAndRetries`: original 401 → refresh 200 → the retried request carries the new token.
  - `failedRefreshLogsOutWithoutLooping`: exactly one refresh request, then navigation to `/login`.
  - `noLocalStorage`: `localStorage.getItem('paymesh.session')` stays null after login.

- [ ] **Step 2: Run them and confirm they fail**
  - Run: `cd wallet-ui && npx ng test --watch=false`

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run the tests and the build**
  - Run: `npx ng test --watch=false && npx ng build`
  - Expected: PASS.

- [ ] **Step 5: Commit**
  - `git commit -m "Keep the session in memory and refresh silently"`
