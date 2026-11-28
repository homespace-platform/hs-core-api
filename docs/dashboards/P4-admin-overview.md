# P4 · Tổng quan (admin) — `hs-admin-portal` `/dashboard`

File frontend khi kiểm kê: `src/pages/dashboard/DashboardPage.tsx` (7 dòng, trang trắng). Route đã có ở `src/router/index.tsx` (`<Route path="/dashboard" element={<DashboardPage />} />`), không gắn `RequirePermission`; sidebar mục "Tổng quan" (`layouts/Sidebar.tsx:44`). Trạng thái: **THIẾT KẾ ĐÃ DUYỆT; API phase 2 tại §13; frontend phase 3**. Năm sửa operations ở §5 vẫn chưa được duyệt, không thuộc implementation này.

## 0. Thay đổi sau kiểm kê (so với bản đầu)
- Khối E cũ ("8 bảng outbox") thay bằng **§4 Sức khoẻ hệ thống** (10 nhóm, endpoint riêng `GET /api/v1/admin/system-health`); sửa tên bảng sai: bảng broadcast là `notification_broadcast_outbox` (`BroadcastNotificationOutbox.java:13`), không phải `notification_broadcast_` + `_notification_outbox`.
- Thêm **§5 Lỗi phải sửa trước** (5 rủi ro ở `AUDIT_LOG.md` mục cuối, mỗi mục một đề xuất sửa) — làm TRƯỚC khi code §4 vì §4 đo bằng cột/bảng do các sửa này tạo ra.
- Thêm thẻ A5 "Tiền kẹt"; A1 thêm tuổi tin cũ nhất; A2 thêm KYC quá hạn và **cần trang danh sách KYC** (§7.3); A3 tách "ESCALATED chưa ai nhận"; thêm B3-B5 (khoá, admin, kẹt onboarding).
- **Một danh sách quyền duy nhất** ở §6 (5 quyền mới); P5, P6 chỉ tham chiếu.
- Overview chỉ giữ tóm tắt `system.level`; chi tiết ở endpoint sức khoẻ riêng (nặng hơn, gọi Eureka, cache khác).
- Mục "Không đưa vào (lý do)" ở §11 cho mọi tín hiệu THIẾU còn lại; mục 12 cập nhật câu hỏi mở.

## 1. Mục đích & người xem
- Người xem: quản trị viên (role ADMIN), mỗi người thấy các khối khớp quyền mình có.
- 10 giây phải trả lời: "Hôm nay có việc gì đang chờ tôi xử lý và hệ thống có đang lỗi không?" → hàng đợi cần xử lý (A) đứng trên cùng; tăng trưởng và tiền (B-D) ở giữa; **sức khoẻ hệ thống (E)** bên dưới; lối tắt (F) ở cuối.
- Trang này là "nhìn nhanh, hôm nay". Xu hướng theo thời gian nằm ở P5, chi tiết blockchain ở P6.

## 2. Bố cục (desktop, lưới 12 cột)
```
+--------------------------------------------------------------------------+
| Tổng quan                                   Cập nhật 14:05 [Tải lại]     |
+--------------------------------------------------------------------------+
| [A] CẦN XỬ LÝ (5 thẻ, màu theo mức khẩn)                                  |
| [Tin chờ 12 · cũ nhất 3 ngày] [KYC chờ 5] [Khiếu nại 3 (1 chưa nhận)]    |
| [Sự cố mở 0] [Tiền kẹt 3]                                                 |
+-----------------------------------------+--------------------------------+
| [B] NGƯỜI DÙNG                          | [C] HỢP ĐỒNG & THUÊ            |
| Mới hôm nay 8 | 7 ngày 41 (sparkline)   | GD thuê đã xác nhận hôm nay 6  |
| KYC đã xác minh 1.204 / 3.310           | HĐ đang hiệu lực 212           |
| Khoá 12 · Admin 3 · Kẹt onboarding 9    |                                |
+-----------------------------------------+--------------------------------+
| [D] DOANH THU GÓI HỘI VIÊN: Hôm nay 490.000đ | Tháng này 12,3tr (7 ngày)   |
+--------------------------------------------------------------------------+
| [E] SỨC KHOẺ HỆ THỐNG   Tổng: CHÚ Ý            Cập nhật 14:05 [Tải lại]  |
| [Dịch vụ OK][Outbox CHÚ Ý][Việc nền OK][Ký số OK][Blockchain OK][Tiền OK]|
| [Thông báo OK][KYC OK][Khiếu nại OK][AI OK]  (bấm chip = mở nhóm)        |
|  ▸ Outbox: contract_notification_outbox  chưa gửi 4 · cũ nhất 12s · OK    |
+--------------------------------------------------------------------------+
| [F] LỐI TẮT: Tin chờ duyệt | Khiếu nại | Cảnh báo vận hành | Người dùng  |
|              Hợp đồng | Blockchain | Thống kê | Thông báo hệ thống        |
+--------------------------------------------------------------------------+
```
Màn nhỏ (<768px): một cột theo thứ tự A → E → B → C → D → F; A thành lưới 2 cột; chip E cuộn ngang; nhóm E mở dạng accordion; sparkline ẩn nếu hẹp hơn 120px. Chỉ cuộn dọc.

## 3. Khối A-D (số liệu nghiệp vụ, từ `GET /api/v1/admin/dashboard/overview`)
Quy ước: "hôm nay" = `[00:00, 24:00)` ngày `BillingTime.today()` múi `Asia/Ho_Chi_Minh` (`hs-common-service/.../time/BillingTime.java:20,54`); "7 ngày" = 7 ngày lịch kết thúc hôm nay; "tháng này" = từ mùng 1 đến hết hôm nay. Dùng `BillingTime.now()` cho mọi mốc hạn nghiệp vụ; **khối E dùng giờ thật** (`Instant.now()`) vì job chạy giờ thật. Chỉ loại hàng `active=true` khi bảng có `BaseEntity.active`.

| # | Khối | Định nghĩa chính xác | Nguồn | Quyền | Bấm vào |
|---|---|---|---|---|---|
| A1 | Tin chờ duyệt | `listings.status='PENDING_REVIEW'` và `active=true` (cùng số `ListingRepository.countAllGroupedByStatus`, `ListingAdminController.java:44`); phụ "+N yêu cầu sửa" (`countByActiveTrueAndPendingEditIdIsNotNull`, `ListingRepository.java:38`); phụ "cũ nhất": `now() - MIN(submitted_at)` của các tin chờ. | `listings` | `LISTING_VIEW` | `/properties/pending` |
| A2 | KYC chờ | Người dùng có trạng thái KYC hiện tại `PENDING`/`REVIEW_REQUIRED`, theo đúng `KycServiceImpl.getCurrentStatus` (`:55-69`): `DISTINCT ON (user_id) ... ORDER BY user_id, (status='VERIFIED') DESC, created_at DESC`. Phụ "quá hạn" = trong số đó `expires_at <= now()` (phiên bỏ dở). | `kyc_verifications` | `KYC_VIEW` | `/users/kyc` (trang mới, §7.3) |
| A3 | Khiếu nại mở | `dispute_cases` `subject_type='RENTAL'`, `status IN ('OPEN','ESCALATED')` (khớp `ComplaintCaseService.adminList`, `:211-216`); phụ "quá hạn" = `respond_by <= BillingTime.now()`; phụ "chưa ai nhận" = `status='ESCALATED' AND assigned_to IS NULL` (`DisputeCase.java:38,44-48`). | `dispute_cases` (`idx_dispute_queue`) | `COMPLAINT_VIEW` | `/operations/complaints` |
| A4 | Sự cố vận hành mở | `operational_incidents.open=true`; thành phần mở rộng ở §5.5. Đỏ nếu > 0. | `operational_incidents` | `INCIDENT_VIEW` | `/operations/system-alerts` |
| A5 | Tiền kẹt | Tổng 4 số của nhóm "Tiền" (§4.1 mục 6): đơn hội viên PENDING quá hạn + ETH chưa settle + quá thanh toán chờ hoàn + khoản TRANSFER_REPORTED quá hạn tự xác nhận. **Chỉ là số đếm**, không có trang hoàn tiền (chủ dự án đã bỏ). | các bảng §4.1.6 | `PAYMENT_VIEW` | cuộn tới nhóm "Tiền" ở E |
| B1 | Người dùng mới | `users.created_at` trong hôm nay/7 ngày, loại ADMIN; sparkline 7 cột. | `users` | `USER_VIEW` | `/users` |
| B2 | KYC đã xác minh | Tử số: trạng thái hiện tại `VERIFIED` chưa hết hạn (dùng đúng `getCurrentStatus`, không viết lại điều kiện); mẫu số: tổng USER. | `kyc_verifications`, `users` | `KYC_VIEW` | `/users` |
| B3 | Tài khoản bị khoá | `COUNT(*) FILTER (WHERE active=false)` / tổng USER (`BaseEntity.active`). | `users` | `USER_VIEW` | `/users` |
| B4 | Admin đang hoạt động | Số `users` có role ADMIN và `active=true` (chốt chặn "admin cuối cùng", `assertAnotherActiveAdminRemains`). Chỉ số đếm, không liệt kê quyền. | `users`⋈`roles` | `USER_VIEW` | `/users` |
| B5 | Kẹt onboarding | USER `on_boarded=false` và `created_at < now() - 3 ngày` (ngưỡng giả định, §12.6). | `users` | `USER_VIEW` | `/users` |
| C1 | Giao dịch thuê đã xác nhận (hôm nay) | `payment_requests` `status='CONFIRMED'`, `direction='TENANT_TO_LANDLORD'`, `type IN (INITIAL, RESERVATION_DEPOSIT, MONTHLY_RENT, UTILITY, OTHER_CHARGE)`, `confirmed_at` hôm nay (`PaymentRequest.java:63-71,126-127`). Loại các loại hoàn. Chỉ số lượng. | `payment_requests` | `STATISTICS_VIEW` | `/analytics/statistics` |
| C2 | HĐ đang hiệu lực | `contracts.status='ACTIVE'` (`Contract.java:63`). | `contracts` | `CONTRACT_VIEW` | `/contracts/all` |
| D | Doanh thu gói hội viên | `SUM(amount_vnd)` của `membership_orders` `status='PAID'`, `paid_at` hôm nay/tháng này; thanh 7 ngày. Gồm `PLAN` + `ADDON`, mọi cổng. Nguồn là `membership_orders` vì `membership_receipts` không có cột tiền (`MembershipReceipt.java` ~28-45; mỗi đơn PAID có đúng một biên lai `uq_membership_receipt_order`). Đơn ETH ghi VND quy đổi lúc báo giá. Chưa có luồng hoàn tiền (giả định). | `membership_orders` | `STATISTICS_VIEW` | `/analytics/statistics` |
| F | Lối tắt | Liên kết tới trang đã có, ẩn theo `ROUTE_PERMISSIONS` (`config/permissions.ts`, `useCanAccessPath`) hoặc `<Can>`. | — | theo trang | route có sẵn |

