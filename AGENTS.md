# HomeSpace Core API — Codex Instructions

## Engineering standards

- Preserve business invariants, public contracts and backward compatibility. Keep authentication, authorization, input validation and error handling intact.
- Protect secrets and sensitive data in code, commits, tests, logs and reports. Treat third-party hooks as untrusted until reviewed and explicitly authorized.
- Change production configuration, deploy or run destructive data operations only when explicitly authorized for the target environment.
- Keep review tasks read-only unless the developer requests fixes.

## Execution policy

- **FAST** is the default for a small fix, one API, a simple UI or configuration change, or a limited refactor. Start as soon as the scope is clear. Use the fewest useful calls, with a soft ceiling of roughly 8–12 in normal conditions.
- **STANDARD** fits multi-file features, API contract changes, broader refactors and hard-to-reproduce bugs. Make a short plan, inspect affected dependencies and verify in one focused round after editing.
- **DEEP** is required for significant security, authentication, authorization, payment, deposit, transaction, contract lifecycle, migration, architecture or incident risk. Identify the relevant invariants and verify the affected boundaries, including failure and denied cases where applicable. Escalate by risk even for small changes.

### Work and tools

- Identify the goal and smallest correct scope. Read the directly affected files, then check callers or dependencies when the change can affect them. Plan mentally or in a few lines for complex work, then execute.
- Give each tool call a purpose that advances the request or reduces a real risk. Batch independent reads and search exact paths or symbols. Reuse results when inputs are unchanged. Skip full repo scans, subagents and independent review passes for routine tasks.
- For a small review, stop at the requested diff when it establishes the findings. Inspect callers, test setup or runtime behavior only if a material finding depends on evidence missing from that diff.
- **No repeated verification without new evidence.** Repeat a read, search or check only when relevant inputs changed, an error or conflicting evidence appeared, or a required risk check remains. If two investigation attempts yield no useful evidence, change approach or stop that investigation.
- Make routine technical decisions independently. Ask when essential information is missing or the action requires authorization. Reuse existing code and patterns, protect user changes, and keep edits and dependencies within scope.

### Git and validation

- Run `git status --short` at most once at the start if needed to identify existing edits, and once at the end if needed to report files. Inspect the scoped diff once when useful. Use `git diff --stat` only to summarize scope and `git diff --check` at most once at the end when applicable. Reuse existing evidence for the same Git state. Recheck affected state for new errors, conflicts or concrete risk. Commit, reset, clean, checkout or discard only when requested.
- Normally validate once after editing with the smallest relevant test or compiler check. Skip full builds for small changes. Expand checks when evidence or risk warrants it. On failure, distinguish code from environment causes, diagnose and rerun only the affected check after a relevant change. Reuse test output already returned. If a check is blocked, use a permitted alternative only when the evidence is necessary, or report the blocker.

### Stop and report

Stop when the request and acceptance criteria are met, required checks are complete and concrete in-scope issues are resolved. Skip speculative optimization, unrelated refactors and automatic chains of review, audit or verification. Report **Completed**, **Validation** and **Notes** when needed. List only checks actually run and state material limits or remaining risks.

## Scope

Java Spring Boot backend with Maven multi-module architecture.

Read the current pom.xml and relevant module documentation before assuming dependencies or build targets.

## Architecture

- Respect existing module boundaries.
- Follow existing controller, service and repository patterns.
- Keep business logic in the appropriate service layer.
- Reuse shared utilities.
- Avoid circular dependencies.
- Preserve API response and error contracts.
- Preserve transaction boundaries and data integrity.

## Maven Modules

Modules (see `pom.xml`): `hs-common-service`, `hs-user-service`, `hs-storage-service`, `hs-listing-service`, `hs-payment-service`, `hs-contract-service`, `hs-api-service`.

Dependency direction (from module poms; re-check the pom before relying on it):
- `hs-common-service` has no internal module dependencies.
- `hs-user-service` and `hs-storage-service` depend on common.
- `hs-listing-service` depends on common, user and storage.
- `hs-payment-service` depends on common, user, storage and listing.
- `hs-contract-service` depends on common, user, storage, listing and payment.
- `hs-api-service` is the assembly module: controllers, facades, security config and the Gateway identity filter. It depends on all other modules.

Identify the affected module from the path of the changed file. Module-specific rules live in the nested `AGENTS.md` of `hs-user-service`, `hs-payment-service` and `hs-contract-service`; read it when working in that module.

## Cross-Module Compatibility

- A change to a public class, DTO, enum, event or repository method in a module can break every module that depends on it. Search dependent modules (downstream in the list above) before changing it.
- Changes in `hs-common-service` affect all modules. Verify at least one dependent module compiles.
- Changes to enums or status values persisted in the database need a migration or compatibility check (see existing `*SchemaMigration` classes).
- Keep module dependencies acyclic. Do not make a lower module depend on a higher one.
- Use the module-scoped Maven form `-pl <module> -am` so that required upstream modules are built. Add dependent modules (`-amd`) only when the change affects a public contract of the module.

## Security and API Contract Boundaries

- The Gateway verifies JWTs and forwards identity headers; `hs-api-service` converts them into the user context. Do not accept user identity from request bodies or parameters when the verified context is available.
- Preserve `@PreAuthorize`, ownership checks and role checks. Do not widen access.
- Preserve response envelopes, error codes and endpoint paths used by the Gateway and web clients. When a shared contract changes, compare request and response shapes, authorization, status and error behavior against this repository’s controllers, DTOs, docs and tests. Identify affected consumers and flag any coordinated compatibility checks that need consumer access.
- Do not log or return sensitive personal data, bank data, tokens or secrets.

## Domain Documentation

Read only the document that matches the task; do not read all of `docs/`:
- Contract lifecycle, amendments, signing, deposit: `docs/CONTRACT_*.md`, `docs/CONTRACT_LIFECYCLE_PHASE_00_RULES.md`, `docs/RENTAL_WORKFLOWS_OPERATIONS.md`
- Monthly invoices: `docs/MONTHLY_BILLING.md`
- Payments and receipts: `docs/PAYMENT_*.md`, `docs/MOBILE_PAYMENT_PROOF_UPLOAD.md`, `docs/SEPAY_MEMBERSHIP_SETUP.md`
- Blockchain proofs: `docs/BLOCKCHAIN_INTEGRATION.md`
- Signing provider: `docs/SMARTCA_INTEGRATION.md`
- Listing rules and addresses: `docs/LISTING_POLICIES.md`, `docs/VIETMAP_*.md`

Dated reports in `docs/` are history, not rules. Verify against code.

## High-Risk Domains

Use DEEP verification (L3 in the verification guide) for:

- Authentication and authorization
- Payments and deposits
- Rental contracts and signatures
- Listing ownership and booking state
- Sensitive personal data
- Shared API contracts
- Database migrations

## Verification

Use module-scoped Maven wrapper commands and the risk-specific checks in `docs/ai/verification.md`. Keep ordinary unit tests independent of databases, Docker and other services.

## Skills

- spring-api-change: Focused backend API implementation.
- targeted-verification: Explicit targeted verification.
- focused-code-review: Explicit review of code changes.
- contract-risk-review: Deep review of contract and deposit flows.

Do not automatically invoke focused-code-review or contract-risk-review after normal implementation tasks.

Report affected modules and any unverified interface or integration risk. Remote Git operations require authorization.
