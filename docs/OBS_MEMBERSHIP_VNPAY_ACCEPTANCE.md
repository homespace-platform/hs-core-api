# OBS Re-review — Membership / VNPAY (post routing repairs)

**Verdict: PASS** (prior critical gateway/core/FE routing failures resolved; live VNPAY sandbox not tested)

Date: 2026-11-10  
Mode: read-only independent re-review  
Repos: `hs-core-api`, `hs-gateway-server`, `hs-web-app` (uncommitted working trees)  
Initial review failed on routing and IPN authorization; this re-review records the fixes.

## Focused verification run

| Check | Result |
| --- | --- |
| `MembershipServiceTest` (4) | PASS |
| `MembershipCheckoutServiceTest` (3) | PASS |
| `ListingStatusServiceTest` (54) | PASS |
| `MembershipControllerRouteTest` (1) | PASS |
| Gateway `GuestSecurityTest` (1) | PASS |
| `node --test lib/membership.test.ts` (7) | PASS |
| Live VNPAY sandbox E2E | **NOT TESTED** (no sandbox credentials / no public callback exercised) |
| Official VNPAY doc URL fetch | Timed out 504 from this environment; setup URLs match documented official paths and app defaults |

## Routing / security checklist (task-critical)

| Criterion | Result | Evidence |
| --- | --- | --- |
| Public GET `/api/v1/membership/vnpay/ipn` | **PASS** | Core: `MembershipController.java:19` dual-maps `{"/membership","/api/v1/membership"}`; IPN at `:55-57` `@GetMapping("/vnpay/ipn")`. Gateway rewrite strips `/api/v1` (`HsGatewayServerApplication.java:182-194`, `rewritePath` at `:214`) so request arrives as `/membership/vnpay/ipn`. |
| Gateway `permitAll` limited to that path | **PASS** | `SecurityConfig.java:49` exact `HttpMethod.GET` + `/api/v1/membership/vnpay/ipn` only (no membership wildcard). `GuestSecurityTest.java:39-42`: GET IPN OK; GET `/me` 401; POST `/checkout` 401; POST IPN 401. Catch-all `/api/v1/**` authenticated at `:55`. |
| All other membership paths authenticated | **PASS** | Same gateway rules + test. Core authed endpoints call `requireUserId()` (`MembershipController.java:32-52,60-64`). `plans()` has no controller auth but is behind gateway JWT. |
| Core receives `/membership/*` after rewrite | **PASS** | Dual mapping includes `/membership`; route test asserts GET `/membership/plans` OK (`MembershipControllerRouteTest.java:22-23`). |
| FE requests `/api/v1/membership/*` | **PASS** | `membership.service.ts:26,41,52,64,76`; `lib/membership.ts:18-27`; FE test asserts paths and confirms unused `/api/membership` BFF removed (`membership.test.ts:224-253`; Glob found 0 BFF files). Axios `baseURL=NEXT_PUBLIC_GATEWAY_BASE_URL` (`axios-client.ts:20-21`, `.env.example` → `http://localhost:8080`). |
| Return URL only polls signed-backend order | **PASS** | `return/page.tsx:56-57,85-103,118-119`: extracts `vnp_TxnRef`/`orderId` for lookup only; explicitly does not trust `vnp_ResponseCode`; polls `membershipService.getOrder` then optional `getMyMembership` after PAID. No FE grant path. |
| Setup Markdown URLs | **PASS** | `VNPAY_MEMBERSHIP_SETUP.md:8-9,14` now document `/api/v1/membership/...` IPN/API paths and Return poll of orders; secrets stay env-only (`.env.example:104-107` blanks for TMN/secret). Pay URL default matches `application.properties:231`. |

## Business-rule / security matrix (recheck)

| Criterion | Result | Evidence |
| --- | --- | --- |
| Tiers/prices Free 3 / Starter 10@49k / Pro 30@129k / Business 100@349k | **PASS** | `MembershipTier.java:3`; FE `lib/membership.ts:37-102` |
| Fixed non-stacking quotas | **PASS** (code) | Paid active uses paid bucket only (`MembershipService.java:46-50`); NEW activate resets `paidUsed` (`:82`) |
| Free 3 lifetime | **PASS** (code) | Hardcoded `3` in view/reserve (`:44,48,107`) |
| ADMIN unlimited backend | **PASS** (code+unit) | `view` MAX_VALUE + unlimited; `reserve(..., adminActor \|\| isAdmin())` (`:44-50,95-98`); `MembershipServiceTest.adminSubmissionDoesNotSpendOwnersFreeUses` |
| 24h upgrade window / difference / target−consumed | **PASS** (code+unit) | `quote`/`activate` (`:62-67,74-77`); STARTER→PRO 80_000 / 28 (`MembershipServiceTest`) |
| After 24h full-price NEW | **PASS** (code+unit) | `MembershipServiceTest.fullPriceRenewalAfterUpgradeWindowResetsCycle` |
| No draft/edit charge | **PASS** (code) | Draft releases reservation (`ListingStatusService.java:73-74`); `beginContentReview` has no reserve (`:307-320`) |
| Concurrent reservation | **PASS** (code) | Owner `FOR UPDATE` via `lockMembershipOwner` + reserved counters |
| Rejection release | **PASS** (code) | Admin REJECTED → `finish(..., false)` (`:134-136`) |
| Republish charge | **PASS** (code) | Reserve when no window; post-termination `reserve+finish(true)` (`:107-108,229-232`) |
| Edit review does not silently renew | **PASS** (code) | `finishContentReview` passes `renewPublication=false` (`:337`) |
| Published listing unaffected by package expiry | **PASS** (code) | Membership `active()` only; listing expiry uses listing `expiresAt` |
| IPN signature/TMN/amount/status/idempotency/late | **PASS** (unit) | `MembershipCheckoutService.ipn` (`:78-115`); unit tests cover activate-once, amount mismatch, failed, late EXPIRED→REVIEW |
| Return URL no grant / FE no secrets | **PASS** | Return page + no VNPAY secret refs in FE source; only gateway base URL in `.env.example` |

## Prior critical failures — disposition

1. Membership API not on gateway contract path → **FIXED** (dual controller mapping + FE `/api/v1` + BFF removed).
2. IPN blocked by gateway auth → **FIXED** (exact GET permitAll + GuestSecurityTest).
3. Live sandbox E2E → still **NOT TESTED** (credentials/public callback absent); does not re-open routing FAIL.

## Residual non-blocking risks (not FAIL)

1. No Flyway/Liquibase migration for membership schema; relies on `JPA_DDL_AUTO=update` or manual DDL (`VNPAY_MEMBERSHIP_SETUP.md:5`).
2. `ListingStatusServiceTest` still does not assert membership reserve/finish wiring (membership may be null in those tests); quota unit coverage remains in `MembershipServiceTest`.
3. Live sandbox payment + public IPN callback still required before production cutover.

## Git diffs inspected (read-only)

- `hs-core-api`: membership controller/services/tests + listing status hooks + docs; controller dual-map + IPN present.
- `hs-gateway-server`: `SecurityConfig` +2 lines permitAll IPN; `GuestSecurityTest` +4 membership assertions.
- `hs-web-app`: membership UI/services; service paths `/api/v1/membership/*`; return page poll-only; BFF gone.