Trạng thái chung mỗi khối: đang tải = `Skeleton` cùng kích thước; trống = hiển thị `0`; lỗi cả endpoint = banner "Không tải được tổng quan [Thử lại]" giữ lối tắt; khối thiếu quyền = server trả `null`, UI ẩn; mất kết nối giữ số cũ + nhãn "Cập nhật lúc …" màu vàng.

## 4. [E] Sức khoẻ hệ thống
Nguyên tắc: mỗi nhóm có mức **OK / CHÚ Ý / LỖI** kèm chữ (không chỉ màu), "cập nhật lúc"; lỗi một nguồn chỉ làm nhóm đó `null` + cờ `degraded` (không hỏng cả trang); không trả `payload` outbox, `sourceKey`, email, userId. Ngưỡng cấu hình `homespace.health.*` (§12.2). Mức tổng = mức xấu nhất; hiển thị cả **lý do** ("Outbox payment: cũ nhất 7 phút").

### 4.1 Nguồn Core — `GET /api/v1/admin/system-health` (§7.2)
| # | Nhóm | Hiển thị và định nghĩa | Nguồn | Mức |
|---|---|---|---|---|
| 1 | Dịch vụ | Mỗi dịch vụ **kỳ vọng** (`homespace.health.expected-services`: gateway, core, chat, news, notification, ai) một ô UP/DOWN/DEGRADED + số instance + tuổi gia hạn Eureka (`lastRenewalAge`) + "đăng ký lúc". Vắng khỏi Eureka = DOWN (vì dịch vụ chết sẽ bị gỡ sau ~90 giây). `DEGRADED` khi còn instance nhưng `lastRenewalAge` > 90 giây (Eureka self-preservation có thể giữ instance chết ở trạng thái UP). Discovery: gọi Eureka lỗi = `DISCOVERY` DOWN. Cơ chế ở §5.5. | `DiscoveryClient`/`EurekaClient` trong Core | DOWN ≥1 = LỖI |
| 2 | Outbox (8 bảng) | Mỗi bảng: `pending` (`sent_at IS NULL AND failed_at IS NULL`), `retrying` (`attempts>0`), `dead` (`failed_at IS NOT NULL`), `oldestAgeSec` (`now() - MIN(created_at)` chưa gửi), `lastSentAgeSec` (`now() - MAX(sent_at)`), cờ **`frozen`** = có hàng chưa gửi cũ hơn ngưỡng LỖI **và** không có hàng nào gửi trong ngưỡng đó (phát hiện chặn đầu hàng ngay cả trước khi sửa §5.1, vì khi hàng đầu độc thì `MAX(sent_at)` đứng yên). Sau khi sửa §5.1 thêm `dead>0` = LỖI (hàng độc đã bị cách ly, cần người xem), `retrying>0` và cũ nhất > ngưỡng CHÚ Ý = CHÚ Ý. Bảng: `contract_`, `listing_`, `appointment_`, `rental_request_`, `payment_`, `membership_`, `account_notification_outbox` và `notification_broadcast_outbox` (tiến độ `recipient_cursor` nếu có). Cột `attempts/last_error/failed_at` chỉ có sau §5.1; trước đó UI ghi "chưa phân biệt chờ/độc". | 8 bảng outbox | OK <60s; CHÚ Ý 60–300s; LỖI >300s hoặc `frozen` hoặc `dead>0` |
| 3 | Việc nền (job) | (a) **Đã ghi lần chạy** (sau §5.4): mỗi job `lastSuccessAge`, `consecutiveFailures`, `lastErrorClass`, `overdue` (= quá `3 × chu kỳ + 60s` kể từ lần thành công cuối). (b) **Việc quá hạn** (truy vấn tạm, chạy được ngay, không cần §5.4): đếm bản ghi mà job lẽ ra đã xử lý — bảng dưới. | `scheduled_job_runs` + truy vấn | job overdue hoặc count>0 quá 2 chu kỳ = CHÚ Ý; `consecutiveFailures ≥ 3` = LỖI |
| 4 | Ký số SmartCA | `signature_requests`: kẹt `CREATED`/`PENDING_USER_CONFIRMATION` quá TTL, kẹt `PROVIDER_SIGNED`/`EMBEDDING` có `processing_started_at` > 5 phút (hạn `failStaleEmbedding` = 3 lần/2 phút), `FAILED`/`EXPIRED` trong 24 giờ, tuổi yêu cầu mở cũ nhất. Thành phần sự cố mới `SMARTCA` (§5.5). Chi tiết hiện có `SignaturePolling/Embedding` 5s. | `signature_requests` (`idx_sig_req_status`; thêm `(status, initiated_at)`) | kẹt>0 = CHÚ Ý; FAILED24h vượt ngưỡng = LỖI |
| 5 | Blockchain | Một dòng: số PENDING/SUBMITTED/FAILED, `stuck` (>15 phút), sự cố `BLOCKCHAIN` mở, tài liệu `integrity_status` MISMATCH/MISSING (cũ nếu chưa ai kiểm). Chi tiết ở P6 (link `/blockchain` nếu có `BLOCKCHAIN_VIEW`). Không lặp bảng. | `contract_document_proofs`, `contract_documents`, `operational_incidents` | FAILED>0 hoặc MISMATCH>0 = LỖI; stuck>0 = CHÚ Ý |
| 6 | Tiền (`PAYMENT_VIEW`) | (a) Đơn hội viên `PENDING` có `expires_at < now()` (không có job hết hạn: `MembershipCheckoutService.java:101-107`), `REVIEW` legacy, `FAILED` 24h; (b) **ETH chưa settle**: `membership_eth_quotes.transaction_hash IS NOT NULL` mà đơn chưa `PAID`; (c) **quá thanh toán hội viên chờ hoàn**: `membership_overpayments` status chờ hoàn, kèm **số đếm và tuổi cũ nhất**, không tổng tiền theo người, không trang hoàn tiền; (d) biên lai PDF (`payment_receipts`+`membership_receipts`) `pdf_status` `PENDING` quá 5 phút hoặc `FAILED` (index `idx_receipt_pdf_queue`; phụ thuộc Gotenberg); (e) `TRANSFER_REPORTED` quá `confirmation_due_at` mà chưa auto-confirm (thuê và hoàn; đồng hồ `max(businessNow, now)` như `ReceiptAutoConfirmScheduler.java:33-34`); (f) `storage_objects` `PAYMENT_PROOF/PAYMENT_RECEIPT` kẹt `PENDING` > 1 giờ. | `membership_orders`, `membership_eth_quotes`, `membership_overpayments`, `*_receipts`, `payment_requests`, `storage_objects` | mọi số >0 = CHÚ Ý; PDF FAILED hoặc (e)>0 = LỖI |
| 7 | KYC (`KYC_VIEW`) | Số chờ, `REVIEW_REQUIRED`, **tuổi chờ cũ nhất** (SLA), phiên quá hạn chưa chốt, lượt VNPT hết hạn chưa `retry_notice_sent_at` (job 60s), dữ liệu tạm eKYC > 1 ngày còn tồn (`purgeTemporaryEvidence`, quyền riêng tư). Cần trang danh sách (§7.3). | `kyc_verifications`, `VnptKycStore` | cũ nhất > 24 giờ = CHÚ Ý (ngưỡng §12.2) |
| 8 | Khiếu nại (`COMPLAINT_VIEW`) | `ESCALATED` chưa ai nhận, stage `WAITING_ADMIN`, quá `respond_by`, tuổi hồ sơ mở cũ nhất, `now - escalated_at` lâu nhất (SLA nhận). % tự giải quyết giữa hai bên chuyển sang P5. | `dispute_cases` (`opened_at`, `escalated_at`) | chưa nhận > 24 giờ = LỖI |
| 9 | Phản hồi yêu cầu thuê/lịch hẹn (`STATISTICS_VIEW`) | Yêu cầu thuê `PENDING` và lịch hẹn `PENDING` chưa được chủ nhà phản hồi quá 24/48 giờ (đếm), đo bằng `created_at`. **Chưa đo được "thời gian phản hồi thật"**: `ViewingAppointment` chỉ có `completedAt`, `RentalRequest` chỉ có `acceptedAt` (từ chối/hủy chỉ `updatedAt`) → cần cột `respondedAt` (+ `responseType`) ở cả hai (§5.4b). Sau khi có cột: trung vị/p90 chuyển sang P5 khối 12. | `rental_requests`, `viewing_appointments` | thông tin; không tính vào mức tổng |
| 10 | Chất lượng dữ liệu & khác | Loại tin không còn mẫu HĐ `SYSTEM` đã xuất bản (chặn tạo HĐ; `countActiveSystemTemplates`, `CONTRACT_TEMPLATE_VIEW`); lượt đăng giữ kẹt (`publication_reservation` khác null mà tin không còn `PENDING_REVIEW`); job quét POI `FAILED/RETRY_WAIT/ATTEMPTS_EXHAUSTED` (`nearby_scan_jobs`, `NEARBY_SCAN_VIEW`); `UserSyncConsumer` lỗi (§11, chưa có dữ liệu). | nhiều bảng | CHÚ Ý nếu >0 |

