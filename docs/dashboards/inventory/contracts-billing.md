# Inventory API: hợp đồng, ký số, hóa đơn tháng, tất toán, khiếu nại, blockchain proof

Ngày 2026-11-28 · chỉ đọc code · không có controller trong `hs-contract-service`; mọi controller nằm ở `hs-api-service`.
Viết tắt: **AC** = `hs-api-service/src/main/java/com/hs/api/controller`; **CS** = `hs-contract-service/src/main/java/com/hs/contract`.
Mọi path có tiền tố `/api/v1` (gateway `rewritePath` về `/{segment}`: `hs-gateway-server/.../HsGatewayServerApplication.java:188,201`); `/api/v1/internal/**` bị `denyAll` (`gateway/.../SecurityConfig.java:58`). Controller map cả `/x` lẫn `/api/v1/x` (riêng `ComplaintController` chỉ `/x`).
Core có `anyRequest().permitAll()` (`hs-api-service/.../config/SecurityConfig.java:26`), nên quyền thật nằm ở `@PreAuthorize` hoặc trong service.
Quyền: **U** = user đăng nhập và là bên liên quan (kiểm trong service); **L** = chủ nhà; **T** = người thuê; **A:P** = ADMIN + permission P; **INT** = `X-Internal-Secret` (`UserContextFilter.java:27,48-50`).

## 1. Bảng endpoint (tổng **117** endpoint = method+path; 15 controller)
Theo controller: ContractAdmin 14 · ContractDocumentAdmin 2 · ComplaintAdmin 3 · Contract 24 · ContractAmendment 10 · ContractLifecycle 8 · ContractSignature 6 · DepositAgreement 3 · MonthlyInvoice 21 · RentalWorkflow 9 · Complaint 4 · ContractAi 7 · BlockchainProof 2 · DocumentIntegrity 3 · DocumentInternal 1. Mỗi dòng có thể gộp vài endpoint cùng quyền; số trong ngoặc là số endpoint của dòng.

