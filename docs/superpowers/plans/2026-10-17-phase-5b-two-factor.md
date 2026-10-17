# Phase 5b — TOTP Two-Factor, Step-Up, Email Unlock

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close out spec §7.5:
- optional TOTP two-factor login with recovery codes;
- step-up, so large payments by 2FA users need a fresh code;
- a one-time email link that unlocks a locked account.

**Architecture:**
- **TOTP:** RFC 6238 (HMAC-SHA1, 30 s steps, 6 digits, ±1 step), implemented in about 40 lines with no dependency.
  - The secret and the last used step are stored on `wallet_user`, so a code can't be replayed.
  - Recovery codes are stored as SHA-256 hashes.
- **Login:** an account with 2FA enabled needs `otp` alongside the password. Without it, the response is 401 with the ProblemDetail type `mfa-required`. A wrong code counts toward the lockout.
- **Token claims:** access tokens carry `mfa: true` for 2FA users, and `amr: ["pwd","otp"]` when a code was given.
- **Step-up:** transaction_service requires a fresh `otp` (`iat` ≤ 5 min) for payments above ₹5,000 by 2FA users. It answers with 403 type `step-up-required`, and the web app prompts for a code. Users without 2FA are unaffected.
- **Unlock link:** the lockout email carries a single-use link (30 min, hash stored) to `GET /api/auth/unlock`. That clears the lock and redirects to the login page.

**Spec:** `docs/superpowers/specs/2026-10-17-paymesh-production-design.md` §7.5

## Global Constraints

- **ProblemDetail types:** `https://paymesh.dev/problems/mfa-required` (401) and `https://paymesh.dev/problems/step-up-required` (403).
- **Step-up uses 403, not 401** (RFC 9470 uses 401). The web app refreshes and retries on any 401, so a 401 here would loop into a logout.
- **TOTP:**
  - The secret is 20 random bytes, base32 without padding.
  - The otpauth URI is `otpauth://totp/PayMesh:<email>?secret=<s>&issuer=PayMesh&algorithm=SHA1&digits=6&period=30`.
  - A code is accepted for steps `now-1..now+1` only if the step is greater than `totp_last_step`.
- **Recovery codes:** 8 per enrolment, format `xxxx-xxxx` (lowercase base32), single use, and accepted wherever an `otp` is.
- **Step-up threshold:** `payments.step-up-threshold` (env `STEP_UP_THRESHOLD`, default 5000), with freshness `PT5M`.
- **Unlock links:**
  - Built from `app.public-url` (env `APP_PUBLIC_URL`, default `http://localhost:4200`).
  - Valid 30 minutes and single use; a new lock replaces the old link.
  - The redirect goes to `<public-url>/login?unlocked=1`, or to `?unlock=invalid` when the link is bad.
- **Commits:** one short plain line each, on `phase-5b-two-factor`. Merge when CI is green.

## Review Focus

- **The same TOTP code used twice** (replay within its 90 s validity) must be rejected the second time. This is covered in Task 3.
- **Brute-forcing the 6-digit code** after a correct password must hit the account lockout: wrong codes count as failed logins. This is covered in Task 3.
- **A refreshed token** (`amr` back to `["pwd"]`) must not satisfy step-up. This is covered in Task 4.
- **An unlock link used twice, or after a newer lock,** must not unlock. This is covered in Task 5.
- **Disabling 2FA** must require a valid code, so a stolen session can't silently remove 2FA. This is covered in Task 2.

---

### Task 1: TOTP and base32 (pure)

**Files:**
- Create: `user_service/src/main/java/com/paymesh/auth/Totp.java`
- Test: `user_service/src/test/java/com/paymesh/auth/TotpTest.java`

**Interfaces:**
- `static String newSecret()`
- `static String code(String base32Secret, long step)`, which gives 6 digits
- `static long matchingStep(String base32Secret, String code, long nowStep)`, which returns the matched step in `[now-1, now+1]`, or `-1`
- `static long stepAt(Instant t)` = epoch seconds / 30
- `static String base32(byte[])` and `static byte[] fromBase32(String)`