**Việc quá hạn (truy vấn tạm, nhóm 3b)** — mỗi dòng: `count` + `oldestAgeSec`; "quá hạn" = quá chu kỳ job + 60 giây dung sai:
| Job | Truy vấn "lẽ ra đã xử lý" |
|---|---|
| ListingExpiration (60s) | `listings` `PUBLISHED` và `expires_at < now()-2 phút` |
| RentalHoldExpiration (30s) | yêu cầu có `hold_expires_at < now()-2 phút` mà chưa sang trạng thái hết hạn / tin còn `RESERVED` (khớp enum khi code) |
| ViewingAppointment (cron 15 phút) | lịch `PENDING` quá hạn hoặc `CONFIRMED` đã qua > 20 phút chưa `COMPLETED` |
| Receipt/RefundAutoConfirm (300s) | xem nhóm Tiền (e) |
| Receipt PDF worker (15s) | xem nhóm Tiền (d) |
| MonthlyBilling (60s) | `monthly_invoices` có `due_at < now()-5 phút` mà trạng thái chưa là OVERDUE/đã trả (khớp enum khi code; `idx_monthly_invoice_due`); HĐ thiếu hoá đơn kỳ tới |
| ContractLifecycle / Amendment (60s) | phụ lục/gia hạn quá `respond_by` chưa đổi trạng thái; HĐ chờ ký quá `tenant_signing_due_at`; **HĐ `ACTIVE` quá ngày kết thúc chưa `EXPIRED` chưa đo được** vì `contracts` không có cột ngày kết thúc (cần `lease_end_exclusive`) |
| ContractClosure | `contract_closures.refund_due_at < now()` mà `REFUND_PENDING/BALANCE_PENDING`; thêm index `(status, refund_due_at)` |
| Contract document generation | `contract_documents.status` `FAILED/STALE` hoặc `GENERATING` > 10 phút (`idx_doc_status`) |
| KycRetryNotice (60s) | xem nhóm KYC |
| BranchDigest (08:00) | chưa đo được (không có loại bản ghi digest riêng); chỉ biết qua §5.4 |

### 4.2 Nguồn ngoài Core (frontend gọi riêng từng nguồn, nên một nguồn lỗi không che nguồn khác)
Các endpoint dưới đây **chưa tồn tại** (notification-service không có endpoint admin dù `AdminGuard` có sẵn: `authentication.module.ts:8-9`). Chung: ADMIN ở Gateway (`GatewayRouteAuthorization.java:43` đã coi `notifications/admin/**` là ADMIN; thêm quy tắc tương tự cho `chat/admin/**`) + `AdminGuard` + kiểm quyền `SYSTEM_HEALTH_VIEW` trong header `X-User-Authorities` (Gateway đã đặt header này: `UserHeaderFilter.java:128`; cần xác minh Nest nhận được, nếu không thì Gateway phải chuyển tiếp); cache trong bộ nhớ 15 giây; timeout từng nguồn 3 giây; **không trả** email/địa chỉ nhận/`providerMessageId`/payload/token thiết bị.
| Endpoint (qua Gateway) | Trả về |
|---|---|
| `GET /api/v1/notifications/admin/delivery-health` (notification-service `GET /admin/delivery-health`) | `{ "email":{"enabled":true,"pending":3,"processing":0,"failed24h":1,"skipped24h":9,"byErrorCode24h":{"http-5xx":1,"opted-out":8},"oldestPendingAgeSec":20,"retryDue":0}, "push":{"enabled":false,"pending":210,"failed24h":0,"accepted24h":0,"oldestPendingAgeSec":5400,"devicesDeactivated24h":2}, "kafka":{"group":"…","lag":0,"dlt":{"notification":4,"news":0}}, "loops":{"email":{"lastTickAt":"…","lastError":null},"push":{…},"eureka":{…}} }`. Nguồn: Mongo `notification_email_deliveries` (index `status,nextAttemptAt`, `email-outbox.ts:83`), `notification_deliveries`, `notification_devices`; `kafkajs` Admin (`kafka.admin()` đã có, `notification-events.consumer.ts:65`): `fetchOffsets` nhóm `KAFKA_GROUP_ID` so với `fetchTopicOffsets` cho lag; độ sâu DLT = high−low watermark của `KAFKA_DLT_TOPIC` và `KAFKA_NEWS_DLT_TOPIC` (biến thứ hai thêm vào env notification-service, đủ mọi file env). **Cờ `enabled=false` hiển thị LỖI cho email (email bị bỏ âm thầm, `email-delivery.service.ts:85`) và CHÚ Ý cho push** (`PUSH_ENABLED=false` làm việc ứ rồi bắn dồn, `notification-delivery.service.ts:238`). Consumer lặp vô hạn vì `PERSISTENCE_FAILED` (`:134-138`) lộ ra qua `lag` tăng. `loops.*.lastTickAt` lấy từ biến trong bộ nhớ (mất khi khởi động lại, UI ghi "chưa chạy từ lúc khởi động"). |
| `GET /api/v1/chat/admin/outbox-health` (chat-service) | `{ "pending":0,"oldestAgeSec":0,"lastPublishedAt":"…","loop":{"lastTickAt":"…"} }` từ `messages` có `pendingNotificationRecipientId` chưa `notificationPublishedAt` (`chat-notification-publisher.ts:73-75`). |
| `GET /api/v1/admin/news/outbox-health` (news-service) | cùng dạng, từ `news_articles` `status=PUBLISHED, publicationSentAt=null` (`news-publication.publisher.ts:58-63`). |
| `GET /api/v1/admin/ai/knowledge/jobs-summary` (ai-service `GET /admin/knowledge/jobs-summary`, `require_admin`) **mới** + `GET .../worker-health` **đã có** (`admin_knowledge.py:388`, heartbeat ≤45s) | `{ "QUEUED":2,"RUNNING":1,"FAILED":0,"oldestQueuedAgeSec":30 }` từ `knowledge_ingestion_jobs` (`models/knowledge.py:146`). Worker offline = LỖI (tài liệu không tìm được). Link sang trang Tri thức; không làm "Dự báo AI". |
Gateway (số 429, trạng thái CB, fallback 503): **chưa có dữ liệu**; chỉ làm được sau §5.2 (cổng quản lý nội bộ). Khi đó Core đọc `/actuator/circuitbreakers` + `/actuator/metrics` qua địa chỉ nội bộ và đưa vào nhóm Dịch vụ; trước đó UI ghi "chưa có số liệu Gateway".

