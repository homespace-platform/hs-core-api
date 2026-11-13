# hs-user-service — Module Instructions

Adds to the repository-level `hs-core-api/AGENTS.md`. Changes in this module are L3 when they touch authentication, authorization, identity, KYC or personal data.

## Scope

Users, roles and permissions, addresses, Keycloak user management and sync, and KYC verification (Didit integration). Depends only on `hs-common-service`; most other modules depend on this one.

## Identity and Authorization

- Identity comes from Keycloak, verified at the Gateway. Do not accept a user ID, role or permission from request payloads where the verified user context exists.
- Preserve the `User` → `Role` → `Permission` model and existing authority names. Do not widen access or rename authorities without checking `@PreAuthorize` usage in `hs-api-service` and other modules.
- Preserve the Keycloak sync behavior (`UserSyncConsumer`, `KeycloakUserService`). A change must keep the Keycloak user and the local user consistent; do not create a path that updates one without the other.
- Admin-only operations stay admin-only. Users may read and change only their own data unless an existing rule says otherwise.

## Sensitive Data

- Treat national ID, date of birth, address, phone, email, KYC documents and decisions as sensitive. Do not log them or add them to responses, events or exception messages.
- Keep validation (`Adult`, `UserValidationPatterns`) intact; do not loosen it to make a test pass.
- Never log Keycloak admin credentials, passwords or tokens.

## KYC

- Preserve `KycStatus` semantics (`NOT_VERIFIED`, `PENDING`, `REVIEW_REQUIRED`, `VERIFIED`, `REJECTED`, `EXPIRED`) and `KycPolicy` / `KycGateService` gating. Other modules rely on the gate.
- Webhooks from the provider must keep signature verification (`DiditWebhookSignatureVerifier`) and duplicate-event handling. Do not trust webhook payloads without verification.
- Provider status mapping (`DiditStatusMapper`) changes need tests, because they decide who is verified.

## Verification

Follow `docs/ai/verification.md`. For this module:
- Add or update security regression tests for changed authorization, ownership or validation behavior (denied cases, not only the success path).
- Check dependent modules when a public DTO, enum or service signature changes (`hs-listing-service`, `hs-payment-service`, `hs-contract-service`, `hs-api-service`).
- Example form, only if the test exists: `.\mvnw.cmd -pl hs-user-service -am -Dtest=<ExistingTest> -Dsurefire.failIfNoSpecifiedTests=false test`.
- Do not call the real Keycloak or KYC provider in tests.
