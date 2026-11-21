---
name: focused-code-review
description: Review a specified HomeSpace code diff, commit, pull request or working tree for correctness, security, compatibility and regressions. Use only when code review is explicitly requested.
---

# Focused Code Review

## Mode

Review-only. Do not modify source code.

## Procedure

1. Identify the requested review scope.
2. Inspect the relevant diff once. Reuse it when reviewing affected behavior.
3. Examine changed behavior and affected interfaces.
4. Identify credible failure scenarios.
5. Validate important findings when necessary.
6. Report actionable results.

## Priorities

P0: Critical security, data loss or financial integrity defects.

P1: Serious correctness, authorization or transaction failures.

P2: Meaningful validation, compatibility or performance problems.

P3: Minor maintainability problems with concrete impact.

## Efficiency

- Start with static evidence. Inspect an affected dependency or run a focused check only to resolve a material uncertainty.
- Do not report subjective style preferences.

## Output

Each finding must include priority, file location, failure scenario, impact and suggested fix.

If no actionable findings are identified, state that clearly.

Report any unverified boundaries.
