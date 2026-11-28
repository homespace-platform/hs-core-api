# Inventory API: Thanh toán, hội viên, biên nhận, lưu trữ (payments-membership)

Phạm vi: `hs-core-api` (controller ở `hs-api-service/src/main/java/com/hs/api/controller` = **AC**; logic ở `hs-payment-service/src/main/java/com/hs/payment` = **PS**). Chỉ đọc code, ngày 28/11/2026.
Gateway (`hs-gateway-server`): `/api/v1/**` -> core (cắt tiền tố `/api/v1`), mặc định `authenticated()`; `/api/v1/internal/**` denyAll; `/u/**` đi thẳng, không cắt tiền tố, permitAll (`config/security/SecurityConfig.java:34,53,57-60`; route `HsGatewayServerApplication.java:208-226`). Core `SecurityConfig` là `permitAll` (`AC/config/SecurityConfig.java:26`) nên **mọi kiểm quyền sở hữu nằm ở tầng service**, không ở controller. Controller `/membership`, `/payment-receipts`, `/membership/receipts` có alias `/api/v1/...` ở cấp lớp.
Ký hiệu quyền: user = JWT hợp lệ + kiểm bên liên quan ở service (chưa đọc sâu từng service); `MP` = `hasAuthority('MEMBERSHIP_PURCHASE')`; `RCP` = `@RequiresCompleteProfile` (hồ sơ đủ).

## 1. Bảng toàn bộ endpoint (tổng: **55**)

### 1.1 Yêu cầu thanh toán thuê (`PaymentRequestController`, 7)
| METHOD | path | file:line | ai gọi | mục đích |
|---|---|---|---|---|
| GET | /api/v1/payment-requests/{id} | AC/payment/PaymentRequestController.java:36 | user (payer/payee) | Chi tiết 1 khoản (kèm snapshot ngân hàng, QR). Gọi `syncContract` (ghi) khi MONTHLY_RENT và gọi service 2 lần (:39-45) |
| GET | /api/v1/payment-requests/mine | :47 | user | Toàn bộ khoản của tôi, KHÔNG phân trang |
| GET | /api/v1/payment-requests/contracts/{contractId} | :55 | user (bên hợp đồng) | Khoản theo hợp đồng |
| POST | /api/v1/payment-requests/{id}/report-transfer | :62 | user (payer) | Người trả khai đã chuyển khoản (+chứng từ); cũng `syncContract` |
| POST | /api/v1/payment-requests/{id}/confirm-receipt | :76 | user (payee) | Bên nhận xác nhận đủ tiền -> CONFIRMED + chụp phiếu thu |
| POST | /api/v1/payment-requests/{id}/reject-receipt | :85 | user (payee) | Bên nhận từ chối (bắt buộc lý do) -> REJECTED |
| GET | /api/v1/payment-requests/{id}/events | :96 | user (bên liên quan) | Lịch sử sự kiện (`payment_events`) |

### 1.2 Chứng từ chuyển khoản / bàn giao upload điện thoại (6)
| METHOD | path | file:line | ai gọi | mục đích |
|---|---|---|---|---|
| POST | /api/v1/payment-requests/{paymentRequestId}/proof-upload-sessions | AC/payment/PaymentProofUploadSessionController.java:32 | user (payer) | Tạo phiên + link/QR để tải ảnh từ điện thoại |
| GET | /api/v1/payment-requests/{paymentRequestId}/proof-upload-sessions/{sessionId} | :60 | user (payer) | Trạng thái phiên (CREATED/UPLOADING/UPLOADED/CONSUMED/EXPIRED/CANCELLED) |
| GET | /u/payment-proof/{token} | AC/payment/MobilePaymentProofUploadController.java:56 | công khai, token 1 lần | Trang HTML tải chứng từ (server-side render) |
| POST | /u/payment-proof/{token} | :102 | công khai, token | Nhận file từ trang trên |
| GET | /u/payment-proof/session | AC/payment/PaymentProofHandoffController.java:31 | công khai, header `X-Proof-Upload-Token` | Trạng thái READY/UPLOADED (JSON, no-store) |
| POST | /u/payment-proof/session | :41 | công khai, header token | Upload multipart từ điện thoại |

