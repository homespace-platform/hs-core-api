# HomeSpace Core API — Verification Policy

## Objective

Verify code changes with the smallest meaningful set of commands.

Correctness and security take priority over token savings.

## L1 — Low Risk

Examples:
- Documentation
- Comments
- Static text
- Nonfunctional formatting

Required:
- Inspect the affected diff.

Do not run Maven tests or builds by default.

## L2 — Normal Risk

Examples:
- Service bug fixes
- Controller implementation changes
- Local validation changes
- Repository query changes

Procedure:
1. Identify the affected Maven module.
2. Find relevant existing tests.
3. Run focused tests.
4. Compile affected modules when useful.
5. Review impacted interfaces.

Do not run the full reactor, unrelated suites or a full build after focused tests pass.

Example commands, only when the actual module and test exist:

.\mvnw.cmd -pl hs-listing-service -am compile

.\mvnw.cmd -pl hs-listing-service -am -Dtest=ListingServiceTest -Dsurefire.failIfNoSpecifiedTests=false test

Replace example targets with actual affected targets.

Verify that the intended test actually executed.

## L3 — High Risk

Examples:
- Payments and deposits
- Contracts and signatures
- Authentication and authorization
- Database migrations
- Cross-service API changes

Procedure:
1. Identify business invariants.
2. Run relevant focused tests.
3. Inspect affected integration boundaries.
4. Run integration tests when applicable.
5. Expand verification when risks require it.

Do not bypass required verification to save time.

## Command Policy

- Do not repeat successful checks without relevant changes. Never rerun a passing test whose inputs are unchanged.
- After a fix, rerun only the affected test, not unrelated suites.
- Do not start duplicate background runs of equivalent commands.
- Do not repeat `git status` / `git diff` without intervening edits; run `git diff --check` once after the final edit.
- Do not rerun a failing command without diagnosing it.
- Do not run clean builds without a concrete reason.
- Do not start unrelated infrastructure.
- Do not install dependencies merely to perform an optional check.
- Preserve enough error output for debugging.

## Completion

Report:
- Risk level
- Commands executed
- Passed tests
- Failed tests
- Blocked tests
- Unverified risks

Compilation alone does not prove tests passed.
