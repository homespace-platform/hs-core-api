---
name: targeted-verification
description: Select and run relevant focused tests for HomeSpace Core API changes. Use when targeted verification or validation is explicitly requested.
---

# Targeted Verification

## Procedure

1. Identify changed behavior and files.
2. Identify the affected Maven module.
3. Classify risk as L1, L2 or L3.
4. Follow docs/ai/verification.md.
5. Select the smallest meaningful verification.
6. Execute necessary checks.
7. Report results and limitations.

## Rules

- Reuse existing tests when possible.
- Add regression coverage when appropriate.
- Avoid full reactor builds for isolated changes.
- Avoid repeated equivalent commands.
- Diagnose failures before retrying.
- Expand verification for high-risk changes.
- Never claim skipped tests passed.

## Stop Condition

Stop when the required risk-based verification has completed or a clearly documented blocker prevents further verification.