### 1.3 Biên nhận thuê (`PaymentReceiptController`, 6)
| METHOD | path | file:line | ai gọi | mục đích |
|---|---|---|---|---|
| GET | /api/v1/payment-receipts?role=&contractId=&rentalRequestId=&page=&size<=100 | AC/payment/PaymentReceiptController.java:28 | user (chủ nhà hoặc người thuê của phiếu) | Danh sách phiếu thu phân trang |
| GET | /api/v1/payment-receipts/{id} | :38 | user | Một phiếu (snapshot, `pdfStatus`) |
| GET | /api/v1/payment-receipts/by-payment/{id} | :43 | user | Phiếu theo khoản thanh toán |
| POST | /api/v1/payment-receipts/{id}/retry | :50 | user | Xếp lại tạo PDF (khi FAILED) |
| GET | /api/v1/payment-receipts/{id}/content | :56 | user | PDF bọc JSON base64 (tránh download manager) |
| GET | /api/v1/payment-receipts/{id}/pdf?download= | :63 | user | PDF nhị phân |

### 1.4 Biên nhận hội viên (`MembershipReceiptController`, 5; chỉ người mua)
| METHOD | path | file:line | ai gọi | mục đích |
|---|---|---|---|---|
| GET | /api/v1/membership/receipts?page=&size<=100 | AC/payment/MembershipReceiptController.java:29 | user (owner) | Danh sách (gồm PURCHASE và OVERPAYMENT) |
| GET | /api/v1/membership/receipts/{id} | :36 | user (owner) | Một phiếu |
| GET | /api/v1/membership/receipts/by-order/{orderId} | :41 | user (owner) | Phiếu theo đơn |
| POST | /api/v1/membership/receipts/{id}/retry | :48 | user (owner) | Tạo lại PDF |
| GET | /api/v1/membership/receipts/{id}/content | :54 | user (owner) | PDF base64 trong JSON |

### 1.5 Gói hội viên, checkout, SePay, ETH (`MembershipController`, 13)
| METHOD | path | file:line | ai gọi | mục đích |
|---|---|---|---|---|
| GET | /api/v1/membership/addons | AC/payment/MembershipController.java:43 | user | Giá/hạn/tối đa lượt mua thêm |
| POST | /api/v1/membership/addons/checkout | :50 | user + MP + RCP | Tạo đơn mua thêm lượt (SEPAY/ETH) |
| GET | /api/v1/membership/plans | :56 | user | Danh sách gói (tier, lượt, ngày, giá) |
| GET | /api/v1/membership/me | :61 | user | Hạn mức hiện tại (`remainingUses`, `reservedUses`, `expiresAt`, add-on...) |
| GET | /api/v1/membership/quote?tier= | :66 | user | Báo giá nâng cấp/gia hạn |
| POST | /api/v1/membership/checkout | :73 | user + MP + RCP | Tạo đơn gói |
| GET | /api/v1/membership/orders/{id} | :79 | user (owner) | Trạng thái đơn |
| POST | /api/v1/membership/orders/{id}/cancel | :85 | user (owner) | Huỷ đơn PENDING |
| GET | /api/v1/membership/orders/{id}/eth/challenge?wallet= | :91 | user (owner) | Thử thách ký ví ETH |
| POST | /api/v1/membership/orders/{id}/eth/quote | :99 | user + MP + RCP | Báo giá ETH (ethVnd, amountWei, hết hạn, chữ ký) |
| POST | /api/v1/membership/orders/{id}/eth/settle | :106 | user (owner) | Gửi tx hash để chốt đơn ETH |
| GET | /api/v1/membership/orders/{id}/eth/status | :113 | user (owner) | Trạng thái giao dịch ETH |
| POST | /api/v1/membership/sepay/ipn | :119 | công khai ở gateway; app kiểm header `X-Secret-Key` (`sepayAuthenticated`) | Webhook SePay xác nhận đã thanh toán |