## 5. Lỗi phải sửa trước (từ `AUDIT_LOG.md`, mục "Kiểm kê toàn bộ API")
Thứ tự: 5.1 và 5.2 là lỗi/rủi ro đang chạy; 5.3-5.5 là nền cho §4. Mọi mục chạm ranh giới Gateway/tiền/thông báo nên theo DEEP (`AGENTS.md`): có test đường lỗi.
1. **Outbox chặn đầu hàng.** `ContractNotificationPublisher.CLAIM_SQL` lấy `ORDER BY o.id LIMIT 1`; hàng cũ nhất gửi lỗi → rollback → nhịp sau chọn lại đúng hàng đó → mọi hàng sau (kể cả hợp đồng khác) đứng yên (`:28-34,70-79`; cùng mẫu `ListingNotificationPublisher.java:25` — kiểm cả 8 publisher). **Sửa:** (a) thêm cột `attempts int not null default 0`, `next_attempt_at timestamptz not null`, `last_error varchar(200)` (chỉ tên lớp lỗi, không nội dung), `failed_at timestamptz` vào cả 8 bảng (`ddl-auto=update`, backfill `next_attempt_at=created_at`); (b) claim theo **khoá tổng hợp** (vd `contract_id`): `WHERE sent_at IS NULL AND failed_at IS NULL AND next_attempt_at <= now() AND NOT EXISTS (SELECT 1 FROM t p WHERE p.<aggregate_id>=o.<aggregate_id> AND p.id<o.id AND p.sent_at IS NULL AND p.failed_at IS NULL) ORDER BY o.id LIMIT n FOR UPDATE SKIP LOCKED` → thứ tự trong một hợp đồng được giữ, hợp đồng khác không bị chặn; (c) khi gửi lỗi, ghi `attempts+1`, `next_attempt_at = now + min(5s·2^attempts, 1h)`, `last_error` trong **transaction riêng** (`REQUIRES_NEW`, để rollback của lần gửi không xoá dấu vết); (d) `attempts ≥ 10` → `failed_at=now()` (cách ly, hàng sau cùng khoá không bị chặn vĩnh viễn, đổi lại có thể sai thứ tự — hiển thị `dead` ở §4.1.2 để người xử lý); (e) tách phần claim thành một lớp dùng chung cho 8 publisher thay vì sửa tám bản. **Test:** hàng độc không chặn hợp đồng khác; cùng hợp đồng giữ thứ tự khi hàng trước đang chờ lùi; lùi tăng theo luỹ thừa và dừng ở 1 giờ; quá 10 lần → `dead`, `last_error` không chứa nội dung/PII; hai worker song song không lấy trùng.
2. **Actuator công khai ở Gateway.** `SecurityConfig.java:32,40` mở `/actuator/**` và `/api/v1/actuator/prometheus`; Core chưa có registry Prometheus nên nhiều khả năng 404, nhưng thêm thư viện là lộ số liệu. **Sửa:** (a) Gateway: `management.server.port=8081` (không publish ra Dokploy/Traefik, HEALTHCHECK Dockerfile trỏ cổng này), exposure `health,info,metrics,circuitbreakers`, `show-details=never` ở cổng chính; (b) `SecurityConfig` bỏ `/actuator/**` và `/api/v1/actuator/prometheus` khỏi danh sách công khai; **giữ** `GET /api/v1/actuator/health` (cả `hs-admin-portal/src/lib/backend-health.ts:9` và mobile `backend-health.ts:14` đang gọi; chỉ trả UP/DOWN); (c) Core: prometheus/metrics chỉ nội bộ, thêm `micrometer-registry-prometheus` **sau** khi (b) xong; (d) sửa `dokploy.md:714` (mô tả sai "health của Gateway"); (e) bỏ allow-list chết `/api/v1/ai/property-search*` (`SecurityConfig.java:50-51`). **Test:** từ ngoài `/actuator/info`, `/api/v1/actuator/prometheus` → 401/404; `/api/v1/actuator/health` vẫn 200 không có `details`; HEALTHCHECK container xanh.
3. **Rate limit theo IP socket.** `RateLimiterConfig.java:115-121` dùng `getRemoteAddress()`, không đọc `X-Forwarded-For`; sau Traefik cả hệ thống có thể chung một xô 50 rps/burst 100 (chưa kiểm trên server). **Sửa:** khoá = `XForwardedRemoteAddressResolver.maxTrustedIndex(N)` (lấy địa chỉ thứ N từ **bên phải** chuỗi `X-Forwarded-For`, nên giá trị client tự thêm ở bên trái không đổi được khoá); `N = homespace.gateway.trusted-proxy-hops` (cấu hình bắt buộc theo quy ước env; Traefik 1 tầng = 1); thiếu header → rơi về IP socket. Đếm 429 bằng counter Micrometer `gateway.ratelimit.rejected` (cho §4.2). Trước khi triển khai: gửi một yêu cầu thử có `X-Forwarded-For` giả và đối chiếu khoá trong log tạm (chưa kiểm chứng trên prod). **Test:** hai IP khác nhau qua proxy → hai xô; header giả bên trái không đổi xô; không header → IP socket.
4. **Không ghi lần chạy job.** 34 `@Scheduled` ở Core + 4 vòng lặp Nest chỉ ghi log. **Sửa (Core):** bảng `scheduled_job_runs(job_name PK, expected_interval_ms, last_started_at, last_finished_at, last_success_at, last_failure_at, consecutive_failures int, last_error_class varchar(120), last_duration_ms)` ghi bằng một aspect `@Around` quanh mọi `@Scheduled` (`REQUIRES_NEW`). Job chạy 1 giây/5 giây (outbox ×8, polling) **không** ghi DB mỗi nhịp: giữ trong bộ nhớ, ghi tối đa 1 lần/30 giây, nhưng ghi ngay khi lỗi hoặc đổi trạng thái; nhiều instance dùng `GREATEST` khi upsert. `expected_interval_ms` đăng ký lúc khởi động (job cron: chu kỳ tính từ biểu thức). Chỉ lưu tên lớp lỗi, không lưu thông điệp. **Nest:** `loops.*.lastTickAt/lastError` trong bộ nhớ (§4.2), không cần bảng. **Tạm thời** (chưa có bảng): nhóm "Việc quá hạn" ở §4.1.3b. **4b. Cột phản hồi:** thêm `responded_at`, `response_type` (`ACCEPTED/REJECTED/CANCELLED`) cho `rental_requests` và `viewing_appointments`, ghi tại chỗ chủ nhà phản hồi; không backfill (số liệu từ ngày bật). **Test:** job ném lỗi → `consecutive_failures` tăng, thành công → về 0; ghi giới hạn tần suất; aspect không nuốt lỗi gốc; bảng không chứa thông điệp lỗi.
5. **Chỉ canh Gateway.** `OperationalHealthMonitor.checkGateway` (`:21`, 60s; 3 lần lỗi liên tiếp mới mở sự cố) là nơi duy nhất canh dịch vụ; 5 dịch vụ khác chết không ai báo. **Sửa:** `ServiceHealthMonitor` thay `checkGateway`, đọc `EurekaClient` so với `homespace.health.expected-services` (vắng hoặc quá hạn gia hạn = hỏng), dùng lại quy tắc 3 lần liền để mở sự cố, tự đóng khi khoẻ lại; mở rộng `component` hợp lệ (`OperationalIncidentService.java:23,57`) từ `BLOCKCHAIN/GATEWAY` thành thêm `CHAT, NEWS, NOTIFICATION, AI, DISCOVERY, SMARTCA, OUTBOX, SCHEDULER` (sửa nhãn ở `/operations/system-alerts`). Nest `/ping` chỉ trả "pong" không kiểm Mongo/Kafka (`health.controller.ts:5`) → thêm `/health/ready` kiểm Mongo + Kafka và để Eureka báo `DEGRADED` khi lỗi (AI đã có `ready`). **Test:** dịch vụ vắng 2 lần chưa mở sự cố, lần 3 mở; trở lại UP đóng; Eureka không trả lời → `DISCOVERY`; không mở trùng.

## 6. Quyền admin — danh sách duy nhất (P4, P5, P6 cùng dùng)
Thêm vào `Permissions.java` rồi `CATALOG` (dòng 42 và 69-98), **không** thêm vào `USER_SCOPE`; `PermissionDataInitializer.java:55` tự gán mọi quyền trong `CATALOG` cho ADMIN khi khởi động; phản chiếu vào `hs-admin-portal/src/config/permissions.ts` (`PERMISSIONS`, `ROUTE_PERMISSIONS`).
| Quyền (mới) | Mô tả | Dùng ở |
|---|---|---|
| `STATISTICS_VIEW` | Xem thống kê doanh thu, tăng trưởng, SLA phản hồi toàn hệ thống | P4 C1, D, E9; P5 toàn bộ |
| `SYSTEM_HEALTH_VIEW` | Xem sức khoẻ hệ thống (dịch vụ, outbox, job, ký số, thông báo, AI) | P4 E1-E5, E10 và mọi endpoint §4.2 |
| `PAYMENT_VIEW` | Xem số liệu tiền kẹt (đơn hội viên, ETH, quá thanh toán, biên lai PDF, tự xác nhận) — chỉ số đếm/tuổi, không danh tính | P4 A5, E6; P5 khối 5b |
| `BLOCKCHAIN_VIEW` | Xem proof blockchain và thanh toán ETH hội viên | P6 |
| `BLOCKCHAIN_MANAGE` | Thử lại proof FAILED (giai đoạn 2) | P6 |
Quyền có sẵn dùng lại: `LISTING_VIEW`, `KYC_VIEW`, `COMPLAINT_VIEW`, `INCIDENT_VIEW` (A4, P5 khối 10), `USER_VIEW`, `CONTRACT_VIEW`, `CONTRACT_TEMPLATE_VIEW`, `NEARBY_SCAN_VIEW`. Thay `INCIDENT_VIEW` ở khối E cũ bằng `SYSTEM_HEALTH_VIEW`.

