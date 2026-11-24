---
name: targeted-verification
description: Select and run relevant focused tests for HomeSpace Core API changes. Use when targeted verification or validation is explicitly requested.
disable-model-invocation: true
---

# Targeted Verification

## Procedure

1. Identify changed behavior and files.
2. Identify the affected Maven module.
3. Use `docs/ai/verification.md` to classify risk. For L1 changes without behavior impact, inspect the affected change once. For L2 or L3, select the checks that cover the remaining risks.
4. Reuse applicable passing results for unchanged inputs. Run any remaining focused checks together after edits and confirm that the intended tests ran. Distinguish setup or dependency failures from code failures before rerunning the affected check.
5. Report results and limitations.

## Rules

- Reuse existing tests when possible.
- Add regression coverage when appropriate.
- Use module-scoped checks for isolated changes and verify affected integration boundaries for L3.

## Stop Condition

Stop when the required risk-based verification has completed or a clearly documented blocker prevents further verification.
