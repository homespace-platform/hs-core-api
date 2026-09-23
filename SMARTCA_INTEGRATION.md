# HomeSpace / VNPT SmartCA

The SmartCA integration runs inside `hs-contract-service` (loaded by `hs-api-service`), not a separate NestJS process. The browser never receives SP credentials or raw signature values.

## Configuration

Copy the SmartCA section of `.env.dev.example` into the local `.env.dev`. Keep `CONTRACT_SIGNATURE_MODE=INTERNAL` and `SMARTCA_ENABLED=false` until a live sandbox test passes. Then set both to `SMARTCA` / `true` and restart the backend. Fill `SMARTCA_SP_ID` and `SMARTCA_SP_PASSWORD` only in the local secret file or deployment secret manager. `SMARTCA_BASE_URL` is the UAT SP769 endpoint by default; use the production endpoint only when VNPT provisions production access. Do not put secrets in Next.js `NEXT_PUBLIC_*` variables.

`SMARTCA_WEBHOOK_ENABLED` must remain `false`. No public callback is registered: the backend scheduler polls VNPT using `transaction_id`. `SMARTCA_WEBHOOK_PUBLIC_URL` is reserved for a future authenticated webhook integration.

Development uses `JPA_DDL_AUTO=update`. For a deployment with schema validation, apply `hs-contract-service/src/main/resources/db/migration/V20260923_01__create_signature_requests.sql` using the project's database deployment process; this repository does not auto-run Flyway.

## Signing sequence

1. Initial payment is confirmed and the landlord creates a contract draft.
2. Landlord exports the revision: DOCX and PDF are stored in parallel; PDF gets a final page with separate signature areas for both parties. PDF conversion must succeed before SmartCA signing.
3. Landlord selects the certificate associated with their verified CCCD and approves the exact PDF on HomeSpace. The backend hashes the PDF signing ByteRange, stores the prepared bytes, and asks VNPT to sign that hash.
4. VNPT app confirms the hash transaction. The backend polls by `transaction_id`, packages the returned signature with the selected X.509 certificate into PDF CMS, verifies it, and stores `SIGNED_LANDLORD`. The contract then becomes `TENANT_SIGNATURE_PENDING`.
5. Tenant signs an incremental update of that landlord-signed PDF. The backend verifies **both** PDF signatures and their CCCD subjects before storing `SIGNED_FINAL`, activating the contract, and completing the listing/rental-request/parking changes.

The web page polls only while a request is in progress. Closing the page does not stop backend reconciliation. A successful confirmation in the VNPT app alone does **not** mark the HomeSpace contract active; the signed PDF must be embedded and verified.

## Live acceptance checklist

- Confirm both parties have Didit VERIFIED KYC, matching CCCD, and a valid VNPT certificate.
- Export a draft and inspect both DOCX and PDF; verify the PDF's last page has two signature areas.
- Sign as landlord; verify app notification, `SIGNED_LANDLORD` PDF, and `TENANT_SIGNATURE_PENDING` on web.
- Sign as tenant; download final PDF and validate both signatures with an independent PDF verifier.
- Reject and expire a test request; verify no contract activation and that retry cannot duplicate a completed VNPT transaction.
- Temporarily interrupt web/browser access after pressing Sign; backend should continue polling and finish.

The API contract follows VNPT's [SP769 specification](https://smartca.vnpt.vn/cms_img/upload/20240524141119VNPT.IT_SmartCA_SP_QD769_API_specification_document_v2.0.pdf). The actual sandbox must be used to confirm VNPT's hash encoding and certificate-signature algorithm for the provisioned account.