- [ ] **Step 1: Write the failing tests**
  - RFC 6238 SHA1 vectors: with the secret = base32 of ASCII `12345678901234567890`, step 1 (T=59) gives `287082` (last 6 digits of `94287082`), and T=1111111109 gives `081804`.
  - `matchingStep` accepts the previous, current and next steps, and rejects ±2 steps.
  - The base32 round-trip works.
  - `newSecret()` decodes to 20 bytes.

- [ ] **Step 2: Run them and confirm they fail.** Expected: compile failure.

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run them again.** Expected: PASS.

- [ ] **Step 5: Commit**
  - `git commit -m "Add TOTP code generation and checking"`

---

### Task 2: Enrolment, recovery codes, `mfa` claim

**Files:**
- Create: `user_service/src/main/resources/db/migration/V6__two_factor.sql`
  - Adds to `wallet_user`: `totp_secret VARCHAR(64) NULL`, `totp_enabled BOOLEAN NOT NULL DEFAULT FALSE`, `totp_last_step BIGINT NULL`.
  - Creates `recovery_code(id BIGINT AI PK, user_id BIGINT NOT NULL, code_hash CHAR(64) NOT NULL, used_at DATETIME(6) NULL, KEY(user_id))`.
- Create: `user_service/src/main/java/com/paymesh/auth/TwoFactor.java` (service), `RecoveryCode.java`, `RecoveryCodeRepository.java`, `TwoFactorController.java`
- Modify: `User.java` (the three fields), `TokenIssuer.java` (`mfa` claim = `user.isTotpEnabled()`)
- Test: `user_service/src/test/java/com/paymesh/auth/TwoFactorIntegrationTest.java`, in the same style as `RefreshFlowIntegrationTest`. Codes are computed with `Totp.code(secret, Totp.stepAt(now))`.

**Interfaces:**
- `POST /api/users/me/2fa/setup` returns 200 `{secret, otpauthUri}`. It is 409 if 2FA is already enabled.
- `POST /api/users/me/2fa/enable {code}` returns 200 `{recoveryCodes: [8]}`. A wrong code gives 400, and it is 409 when there's no pending setup.
- `POST /api/users/me/2fa/disable {code}` returns 204. It takes a TOTP code or a recovery code; a wrong one gives 400.
- `TwoFactor.verify(User user, String code)` returns `boolean`. It checks TOTP with replay protection (updating `totp_last_step`) or an unused recovery code (marking it used). It must run inside a transaction.

- [ ] **Step 1: Write the failing tests**
  - `setupReturnsSecretAndOtpauthUri`
  - `enableNeedsAValidCode`
  - `enableReturnsEightRecoveryCodes`
  - `setupAgainIs409`
  - `disableNeedsAValidCode`
  - `tokensOf2faUsersCarryMfaTrue`

- [ ] **Step 2: Run them and confirm they fail**

- [ ] **Step 3: Implement.** Bump `SchemaMigrationTest` to versions `"1".."6"`.

- [ ] **Step 4: Run the tests again.** Expected: PASS.

- [ ] **Step 5: Commit**
  - `git commit -m "Let users turn on two-factor login"`

---

### Task 3: Login with a code, step-up endpoint

**Files:**
- Modify: `UserDtos.LoginRequest` (add `String otp`, optional), `UserOperationsService.login`, `UserController`
- Create: `user_service/src/main/java/com/paymesh/auth/MfaRequiredException.java` (`ErrorResponseException`, 401, type mfa-required)
- Add: `POST /api/users/me/step-up {code}`, authenticated. It returns `AuthResponse` with a new token carrying `amr ["pwd","otp"]`. A wrong code gives 400 and counts as a failed login.
- Test: add to `TwoFactorIntegrationTest`

- [ ] **Step 1: Write the failing tests**
  - `loginWithout2faIsUnchanged`
  - `loginWithoutCodeIs401MfaRequired`: check the `type`.
  - `loginWithCodeGivesOtpAmr`
  - `sameCodeCannotBeUsedTwice`
  - `wrongCodesCountTowardLockout`: 5 wrong codes give 429.
  - `recoveryCodeWorksOnce`
  - `stepUpReturnsAFreshOtpToken`

- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the tests and the user_service suite.** Expected: PASS.
- [ ] **Step 5: Commit**
  - `git commit -m "Ask for the code at login and on step-up"`

---

### Task 4: Step-up for large payments