### 1.6 Tài khoản ngân hàng, danh mục ngân hàng, VietQR (8)
| METHOD | path | file:line | ai gọi | mục đích |
|---|---|---|---|---|
| GET | /api/v1/bank-accounts | AC/payment/BankAccountController.java:35 | user (chủ tài khoản) | Danh sách của tôi (số bị che) |
| GET | /api/v1/bank-accounts/{id} | :43 | user (owner) | Một tài khoản (che) |
| GET | /api/v1/bank-accounts/{id}/reveal | :51 | user (owner) | Số tài khoản đầy đủ (PII) |
| POST | /api/v1/bank-accounts | :59 | user | Thêm; kích hoạt `completeOnboardingIfEligible` |
| PUT | /api/v1/bank-accounts/{id} | :70 | user (owner) | Sửa |
| PUT | /api/v1/bank-accounts/{id}/defaults | :82 | user (owner) | Đặt mặc định nhận/trả |
| DELETE | /api/v1/bank-accounts/{id} | :94 | user (owner) | Xoá |
| GET | /api/v1/public/banks | AC/publish/PublicBankController.java:23 | công khai | Danh mục ngân hàng VietQR (`VietQrBankService`) |

Ghi chú: VietQR không có endpoint riêng; `qrProvider/qrImageUrl` nằm trong `PaymentRequestResponse` (`PS/service/qr/*`, `PaymentRequest.java:102-105`).

### 1.7 Lưu trữ dùng cho chứng từ/biên nhận (9)
| METHOD | path | file:line | ai gọi | mục đích |
|---|---|---|---|---|
| POST | /api/v1/storage/uploads | AC/storage/StorageController.java:30 | user | Xin URL upload (purpose PAYMENT_PROOF...; CONTRACT_TEMPLATE chỉ admin) |
| POST | /api/v1/storage/{storageId}/complete | :45 | user (chủ đối tượng) | Xác nhận upload xong |
| GET | /api/v1/storage/{storageId} | :56 | user (có quyền, `PaymentStorageAccessChecker`) | Metadata |
| GET | /api/v1/storage/{storageId}/view-url | :63 | **công khai ở gateway** (`SecurityConfig.java:57`) | URL xem ảnh (kiểm quyền ở service) |
| GET | /api/v1/storage/{storageId}/download-url | :70 | user | URL tải |
| GET | /api/v1/storage?referenceType=&referenceId=&purpose=&status=READY | :77 | user | Đối tượng của tôi, phân trang <=100 |
| DELETE | /api/v1/storage/{storageId} | :88 | user (owner) | Xoá |
| GET | /api/v1/admin/storage/{id} | AC/admin/StorageAdminController.java:17 | ADMIN + `STORAGE_VIEW` | Metadata đối tượng bất kỳ |
| GET | /api/v1/admin/storage/{id}/download-url | :22 | ADMIN + `STORAGE_VIEW` | URL tải đối tượng bất kỳ |

### 1.8 API nội bộ tài liệu (1)
| METHOD | path | file:line | ai gọi | mục đích |
|---|---|---|---|---|
| GET | /internal/documents/{kind}/{id}?recipientId= (gateway chặn) | AC/internal/DocumentInternalController.java:45 | secret gateway/service-to-service (`UserContextFilter.java:56-60`) | PDF đính kèm email cho notification-service; kind = rental-receipts, membership-receipts, overpayment-receipts, lease-bundles; tối đa 35 MB tổng (:34) |

### 1.9 Liên quan nhưng thuộc khu vực khác (KHÔNG tính vào 55; agent hợp đồng/khiếu nại kiểm)
`PUT /rental-requests/{id}/onboarding/payments/{paymentId}/review` (AC/listing/RentalOnboardingController.java:27, chủ nhà duyệt khoản onboarding); `POST /monthly-invoices/{id}/extend-payment|extension-requests...` (AC/contract/MonthlyInvoiceController.java:30-54); `/rental-requests/{id}/complaints`, `/complaints/{id}`, `/admin/complaints` (`ComplaintController.java:18-22`, `ComplaintAdminController.java:17-24`).
**Không tồn tại endpoint cho**: hoàn tiền (refund chỉ là `payment_requests` loại `DEPOSIT_REFUND`/`INITIAL_PAYMENT_REFUND` đi qua 1.1); quá thanh toán hội viên (`membership_overpayments` không có controller: `MembershipOverpaymentRepository` chỉ được dùng ở `MembershipPaymentApplier` và `MembershipReceiptService`); tranh chấp thanh toán (`DISPUTED`, `DISPUTE_ROUTED` là legacy: `PaymentStatus.java`, `PaymentEventType.java`; `dispute_cases` nay chủ yếu cho khiếu nại thuê); admin xem/duyệt thanh toán (không có controller admin payment).

## 2. Tín hiệu giám sát

