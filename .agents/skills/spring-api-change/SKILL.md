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
6. Follow docs/ai/verification.md.
7. Inspect the final diff once, after the last edit.

## Execution Rules

- Prefer module-scoped Maven commands.
- Reuse relevant existing tests.
- Add regression tests for meaningful bug fixes.
- Do not run all modules by default.
- Do not launch unrelated infrastructure.
- Do not repeatedly execute equivalent commands. Never rerun a passing test whose inputs are unchanged; after a failure, diagnose, then rerun only the affected test.
- Do not dispatch subagents, and do not start a review skill after implementation unless requested.
- Do not refactor unrelated code.

## Output

Report implementation, changed files, verification and unresolved risks.