| M | path | file:line | ai gọi | mục đích |
|---|---|---|---|---|
| **Admin: mẫu hợp đồng** (`AC/admin/ContractAdminController.java`; class `A:CONTRACT_TEMPLATE_VIEW` :37) |
| GET (4) | /admin/contract-template-fields · /admin/contract-templates · /admin/contract-templates/{id} · .../{id}/versions | :50, :60, :89, :130 | A:CONTRACT_TEMPLATE_VIEW | trường mẫu, danh sách mẫu (size≤100), chi tiết, danh sách phiên bản |
| GET (1) | /admin/contract-templates/{id}/versions/{versionId} | :140 | A:CONTRACT_TEMPLATE_VIEW | chi tiết phiên bản (placeholder, lỗi) |
| POST (1) | .../versions/{versionId}/test-preview | :168 | A:CONTRACT_TEMPLATE_VIEW (chỉ quyền lớp) | render thử PDF/DOCX |
| POST/PATCH/POST (3) | /admin/contract-templates · /admin/contract-templates/{id} · .../{id}/versions | :75, :100, :115 | A:CONTRACT_TEMPLATE_MANAGE | tạo mẫu, sửa mẫu, thêm phiên bản |
| POST (1) | .../versions/{versionId}/publish | :154 | A:CONTRACT_TEMPLATE_PUBLISH | xuất bản phiên bản |
| **Admin: hợp đồng, tài liệu, khiếu nại** |
| GET (4) | /admin/contracts · /{id} · /{id}/revision · /{id}/documents | ContractAdminController:197, :212, :223, :234 | A:CONTRACT_VIEW | danh sách HĐ (status, keyword, documentKind, size≤100; chỉ id, không PII), chi tiết, revision, tài liệu |
| GET,POST (2) | /admin/contracts/documents/{documentId}/content | `AC/admin/ContractDocumentAdminController.java:28` | A:CONTRACT_VIEW | xem PDF đã ký đã kiểm toàn vẹn |
| GET (2) | /admin/complaints · /admin/complaints/{id} | `AC/admin/ComplaintAdminController.java:20`, :22 | A:COMPLAINT_VIEW | hàng đợi (status, category, overdue; size≤50), chi tiết + lịch sử |
| POST (1) | /admin/complaints/{id}/actions/{action} | :24 | A:COMPLAINT_RESOLVE | CLAIM / REQUEST_INFO / COMMENT / DECIDE |
| **Hợp đồng của tôi** (`AC/contract/ContractController.java`) |
| GET (3) | /contracts/template-fields · /contracts/templates/system · /contracts/applicable-templates | :55, :63, :123 | U | trường mẫu, mẫu hệ thống đã xuất bản, mẫu áp dụng cho yêu cầu thuê |
| GET (3) + POST (1) | /contracts/templates/mine · /templates/{id} · /templates/{id}/versions · POST .../versions/{vid}/test-preview | :73, :83, :90, :97 | L (chủ mẫu) | mẫu riêng của chủ nhà, phiên bản, render thử |
| GET (2) | /contracts/by-rental-request/{rentalRequestId} · /contracts | :133, :158 | U | HĐ theo yêu cầu thuê; HĐ của tôi (status, role, billableOnly, agreementKind, size≤100) |
| GET (6) | /contracts/{id} · /{id}/revision · /{id}/listing-sync · /{id}/completeness · /{id}/preview-status · /{id}/payment-breakdown | :174, :182, :190, :211, :231, :250 | U | chi tiết, revision, đồng bộ tin đăng, độ đầy đủ, trạng thái xem trước, bảng tiền đầu kỳ |
| GET (3) | /contracts/{id}/documents · /contracts/documents/{documentId} · /contracts/{id}/effective-document | :284, :292, :300 | U | tài liệu của HĐ, một tài liệu, tài liệu đang hiệu lực |
| POST (1) | /contracts | :145 | L + CONTRACT_MANAGE + KYC | tạo bản nháp |
| PATCH (1), POST (2) | /contracts/{id}/revision · /{id}/previews · /{id}/send | :199, :221, :241 | L + CONTRACT_MANAGE | sửa nháp, sinh bản xem trước, gửi người thuê |
| POST (1) | /contracts/{id}/sign | :275 | U + CONTRACT_SIGN | ký (luồng cũ, không SmartCA) |
| POST (1) | /contracts/{id}/pay-mock | :261 | U, chỉ khi `homespace.payment.mock.enabled` | thanh toán giả lập |
| **Ký số SmartCA** (`ContractSignatureController.java`, `/contracts/{contractId}/signatures`) |
| GET (3) | .../state · .../certificates · .../{requestId} | :40, :51, :79 | U | trạng thái ký của tôi; chứng thư (gọi VNPT mỗi lần); một yêu cầu ký |
| POST (2) | .../initiate · .../{requestId}/retry | :64, :109 | U + CONTRACT_SIGN | tạo yêu cầu ký; thử lại sau REJECTED/EXPIRED/FAILED |
| POST (1) | .../{requestId}/refresh | :93 | U | poll VNPT ngay |
| **Phụ lục** (`ContractAmendmentController.java`, `/contracts/{id}/amendments`) |
| GET (3) | ... · /options · /draft | :18, :19, :26 | U | đề xuất hiện tại, lựa chọn được phép, bản nháp |
| POST (1), PUT (1), DELETE (1) | /preview · /draft · /draft | :20, :27, :28 | U | xem trước điều khoản, lưu nháp, bỏ nháp |
| POST (2) | ... (đề xuất) · /tenant-requests/{requestId}/decision | :23, :25 | L | chủ nhà đề xuất; quyết định yêu cầu của người thuê |
| POST (1) | /tenant-requests | :24 | T | người thuê xin thương lượng |
| POST (1) | /{proposalId}/decision | :29 | U (bên nhận) | chấp nhận/từ chối/đề nghị sửa |
| **Gia hạn, bàn giao, tất toán** (`ContractLifecycleController.java`, `/contracts/{id}/lifecycle`) |
| GET (1) | ... | :26 | U | trạng thái vòng đời (có đồng bộ ghi, xem 3.2) |
| POST (1) | /renewals | :27 | T | xin gia hạn |
| POST (2) | /renewals/{renewalId}/approve · /reject | :30, :33 | L | duyệt/từ chối gia hạn |
| POST (2) | /closure/propose · /final-costs | :36, :39 | L | đề xuất tất toán; chốt điện nước cuối/bàn giao |
| POST (2) | /closure/accept · /closure/dispute | :42, :45 | T | đồng ý quyết toán; phản đối (mở khiếu nại) |
| **Hợp đồng cọc** (`DepositAgreementController.java`, `/rental-requests/{rentalId}/deposit-agreement`) |
| GET (1), POST (2) | ... · /prepare · /reopen-signing | :30, :33, :39 | U | xem hồ sơ cọc; sinh bản cọc; mở lại ký cọc |
| **Hóa đơn tháng** (`MonthlyInvoiceController.java`, `/monthly-invoices`) |
| GET (1) | /contracts/{contractId} | :56 | L,T | danh sách hóa đơn (khóa ghi + sync, xem 3.2) |
| POST (1) | /{invoiceId}/extension-requests | :30 | T | xin gia hạn hạn thanh toán |
| POST (3) | /{invoiceId}/extension-requests/{requestId}/approve · .../reject · /{invoiceId}/extend-payment | :37, :43, :49 | L | duyệt, từ chối, tự gia hạn hạn |
| POST (3) | /{invoiceId}/issue · /prepare · /overdue-actions | :62, :70, :78 | L | phát hành hóa đơn với chỉ số, lưu chỉ số để tự phát hành, ghi phương án quá hạn |
| POST (1) | /{invoiceId}/overdue-actions/{actionId}/acknowledge | :163 | T | xác nhận đã đọc phương án quá hạn |
| POST (1) | /{invoiceId}/defer-to-next-period | :86 | L | chốt nợ chuyển kỳ sau (ROLLED_OVER) |
| POST (1) | /{invoiceId}/termination/propose | :93 | (không ai) | luôn ném `TERMINATION_NOT_ALLOWED` (:93-98): endpoint chết |
| POST (4) | /{invoiceId}/unilateral-termination · .../{noticeId}/decision · /withdraw · /handover | :100, :110, :115, :120 | L | chấm dứt đơn phương: phát hành, quyết định xem xét, rút, bàn giao |
| POST (1) | .../unilateral-termination/{noticeId}/review | :105 | T | xin xem xét trong 24h |
| POST (2) | /{invoiceId}/termination/accept · /decline | :126, :134 | T | đồng ý/từ chối chấm dứt sớm |
| POST (3) | /{invoiceId}/termination/withdraw · /complete · /force-after-decline | :141, :148, :156 | L | rút đề nghị; hoàn tất + quyết toán cọc; cưỡng chế sau từ chối |
| **Quy trình thuê** (`RentalWorkflowController.java`, `/rental-requests/{id}/workflow`) |
| GET (3) | /{kind}/documents · /cancellation · /early-exit | :21, :23, :26 | U | văn bản ký riêng (chỉ early-exit), trạng thái hủy trước nhận phòng, trạng thái chấm dứt sớm (đều khóa ghi) |
| GET (1) | /early-exit/calculation | :27 | U | tính tiền thuê cuối |
| POST (5) | /{kind}/documents · /cancellation · /cancellation/{action} · /early-exit · /early-exit/{action} | :22, :24, :25, :28, :29 | U | lập văn bản; đề nghị; phản hồi (hủy, chấm dứt sớm) |
| **Khiếu nại (user)** (`ComplaintController.java`) |
| GET (2) | /rental-requests/{id}/complaints · /complaints/{id} | :18, :21 | U | khiếu nại của MỘT yêu cầu thuê (không có "của tôi"); chi tiết |
| POST (2) | /rental-requests/{id}/complaints · /complaints/{id}/actions/{action} | :20, :22 | U (mở: + COMPLAINT_CREATE) | mở khiếu nại; trả lời/đề xuất/chuyển HomeSpace/đóng/mở lại |
| **Truy vấn cho AI** (`ContractAiController.java`, `/contracts/ai`) |
| GET (2) | /summary · /search | :31, :37 | U | tổng hợp HĐ của tôi (byStatus, awaitingSignature, expiring); tìm HĐ |
| GET (5) | /items/{id} · /terms · /amendments · /lifecycle · /financial | :49, :54, :61, :66, :71 | U | chi tiết rút gọn, điều khoản, phụ lục, vòng đời, hóa đơn/cọc/quyết toán |
| **Blockchain proof, toàn vẹn** |
| GET (1), POST (1) | /contracts/documents/{documentId}/blockchain-proof · .../verify | `AC/contract/ContractBlockchainProofController.java:20`, :26 | U | proof (GET ghi enqueue nếu chưa có); so hash ứng viên với on-chain |
| POST (1), GET,POST (2) | .../{documentId}/integrity-check · .../{documentId}/content | `AC/contract/ContractDocumentIntegrityController.java:23`, :29 | U | kiểm PDF+hash+on-chain (ghi `integrity_status`); PDF đã kiểm (`X-Document-Integrity`) |
| **Nội bộ** |
| GET (1) | /internal/documents/{kind}/{id}?recipientId= | `AC/internal/DocumentInternalController.java:45` | INT | PDF base64 (rental/membership/overpayment receipts, lease-bundles), tối đa 35MB |