Nguồn: `payment_requests` (idx `payer_id,status`, `payee_id,status`; `PaymentRequest.java:19-24`), `payment_events` (idx `payment_request_id,created_at`), `payment_receipts` (idx duy nhất kiểu hàng đợi `pdf_status,next_attempt_at`), `membership_orders`, `membership_receipts` (idx `owner_id,issued_at`), `membership_overpayments` (idx `status,received_at`), `membership_eth_quotes`, `payment_proof_upload_sessions`, `bank_accounts`, `deposit_records`, `payment_notification_outbox`, `membership_notification_outbox`.
Trạng thái: `PaymentStatus` AWAITING_TRANSFER, TRANSFER_REPORTED, CONFIRMED, REJECTED, OVERDUE, EXPIRED, CANCELLED (+DISPUTED legacy); `MembershipOrder.Status` PENDING, PAID, FAILED, EXPIRED, CANCELLED, REVIEW; PDF: PENDING, READY, FAILED.

### 2.1 Chủ nhà
| # | Tín hiệu | Vì sao quan trọng | Nguồn | P1..P6 |
|---|---|---|---|---|
| L1 | Khoản người thuê đã khai chuyển, chờ tôi xác nhận (số, tiền, `confirmation_due_at` đếm ngược) | Tiền + hạn: quá hạn thì hệ thống tự xác nhận thay (`autoConfirmOverdueReceipt`, `PaymentRequestService.java:654`) | `payment_requests` payee=owner, TRANSFER_REPORTED (đã có qua attention PAYMENT_CONFIRM) | CÓ: P1 §3.5 chip "Xác nhận tiền" |
| L2 | Khoản quá hạn (số + tổng tiền) | Rủi ro thu tiền | `payment_requests` OVERDUE (trạng thái lưu có thể cũ so với `due_at`) | CÓ: P1 §3.4 |
| L3 | Đã nhận vs dự kiến tháng này | Dòng tiền | `payment_receipts` JOIN `payment_requests` | CÓ: P1 §3.3 |
| L4 | Hoàn tiền tôi còn phải chuyển (cọc, tháng đầu) và hạn | Nghĩa vụ tiền; chủ nhà là bên trả | `payment_requests` LANDLORD_TO_TENANT AWAITING_TRANSFER/REJECTED | MỘT PHẦN: chỉ chip OWNER_REFUND (P1 §3.5), chưa có tổng tiền |
| L5 | Số khoản của tôi bị hệ thống tự xác nhận (30 ngày) | Báo hiệu chủ nhà bỏ sót thông báo | `payment_events` RECEIPT_CONFIRMED, `actor_id`='SYSTEM', lý do `RECEIPT_AUTO_CONFIRM_REASON` (:637) | THIẾU (có dữ liệu, chưa có API) |
| L6 | Khoản tôi đã từ chối (REJECTED) chờ người thuê khai lại | Tiền treo | `payment_requests` payee=owner, REJECTED, `rejected_reason` | THIẾU |
| L7 | Phiếu thu PDF chưa sẵn sàng/FAILED của tôi | Chứng từ pháp lý, người thuê không tải được | `payment_receipts.pdf_status`, `attempts` | THIẾU |
| L8 | Hạn mức đăng tin: `remainingUses`, `reservedUses`, `expiresAt`, add-on | Không đăng được tin mới | `GET /membership/me` | CÓ: P1 §3.9 |
| L9 | Đơn gói/add-on PENDING sắp hết hạn, đơn lỗi | Mất quyền đăng nếu không thanh toán | `membership_orders` owner, PENDING, `expires_at` | THIẾU (P1 §3.9 chỉ có số lượt) |
| L10 | Tài khoản ngân hàng nhận tiền: có mặc định chưa, trạng thái xác minh | Không có thì không phát hành được yêu cầu thanh toán/QR | `bank_accounts` (`BankAccountStatus`, `BankAccountVerificationMethod`) | THIẾU |
| L11 | Chi phí hội viên đã trả (tháng/năm) | Chi phí vận hành | `membership_orders` PAID, `paid_at` | THIẾU |

