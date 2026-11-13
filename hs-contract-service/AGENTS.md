# hs-contract-service — Module Instructions

Adds to the repository-level `hs-core-api/AGENTS.md`. Changes in this module are L3 by default except documentation and static text.

## Scope

Rental contracts and templates, revisions and PDF documents, deposit agreements, signing (SmartCA), amendments, renewals, closure and mutual termination, monthly invoices, complaints, and blockchain proof submission. Depends on common, user, storage, listing and payment.

## Ownership and Authorization

- Contract parties come from backend records (`Contract.landlordId` / `tenantId`, rental request owner/renter) and the verified user context, not from snapshot or payload fields. Do not let a payload change who the signer is.
- Preserve per-party access checks and the evidence/storage access checkers (`ContractStorageAccessChecker`, `*EvidenceAccessChecker`). Do not expose a document or evidence to a user who is not a party or an authorized admin.
- Signing identity must match the authenticated party. Do not edit identity data on a draft to sign with another person's certificate.

## Lifecycle Invariants

- `ContractStatus` values: `DRAFT`, `PENDING_REVIEW`, `LANDLORD_SIGNATURE_PENDING`, `TENANT_SIGNATURE_PENDING`, `ACTIVE`, `EXPIRED`, `CLOSED`, `TERMINATED`, `CANCELLED`. Transitions go through the lifecycle services; do not set status directly from controllers or add transitions that skip signature or validation.
- Terms (rent, deposit, duration, dates, occupants) come from business sources documented in `docs/CONTRACT_LIFECYCLE_PHASE_00_RULES.md`; client-edited snapshot fields do not override them. Read that document before changing draft editing or data sources.
- Time rules (due dates, late fees, rollover) are centralized (`MonthlyBillingSchedule`, `LatePaymentPolicy`, billing time helpers). Do not duplicate them.

## Signing and Document Integrity

- `SignatureRequestStatus` values: `CREATED`, `PENDING_USER_CONFIRMATION`, `PROVIDER_SIGNED`, `EMBEDDING`, `SIGNED`, `REJECTED`, `EXPIRED`, `FAILED`, `CANCELLED`. Terminal states and retry rules are defined on the enum; preserve them. The embedding phase and its schedulers (`SignaturePollingScheduler`, `SignatureEmbeddingScheduler`) must stay idempotent.
- A signed document (`SIGNED_FINAL`) is immutable. Never modify or delete it to make a flow continue. Changes create a new revision or amendment; old revisions stay.
- Select the correct revision, purpose and ready document before preview or signing.
- Blockchain proof: the registry hash must be the SHA-256 of the exact signed PDF bytes, with no personal data on-chain. `ProofStatus` (`PENDING`, `SUBMITTED`, `CONFIRMED`, `FAILED`) transitions and the sender lease must stay idempotent and retry-safe. See `docs/BLOCKCHAIN_INTEGRATION.md`.
- Never call the real SmartCA provider or chain from tests.

## Deposit and Payment Interactions

- Deposit and payment truth lives in `hs-payment-service` (`PaymentRequest`, `DepositRecord`). Contract documents show it but do not own it. Do not change payment or deposit amounts from contract payloads.
- Keep `ContractDraftFunding`-style checks that tie draft amounts to confirmed funds.
- A change to a public type shared with `hs-payment-service` must be checked in both modules.

## Amendments and Revisions

- Amendments and renewals apply only from their effective date; do not apply a future amendment early. Issued invoices do not change when terms change later.
- Keep amendment comparison and revision history intact; see `docs/CONTRACT_AMENDMENTS.md`.

## Verification (L3)

Follow `docs/ai/verification.md`.
- State the invariant being protected (who may act, which transition is allowed, what stays immutable) and test the denied and duplicate cases.
- Check `hs-payment-service` and `hs-api-service` usages when a public type, event or endpoint changes.
- Example form, only if the test exists: `.\mvnw.cmd -pl hs-contract-service -am -Dtest=<ExistingTest> -Dsurefire.failIfNoSpecifiedTests=false test`.
- Use database integration tests only for persistence, locking or migration changes.
- Use the `contract-risk-review` skill when a deep contract or deposit review is requested; do not run it automatically.