**Files:**
- Create: `transaction_service/src/main/java/com/paymesh/controller/StepUpPolicy.java` (`@Component`, with `void check(Jwt jwt, BigDecimal amount)`; throws an `ErrorResponseException` 403 of type step-up-required)
- Modify: `TransactionController.create`, which calls `check` before both the keyed and unkeyed paths
- Modify: `transaction_service/src/main/resources/application.properties` (`payments.step-up-threshold = ${STEP_UP_THRESHOLD:5000}`)
- Test: `transaction_service/src/test/java/com/paymesh/controller/StepUpPolicyTest.java` (unit, using `Jwt.withTokenValue(...)`)

- [ ] **Step 1: Write the failing tests**
  - `smallPaymentNeverNeedsStepUp`
  - `userWithout2faIsNotAsked`
  - `largePaymentBy2faUserNeedsFreshOtp`
  - `freshOtpTokenPasses`
  - `staleOtpTokenIsAskedAgain`: `iat` 6 minutes ago.
  - `refreshedTokenIsAskedAgain`: `amr ["pwd"]`, `mfa` true.

- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement**, including wiring into the controller. `TransactionControllerTest` keeps passing with a mocked `StepUpPolicy`, or the real one with low amounts.
- [ ] **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit**
  - `git commit -m "Require a fresh code for large payments"`

---

### Task 5: Email unlock link

**Files:**
- Create: `user_service/src/main/resources/db/migration/V7__unlock_token.sql`, adding `unlock_token_hash CHAR(64) NULL` and `unlock_expires_at DATETIME(6) NULL` to `wallet_user`
- Modify:
  - `User.java`: the fields, plus an `unlock()` method that clears the lock and the token.
  - `LockoutNotifier`: generates the token, stores its hash, and includes the link in the email.
  - `UserController`: `GET /api/auth/unlock?token=`, which redirects with 302.
  - `api_gateway` `GatewaySecurityConfig`: public `GET /api/auth/unlock`.
  - `application.properties`: `app.public-url`.
- Test: add `unlockLinkUnlocksOnce` and `staleOrUnknownLinkDoesNothing` to `LoginLockoutIntegrationTest`. Read the link out of the `outbox_event` payload, and add a gateway test for the public unlock path.

- [ ] **Step 1: Write the failing tests.**
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the user_service and api_gateway suites.** Expected: PASS.
- [ ] **Step 5: Commit**
  - `git commit -m "Unlock a locked account from the email link"`

---

### Task 6: Web app — code at login, step-up prompt, 2FA settings

**Files:**
- Modify: `wallet-ui/src/app/api.ts`:
  - `login(username, password, otp?)`, plus `isProblem(e, 'mfa-required' | 'step-up-required')`;
  - `stepUp(code)`, `setup2fa()`, `enable2fa(code)`, `disable2fa(code)`.
- Modify: `pages/auth.ts` (shows an OTP field after a mfa-required answer; an "Account unlocked" banner for `?unlocked=1`), `pages/pay.ts` (on step-up-required, asks for the code, calls `stepUp`, then retries with the **same** idempotency key), `pages/profile.ts` (a 2FA section: the secret and otpauth URI as text, the enable form, the recovery codes shown once, and the disable form)
- Test: `api.spec.ts`: `loginSendsTheOtp`, `stepUpReplacesTheSessionToken`, `isProblemReadsTheType`

- [ ] **Step 1: Write the failing tests.**
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npx ng test --watch=false && npx ng build`. Expected: PASS.
- [ ] **Step 5: Commit**
  - `git commit -m "Add two-factor screens to the web app"`

---

### Task 7: Compose and README

- [ ] **Step 1: Update compose and the README**
  - Compose: user-service gets `APP_PUBLIC_URL: ${APP_PUBLIC_URL:-http://localhost:4200}`, and transaction-service gets `STEP_UP_THRESHOLD`.
  - README:
    - Security model rows for 2FA, step-up and the unlock link;
    - API reference rows;
    - the login flow diagram gets an OTP branch;
    - configuration rows;
    - remove the "lockout can be abused" known limitation, or reword it: the unlock link mitigates it.
- [ ] **Step 2: Commit**
  - `git commit -m "Document two-factor login and unlock links"`