## 7. API
### 7.1 `GET /api/v1/admin/dashboard/overview` (A-D)
Controller mới `hs-api-service/.../controller/admin/AdminDashboardController.java` (`@RequestMapping({"/admin/dashboard","/api/v1/admin/dashboard"})`, mẫu `ComplaintAdminController.java:17`). Auth: `@PreAuthorize("hasAuthority('ADMIN')")` ở lớp; **từng khối** kiểm quyền trong service bằng `Authentication.getAuthorities()`; thiếu quyền → khối `null` (không 403); không nhận userId/role từ tham số. Response (`ApiResponse`, tiền là số nguyên VND, cộng bằng `long`):
```json
{ "code":1000, "result": { "generatedAt":"2026-11-28T14:05:11+07:00", "businessDate":"2026-11-28",
  "attention":{ "pendingListings":12, "oldestPendingListingAgeSec":259200, "pendingListingEdits":2, "pendingKyc":5, "expiredKyc":1,
                "openComplaints":3, "overdueComplaints":1, "escalatedUnassigned":1, "openIncidents":0, "stuckMoney":3 },
  "users":{ "newToday":8, "new7d":41, "daily7d":[3,5,7,6,4,8,8], "kycVerified":1204, "totalUsers":3310, "disabled":12, "activeAdmins":3, "stuckOnboarding":9 },
  "rentals":{ "confirmedPaymentsToday":6, "activeContracts":212 },
  "membership":{ "revenueToday":490000, "revenueMonth":12300000, "daily7d":[0,0,0,0,0,0,490000] },
  "system":{ "level":"WARN", "reasons":["outbox:payment_notification_outbox 7 phút"] } } }
```
`system` lấy từ cùng bộ nhớ đệm với §7.2 (không chạy lại truy vấn). Truy vấn: một `@Service` `@Transactional(readOnly=true)`, `NamedParameterJdbcTemplate`/`EntityManager` native, mỗi khối một câu `GROUP BY`/`COUNT(*) FILTER` (≈14 câu), không N+1. Index cần: `users(created_at)`, `payment_requests(status, confirmed_at)`, `membership_orders(status, paid_at)`, `membership_orders(status, expires_at)`. Cache 15 giây theo tập quyền; "Tải lại" `?fresh=true` giới hạn 1 lần/10 giây/người; frontend tự làm mới 60 giây khi tab hiển thị.
### 7.2 `GET /api/v1/admin/system-health` (E nguồn Core)
`AdminSystemHealthController` cùng package. `ADMIN`; từng nhóm kiểm quyền ở §6 (thiếu → `null`). Cache 30 giây (gọi Eureka và nhiều truy vấn), `?fresh=true` 1 lần/30 giây/người. Mỗi nhóm bắt lỗi riêng → `null` + `"degraded":["services"]`. Response:
```json
{ "code":1000, "result": { "generatedAt":"…", "level":"WARN", "degraded":[],
  "services":[{"name":"hs-chat-service","status":"UP","instances":1,"lastRenewalAgeSec":12}],
  "outbox":[{"table":"contract_notification_outbox","pending":4,"retrying":0,"dead":0,"oldestAgeSec":12,"lastSentAgeSec":1,"frozen":false,"level":"OK"}],
  "jobs":{ "recorded":[{"job":"PaymentDueReminder","lastSuccessAgeSec":40,"consecutiveFailures":0,"lastErrorClass":null,"overdue":false}],
           "overdueWork":[{"key":"listing_expiry","count":0,"oldestAgeSec":0}] },
  "signing":{"stuck":0,"failed24h":1,"oldestOpenAgeSec":120},
  "blockchain":{"pending":3,"stuck":2,"failed":1,"integrityMismatch":0,"openIncidents":0},
  "payments":{"membershipOrdersStuck":2,"ethUnsettled":0,"overpaymentsAwaitingRefund":1,"oldestOverpaymentAgeSec":86400,"receiptPdfFailed":0,"receiptPdfOverdue":0,"autoConfirmOverdue":0,"storageStuck":0},
  "kyc":{"pending":5,"reviewRequired":1,"oldestPendingAgeSec":7200,"expired":2,"retryNoticeOverdue":0,"tempEvidenceOverdue":0},
  "complaints":{"escalatedUnassigned":1,"waitingAdmin":2,"overdue":1,"oldestOpenAgeSec":86400,"oldestUnassignedAgeSec":3600},
  "responseSla":{"rentalPendingOver24h":2,"appointmentPendingOver24h":5,"respondedAtAvailable":false},
  "dataQuality":{"listingTypesWithoutTemplate":0,"stuckPublicationReservations":0,"nearbyJobsFailed":1},
  "gateway":null } }
```
Chỉ số liệu tổng hợp; không có `sourceKey`, payload, email, userId. Index mới cần: `signature_requests(status, initiated_at)`, `contract_closures(status, refund_due_at)`, `contract_renewals(status, respond_by)`, `kyc_verifications(created_at)`, các cột outbox mới `(sent_at, failed_at, next_attempt_at)`.
### 7.3 Danh sách KYC cho admin (bắt buộc để A2/E7 bấm vào được)
Hiện chỉ có `GET/PUT /admin/users/{userId}/kyc` (`AdminKycController.java`), không có danh sách. Cần: `GET /api/v1/admin/kyc/counts` (đếm theo trạng thái × nhà cung cấp × loại lý do từ chối) và `GET /api/v1/admin/kyc?status=&provider=&overdue=&page=&size=` (≤50; cột: `userId`, `status`, `provider`, `createdAt`, `expiresAt`, `rejectionCategory`; **không** CCCD/tên/email/`rejection_reason` thô), quyền `KYC_VIEW`; trang `/users/kyc` (route bọc quyền). Trạng thái hiện tại lấy theo `getCurrentStatus`. Thiết kế chi tiết trang này thuộc một file riêng (ngoài P4), nhưng P4 phụ thuộc nó.

## 8. Biểu đồ
`hs-admin-portal/package.json` **không có thư viện biểu đồ**. Token màu có sẵn (`--chart-1..5`, `src/index.css:33-37,85-89`). Sparkline 7 điểm và thanh 7 ngày vẽ bằng SVG/`div` Tailwind trong `components/common/MiniBars.tsx` (≈40 dòng, dùng lại ở P5 nếu không thêm thư viện). Khối E không có biểu đồ: chip mức + bảng; mức luôn kèm chữ. Mỗi cột có `title`/`aria-label` ("28/11: 8 người dùng mới").

## 9. Hiệu năng & bảo mật
- ≤150 ms cho overview (tất cả `COUNT/SUM` có index; câu KYC `DISTINCT ON` quét `kyc_verifications` có `idx_kyc_user_status`, cache 15 giây). System-health ≤500 ms khi nguồn khoẻ; mỗi truy vấn có `statement_timeout` 2 giây và gọi Eureka/HTTP có timeout 3 giây để trang không treo.
- Chỉ trả số tổng hợp; không PII/ID người dùng; tiền chỉ cho `STATISTICS_VIEW`/`PAYMENT_VIEW`. Quyền kiểm bằng `hasAuthority` ở backend; frontend chỉ ẩn. `Cache-Control: no-store`.
- Đường nóng: `/internal/users/{userId}/permissions` không cache, mỗi request đăng nhập gọi một lần; tự làm mới 60 giây của nhiều admin cộng thêm tải đó → đo trước khi tăng tần suất.
- Các endpoint ghi/khoá (`PATCH /admin/users/{id}/disable`, `GET /admin/complaints/{id}`) không được dashboard gọi. Eureka dashboard không xác thực nếu mở domain (`dokploy.md:35`): không nhúng và không liên kết.

## 10. Kiểm thử cần viết
Backend (`AdminDashboardControllerTest`, `AdminSystemHealthControllerTest`, mẫu `ContractBlockchainProofControllerTest`; truy vấn trên PostgreSQL container tạm):
1. 401 chưa đăng nhập; 403 USER; ADMIN chỉ có `LISTING_VIEW` → chỉ `pendingListings` khác `null`, mọi khối còn lại `null`; có `STATISTICS_VIEW` mới thấy `membership`; có `SYSTEM_HEALTH_VIEW` mới thấy `services/outbox/jobs`; chỉ có `PAYMENT_VIEW` thì `stuckMoney` hiện nhưng `membership` ẩn.
2. Ranh giới ngày 23:59:59/00:00:00 giờ Việt Nam; đơn `PAID` hôm qua không vào "hôm nay"; `DEPOSIT_REFUND` không đếm; ADMIN không vào "mới"; KYC có bản `VERIFIED` cũ và `PENDING` mới → tính `VERIFIED`.
3. Outbox: hàng chưa gửi cũ 400 giây + không gửi nào trong 400 giây → `frozen=true`, `level=ERROR`; không hàng → `OK`, `oldestAgeSec=0`; sau §5.1: `dead>0` → ERROR, hàng độc không làm `frozen` khi các hợp đồng khác vẫn gửi.
4. Dịch vụ: Eureka trả thiếu `chat` → `DOWN`; `lastRenewalAge` 120 giây → `DEGRADED`; Eureka ném lỗi → nhóm `services` `null` + `degraded`, trang vẫn trả 200.
5. Việc quá hạn: tin `PUBLISHED` quá `expires_at` 3 phút → count 1; 30 giây → 0; hoá đơn/giữ chỗ/closure tương tự (mỗi truy vấn một ca trên, một ca dưới ngưỡng).
6. Tiền: ETH có `transaction_hash` mà đơn `PENDING` → `ethUnsettled=1`; quá thanh toán chờ hoàn → đếm, JSON không có số tiền theo người; PDF `FAILED` → ERROR.
7. Khiếu nại: `ESCALATED` + `assigned_to` null → `escalatedUnassigned`; đã nhận → 0. KYC: phiên quá hạn đếm đúng, không lộ PII. SLA phản hồi: `respondedAtAvailable=false` khi chưa có cột.
8. Không rò rỉ: JSON chỉ chứa khoá cho phép (không `sourceKey`, `payload`, `email`, `userId`). Cache: hai lần gọi liền chỉ chạy truy vấn một lần.
9. notification-service (jest, mẫu `admin.guard.spec.ts`): 403 khi thiếu `SYSTEM_HEALTH_VIEW`/không ADMIN; Kafka lỗi → `kafka:null` còn email/push vẫn trả; response không có địa chỉ email/token; `enabled=false` được báo.
10. §5: các test ở từng mục 5.1-5.5.
Frontend (`node --test`): `overviewToCards()` ẩn khối `null`; `healthToChips()` mức xấu nhất, nhóm `null` hiển thị "không có số liệu" (không phải OK); định dạng VND/tuổi ("7 phút"); mỗi lối tắt ẩn đúng khi thiếu quyền; một nguồn ngoài Core lỗi không làm mất nhóm khác; banner lỗi giữ lối tắt.

