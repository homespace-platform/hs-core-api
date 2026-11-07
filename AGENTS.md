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