### 2.2 Người thuê
| # | Tín hiệu | Vì sao | Nguồn | P1..P6 |
|---|---|---|---|---|
| T1 | Khoản phải trả (số, tiền, hạn, quá hạn) | Nguy cơ mất hợp đồng, phí trễ | `payment_requests` payer=user, AWAITING_TRANSFER/REJECTED/OVERDUE | CÓ: P2 W1/W3 (endpoint mới) |
| T2 | Khoản bị chủ nhà từ chối, cần khai lại (kèm lý do) | Phải hành động trước hạn | `payment_requests` REJECTED, `rejected_reason`, `due_at` | MỘT PHẦN: REJECTED nằm trong W3, chưa nêu lý do |
| T3 | Khoản đã khai chuyển, chờ chủ nhà xác nhận + thời điểm tự xác nhận | Biết tiền đã được ghi nhận chưa | `payment_requests` TRANSFER_REPORTED, `payer_reported_at`, `confirmation_due_at` | THIẾU (W3 loại TRANSFER_REPORTED khỏi "phải trả") |
| T4 | Hoàn tiền tôi sắp nhận/cần xác nhận | Tự xác nhận sau 72h (`rental.refund-auto-confirm-hours`, cảnh báo 24h) | `payment_requests` LANDLORD_TO_TENANT TRANSFER_REPORTED | CÓ: P2 W4 CONFIRM_REFUND |
| T5 | Chi tiêu theo tháng, tách cọc | Quản lý chi phí | `payment_receipts` JOIN `payment_requests` | CÓ: P2 W7 |
| T6 | Tiền cọc đang giữ (HELD/REFUND_DUE/REFUNDED) | Tài sản người thuê | `deposit_records` (`DepositStatus`) | THIẾU (không có khối cọc riêng) |
| T7 | Phiên upload chứng từ điện thoại đang mở/hết hạn | Mất ảnh chứng từ | `payment_proof_upload_sessions` | THIẾU |
| T8 | Phiếu thu của tôi chưa có PDF | Không tải được | `payment_receipts.pdf_status` | THIẾU |

