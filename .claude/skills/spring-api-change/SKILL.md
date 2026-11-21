---
name: spring-api-change
description: Implement focused changes to HomeSpace Spring Boot controllers, DTOs, services and repositories. Use for backend API implementation and bug fixes.
---

# Spring API Change

## Goal

Implement the smallest correct backend change while preserving architecture, security and compatibility.

## Procedure

1. Identify the affected Maven module.
2. Inspect relevant source files and interfaces.
3. Determine required behavior.
4. Implement the focused change.
5. Preserve validation, authorization and error contracts.
6. Follow `docs/ai/verification.md` and finish after the required checks.

## Execution Rules

- Prefer module-scoped Maven commands.
- Reuse relevant existing tests.
- Add regression tests for meaningful bug fixes.
- Do not launch unrelated infrastructure.
- For environment-backed configuration changes, compare `.env` and `.env.example` by key name, keep them in sync, and never print or commit `.env` values. Do not add silent fallback values for missing required variables; make startup fail with the missing variable name.

## Output

Report changed modules, verification results and unresolved risks.