Không có endpoint "chỉ đếm": mọi số hiện chỉ lấy được qua `totalElements` của danh sách phân trang (`PageResponse`, `size=1`) hoặc tải cả danh sách. Repository hợp đồng không có truy vấn `count/group by` nào (chỉ `BranchContractAssignmentRepository:18,106`, `ContractTemplateVersionRepository:41`).

## 2. Tín hiệu giám sát (C = đã có trong P1..P6; P = có một phần; **M** = THIẾU)
Trạng thái nguồn: `ContractStatus` DRAFT, PENDING_REVIEW, *_SIGNATURE_PENDING, ACTIVE, EXPIRED, CLOSED, TERMINATED, CANCELLED; `MonthlyInvoiceStatus` DRAFT/UNPAID/OVERDUE/PAID/ROLLED_OVER/CANCELLED; `SignatureRequestStatus` CREATED…SIGNED/FAILED; closure `WAITING_HANDOVER/PROPOSED/REFUND_PENDING/BALANCE_PENDING/DISPUTED/CLOSED/RETURNED` (`ContractLifecycleService.java:177,195,251,259,274,473`).

### Chủ nhà (P1)
| # | tín hiệu · vì sao | nguồn | P |
|---|---|---|---|
| L1 | HĐ chờ mình ký/mở lại ký (hạn ký, mất khách) | attention `OWNER_SIGN`,`SIGNING_REOPEN` (`BranchAttentionResponse.java:15-23`) | C: P1 §3.5 chip "Hợp đồng cần ký" |
| L2 | HĐ sắp hết 60/30 ngày (doanh thu, phòng trống) | `BranchMonitoringService.expiry` | C: P1 §3.6 |
| L3 | Khoản quá hạn (số, tiền), xin gia hạn hạn | `payment_requests` OVERDUE, `PAYMENT_EXTENSION_REQUEST` | C: P1 §3.4 + chip "Quá hạn" |
| L4 | Hạn chốt điện nước (`METER_UPCOMING/TODAY`, `DRAFT_MISSED`, `METER_AUTOFILLED`): trễ thì hệ thống tự điền | attention, `monthly_invoices.meter_*_logged_at` | P: P1 gộp vào chip "Khác", không widget riêng |
| L5 | Phụ lục/đề nghị chờ phản hồi (`respondBy`, `signBy`) | `AMENDMENT_RESPONSE`, `idx_amendment_due` | P: chỉ chip "Khác" |
| L6 | Chấm dứt sớm/đơn phương chờ trả lời (24h) | `TERMINATION_RESPONSE`, `TERMINATION_REVIEW_HOURS=24` | C: chip "Quá hạn" |
| L7 | HĐ hết kỳ chưa tất toán; closure kẹt (`WAITING_HANDOVER`, `refund_due_at`, `overdue_logged_at`, `DISPUTED`): cọc phải hoàn | `contract_closures` (`ContractClosure.java:26-39`), `/contracts/ai/summary.leaseEndedNotClosed` (`AiContractQueryService:101`) | **M**: WorkType không có closure; `OWNER_REFUND` chỉ khi đã có payment hoàn |
| L8 | Khiếu nại mở + hạn phản hồi của mình | `dispute_cases.respond_by/response_due_for` (`DisputeCase.java:46-47`) | **M**: không có `GET /complaints/mine`; attention chỉ dùng khiếu nại để chặn ký (`BranchAttentionService.java:655-658,765`) |
| L9 | Tổng quan HĐ của mình: theo trạng thái, chờ ký, đang hiệu lực | `GET /contracts/ai/summary` (`ROW_CAP=5000`, đếm trong bộ nhớ) | **M**: P1 không dùng, tái dùng được |
| L10 | Yêu cầu ký SmartCA chờ xác nhận/sắp hết hạn/FAILED | `signature_requests.status/expires_at/failure_code` | **M**: chỉ có theo từng HĐ (`/signatures/state`) |
| L11 | Hồ sơ cọc chưa ký/bị chặn (`blockedReason`) | `DepositAgreementController:30` (theo rental) | P: chỉ qua `SIGNING_REOPEN` |
| L12 | Phí chậm, nợ chuyển kỳ | `monthly_invoices.late_fee_amount`, ROLLED_OVER, `deferred_at` | **M**: P1/P3 không tách |
| L13 | Mẫu HĐ riêng: số mẫu ACTIVE/PUBLISHED, phiên bản lỗi | `/contracts/templates/mine` | **M** (ưu tiên thấp) |
### Người thuê (P2)
| # | tín hiệu · vì sao | nguồn | P |
|---|---|---|---|
| T1 | Ký cọc/HĐ/phụ lục trước `signBy` | NextAction `SIGN_*`, `SIGN_ANNEX` | C: P2 W4 |
| T2 | Khoản phải trả/quá hạn theo kỳ | `payment_requests`; `monthly_invoices.status` có thể trễ (`AiContractQueryService:374`) | C: P2 W3 |
| T3 | Phản hồi phụ lục, chấm dứt sớm, quyết toán | `AMENDMENT_RESPONSE`, `TERMINATION_RESPONSE`, `CLOSURE_RESPONSE` | C: P2 W4 |
| T4 | HĐ sắp hết (gia hạn mở 30 ngày) | `ContractLeaseCalc.renewalOpens` | C: P2 `LEASE_EXPIRING` |
| T5 | Khiếu nại của tôi cần trả lời | `dispute_cases` | **M** (như L8) |
| T6 | Gia hạn đã xin, đang chờ chủ nhà (`contract_renewals` REQUESTED, `respond_by`) | `ContractRenewal.java:18` | P: P2 §4 đọc `contract_renewals` nhưng không có widget "chờ chủ nhà" |
| T7 | Tiến độ hoàn cọc sau khi đồng ý (`REFUND_PENDING`, `refund_due_at`, `refund_amount`) | `contract_closures` | **M**: P2 chỉ có bước phản hồi |
| T8 | Yêu cầu ký SmartCA chờ xác nhận trên app | `signature_requests` | **M** |
### Admin (P4/P5/P6)
| # | tín hiệu · vì sao | nguồn | P |
|---|---|---|---|
| A1 | HĐ theo trạng thái / đang hiệu lực | `contracts.status`; hiện có `GET /admin/contracts?status=&size=1` | C: P4 C2, P5 #4 |
| A2 | HĐ ký đủ theo kỳ, phễu tạo→ký→thanh toán | `contracts.signed_at` | C: P5 KPI, #9(c)(d) |
| A3 | Khiếu nại mở/quá hạn | `dispute_cases` | C: P4 A3 (gộp OPEN+ESCALATED) |
| A3b | Hàng đợi thật của admin: ESCALATED chưa ai nhận (`assigned_to is null`), stage `WAITING_ADMIN`, theo `complaint_category`, số lần admin xem (tối đa 2) | `DisputeCase.java:38,44-48`, `ComplaintCaseService.java:41,211-216,233` | **M** |
| A4 | SLA khiếu nại: tuổi hồ sơ, thời gian chờ nhận, % tự giải quyết giữa hai bên | `opened_at`, `escalated_at`, `resolved_at`, bảng sự kiện khiếu nại | **M** |
| A5 | Proof theo trạng thái, chờ >15 phút, FAILED, số lần thử | `contract_document_proofs` (`status,attempts,error_code,next_attempt_at`) | C: P6 S/T |
| A6 | Tài liệu `integrity_status` MISMATCH/MISSING/UNAVAILABLE | `contract_documents.integrity_status` (ghi ở `ContractDocumentIntegrityService.java:161`) | P: P6 F chỉ kiểm từng tài liệu; không tổng hợp |
| A7 | SmartCA: tỉ lệ FAILED/REJECTED/EXPIRED theo ngày; kẹt `PROVIDER_SIGNED`/`EMBEDDING` (`processing_attempts`, `processing_started_at`); thời gian initiated→confirmed | `signature_requests` (`idx_sig_req_status`) | **M**: không trang nào nhắc SmartCA |
| A8 | HĐ kẹt chờ ký lâu (`tenant_signing_due_at` quá hạn) | `contracts` | **M** |
| A9 | Hóa đơn tháng toàn hệ thống: theo trạng thái, OVERDUE, DRAFT không tự phát hành, ROLLED_OVER, tổng phí chậm | `monthly_invoices` (`idx_monthly_invoice_due(status,due_at)`) | **M**: P4/P5 chỉ đọc `payment_requests` |
| A10 | Quyết toán quá hạn: `refund_due_at < now`, `REFUND_PENDING/BALANCE_PENDING`, `DISPUTED` | `contract_closures` | **M** |
| A11 | Chấm dứt sớm/đơn phương/đồng thuận: số, tỉ lệ bị từ chối, cưỡng chế | `contracts.termination_*_at` (`Contract.java:104-114`) | **M** |
| A12 | Phụ lục/gia hạn theo trạng thái, quá `respond_by` | `contract_amendments`, `contract_renewals` | **M** |
| A13 | Sinh tài liệu: FAILED/STALE/GENERATING lâu | `contract_documents.status` (`idx_doc_status`) | **M** |
| A14 | Mẫu HĐ: loại tin không còn mẫu SYSTEM đã xuất bản (chặn tạo HĐ), phiên bản DRAFT/lỗi placeholder | `ContractTemplateVersionRepository.countActiveSystemTemplates:41` | **M** |
| A15 | Cơ cấu HĐ cọc vs thuê | `contracts.agreement_kind`, `/admin/contracts?documentKind=` | P: P5 #4 không tách kind |
| A16 | Backlog outbox thông báo hợp đồng | `contract_notification_outbox` (`idx_contract_outbox_pending`) | C: P4 E |
### Vận hành - kỹ thuật
| # | tín hiệu · vì sao | nguồn | P |
|---|---|---|---|
| O1 | Worker blockchain: lag, retry, sự cố mở | `BlockchainProofScheduler.java:22` (10s), incident `ContractDocumentProofWorker.java:194` | P: P4 A4 + P6 có; thiếu lease/lỗi RPC |
| O2 | Heartbeat scheduler: SignaturePolling 5s, Embedding 5s, billing sync 60s (`MonthlyBillingService.java:1045`), onboarding tick 30s (`RentalOnboardingService.java:772`), nhắc ký 5 phút, `BranchDigestScheduler` 08:00 | chỉ có log | **M**: không có "lần chạy cuối" |
| O3 | HĐ lỗi trong sync billing: `catch` chỉ `log.error` (`MonthlyBillingService.java:1057`) | log | **M** |
| O4 | VNPT SmartCA ngừng: incident chỉ nhận BLOCKCHAIN/GATEWAY (`OperationalIncidentService.java:23`) | không có | **M** |
| O5 | Prometheus bật (`application.properties:277-284`) nhưng không có metric nghiệp vụ hợp đồng (chỉ `NearbyMetrics` ở listing) | actuator | **M** |
| O6 | 5xx/độ trễ nhóm `/contracts`, `/monthly-invoices`, `/signatures` | `http.server.requests` histogram (`application.properties:284`) | **M**: P4 E không dùng |