### 2.3 Admin
| # | Tín hiệu | Vì sao | Nguồn | P1..P6 |
|---|---|---|---|---|
| A1 | Giao dịch thuê CONFIRMED (hôm nay/kỳ), GMV | Quy mô nền tảng | `payment_requests` CONFIRMED, `confirmed_at` (idx `status,confirmed_at` chưa có) | CÓ: P4 C1, P5 #5 |
| A2 | Doanh thu hội viên theo ngày/gói/cổng | Doanh thu nền tảng | `membership_orders` PAID (`provider`, `tier`, `kind`) | CÓ: P4 D, P5 #6 #7 |
| A3 | **Quá thanh toán hội viên chờ hoàn (REFUND_PENDING): số, tổng VND, tuổi** | Tiền khách đang giữ; không endpoint nào đánh dấu REFUNDED | `membership_overpayments` status, `received_at`, `amount_vnd` | **THIẾU** |
| A4 | Đơn hội viên kẹt: PENDING quá `expires_at` (không có job hết hạn; chỉ đổi khi chính chủ checkout lại, `MembershipCheckoutService.java:101-107`), REVIEW legacy, FAILED | Tiền đã trừ nhưng quyền chưa cấp; lệch số A2 | `membership_orders` | **THIẾU** |
| A5 | Tỉ lệ checkout -> PAID theo cổng (SEPAY/ETH) | Chất lượng cổng thanh toán | `membership_orders` (`created_at`, `paid_at`, `provider`) | THIẾU (P5 #7 chỉ đơn PAID) |
| A6 | IPN SePay bị từ chối (401/400) hoặc trùng | Lỗi cấu hình secret, giả mạo | Không ghi bảng nào (`MembershipController.java:119-134`) | **THIẾU DỮ LIỆU** |
| A7 | ETH: quote hết hạn không settle, `transaction_hash` có nhưng đơn chưa PAID | Tiền on-chain đã gửi mà chưa cấp quyền | `membership_eth_quotes` (`expires_at`, `transaction_hash`, `confirmed_at`) JOIN `membership_orders` | MỘT PHẦN: P6 khối E (hàng có tx hash); thiếu đối soát với đơn chưa PAID |
| A8 | Khoản TRANSFER_REPORTED quá `confirmation_due_at` mà chưa auto-confirm | Scheduler 5 phút (`ReceiptAutoConfirmScheduler.java:30`) hỏng thì tiền tồn | `payment_requests` + `payment_events` | THIẾU |
| A9 | Số khoản bị hệ thống tự xác nhận (thuê + hoàn) theo ngày | Mức không phản hồi của các bên | `payment_events` actor SYSTEM | THIẾU |
| A10 | Phiếu thu thuê/hội viên PDF FAILED hoặc PENDING quá lâu, `attempts` | Chứng từ pháp lý, email thiếu đính kèm | `pdf_status`, `attempts`, `next_attempt_at` (worker 15s) | THIẾU |
| A11 | Tỉ lệ khoản bị từ chối theo chủ nhà | Dấu hiệu gian lận/tranh chấp | `payment_events` RECEIPT_REJECTED | THIẾU |
| A12 | Khiếu nại thuê mở | SLA giải quyết | `dispute_cases` subject RENTAL, OPEN/ESCALATED | CÓ: P4 A3 (tranh chấp thanh toán riêng không còn, legacy) |
| A13 | Đối tượng lưu trữ PAYMENT_PROOF/PAYMENT_RECEIPT kẹt PENDING, REJECTED | Upload dở, mồ côi (`RECEIPT_ORPHAN_CLEANUP_FAILED` chỉ ở log) | `storage_objects` (`StorageStatus`, `StoragePurpose`) | THIẾU |
| A14 | Số chủ nhà đã có/chưa có tài khoản nhận tiền mặc định | Chặn phát hành thanh toán | `bank_accounts` | THIẾU |

### 2.4 Vận hành / kỹ thuật
| # | Tín hiệu | Vì sao | Nguồn | P1..P6 |
|---|---|---|---|---|
| O1 | Backlog outbox `payment_notification_outbox`, `membership_notification_outbox` (chưa gửi, tuổi lớn nhất) | Thông báo tiền không tới người dùng | `sent_at IS NULL`; poll 1s (`PaymentNotificationPublisher.java:39`) | CÓ: P4 E (8 bảng outbox) |
| O2 | Outbox: số lần thử/lỗi gần nhất | Phân biệt "chậm" với "hỏng" | `PaymentNotificationOutbox` chỉ có `created_at`, `sent_at` (`:27-38`) | **THIẾU DỮ LIỆU** |
| O3 | Sức khoẻ 5 job: auto-confirm thuê (5p), auto-confirm hoàn (5p), nhắc đến hạn (60s), PDF worker (15s), nhắc hết hạn hội viên (1h) | Mất job thì tiền/biên nhận tồn | Chỉ log, không lưu lần chạy gần nhất | **THIẾU DỮ LIỆU** |
| O4 | Cổng ngoài: SePay IPN lần cuối, RPC Ethereum, giá ETH/VND (`EthVndPriceService`), VietQR | Cổng thanh toán chết | Chưa ghi nhận; `OperationalHealthMonitor` chỉ kiểm gateway (`:21-36`) | **THIẾU DỮ LIỆU** |
| O5 | Tỉ lệ/độ trễ 4xx/5xx endpoint thanh toán | SLA | Prometheus qua gateway (`/actuator/prometheus`) | Không có trong P1..P6 |
| O6 | Phiên upload chứng từ: tỉ lệ CONSUMED/EXPIRED, token sai | Ma sát UX / dò token | `payment_proof_upload_sessions`; token sai chỉ ném `PROOF_SESSION_NOT_FOUND` (`PaymentProofHandoffController.java:53`) | THIẾU |

## 3. Thiếu sót

### 3.1 Tín hiệu chưa có API/dữ liệu (cần ghi thêm)
- **Không có endpoint admin nào cho thanh toán**: không có controller admin cho `payment_requests`, biên nhận, quá thanh toán, bank account. A3, A4, A8-A11, A13, A14 đều cần endpoint đọc mới có quyền riêng (đề xuất `PAYMENT_VIEW` hoặc dùng `STATISTICS_VIEW`).
- **Quá thanh toán hội viên (A3)**: có bảng, biên nhận loại OVERPAYMENT, thông báo, nhưng không code nào ghi `REFUNDED`/`refunded_by` (grep) -> không biết đã hoàn chưa. Cần endpoint admin đánh dấu hoàn + danh sách + cảnh báo tuổi.
- **IPN SePay (A6)**: không lưu yêu cầu bị từ chối/trùng; cần log tối thiểu (thời điểm, kết quả, mã lỗi, không lưu body).
- **Lịch sử chạy job (O3)** và **sức khoẻ cổng ngoài (O4)**: chưa lưu; cần `last_run_at`, `last_success_at`, `processed`, `failed` hoặc metric Micrometer.
- **Outbox (O2)**: thiếu `attempts`, `last_error`, `next_attempt_at`.
- **Auto-confirm (L5, A9)**: chỉ phân biệt được qua `payment_events.actor_id='SYSTEM'` và so chuỗi lý do (`PaymentRequestService.java:588,637`), dễ vỡ khi đổi câu chữ. Nên có cờ `auto_confirmed` hoặc `PaymentEventType` riêng.
- **Hết hạn đơn hội viên**: không có job; mọi số "đơn PENDING" phải tự lọc `expires_at > now`.
- **Chỉ mục**: `payment_receipts` không có idx theo `landlord_id`/`tenant_id, issued_at` (P1 đã nêu); thiếu `payment_requests(status, confirmed_at)`, `membership_orders(status, paid_at)` (P4/P5 đã đề xuất) và `membership_orders(status, expires_at)` cho A4.

### 3.2 Endpoint không phù hợp để dùng trong dashboard
| Endpoint | Vấn đề |
|---|---|
| GET /payment-requests/mine (:47) | Không phân trang; mỗi dòng kèm snapshot tài khoản ngân hàng (PII) + QR (P2 đã kết luận) |
| GET /payment-requests/{id} (:36-45), POST report-transfer (:62-69) | GET có tác dụng ghi (`syncContract` cho MONTHLY_RENT, khoá bản ghi) và gọi `getPaymentRequest` 2 lần; không poll/gọi hàng loạt từ dashboard |
| GET /payment-requests/contracts/{id} (:55) | Không phân trang, theo từng hợp đồng -> N+1 nếu lặp |
| GET /bank-accounts/{id}/reveal (:51) | Trả số đầy đủ; không dùng cho dashboard |
| POST .../retry (payment-receipts, membership/receipts) | Ghi trạng thái; chỉ cho nút thao tác, không polling |
| GET /storage/{id}/view-url (gateway permitAll) | Công khai ở gateway, an toàn phụ thuộc kiểm quyền ở service; không dùng làm tín hiệu |
| GET /membership/quote, /orders/{id}/eth/challenge | Mỗi lần gọi tính toán/tạo challenge ví; không dùng cho KPI |
| GET /internal/documents/{kind}/{id} | Base64 tới 35 MB trong một response; chỉ service-to-service |
| /u/payment-proof/** (6 endpoint) | Công khai, xác thực bằng token 1 lần; không rate limit riêng ngoài limiter gateway (chưa kiểm cấu hình); giữ ngoài mọi thống kê có PII |
| Trùng lặp | `/payment-receipts/{id}/content` và `/pdf` cùng trả PDF; `GET /u/payment-proof/{token}` (HTML) và `/u/payment-proof/session` (JSON) cùng một phiên. Dashboard không cần cái nào |
| Có vẻ ít dùng | `GET /bank-accounts/{id}` khi đã có danh sách (chưa kiểm phía web/mobile, ngoài phạm vi) |

### 3.3 Lưu ý cho thiết kế P1..P6
- Tiền thuê trong dashboard đọc từ `payment_requests` (nguồn sự thật), không đọc `payment_receipts.snapshot_json` (TEXT, không SUM được).
- Đồng hồ: khoản onboarding/hợp đồng dùng `BillingTime` qua `PaymentTimePolicy.usesBillingTime`; scheduler auto-confirm so với `max(businessNow, now)` (`ReceiptAutoConfirmScheduler.java:33-34`). Đếm ngược "còn bao lâu tự xác nhận" phải dùng cùng đồng hồ.
- Cửa sổ tự xác nhận lấy từ cấu hình (`rental.receipt-auto-confirm-warning-hours:12`, `rental.refund-auto-confirm-hours:72`, `rental.refund-auto-confirm-warning-hours:24`, chu kỳ 5 phút); không hardcode ở web.
- Hội viên KHÔNG nằm trong `payment_receipts` (bảng riêng `membership_receipts`, chứa cả PURCHASE và OVERPAYMENT, `MembershipReceiptView.java:23`); tổng biên nhận mua phải lọc loại PURCHASE.
