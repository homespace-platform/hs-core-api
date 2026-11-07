---
name: contract-risk-review
description: Perform an explicit high-risk review of rental contracts, deposit flows, signing operations and associated financial or data-integrity boundaries. Use when a deep contract or deposit review is specifically requested.
---

# Contract Risk Review

## Mode

Review-only unless implementation is explicitly requested.

## Review Checklist

### Authorization
- Contract ownership
- Tenant and landlord permissions
- Unauthorized document access

### Financial Integrity
- Deposit amount correctness
- Currency precision
- Duplicate transactions
- Payment state consistency

### Contract Lifecycle
- Valid state transitions
- Signing authorization
- Duplicate signing requests
- Cancellation and expiration
- Idempotency where applicable

### Document Integrity
- Generated document consistency
- Required business fields
- Unauthorized document modification
- Signature-related integrity

### Integration
- Payment integrations
- Blockchain integrations when present
- Asynchronous events
- Failure handling and retries

## Verification

- Inspect relevant code and existing evidence first.
- Run focused verification for material findings when needed.
- Do not automatically start full infrastructure.
- Do not run unrelated tests.
- Report verification limitations.

## Output

Findings ordered by severity, with file location, failure scenario, impact and recommended correction.

Do not report speculative findings without a credible failure scenario.