## 11. Không đưa vào (lý do)
| Tín hiệu (inventory) | Lý do / hướng xử lý |
|---|---|
| Hoàn tiền quá thanh toán: trang/hành động hoàn, tổng tiền theo người | Chủ dự án bỏ trang hoàn; chỉ đếm + tuổi (E6c). Nếu sau này làm, cần cột `refunded_at/refunded_by` (hiện không code nào ghi `REFUNDED`). |
| Lệch Keycloak ↔ `users`, `UserSyncConsumer` nuốt lỗi (`UserSyncConsumer.java:70-72`), lời mời admin chưa nhận, đăng nhập cuối/DAU | Không có dữ liệu. Cần bảng `user_sync_failures` (ghi trong `catch`) rồi mới thêm vào E10; đăng nhập do Keycloak giữ. |
| IPN SePay bị từ chối/trùng; sức khoẻ cổng ngoài (SePay, RPC Ethereum, giá ETH/VND, VietQR) | Không ghi bảng nào. Cần bảng log tối thiểu (thời điểm, kết quả, mã; không lưu body). Giai đoạn 2. |
| Lịch sử broadcast (ai/khi nào/bao nhiêu người) | `BroadcastNotificationOutbox` không lưu người gửi; cần cột actor + endpoint đọc. Chỉ hiển thị tiến độ `recipient_cursor` trong E2. |
| Nhật ký kiểm toán admin (ai khoá/đổi quyền/nhập KYC tay, lý do) | Cần `admin_audit_log` mới; ngoài phạm vi dashboard. |
| Mã lỗi/độ trễ theo nhóm đường dẫn, tra quyền Gateway→Core lỗi, VNPT CB mở, lỗi bước VNPT | Cần Micrometer/Prometheus (đang không có) và Gateway §5.2; `kyc_provider_steps` bị xoá sau 1 ngày nên cần cộng dồn theo ngày trước khi xoá. Giai đoạn 2. |
| Hạn mức TomTom/Vietmap đã dùng, AI search/LLM (lỗi, token, câu bị cắt), lượt xem tin | Chỉ có log/cấu hình, không có bộ đếm; lượt xem sai (`news.service.ts:93`, Redis lỗi vẫn tăng `ListingViewService.java:69-71`). Không hiển thị số không tin được. |
| Container healthy/restart/bộ nhớ, hạ tầng (Postgres, Mongo, Redis, Kafka, Gotenberg, Keycloak) | Thuộc Dokploy/Docker, không có Prometheus/Alertmanager. Gián tiếp: Eureka (E1), lag Kafka (§4.2), PDF kẹt (Gotenberg, E6d). |
| Nhắc hạn (hợp đồng, gói, lịch xem, thanh toán) chưa gửi | Không có bản ghi "đã nhắc"; chỉ phát hiện qua `scheduled_job_runs` (§5.4). |
| Vi phạm cọc (`rental_requests.default_*`), tỉ lệ khoản bị từ chối theo chủ nhà, chủ nhà chưa có tài khoản nhận tiền | Theo người (cá nhân) hoặc cần định nghĩa; tổng tỉ lệ từ chối ở P5 khối 5b. Giai đoạn 2. |
| Số lượt "lưu tin", tin tức DRAFT/PUBLISHED, khối lượng chat/AI | Không thuộc tổng quan quản trị; danh sách tin tức đã có ở trang Tin tức. |
| Thông báo chưa đọc, chat chưa đọc, kênh push/email bị tắt của từng người (L1-L3) | Là tín hiệu của chủ nhà/người thuê (P1/P2), không phải admin. |
| Thời gian khắc phục sự cố, hiệu suất duyệt, SLA phản hồi (trung vị/p90) | Là xu hướng → P5 khối 10-12. |

## 12. Câu hỏi mở
1. Doanh thu có nên trừ đơn đã hoàn/hủy sau khi `PAID`? Mặc định: không (không có luồng hoàn).
2. Ngưỡng và danh sách dịch vụ: outbox 60/300 giây, KYC chờ 24 giờ, khiếu nại chưa nhận 24 giờ, kẹt ký số, `expected-services`? Mặc định như trên, cấu hình `homespace.health.*` (theo quy ước env: `${VAR}` trong properties, không default trong compose, thêm đủ 5 file env).
3. Trang danh sách KYC (§7.3) làm trước hay cùng P4? Mặc định: làm cùng (P4 chỉ dẫn tới nó); nếu hoãn, A2 dẫn `/users` và ghi chú.
4. Thứ tự sửa §5 và code §4? Mặc định: §5.1-5.3 trước (lỗi đang chạy), §5.4-5.5 cùng khối E; không code §4 khi chưa duyệt §5.
5. Dọn trang thừa C1: `AdminsPage.tsx`, `MembersPage.tsx` không có route, không nơi nào import, đều là trang trắng 7 dòng. Mặc định: xoá cùng commit dọn dẹp, ghi vào `AUDIT_LOG.md`.
6. "Kẹt onboarding" 3 ngày; "chưa phản hồi" 24/48 giờ có phù hợp? Mặc định như trên.
7. Quyền ở Nest: Gateway có chuyển `X-User-Authorities` tới notification/chat/ai không? Nếu không, mặc định chỉ kiểm ADMIN ở Gateway và coi mọi ADMIN là xem được E (hạ về một quyền thô cho 4 endpoint §4.2).

## 13. Hợp đồng backend thực tế — phase 2, 28/11/2026

### Endpoint và request

| GET endpoint (cả hai alias) | Request | Quyền |
|---|---|---|
| `/admin/dashboard/overview`, `/api/v1/admin/dashboard/overview` | Chỉ `fresh=true|false`, mặc định false, đúng một giá trị. Controller parse thành boolean, không có body/identity DTO. | Verified-context ADMIN và ít nhất một quyền LISTING_VIEW, USER_VIEW, KYC_VIEW, COMPLAINT_VIEW, INCIDENT_VIEW, PAYMENT_VIEW, CONTRACT_VIEW, STATISTICS_VIEW, SYSTEM_HEALTH_VIEW; lọc quyền từng field/khối. |
| `/admin/system-health`, `/api/v1/admin/system-health` | Như overview. | Verified-context ADMIN và ít nhất một quyền SYSTEM_HEALTH_VIEW, PAYMENT_VIEW, KYC_VIEW, COMPLAINT_VIEW, STATISTICS_VIEW; lọc quyền từng nhóm. |
| `/admin/kyc`, `/api/v1/admin/kyc` | `AdminKycQueueService.Query(status:KycStatus?,provider:KycProvider?,overdue:Boolean?,page:int=0,size:int=20)`; page 0–10000, size 1–50. | ADMIN + KYC_VIEW. |
| `/admin/kyc/counts`, `/api/v1/admin/kyc/counts` | Cùng DTO nhưng chỉ nhận status/provider/overdue; không nhận page/size. | ADMIN + KYC_VIEW. |

Mọi endpoint dùng `ApiResponse` code 1000, `Cache-Control: no-store`. Chưa đăng nhập/header giả → 401; USER hoặc thiếu KYC_VIEW ở hai endpoint KYC → 403. Query key lạ (kể cả userId/role), trùng key, rỗng, sai enum/bool hoặc vượt giới hạn → 400/code 1001. Chỉ honor identity từ UserContextFilter đã kiểm secret Gateway; không đọc identity từ query/body. Không thay API KYC chi tiết đang có ở `/admin/users/{userId}/kyc`.

### DTO overview chính xác

`com.hs.contract.dto.admin.AdminDashboardResponse(generatedAt,businessDate,attention,users,rentals,membership,system,sources,degraded)`. generatedAt là Instant giờ thật, businessDate là BillingTime.today theo Asia/Ho_Chi_Minh. Count và tiền hội viên là JSON integer (SUM của amount_vnd đã lưu), không double/làm tròn. Các field không có quyền là null; nếu cả group không có field nào được xem, group đó null.

