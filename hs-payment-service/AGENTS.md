# hs-payment-service — Module Instructions

Adds to the repository-level `hs-core-api/AGENTS.md`. Changes in this module are L3 by default except documentation and static text.

## Scope

Payment requests and line items, evidence and proof uploads, deposits and the deposit ledger, disputes, receipts (PDF), bank accounts, membership orders (SePay) and ETH membership payments (EIP-712 quotes, wallet challenges, on-chain verification). Depends on common, user, storage and listing; `hs-contract-service` depends on this module.

## Financial Data Integrity

- Money is `BigDecimal` (VND amounts). Never use `double` or `float`. Keep scale and rounding explicit and consistent with the surrounding code. ETH amounts are wei (integer) and must not be mixed with VND without the existing price service.
- Amounts, currency and recipient used for a payment come from backend-owned records (`PaymentRequest`, `PaymentLineItem`, quotes), not from client payloads.
- Payment and bank-account snapshots on a transaction must not change retroactively when a profile changes. Do not edit receipts or confirmed records to fix display issues.
- A reported transfer (`TRANSFER_REPORTED`) is not a confirmed payment. Only the confirmation path may produce `CONFIRMED`.

## State Transitions

- `PaymentStatus` values: `AWAITING_TRANSFER`, `TRANSFER_REPORTED`, `CONFIRMED`, `REJECTED`, `OVERDUE`, `EXPIRED`, `CANCELLED`, `DISPUTED`. `isFinalState()` treats `CONFIRMED`, `EXPIRED`, `CANCELLED` as final. Do not add a path that leaves a final state or skips validation.
- `DepositStatus` values: `PENDING`, `HELD`, `SETTLEMENT_PENDING`, `REFUND_DUE`, `REFUND_REPORTED`, `REFUNDED`, `DISPUTED`, `RETAINED_BY_LANDLORD`. Deposit moves go through `DepositLedgerService`; keep ledger entries consistent with payments.
- Adding or renaming a persisted status needs a compatibility or migration check (see `*SchemaMigration` classes in `config`).
- Time rules (due dates, overdue, expiry) are centralized in `PaymentTimePolicy`; do not re-implement them elsewhere.

## Idempotency, Concurrency and Reconciliation

- Duplicate callbacks, duplicate confirmations and repeated user actions must not create duplicate payments, ledger entries or refunds. Respect existing unique constraints (for example the unique transaction hash on ETH quotes) and `@Version` / pessimistic locks. Do not remove locks or constraints to fix a test.
- Keep ledger writes and status changes in one transaction.
- External provider data (SePay, chain) must be verified (signature, transaction receipt) before it changes state. Do not trust webhook or client-supplied transaction results.
- Preserve transfer-reference generation and matching (`TransferReferenceGenerator`) so bank transfers can still be reconciled.
- Bank account data stays encrypted (`BankAccountCryptoConverter`); never log or return it beyond what existing responses expose.

## Verification (L3)

Follow `docs/ai/verification.md`.
- Identify the business invariant first (amount conserved, one confirmation, allowed transition) and test it, including duplicate and invalid-transition cases.
- Check `hs-contract-service` and `hs-listing-service` usages (`RentalPaymentLifecycleAdapter`, events) when a public type or event changes.
- Example form, only if the test exists: `.\mvnw.cmd -pl hs-payment-service -am -Dtest=<ExistingTest> -Dsurefire.failIfNoSpecifiedTests=false test`.
- Use integration tests with a database only when the change touches persistence, locking or migrations. Do not call SePay, chain RPC or real wallets in tests.
- For contract or deposit flow risk, use the `contract-risk-review` skill when a review is requested.