Tổng 44 tín hiệu: C = 13, P = 7, **M = 24**.

## 3. Thiếu sót
### 3.1 Chưa có dữ liệu/API (cần ghi thêm)
1. **Không có `GET /complaints/mine` và không có WorkType khiếu nại**: chủ nhà/người thuê không thấy hạn `respondBy`. Cần đọc `dispute_cases` theo `payer_id/payee_id`, `status!=RESOLVED` (hiện chỉ có `idx_dispute_queue(status,opened_at)`, chưa có chỉ mục theo người).
2. **Bảng `contracts` không có ngày kết thúc** (chỉ `effective_lease_months`, `agreed_early_end_exclusive`; ngày hết tính từ revision bằng `ContractLeaseCalc`): admin không đếm "HĐ sắp hết hạn" bằng SQL, chỉ nạp hàng loạt (cap 5000). Nên lưu cột `lease_end_exclusive` khi ký/phụ lục.
3. **Không có lịch sử chuyển trạng thái HĐ** (chỉ `created_at`, `landlord_confirmed_at`, `signed_at`, `tenant_signing_due_at`): không tính được "ở trạng thái X bao lâu".
4. **Dữ liệu nằm trong JSON TEXT, không tổng hợp được**: thông báo đơn phương `contracts.unilateral_termination_snapshot` (`Contract.java:117`), phương án quá hạn `monthly_invoices.overdue_actions_snapshot` (`MonthlyInvoice.java:101`), yêu cầu gia hạn hạn `extension_requests_snapshot` (:79). Muốn đếm cần cột/bảng riêng (loại, trạng thái, thời điểm).
5. **Không có heartbeat** cho 6 scheduler (O2), không đếm lỗi sync billing (O3); chưa có bảng `job_runs` hay Micrometer counter.
6. **SmartCA không có sự cố/chỉ số** (A7, O4): cần đếm theo `status`+`failure_code` mỗi giờ và thêm thành phần `SMARTCA` cho `OperationalIncident`.
7. **`integrity_status` chỉ mới khi có người mở/kiểm** (`ContractDocumentIntegrityService:161`): thống kê MISMATCH sẽ cũ; cần job kiểm định kỳ có giới hạn tốc độ.
8. **Index thiếu cho thống kê hệ thống**: `contracts(created_at)`, `contracts(status, signed_at)` (hiện chỉ `idx_contract_landlord_status`, `idx_contract_tenant_status`, `idx_contract_branch`), `signature_requests(status, initiated_at)`, `contract_closures(status, refund_due_at)` (chưa khai báo index), `contract_renewals(status, respond_by)`.
### 3.2 Endpoint không nên dùng cho dashboard / có vấn đề
| endpoint | vấn đề (bằng chứng) |
|---|---|
| GET `/monthly-invoices/contracts/{id}` | `findByIdForUpdate` + `syncContract` trong `@Transactional` (`MonthlyBillingService.java:430-438`): GET khóa ghi và có thể tạo hóa đơn; P2 W3 đã ghi nhận |
| GET `/contracts/{id}/lifecycle` | `syncLocked(c)` ngay trong GET (`ContractLifecycleService.java:77-78`) |
| GET `.../workflow/cancellation`, `.../early-exit`, `.../{kind}/documents` | `RentalWorkflowSupport.lock` = `PESSIMISTIC_WRITE` trên yêu cầu thuê + HĐ (`RentalWorkflowSupport.java:35-42`; `PreTenancyResolutionService.java:44`) |
| GET `/admin/complaints/{id}` | `lockSource` + `lockCase` (`DisputeCaseRepository.java:12`, `ComplaintCaseService.java:222`): admin mở hồ sơ khóa cả HĐ; list (:20) chỉ đọc |
| GET `/admin/complaints` | trả `reason`, `decision`, id hai bên (`ComplaintView`): văn bản tự do, có thể có PII; trang tổng quan chỉ nên dùng `totalElements` với `size=1` |
| GET `.../blockchain-proof` | GET nhưng `@Transactional` ghi, tự `enqueue` khi chưa có proof (`ContractDocumentProofQueryService.java:35-39,59-73`); P6 đã tránh bằng endpoint admin mới |
| POST `.../integrity-check`, GET/POST `.../content` | đọc S3 + gọi RPC blockchain + ghi `integrity_status`; chỉ thao tác thủ công, không polling |
| GET `.../signatures/certificates`, POST `.../{requestId}/refresh` | gọi VNPT trực tiếp theo yêu cầu người dùng; dashboard không được poll |
| POST `/contracts/{id}/pay-mock` | không `@PreAuthorize`, chỉ chặn bằng cờ (`ContractController.java:259,263`); phải tắt ở production |
| POST `.../termination/propose` | luôn lỗi `TERMINATION_NOT_ALLOWED` (`MonthlyInvoiceController.java:93-98`): endpoint chết, nên gỡ |
| GET `/contracts/ai/*` | dành cho `hs-ai-service` (`tools/contracts.py`); `summary/search` nạp tới 5000 hàng trong bộ nhớ mỗi lần; số liệu tái dùng được (L9) nhưng dashboard nên có truy vấn SQL riêng |
| POST `/admin/contract-templates/.../test-preview` | render nặng nhưng chỉ cần quyền lớp `CONTRACT_TEMPLATE_VIEW` (`:168` không có `@PreAuthorize` riêng) |
| Trùng lặp | `GET /contracts/{id}/documents` và bản admin; `content`/`test-preview` có ở cả user và admin; `/contracts/{id}/sign` (luồng cũ) song song `/signatures/initiate` (SmartCA) |
| `/internal/documents/{kind}/{id}` | an toàn (cần `X-Internal-Secret`, gateway `denyAll`); `recipientId` là tham số do dịch vụ tin cậy gửi; trả base64 đến 35MB, không đưa vào dashboard |
