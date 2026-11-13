# HomeSpace Core API — Codex Instructions

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
- Preserve response envelopes, error codes and endpoint paths used by `hs-web-app`, `hs-admin-portal` and Gateway routes. Use the `platform-contract-review` workspace skill when a shared contract changes.
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

Classify changes involving these areas as L3:

- Authentication and authorization
- Payments and deposits
- Rental contracts and signatures
- Listing ownership and booking state
- Sensitive personal data
- Shared API contracts
- Database migrations

## Coding Workflow

1. Identify the affected Maven module.
2. Inspect relevant controllers, services, DTOs and repositories.
3. Implement the smallest correct change.
4. Preserve existing validation and authorization.
5. Identify affected tests.
6. Verify the changed behavior.
7. Inspect the final diff.

Do not refactor unrelated modules.

## Verification

Follow docs/ai/verification.md.

Prefer module-scoped Maven commands using the repository wrapper.

Do not automatically run the complete Maven reactor for every change.

Do not run clean builds unless necessary.

Do not start databases, Docker containers or other services for ordinary unit tests.

## Skills

- spring-api-change: Focused backend API implementation.
- targeted-verification: Explicit targeted verification.
- focused-code-review: Explicit review of code changes.
- contract-risk-review: Deep review of contract and deposit flows.

Do not automatically invoke focused-code-review or contract-risk-review after normal implementation tasks.

## Command Discipline

- Use a single agent. Do not dispatch subagents or workers for routine implementation or bug fixes in one module.
- A local backend fix does not trigger a platform-wide or cross-module review; check dependent modules only when a public contract changes.
- Do not repeat `git status` / `git diff` without intervening edits. One final diff review, and `git diff --check` once after the last edit.
- Never rerun a successful test whose inputs have not changed. After a failure, diagnose first, then rerun only the affected test.
- Avoid repeated successful test commands.
- Avoid unrelated repository scans.
- Do not retry failed commands unchanged without diagnosis.
- Do not install dependencies unnecessarily.
- Do not run external infrastructure without a reason.

## Safety

Never expose credentials, modify production data, discard user changes or perform remote Git operations without authorization.

## Completion

Report changed modules, verification results and remaining risks.

Stop when the task is adequately verified.