| Group/field | Permission và công thức thực tế |
|---|---|
| `attention.pendingListings,oldestPendingListingAgeSec,pendingListingEdits` | LISTING_VIEW; active PENDING_REVIEW; tuổi submitted_at theo BillingTime, pending_edit_id khác null. Không có hàng chờ → tuổi null. |
| `attention.pendingKyc,expiredKyc` | KYC_VIEW; trạng thái hiện tại như phần KYC bên dưới. Phiên hết hạn không còn tính là pending. |
| `attention.openComplaints,overdueComplaints,escalatedUnassigned` | COMPLAINT_VIEW; active RENTAL OPEN/ESCALATED, respond_by<=BillingTime.now, ESCALATED và assigned_to null. |
| `attention.openIncidents` | INCIDENT_VIEW; đếm open=true, chỉ các component đã được monitor hiện hữu ghi. |
| `attention.stuckMoney` | PAYMENT_VIEW; cộng bốn count membershipOrdersStuck + ethUnsettled + overpaymentsAwaitingRefund + autoConfirmOverdue từ snapshot health cùng quyền; một nguồn thiếu → null, không cộng số thiếu thành 0. |
| `users.newToday,new7d,daily7d,disabled,activeAdmins,stuckOnboarding` | USER_VIEW; USER active theo created_at hôm nay/7 ngày Việt Nam; daily7d luôn 7 điểm zero-fill. disabled riêng đếm USER active=false; activeAdmins chỉ ADMIN active; onboarding=false và tạo trước BillingTime.now−3 ngày. |
| `users.kycVerified,totalUsers` | KYC_VIEW; current VERIFIED / tổng USER active, kể cả USER chưa có attempt (NOT_VERIFIED). Không trả số USER ở field này khi chỉ có USER_VIEW. |
| `rentals.confirmedPaymentsToday` | STATISTICS_VIEW; active CONFIRMED, VND, TENANT_TO_LANDLORD, INITIAL/RESERVATION_DEPOSIT/MONTHLY_RENT/UTILITY/OTHER_CHARGE, confirmed_at trong ngày Việt Nam; không khoản hoàn. |
| `rentals.activeContracts` | CONTRACT_VIEW; root active=true, status ACTIVE; không appendix, vẫn gồm root DEPOSIT và legacy kind null. |
| `membership.revenueToday,revenueMonth,daily7d` | STATISTICS_VIEW; PAID theo paid_at, PLAN+ADDON mọi provider, VND đã lưu; hôm nay/tháng tới hết ngày BillingTime, 7 điểm liên tục. Không suy ngày từ created_at khi paid_at null. |
| `system` | SYSTEM_HEALTH_VIEW; `{level,reasons}` lấy cùng snapshot/cache system-health, không gọi probe khác. |
| `sources,degraded` | Map tên nguồn → `{available,source,limit}`; danh sách tên truy vấn lỗi/thiếu schema, không SQL/raw exception. Thiếu quyền không chạy query và không phải lỗi degraded. |

### DTO health chính xác

`AdminDashboardResponse.Health(generatedAt,level,reasons,services,outbox,jobs,signing,blockchain,payments,kyc,complaints,responseSla,dataQuality,gateway,sources,degraded)`. Các group thiếu quyền là null. Mức tổng ưu tiên ERROR, WARN, UNKNOWN, OK; nguồn chưa đo được không làm tổng thành OK giả. `reasons` chỉ key/mức, không thông tin người dùng hay thông báo lỗi thô.

| Group | Permission; response và giới hạn |
|---|---|
| `services` | SYSTEM_HEALTH_VIEW; danh sách 6 service kỳ vọng gateway/core/chat/news/notification/ai: `{name,status,instances,lastRenewalAgeSec,registeredAt}`. Adapter trong assembly chỉ đọc Eureka client cache, không HTTP/probe vào dịch vụ; vắng → DOWN, lease >90s hoặc instance không UP → DEGRADED/DOWN, thiếu lease → UNKNOWN. Chỉ inspect tối đa 100 instance/service; vượt → UNKNOWN và giữ count thực. Không URI/IP/metadata. Client vắng/lỗi → group null, DISCOVERY_UNAVAILABLE. Đây là registration, không phải readiness Mongo/Kafka. |
| `outbox` | SYSTEM_HEALTH_VIEW; 8 row `{table,pending,retrying,dead,oldestAgeSec,lastSentAgeSec,frozen,level}`. pending sent_at null (và failed_at null nếu có). retrying/dead null khi chưa có attempts/failed_at, có source capability riêng; **không thêm các cột này**. Không hàng pending → oldestAgeSec=0; chưa từng gửi → lastSentAgeSec=null. frozen khi pending>0, cũ nhất>300s và chưa gửi lần nào hoặc lần gửi cuối>300s; WARN >=60s, ERROR >300s/dead>0/frozen. Broadcast có thêm `startedBroadcasts` (số pending row có cursor), không số người đã nhận: recipient_cursor là **user ID**, tuyệt đối không trả cursor hoặc payload. |
| `jobs` | SYSTEM_HEALTH_VIEW; `{recorded,overdueWork}`. recorded=null + source unavailable nếu scheduled_job_runs chưa có; API không tạo bảng/aspect/ghi runs. Nếu bảng đã có đúng cột: tối đa 200 `{job,lastSuccessAgeSec,consecutiveFailures,lastErrorClass,overdue}`; chỉ class name đúng regex, không raw error; overdue >3×expected_interval_ms+60s, failure>=3 ERROR. |
| `jobs.overdueWork` | Mảng cố định `{key,count,oldestAgeSec}` cho listing_expiry, rental_hold_expiry, appointment_expiry, monthly_invoice_overdue, renewal_response, renewal_signing, amendment_response, amendment_signing, tenant_signing, closure_payment, document_generation; query lỗi → count/tuổi null cùng source degraded. Count=0 → tuổi 0. Đây là ứng viên quá hạn trong DB, không bằng chứng job đã chạy/thất bại. |
| `signing` | SYSTEM_HEALTH_VIEW; `{stuck,failed24h,unmeasuredFailures,oldestOpenAgeSec,level}`. TTL CREATED/PENDING_USER_CONFIRMATION theo expires_at; PROVIDER_SIGNED/EMBEDDING quá 5 phút theo processing_started_at hoặc initiated_at/created_at. FAILED/EXPIRED chỉ vào failed24h nếu có failed_at trong 24h; legacy thiếu timestamp vào unmeasuredFailures, không dùng updated_at; chỉ có mẫu chưa đo được thì level UNKNOWN. failed24h>0 ERROR, stuck>0 WARN. Không SmartCA call. |
| `blockchain` | SYSTEM_HEALTH_VIEW; `{pending,submitted,failed,stuck,integrityMismatch,openIncidents,level}` từ proof đã lưu, PENDING/SUBMITTED>15 phút, document MISMATCH/MISSING đã lưu và incident BLOCKCHAIN open. Không RPC/verify tài liệu hoặc sửa P6. |
| `payments` | PAYMENT_VIEW; `{membershipOrdersStuck,legacyReview,failed24h,ethUnsettled,overpaymentsAwaitingRefund,oldestOverpaymentAgeSec,receiptPdfFailed,receiptPdfOverdue,autoConfirmOverdue,storageStuck,level}`. PENDING order quá expires_at; REVIEW legacy; failed24h là **creation cohort** (không có failure timestamp). ETH có tx mà order chưa PAID, không gọi chain; overpayment REFUND_PENDING chỉ count/tuổi, không tiền theo người; PDF cả hai bảng FAILED/PENDING>5 phút theo issued_at; TRANSFER_REPORTED qua confirmation_due_at so max(BillingTime,real); storage PENDING PAYMENT_PROOF/PAYMENT_RECEIPT>1h. PDF FAILED/auto deadline passed → ERROR; count khác>0 → WARN. Các deadline này là tín hiệu kiểm tra, không khẳng định đủ điều kiện tự xác nhận khi có tranh chấp. |
| `kyc` | KYC_VIEW; `{pending,reviewRequired,expired,oldestPendingAgeSec,retryNoticeOverdue,verifiedUsers,totalUsers,tempEvidenceOverdue,level}`. Current selection bên dưới; phiên VNPT pending/review hết expires_at chưa retry_notice_sent_at, và step VNPT tồn >1 ngày. Không dữ liệu sinh trắc học/raw reason. Tuổi>24h hoặc việc dọn/nhắc quá hạn → WARN; source dọn evidence lỗi mà chưa có cảnh báo khác → UNKNOWN. |
| `complaints` | COMPLAINT_VIEW; `{open,escalatedUnassigned,waitingAdmin,overdue,oldestOpenAgeSec,oldestUnassignedAgeSec,level}`; active RENTAL OPEN/ESCALATED, stage WAITING_ADMIN; health dùng giờ thật cho tuổi/SLA, overview dùng BillingTime cho respond_by. Chưa nhận>24h ERROR. |
| `responseSla` | STATISTICS_VIEW; `{rentalPendingOver24h,appointmentPendingOver24h,rentalPendingOver48h,appointmentPendingOver48h,respondedAtAvailable}`; active PENDING theo created_at và giờ thật, không suy response time từ updated_at. Flag kiểm cả hai cột responded_at phase1, không thêm/backfill cột. Actual percentile ở P5. |
| `dataQuality` | SYSTEM_HEALTH_VIEW; `{listingTypesWithoutTemplate,stuckPublicationReservations,nearbyJobsFailed,level}`. Template field cần thêm CONTRACT_TEMPLATE_VIEW: loại tin active PUBLISHED/RESERVED/RENTED không có SYSTEM LEASE ACTIVE + version PUBLISHED active. reservation khác null ngoài PENDING_REVIEW. Nearby field cần thêm NEARBY_SCAN_VIEW: FAILED/RETRY_WAIT hoặc last_error_code ATTEMPTS_EXHAUSTED (không phải status). User-sync thất bại không có counter, source unavailable, không số 0 giả. |
| `gateway` | null; chưa có nguồn metrics được bảo vệ/cho phép đọc qua Core; không đổi actuator/IP rate limit. |
| `sources` ngoài Core | notificationDelivery và chatAndAiMetrics unavailable ở Core; frontend gọi notification delivery-health riêng do P6 sở hữu; không gọi/sửa repo ngoài Core. Không suy enabled từ cài đặt không đọc được. |

Các ứng viên quá hạn: listing PUBLISHED quá expires_at 2 phút; lịch PENDING/CONFIRMED qua appointment_date+end_time Việt Nam 20 phút; invoice chỉ UNPAID quá due_at 5 phút, không đếm DRAFT/đã OVERDUE; renewal REQUESTED theo respond_by, APPROVED theo sign_by; amendment REQUESTED/CHANGES_REQUESTED/TENANT_REQUESTED/TENANT_ACCEPTED theo respond_by, APPROVED theo sign_by (2 phút grace); tenant-signing (2 phút); closure REFUND_PENDING/BALANCE_PENDING qua refund_due_at; document FAILED/STALE hoặc GENERATING>10 phút. Rental hold chỉ ACCEPTED quá hold_expires_at 2 phút và listing còn RESERVED (kể cả operational status khi pending review), loại bảo vệ theo contract đang review/ký/ACTIVE, dispute active_key khác null, hoặc INITIAL/RESERVATION_DEPOSIT CONFIRMED/TRANSFER_REPORTED/REJECTED/AWAITING_TRANSFER còn hạn hoặc không có due_at. Không viết lại các state machine.

Clock: creation/revenue của overview theo ngày BillingTime Việt Nam; **hạn nghiệp vụ** (hold/invoice/renewal/amendment/tenant-signing/closure) so BillingTime, kể cả bảng ứng viên job; lease Eureka/outbox/SmartCA/KYC/PDF/evidence/lịch xem dùng giờ thật. Source limit nêu clock của từng hàng. Thiếu kỳ hóa đơn tiếp theo, lease end, branch digest, user-sync counters, job recording và metrics dịch vụ → explicit unavailable; không ước từ snapshot JSON hoặc updated_at. Invoice/closure candidate không chứng minh scheduler thất bại vì còn chốt nghiệp vụ/thanh toán/tranh chấp.

### KYC list/counts và quy tắc hiện tại

User module giữ `AdminKycQueueService` và `Query/Page/Item` DTO, assembly giữ controller. Một window SQL chọn attempt mỗi user active (attempt active), ưu tiên VERIFIED, verified_at DESC (NULLS FIRST như PostgreSQL/JPA hiện tại), rồi created_at DESC và id để tie ổn định. Nếu không có VERIFIED, lấy attempt mới nhất; VNPT pending/review expires_at<=giờ thật → EXPIRED; pending/review provider legacy khác VNPT → EXPIRED. **VERIFIED không hết hạn theo expires_at của phiên**, đúng getCurrentStatus đang chạy; không áp câu chữ “verified chưa hết hạn” cũ của §3 để đổi nghiệp vụ. User chưa có attempt → NOT_VERIFIED, provider VNPT, hai timestamp null. Không có KYC gate/provider call hay cập nhật trạng thái.

- List response: `Page{items:[Item{userId,status,provider,createdAt,expiresAt,rejectionCategory}],total,page,size}`; userId là ID drilldown duy nhất được phép trong **list có KYC_VIEW**, không có ở overview/health/counts. Sắp createdAt DESC NULLS LAST rồi userId, pagination 0-based. Không tên/email/CCCD/token/session ID/rejection_reason.
- Count response: `[{status,provider,rejectionCategory,count}]`, cùng tập current và filter, nhóm ba chiều. Category chỉ DUPLICATE_ID/DOCUMENT_OR_FACE/OTHER cho REJECTED, còn lại null; chỉ hai chuỗi cố định từ VnptKycStore được map cụ thể, mọi reason lạ → OTHER.
- `overdue=true`: current attempt stored PENDING/REVIEW đã hết expires_at hoặc effective pending/review đã tạo >24h; `false` là phần bù, kể cả user chưa có attempt. Không lọc theo chuỗi lý do thô, không nhận arbitrary SQL/sort.
- Với role USER active=false, không đưa vào queue/current KYC aggregates; disabled được đếm riêng trong overview khi có USER_VIEW. Thiếu timestamps legacy không được bịa ngày/tuổi; không thay KycServiceImpl hoặc contract gate.

### Vận hành và giới hạn kiểm chứng

- Chỉ đọc, transaction repeatable-read; mỗi nguồn aggregate dùng JDBC timeout 2s và savepoint để lỗi PostgreSQL không làm mất nhóm sau. Metadata cột đọc batch một lần; 8 bảng outbox và danh sách công việc là tập cố định, không N+1 theo bản ghi. KYC list là count + một page query.
- Cache overview 15s/health 30s, key gồm tập authority và ngày BillingTime, tối đa 32 snapshot mỗi loại. Quyền kiểm trước cache; snapshot lỗi degraded không cache. `fresh=true` được force tối đa mỗi 10s overview/30s health/người; yêu cầu fresh quá nhanh dùng snapshot cache, không 429; bản đồ throttle tối đa 256 key. `system` reuse health snapshot, nên có thể cũ tối đa 30s.
- `AdminDashboardIndexMigration`: 10 idempotent index, chia sẻ bốn tên idx_stats_* đã có ở P5 để tránh tạo trùng; thêm sáu idx_admin_* cho membership expiry, signature age, current KYC, KYC step age, renewal respond_by và invoice due. Bảng chưa tồn tại được bỏ qua. Không thêm cột attempts/dead/job runs, không sửa monitor, outbox claim, gateway, config/env hoặc dịch vụ ngoài Core.
- `Permissions.java` thêm đúng STATISTICS_VIEW, SYSTEM_HEALTH_VIEW, PAYMENT_VIEW, BLOCKCHAIN_VIEW, BLOCKCHAIN_MANAGE vào CATALOG; KYC_VIEW dùng lại. Không đổi USER_SCOPE/quyền cũ; PermissionDataInitializer hiện hữu cấp toàn catalog cho ADMIN, đã có test xác nhận giữ legacy rows và không cấp admin scope cho USER.
- Chưa đo cold latency 150/500ms trên dữ liệu production hoặc lock khi tạo index lớn; cache chỉ local instance, không phải phân tán. Chưa thử provider thật, live Eureka, frontend/mobile, production hoặc full suite. Các frontend phase3 phải đọc source capabilities và null/UNKNOWN, không hiển thị “OK” khi source chưa đo được.

### Kiểm thử đã chạy (L3)

- `mvnw.cmd -Pisolated-build -pl hs-api-service -am "-Dtest=AdminDashboard*Test,AdminDiscoverySnapshotTest,AdminKycQueue*Test,PermissionDataInitializerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` → **BUILD SUCCESS**, 11 test tại thời điểm chạy: catalog 1, initializer 3, KYC query parse 1, Eureka 2, HTTP 4; mọi module upstream và assembly compile. Dùng lại các kết quả không đổi này, không gọi đó là full suite. Lỗi compile đầu tiên do tên accessor Eureka đã được sửa theo thư viện thực tế trước lượt thành công.
- PostgreSQL **16.16** trong container riêng `hs-p4-admin-3115d9b5`, random loopback port **53236**, database `hs_admin_test`, biến test `HS_P4_TEST_PG_URL=jdbc:postgresql://127.0.0.1:53236/hs_admin_test`; schema do Hibernate tạo thật. Không đọc .env hoặc truy cập DB/service/provider hiện hữu. Container/volume của worker được xóa sau kiểm tra.
- `mvnw.cmd -Pisolated-build -pl hs-api-service -am "-Dtest=AdminDashboardPostgresTest,AdminKycQueueQueryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`: KYC parse 1 PASS; 11/12 PostgreSQL case PASS, một fixture hold/dispute sai vì rental_request_id immutable sau insert. Đã sửa riêng fixture và chạy `mvnw.cmd -Pisolated-build -pl hs-api-service -am "-Dtest=AdminDashboardPostgresTest#operationalAndBusinessBacklogsUseCorrectClockAndGrace" "-Dsurefire.failIfNoSpecifiedTests=false" test` → **BUILD SUCCESS, 1 test, 0 failure/error/skipped**; giữ 11 PostgreSQL kết quả khác vì input không đổi. Tổng evidence **23 test phân biệt PASS**, gồm **12 PostgreSQL**, không cộng trùng lần chạy lại.
- PostgreSQL case phủ: tất cả query trên dữ liệu trống/hỗn hợp; ngày Việt Nam hôm qua/00:00/23:30; ADMIN/USER/inactive và tiền PAID/CONFIRMED/refund; verified cũ so pending mới và legacy verified_at null so JPQL gốc; expired VNPT/legacy session, chưa có attempt, category thô lạ bị map OTHER; list/count/filter/pagination qua HTTP; ADMIN chỉ LISTING_VIEW/PAYMENT_VIEW/STATISTICS_VIEW không lộ cache privileged; tám outbox legacy thiếu attempts/failed_at, frozen khi chưa từng gửi, optional retry/dead columns nếu có; scheduled_job_runs vắng và bảng nguồn có sẵn trong test, raw error không trả; business deadlines/grace và hold được payment/contract/dispute bảo vệ; SmartCA, proof/integrity, ETH chưa settle, overpayment, hai nguồn receipt PDF, storage, evidence quá hạn, nearby/template/publication; source mất bảng/cột và discovery lỗi không hỏng nhóm khác; timeout thật 2s và query sau savepoint; cache/fresh throttle và index idempotent. Không thực thi provider/probe thật hoặc sửa scheduler.
- Log cục bộ không commit: `hs-api-service/target/p4-tests.log`, `p4-pg-final.log`, `p4-pg-boundary.log`; surefire reports tại `target/isolated/surefire-reports` của từng module. Bản ghi boundary cuối chỉ có 1 case vì rerun đúng case đã fail; 11 case khác nằm trong log lượt 12 case trước đó.
