# P5 · Thống kê (admin) — `hs-admin-portal` `/analytics/statistics`

File frontend khi kiểm kê: `src/pages/analytics/StatisticsPage.tsx` (7 dòng, trang trắng). Route có sẵn (`router/index.tsx`, trong `<Route path="analytics">`), chưa gắn quyền; sidebar "Báo cáo & Phân tích → Thống kê số liệu" (`layouts/Sidebar.tsx:90`). Trạng thái: **THIẾT KẾ ĐÃ DUYỆT; API phase 2 triển khai tại §10; frontend phase 3**. Không làm "Dự báo AI" (trang `ai-forecast` giữ nguyên).

## 0. Thay đổi sau kiểm kê
- Thêm 5 khối xu hướng: (10) sự cố vận hành + thời gian khắc phục, (11) hiệu suất kiểm duyệt tin, (12) yêu cầu thuê & lịch hẹn + SLA phản hồi, (13) gói hội viên đang hiệu lực, (14) hóa đơn tháng; mở rộng (2), (4), (5b), (7), (8), (9) như bảng §4.
- Quyền: dùng danh sách duy nhất ở **P4 §6** (`STATISTICS_VIEW`; khối 5b cần thêm `PAYMENT_VIEW`; khối 10 cần thêm `INCIDENT_VIEW`; thiếu quyền phụ → khối `null`, không 403).
- SLA phản hồi (khối 12) **chưa đo được** cho tới khi có cột `responded_at` (P4 §5.4b); số liệu chỉ tính từ ngày bật cột.
- Mục "Không đưa vào (lý do)" ở §9. Sức khoẻ/hàng đợi hiện tại không ở trang này mà ở P4 §4.

## 1. Mục đích & người xem
- Người xem: quản trị/vận hành có quyền `STATISTICS_VIEW` (mới). Câu hỏi: "Nền tảng đang tăng hay giảm so với kỳ trước, ở khâu nào phễu bị rớt (đăng tin → duyệt → hợp đồng → thanh toán), và tiền gói hội viên đến từ đâu?"
- 10 giây: nhìn hàng 4 thẻ KPI có mũi tên so với kỳ liền trước; cuộn xuống xem biểu đồ chi tiết. Khác P4: P4 là "hôm nay cần làm gì", P5 là xu hướng trong khoảng thời gian chọn.

## 2. Bố cục (desktop)
```
+--------------------------------------------------------------------------+
| Thống kê số liệu        [Từ 01/11/2026][Đến 28/11/2026] [Ngày|Tháng]     |
|                         Nhanh: 7 ngày | 30 ngày | Tháng này | 12 tháng    |
|                                              [Xuất CSV]  [Tải lại]       |
+--------------------------------------------------------------------------+
| KPI: [Người dùng mới 412 ▲8%] [Tin gửi duyệt 230 ▼3%]                    |
|      [HĐ đã ký 61 ▲12%] [GMV tiền thuê 1,82 tỷ ▲5%] [Doanh thu HV 12,3tr]|
+--------------------------------------+-----------------------------------+
| (1) Người dùng & chủ nhà mới theo kỳ | (2) Tin đăng theo trạng thái      |
|     đường 2 series                   |     thanh ngang (10 trạng thái)   |
+--------------------------------------+-----------------------------------+
| (3) Tin đăng theo tỉnh (top 10)      | (4) Hợp đồng theo trạng thái      |
|     thanh ngang                      |     thanh ngang                   |
+--------------------------------------+-----------------------------------+
| (5) GMV tiền thuê đã xác nhận theo kỳ (cột, tách theo loại khoản)         |
+--------------------------------------+-----------------------------------+
| (6) Doanh thu hội viên theo gói      | (7) Doanh thu hội viên theo cổng  |
|     cột chồng theo kỳ                |     bảng + thanh                  |
+--------------------------------------+-----------------------------------+
| (8) KYC theo nhà cung cấp (bảng)     | (9) Tỉ lệ chuyển đổi (5 thanh %)  |
+--------------------------------------+-----------------------------------+
| (5b) Tự xác nhận & từ chối khoản     | (10) Sự cố vận hành + MTTR        |
| (11) Hiệu suất kiểm duyệt tin        | (12) Yêu cầu thuê & lịch hẹn, SLA |
| (13) Gói hội viên đang hiệu lực      | (14) Hóa đơn tháng                |
+--------------------------------------+-----------------------------------+
```
Màn nhỏ: bộ lọc xếp dọc, KPI lưới 2 cột, mỗi biểu đồ một hàng đủ rộng, bảng cuộn ngang trong khung riêng; nút "Xuất CSV" nằm trong thanh bộ lọc.

## 3. Bộ lọc & định nghĩa chung
- `from`, `to` (ngày, bao gồm cả hai đầu, theo `Asia/Ho_Chi_Minh`, mặc định 30 ngày gần nhất kết thúc `BillingTime.today()`, `BillingTime.java:54`); `granularity = DAY | MONTH`. Giới hạn: `DAY` tối đa 92 ngày, `MONTH` tối đa 36 tháng; vượt → 400 và UI tự chuyển sang MONTH. `to` không được ở tương lai; `from <= to`.
- Kỳ trước (để tính ▲▼): cùng độ dài, liền trước `from`. Nếu kỳ trước bằng 0 → hiển thị "mới" thay vì %.
- Bucket: `date_trunc('day'|'month', <cột thời gian> AT TIME ZONE 'Asia/Ho_Chi_Minh')`; bucket không có dữ liệu vẫn trả giá trị 0 (dùng `generate_series`) để trục thời gian liên tục.
- Mọi số là **tổng hợp toàn hệ thống**; không có chiều theo từng người dùng/chủ nhà, không có danh sách. Nhóm có ít hơn 5 bản ghi vẫn hiển thị (không phải dữ liệu cá nhân vì không có định danh), nhưng không bao giờ trả `owner_id`, `payer_id`, tên, email, ví.

## 4. Từng khối (nguồn đều là NEW endpoint mục 5; không endpoint admin hiện có trả dữ liệu theo thời gian)
Hiện có chỉ `GET /api/v1/admin/listings/counts` (`ListingAdminController.java:44`, đếm theo trạng thái, không theo thời gian), `GET /admin/users` phân trang (`UserAdminController.java:51-58`) và `GET /admin/contracts` (`ContractAdminController.java:197`) — đều không gộp theo kỳ; tính phía client sẽ phải tải mọi bản ghi và lộ dữ liệu → không dùng.

| # | Khối | Công thức | Bảng/cột | Bấm vào |
|---|---|---|---|---|
| KPI | 5 thẻ | Tổng của kỳ cho: người dùng mới (1), tin gửi duyệt (2), HĐ đã ký (4), GMV (5), doanh thu hội viên (6) + % so kỳ trước | như bên dưới | cuộn tới biểu đồ tương ứng |
| 1 | Người dùng & chủ nhà mới | **Người dùng**: `COUNT(users)` theo `created_at`, loại tài khoản ADMIN (`User.java:24`, `role_id`). **Chủ nhà**: hệ thống không có vai trò "chủ nhà" riêng (role chỉ ADMIN/USER, `Permissions.java:17-18`); định nghĩa = người dùng có tin đăng đầu tiên trong kỳ: `COUNT(DISTINCT owner_id)` trong đó `MIN(listings.created_at)` của `owner_id` nằm trong bucket (`Listing.java:30`). Đường 2 series. | `users`, `listings` | `/users` |
| 2 | Tin đăng theo trạng thái | Tin có `submitted_at` trong kỳ (đã gửi duyệt; loại `DRAFT` thuần chưa gửi), nhóm theo `status` hiện tại (10 giá trị `ListingStatus`: DRAFT, PENDING_REVIEW, PUBLISHED, RESERVED, RENTED, RENTED_EXTERNALLY, EXPIRED, REJECTED, HIDDEN, VIOLATION; `hs-listing-service/.../ListingStatus.java:3-14`). Kèm số tin gửi mới theo bucket (cột). `active = true`. Thêm hai số nguồn cung: tin `PUBLISHED` sẽ hết hạn trong 7 ngày (`expires_at`) và tin `PUBLISHED` đã quá `expires_at` chưa được quét (cùng truy vấn P4 §4.1.3b). | `listings.status`, `submitted_at`, `expires_at` (index `idx_listings_status_submitted`, `Listing.java:16`) | `/properties/all` |
| 3 | Tin đăng theo tỉnh | Cùng tập tin như (2) nhưng chỉ `status IN (PUBLISHED, RESERVED, RENTED)` ("đang/đã hoạt động"), nhóm theo `listing_addresses.city` (cột `city_id`, `city`; `AddressFields.java:19-20`; 1-1 với tin qua `listing_id` unique). Top 10, phần còn lại gộp "Khác". Tin chưa có địa chỉ → "Chưa rõ". | `listing_addresses`⋈`listings` | `/properties/all` |
| 4 | Hợp đồng theo trạng thái | Hợp đồng có `created_at` trong kỳ, nhóm theo `contracts.status` (`ContractStatus`: DRAFT, PENDING_REVIEW, LANDLORD_SIGNATURE_PENDING, TENANT_SIGNATURE_PENDING, ACTIVE, EXPIRED, CLOSED, TERMINATED; `Contract.java:63`). Cộng số HĐ đã ký đủ trong bucket theo `signed_at` (`Contract.java:83`). Chỉ tính HĐ gốc `parent_contract_id IS NULL` (loại phụ lục/gia hạn để không đếm trùng một quan hệ thuê). Thêm: tách theo `agreement_kind` (HĐ cọc vs HĐ thuê) và số chấm dứt trong bucket theo các cột `termination_*_at` (`Contract.java:104-114`: sớm/đơn phương/đồng thuận), kèm tỉ lệ bị từ chối. | `contracts` | `/contracts/all` |
| 5 | GMV tiền thuê đã xác nhận | `SUM(total_amount)` của `payment_requests` có `status=CONFIRMED`, `direction=TENANT_TO_LANDLORD`, `type IN (INITIAL, RESERVATION_DEPOSIT, MONTHLY_RENT, UTILITY, OTHER_CHARGE)`, theo bucket `confirmed_at` (`PaymentRequest.java:63-78,126-127`). Tách cột chồng theo `type`. Loại các khoản hoàn (`DEPOSIT_REFUND`, `DEPOSIT_ADJUSTMENT`, `INITIAL_PAYMENT_REFUND`). Đơn vị VND (`currency='VND'`). Chỉ số tổng và số giao dịch; **không** có người nộp/người nhận, mã chuyển khoản, tham chiếu ngân hàng. | `payment_requests` | không (tổng hợp) |
| 5b | Tự xác nhận & từ chối khoản (cần thêm `PAYMENT_VIEW`) | (a) số khoản bị **hệ thống tự xác nhận** theo bucket: `payment_events` `event_type='RECEIPT_CONFIRMED'` và `actor_id='SYSTEM'`, tách thuê/hoàn theo `payment_requests.direction`; **không** so khớp chuỗi lý do (dễ vỡ khi đổi câu chữ, `PaymentRequestService.java:588,637`) — khuyến nghị thêm cờ `auto_confirmed` hoặc `PaymentEventType` riêng; (b) tỉ lệ từ chối chung = số `RECEIPT_REJECTED` / số khoản `TRANSFER_REPORTED` trong kỳ (cohort, "x / y"); **không** theo chủ nhà. | `payment_events`, `payment_requests` | không |
| 6 | Doanh thu hội viên theo gói | `SUM(amount_vnd)` của `membership_orders` `status=PAID`, `paid_at` trong kỳ, cột chồng theo `tier` (FREE/STARTER/PRO/BUSINESS; `MembershipTier.java:4`) cho `kind=PLAN` và một nhóm "Mua thêm lượt" cho `kind=ADDON` (`MembershipOrder.java:19-22`). Nguồn là `membership_orders`, **không** phải `membership_receipts` (bảng này không có cột tiền, chỉ `snapshot_json`; xem P4 khối D). | `membership_orders` | không |
| 7 | Doanh thu hội viên theo cổng | Như (6) nhưng nhóm theo `provider` (`SEPAY`, `ETH`, `VNPAY` lịch sử; `MembershipOrder.java:45`): số đơn, tổng VND, tỉ trọng %. Đơn ETH chỉ cộng VND quy đổi, không hiện giá trị ETH/ví. Thêm cột **checkout → PAID** theo cổng: trong số đơn tạo trong kỳ (`created_at`, mọi trạng thái), % `PAID` (cohort, "x / y"); đơn `PENDING` quá `expires_at` tính là chưa thành công (không có job hết hạn). | `membership_orders` | không |
| 8 | KYC theo nhà cung cấp | Bản ghi `kyc_verifications` tạo trong kỳ, nhóm `provider` (`VNPT`, `DIDIT` lịch sử, `ADMIN` nhập tay; `KycProvider.java`) × `status` (NOT_VERIFIED… EXPIRED; `KycStatus.java`). Cột: tổng, VERIFIED, REJECTED, đang chờ, tỉ lệ thành công = VERIFIED/(VERIFIED+REJECTED) (loại đang chờ khỏi mẫu số). Thêm: (i) **lý do từ chối theo loại** từ `rejection_reason` (hai chuỗi cố định, `VnptKycStore.java:128,130`): "CCCD đã dùng ở tài khoản khác" (nghi trùng/gian lận) vs "giấy tờ/khuôn mặt không đạt"; chuỗi lạ → "Khác"; không bao giờ trả chuỗi thô; (ii) **KYC nhập tay bởi admin theo bucket** (`provider='ADMIN'`, `verified_at`) — chỉ số lượng, "ai làm" xem §9. | `kyc_verifications` (`KycVerification.java:23-40`) | `/users` |
| 9 | Tỉ lệ chuyển đổi (theo nhóm đối tượng tạo trong kỳ, không phải tỉ lệ hai tổng khác nhau) | (a0) **Đăng ký → xong onboarding**: trong số USER tạo trong kỳ, % có `on_boarded=true` (bước bị bỏ qua ở bản đầu). (a) **Đăng ký → KYC**: trong số người dùng USER tạo trong kỳ, % có ít nhất một `kyc_verifications.status=VERIFIED`. (b) **Tin gửi duyệt → được đăng**: trong số tin `submitted_at` trong kỳ, % có `published_at IS NOT NULL` (`Listing.java:68`). (c) **HĐ tạo → ký đủ**: trong số HĐ gốc tạo trong kỳ, % có `signed_at IS NOT NULL`. (d) **HĐ ký đủ → có thanh toán xác nhận**: trong số HĐ gốc ký trong kỳ, % có ít nhất một `payment_requests` `CONFIRMED` loại `INITIAL` (`contract_id` có index `idx_payment_request_contract`, `PaymentRequest.java:20`). Mỗi thanh hiển thị "x / y (z%)"; nhóm mẫu số = 0 → "—". | nhiều bảng | không |
| 10 | Sự cố vận hành (cần thêm `INCIDENT_VIEW`) | `operational_incidents` mở trong kỳ (`opened_at`): số theo `component` (mở rộng ở P4 §5.5), **thời gian khắc phục** trung vị/p90 = `percentile_cont(resolved_at - opened_at)` của sự cố đã đóng, số sự cố còn mở, thành phần lặp (≥2 sự cố/kỳ). Không trả `sourceKey`/`resourceId`. | `operational_incidents` | `/operations/system-alerts` |
| 11 | Hiệu suất kiểm duyệt tin | Chuyển trạng thái từ `PENDING_REVIEW` sang `PUBLISHED`/`REJECTED`/`VIOLATION` trong bucket (`listing_status_history`, `ListingStatusHistory.java:12-47`; chỉ lấy chuyển do admin, `changed_by_type`) và **thời gian duyệt** trung vị/p90 = thời điểm chuyển − `submitted_at`; tỉ lệ duyệt/từ chối/vi phạm. **Không theo người duyệt** (§9). Tên cột thời điểm chuyển khớp entity khi code. | `listing_status_history`, `listings` | `/properties/all` |
| 12 | Yêu cầu thuê & lịch hẹn | `rental_requests` tạo trong kỳ nhóm theo `status` (ACCEPTED, REJECTED, EXPIRED, CANCELLED_BY_SYSTEM…) và tỉ lệ chấp nhận (cohort "x / y"); `viewing_appointments` nhóm theo `status` — `EXPIRED` = chủ nhà bỏ qua; `COMPLETED` do scheduler tự đặt cho mọi hẹn `CONFIRMED` đã qua (`ViewingAppointmentService.java:649-661`) nên nhãn là "đã qua lịch", **không** là "khách đã đến". **SLA phản hồi** = trung vị/p90 `responded_at - created_at` chỉ cho dòng có `responded_at`; khi chưa có cột trả `{"available":false}` và UI ghi "chưa đo được" (P4 §5.4b). | `rental_requests`, `viewing_appointments` | không |
| 13 | Gói hội viên đang hiệu lực (ảnh chụp hiện tại, không theo kỳ) | `membership_periods` còn hiệu lực (`end_at > now()`; `MembershipPeriod.java:28-32`): số theo `tier`, số **hết lượt** (`quota - used - reserved <= 0`), số **hết hạn trong 7 ngày**. Không trả chủ nhà. Tỉ lệ nâng cấp/rời bỏ → §9. | `membership_periods` | không |
| 14 | Hóa đơn tháng | `monthly_invoices` có `due_at` trong kỳ nhóm theo `status` (PAID, OVERDUE, ROLLED_OVER, DRAFT…; `idx_monthly_invoice_due`), số `DRAFT` đã quá `due_at` (không tự phát hành), tổng phí chậm (tên cột khớp `MonthlyInvoice.java` khi code). Chỉ số tổng, không theo hợp đồng/người. | `monthly_invoices` | không |

Trạng thái: đang tải = skeleton theo từng thẻ/biểu đồ; trống (mọi series bằng 0) = khung biểu đồ kèm "Không có dữ liệu trong khoảng này"; lỗi = banner có nút thử lại, giữ nguyên bộ lọc; khoảng không hợp lệ = lỗi tại ô nhập, không gọi API.

## 5. API mới tổng hợp & biểu đồ
`GET /api/v1/admin/statistics?from=2026-11-01&to=2026-11-28&granularity=DAY` — `AdminStatisticsController` (cùng package với `ComplaintAdminController`, mẫu `@RequestMapping({"/admin/statistics","/api/v1/admin/statistics"})`).
- Auth: `@PreAuthorize("hasAuthority('ADMIN') and hasAuthority('STATISTICS_VIEW')")` (cùng mẫu `OperationalIncidentController.java:12`); khối 5b cần thêm `PAYMENT_VIEW`, khối 10 cần thêm `INCIDENT_VIEW` (kiểm trong service, thiếu → khối `null`). Quyền mới định nghĩa **một chỗ duy nhất ở P4 §6** (thêm vào `Permissions.java` + `CATALOG`; ADMIN tự nhận ở `PermissionDataInitializer.java:55`; phản chiếu ở `config/permissions.ts` và thêm `"/analytics/statistics": { permission: PERMISSIONS.STATISTICS_VIEW }` vào `ROUTE_PERMISSIONS`; router bọc bằng `guard("/analytics/statistics", …)`; sidebar tự ẩn theo `useCanAccessPath`). Không nhận userId từ tham số.
- Tham số kiểm ở `@Validated`: `from`/`to` `LocalDate`, `granularity` enum; vi phạm giới hạn → `AppException INVALID_REQUEST`.
- Response:
```json
{ "code":1000, "result": {
  "range":{"from":"2026-11-01","to":"2026-11-28","granularity":"DAY","timezone":"Asia/Ho_Chi_Minh"},
  "kpi":{ "newUsers":{"value":412,"previous":381}, "submittedListings":{...}, "signedContracts":{...},
          "gmvVnd":{"value":1820000000,"previous":1733000000}, "membershipRevenueVnd":{...} },
  "series":{ "buckets":["2026-11-01","2026-11-02"],
    "newUsers":[12,9], "newLandlords":[3,2], "submittedListings":[8,6], "signedContracts":[2,3],
    "kycAdminManual":[0,1], "autoConfirmedRent":[0,2], "autoConfirmedRefund":[0,0],
    "gmvByType":{"INITIAL":[..],"MONTHLY_RENT":[..],"UTILITY":[..],"OTHER_CHARGE":[..],"RESERVATION_DEPOSIT":[..]},
    "membershipByTier":{"STARTER":[..],"PRO":[..],"BUSINESS":[..],"ADDON":[..]} },
  "breakdown":{ "listingsByStatus":{"PUBLISHED":120,"REJECTED":7},
                "listingsByCity":[{"city":"Hà Nội","count":88},{"city":"Khác","count":31}],
                "contractsByStatus":{"ACTIVE":40,"DRAFT":9},
                "membershipByProvider":[{"provider":"SEPAY","orders":31,"amountVnd":9100000}],
                "kycByProvider":[{"provider":"VNPT","total":210,"verified":150,"rejected":20,"pending":40}],
                "kycRejectByCategory":{"DUPLICATE_ID":4,"DOCUMENT_OR_FACE":14,"OTHER":2},
                "listingSupply":{"expiringIn7d":18,"overdueUnswept":0},
                "contractsByKind":{"DEPOSIT":20,"LEASE":41}, "terminations":{"early":2,"unilateral":1,"mutual":3},
                "paymentEvents":{"rejectRate":{"num":3,"den":120}},          // null nếu thiếu PAYMENT_VIEW
                "membershipCheckout":[{"provider":"SEPAY","paid":31,"created":40}],
                "moderation":{"approved":[..],"rejected":[..],"violation":[..],"reviewMedianSec":14400,"reviewP90Sec":90000},
                "rentalRequests":{"byStatus":{"ACCEPTED":30,"EXPIRED":9},"acceptRate":{"num":30,"den":70}},
                "appointments":{"byStatus":{"COMPLETED":50,"EXPIRED":12}},
                "responseSla":{"available":false},
                "membershipActive":{"byTier":{"STARTER":40,"PRO":12},"exhausted":5,"expiring7d":4},
                "invoices":{"byStatus":{"PAID":300,"OVERDUE":12},"draftOverdue":1,"lateFeeVnd":4200000},
                "incidents":{"byComponent":{"BLOCKCHAIN":1},"open":0,"mttrMedianSec":1800,"mttrP90Sec":7200,"repeatComponents":[]} },   // null nếu thiếu INCIDENT_VIEW
  "funnel":{ "registeredToOnboarded":{"num":300,"den":412}, "registeredToKyc":{"num":150,"den":412}, "submittedToPublished":{...}, "contractCreatedToSigned":{...}, "signedToPaid":{...} } } }
```
- Cài đặt: một `@Transactional(readOnly=true)` service, native SQL Postgres, mỗi khối một câu `GROUP BY` (≈26 câu, chạy tuần tự trong một transaction; không tải thực thể; trung vị/p90 bằng `percentile_cont`). Index mới cần: `users(created_at)`, `listings(created_at)`, `contracts(created_at)` (chưa có; `Contract.java:15-20` chỉ có theo `contract_number`, `landlord_id`…), `payment_requests(status, confirmed_at)`, `membership_orders(status, paid_at)`, `membership_orders(created_at)`, `kyc_verifications(created_at)`, `payment_events(event_type, created_at)`, `rental_requests(created_at)`, `viewing_appointments(created_at)`, `operational_incidents(opened_at)`, `monthly_invoices(due_at)` (đã có `idx_monthly_invoice_due(status,due_at)`).
- Cache: 60 giây theo `(from,to,granularity)`; kỳ có `to = hôm nay` TTL 60s, kỳ kết thúc trước hôm nay TTL 10 phút.
- **Biểu đồ:** `package.json` không có thư viện biểu đồ. Khuyến nghị mặc định (xem Câu hỏi 1): **thêm `recharts`** (đã quen với React 19, tree-shake, hỗ trợ cột chồng/đường/thanh ngang/tooltip/legend) vì trang này cần 9 biểu đồ có tooltip, chú giải và trục; viết tay SVG sẽ tốn và khó kiểm tra. Phương án thay thế không thêm phụ thuộc: tái dùng `MiniBars` của P4 cho cột/thanh, bỏ đường chuỗi (thay bằng cột) — kém hơn. Màu lấy từ token `--chart-1..5` (`index.css:85-89`), nhãn có chữ (không chỉ màu), trục Y bắt đầu từ 0, định dạng `vi-VN` (VND: `1,82 tỷ`, `12,3 tr`). Loại biểu đồ: (1) đường 2 series theo kỳ; (2)(3)(4) thanh ngang theo hạng mục; (5)(6) cột chồng theo kỳ; (7) bảng + thanh tỉ trọng; (8) bảng; (9) 4 thanh phần trăm.
- **Xuất CSV:** thực hiện ở client từ chính JSON đã tải (không thêm endpoint, nên quyền và số liệu đúng bằng màn hình). Một nút → một file `thong-ke_{from}_{to}_{granularity}.csv` UTF-8 có BOM (Excel đọc đúng tiếng Việt), mỗi khối một đoạn có dòng tiêu đề, tiền là số nguyên VND không dấu phân cách. Chống CSV injection: ô văn bản bắt đầu bằng `= + - @` thì thêm `'` đứng trước; tên tỉnh đến từ dữ liệu người dùng nên bắt buộc làm.

## 6. Hiệu năng & bảo mật
- Chi phí ở 10k tin / 100k thanh toán / 100k người dùng: các `GROUP BY` trên cột có index trong khoảng ≤ 92 ngày quét < 50k hàng/câu → mục tiêu tổng < 800 ms với ≈26 câu (cache 60 giây che phần còn lại; `statement_timeout` 5 giây mỗi câu, quá hạn → khối đó `null` + `degraded`); (9a)(9d) dùng `EXISTS`/`LEFT JOIN` theo tập kỳ, không tương quan từng hàng; (3) `JOIN` 1-1 qua `listing_id` unique. Tránh N+1: không dùng JPA entity. Kỳ 36 tháng ở MONTH chỉ quét thêm, vẫn có index; vượt giới hạn bị chặn ở validation (không để admin tự DoS bằng khoảng 10 năm).
- Rò rỉ: chỉ tổng hợp; không có `owner_id/payer_id/payee_id`, tên, email, CCCD (`user_kyc_details`), số tài khoản (`payer_bank_account_snapshot`), ví ETH (`membership_eth_quotes.payer_wallet`), tham chiếu giao dịch. Tên tỉnh hiển thị qua React text (không `dangerouslySetInnerHTML`).
- Quyền backend là nguồn đúng; ẩn route/sidebar ở frontend chỉ là tiện lợi. `Cache-Control: no-store` (số liệu tài chính).
- Đồng hồ: bucket theo giờ Việt Nam; khi `homespace.time.simulated-at` bật (chỉ dev) `to` mặc định theo `BillingTime.today()` — dữ liệu `created_at` vẫn là giờ thật nên số có thể lệch trong môi trường mô phỏng (giả định, chấp nhận).

## 7. Kiểm thử cần viết
Backend (`AdminStatisticsControllerTest` + `AdminStatisticsQueryTest` trên PostgreSQL container tạm):
1. 401 khi chưa đăng nhập; 403 khi ADMIN thiếu `STATISTICS_VIEW`; 403 khi USER; 400 khi `from > to`, `to` tương lai, DAY > 92 ngày, MONTH > 36 tháng, `granularity` sai.
2. Bucket liên tục: kỳ 3 ngày có dữ liệu ngày 1 và 3 → ngày 2 trả 0; mọi mảng `series` cùng độ dài `buckets`.
3. Ranh giới múi giờ: bản ghi 23:30 giờ Việt Nam (= 16:30 UTC) vào đúng ngày Việt Nam; 00:10 sang ngày kế.
4. GMV: `DEPOSIT_REFUND` và `status != CONFIRMED` không tính; `direction=LANDLORD_TO_TENANT` không tính; tổng bằng tổng các loại.
5. Hội viên: đơn `PENDING/EXPIRED/CANCELLED/FAILED` không tính; `provider` nhóm đúng; `ADDON` tách riêng.
6. Phễu: mẫu số 0 trả `{num:0, den:0}` (không chia cho 0); HĐ phụ lục (`parent_contract_id` khác null) không vào (4)/(9c).
7. Không rò rỉ: JSON response không chứa khoá `ownerId/payerId/email/...` (so khớp danh sách khoá cho phép); `kycRejectByCategory` không chứa chuỗi `rejection_reason` thô; sự cố không có `sourceKey`.
8. Quyền phụ: ADMIN chỉ có `STATISTICS_VIEW` → `paymentEvents` và `incidents` là `null`, phần còn lại có; có thêm `PAYMENT_VIEW`/`INCIDENT_VIEW` → hiện.
9. Khối mới: `RECEIPT_CONFIRMED` do `SYSTEM` tính tự xác nhận, do người thì không; tỉ lệ từ chối mẫu số 0 → `{num:0,den:0}`; kiểm duyệt: tin chuyển do hệ thống (hết hạn) không vào (11), trung vị/p90 đúng với 3 mẫu; lịch hẹn `COMPLETED` do scheduler vẫn hiện nhãn "đã qua lịch"; `responded_at` chưa có cột → `{"available":false}`, có cột nhưng dòng cũ null → không tính vào trung vị; gói hiệu lực: `end_at = now()` không tính, hết lượt đúng công thức; hóa đơn `DRAFT` quá hạn đếm đúng; sự cố đang mở không vào MTTR.
Frontend (`node --test`): hàm `buildCsv()` — BOM, escape dấu phẩy/ngoặc kép, chống injection (`=cmd` → `'=cmd`), tiền là số nguyên; `previousPeriod(from,to)`; `clampGranularity`; bộ lọc không hợp lệ không gọi API; thiếu `STATISTICS_VIEW` → route bị `RequirePermission` chặn.

## 8. Câu hỏi mở
1. Thêm `recharts` hay tự vẽ SVG? Mặc định: thêm `recharts` (một phụ thuộc, dùng cho cả P5); P4 vẫn dùng `MiniBars` thuần.
2. "GMV tiền thuê" có gồm tiền cọc giữ chỗ (`RESERVATION_DEPOSIT`) và khoản đầu kỳ `INITIAL` (có thể chứa tiền cọc) không? Mặc định: có (là tiền khách thực nộp qua nền tảng), nhưng hiển thị tách theo `type` để chủ dự án loại bớt; nếu cần "chỉ tiền thuê", chỉ lấy `MONTHLY_RENT`.
3. "Chủ nhà mới" = người có tin đăng đầu tiên trong kỳ, hay = người đã KYC + có tin? Mặc định: tin đăng đầu tiên (đơn giản, kiểm chứng được); không dùng KYC để tránh phụ thuộc vào luồng xác minh.
4. Có cần lưu cấu hình bộ lọc theo từng admin? Mặc định: không; ghi nhớ lựa chọn gần nhất trong `localStorage` (bọc `try/catch`), không phải dữ liệu máy chủ.
5. Thời gian giữ dữ liệu mô phỏng/seed có làm méo thống kê? Mặc định: thống kê tính trên mọi hàng `active=true`; môi trường production không có dữ liệu seed; không thêm cờ "loại dữ liệu demo".
6. MTTR/thời gian duyệt dùng trung vị + p90 (không dùng trung bình vì lệch); có đủ không? Mặc định: có.
7. Bắt đầu ghi `responded_at` (P4 §5.4b) từ ngày nào, có backfill từ `acceptedAt`/`updatedAt` không? Mặc định: không backfill (không đúng nghĩa), số liệu từ ngày bật.
8. Cờ `auto_confirmed` trên `payment_events` (thay so `actor_id='SYSTEM'`)? Mặc định: dùng `actor_id='SYSTEM'` + `event_type` ngay; thêm cờ khi chủ dự án duyệt thay đổi schema.

## 9. Không đưa vào (lý do)
| Tín hiệu (inventory) | Lý do |
|---|---|
| Duyệt/từ chối **theo người duyệt** (`listing_status_history.changed_by`) | Đánh giá nhân sự, cần chính sách riêng và nhật ký kiểm toán admin (chưa có); chỉ tổng hợp theo kỳ (khối 11). |
| KYC nhập tay: **ai làm** | Admin id chỉ nằm ở log, chưa có `admin_audit_log`; chỉ số lượng theo ngày (khối 8). |
| Tỉ lệ nâng cấp/rời bỏ gói, lượt dùng quota theo ngày | Cần đọc sổ cái `membership_events` (chưa endpoint nào đọc); khối 13 chỉ có ảnh chụp hiện tại. Giai đoạn 2. |
| Lượt xem tin toàn nền tảng, top tin | `view_count` sai khi Redis lỗi/`dev` (`ListingViewService.java:47-53,69-71`); tin tức tăng mỗi POST (`news.service.ts:93`). Không hiển thị số không tin được; cần bảng `listing_view_daily` (xem P3). |
| AI search/LLM (lượt, lỗi, token, kết quả rỗng), Vietmap, hạn mức TomTom | Không có bộ đếm; chỉ log. Cần Micrometer + Prometheus (P4 §5.2, §11). |
| Phễu yêu cầu thuê theo từng chủ nhà, tỉ lệ từ chối theo chủ nhà | Là dữ liệu cá nhân; P5 chỉ tổng hợp toàn hệ thống. |
| HĐ sắp hết hạn theo kỳ, thời gian ở mỗi trạng thái HĐ | `contracts` không có ngày kết thúc và không có lịch sử chuyển trạng thái; cần cột `lease_end_exclusive` và bảng lịch sử trước. |
| Sự cố SmartCA/VNPT, tỉ lệ lỗi ký số theo ngày | Chưa có thành phần sự cố `SMARTCA` và bộ đếm; sức khoẻ hiện tại ở P4 §4.1.4. Sau khi có, có thể thêm vào khối 10. |

## 10. Hợp đồng API thực tế — phase 2, 28/11/2026

Backend đã triển khai trong `hs-contract-service` (DTO/service/native SQL/index), controller tại `hs-api-service`. Không sửa luồng ghi nghiệp vụ hoặc các file P1–P4/P6. Quyền `STATISTICS_VIEW` dùng literal theo catalog P4 §6; P4 chịu trách nhiệm đăng ký quyền chung.

### Endpoint và request

- `GET /admin/statistics` và alias `GET /api/v1/admin/statistics`.
- Response envelope `ApiResponse<AdminStatisticsResponse>`, `code=1000`, header `Cache-Control: no-store`.
- Cần identity từ Gateway đã kiểm secret, role `ADMIN` **và** `STATISTICS_VIEW`; không đăng nhập/identity giả → 401, thiếu role/quyền → 403. Service cũng kiểm cùng điều kiện, không nhận identity từ query.
- DTO `com.hs.contract.dto.statistics.AdminStatisticsQuery(LocalDate from, LocalDate to, Granularity granularity)`; enum `DAY|MONTH`. Controller parse query vào DTO; chỉ nhận ba key này, mỗi key đúng một giá trị, không rỗng. Key lạ, lặp, ngày sai, enum sai → 400/code 1001. Không nhận body hoặc `userId`.
- Mặc định `to=BillingTime.today()`, `from=to-29 ngày`, `granularity=DAY`. Hai đầu inclusive theo Việt Nam; SQL dùng `[00:00 from,00:00 to+1)` UTC. `from<=to`, năm 1–9999, `to<=BillingTime.today()`, DAY ≤92 ngày, MONTH ≤36 tháng lịch giao với khoảng. MONTH giữ hai đầu ngày đã chọn, không tự mở rộng ra trọn tháng. Kỳ trước có cùng số ngày và kết thúc ngay trước `from`.

### Response DTO chính xác

`AdminStatisticsResponse(range, generatedAt, businessAsOf, kpi, series, breakdown, funnel, sources, degraded)`; mọi số tiền là JSON numeric chính xác bằng VND, giữ phần lẻ có trong dữ liệu, không chuyển sang double/không tự làm tròn. Count là numeric nguyên. Không trả danh sách bản ghi hoặc định danh cá nhân.

| Field | Shape/ý nghĩa |
|---|---|
| `range` | `{from,to,granularity,timezone:"Asia/Ho_Chi_Minh"}` |
| `generatedAt`, `businessAsOf` | ISO Instant; lần tạo snapshot theo giờ thật và giờ BillingTime. Snapshot cache giữ nguyên hai mốc này. |
| `kpi` | `newUsers`, `submittedListings`, `signedContracts`, `gmvVnd`, `membershipRevenueVnd`: mỗi field `{value,previous}` hoặc null khi truy vấn lỗi. |
| `series.buckets` | Chuỗi liên tục `YYYY-MM-DD` hoặc `YYYY-MM`, kể cả bucket trống. |
| `series` đếm | `newUsers`, `newLandlords`, `submittedListings`, `signedContracts`, `kycAdminManual`: mảng theo buckets, zero-fill. |
| `series.gmvByType` | Map `INITIAL`, `RESERVATION_DEPOSIT`, `MONTHLY_RENT`, `UTILITY`, `OTHER_CHARGE` → mảng VND; đủ 5 key dù rỗng. |
| `series.membershipByTier` | Map `FREE`, `STARTER`, `PRO`, `BUSINESS`, `ADDON` → mảng VND; tier legacy không rõ dùng `UNKNOWN`. |
| `series.autoConfirmedRent`, `autoConfirmedRefund` | Mảng số event `RECEIPT_CONFIRMED`, actor `SYSTEM`, phân theo direction; null nếu thiếu PAYMENT_VIEW hoặc source lỗi. |
| `breakdown.listingsByStatus`, `contractsByStatus`, `contractsByKind`, `kycRejectByCategory` | Map dimension → count. Kind null legacy trả `LEGACY_UNSPECIFIED`, không giả định là LEASE. Lý do KYC chỉ `DUPLICATE_ID`, `DOCUMENT_OR_FACE`, `OTHER`, không chuỗi thô. |
| `breakdown.listingsByCity` | `[{city,count}]`, top 10 nhãn tỉnh + phần còn lại `Khác`; không có địa chỉ active → `Chưa rõ`. Không trả đường/số nhà. |
| `breakdown.listingSupply` | `{expiringIn7d,overdueUnswept}` snapshot **giờ thật**, PUBLISHED hoặc PENDING_REVIEW có operational status PUBLISHED. |
| `breakdown.gmv` | `{confirmedTransactions,grossVnd,currency:"VND"}`. Gross gồm tiền cọc theo D2, không phải doanh thu thuần P3. Chỉ active CONFIRMED, VND, TENANT_TO_LANDLORD, 5 loại trên, theo confirmed_at; loại mọi khoản hoàn/chênh lệch cọc. Không đổi công thức P1/P3. |
| `breakdown.terminations` | `{mutual,unilateral,declined,proposed,early:null,rejectRate:{num,den}}`; completed_at không có forced_at vào mutual; forced_at vào unilateral, không đếm kép. Đây là timestamp lưu trên root contract (kể cả HĐ cọc); không phải lịch sử đầy đủ mọi lần đề xuất. `early` unavailable vì không có timestamp hoàn tất chấm dứt sớm riêng; ngày hiệu lực/đồng ý trả nhà không được dùng thay. |
| `breakdown.membershipByProvider` | `[{provider,orders,amountVnd}]`, PAID theo paid_at; provider null → UNKNOWN, gồm provider legacy VNPAY; tiền ETH là VND đã lưu. |
| `breakdown.membershipCheckout` | `[{provider,created,paid}]`, cohort theo created_at, paid là trạng thái hiện tại. |
| `breakdown.kycByProvider` | `[{provider,total,verified,rejected,pending}]`; pending = tất cả trạng thái ngoài VERIFIED/REJECTED, bao gồm EXPIRED/NOT_VERIFIED; UI không gắn nhãn "đang xử lý" cho toàn bộ nhóm này. |
| `breakdown.rentalRequests` | `{byStatus,acceptRate:{num,den}}` cohort created_at; số active ACCEPTED / mọi active request trong cohort. |
| `breakdown.appointments` | `{byStatus,completedMeaning:"PAST_SCHEDULE_NOT_ATTENDANCE"}` cohort created_at; COMPLETED không xác nhận khách thực đến. |
| `breakdown.responseSla` | `{available,rentalRequests,appointments}`; mỗi con `{observations,medianSec,p90Sec,unmeasured,invalid}` hoặc null khi thiếu source. Chỉ dùng responded_at thật ≥created_at; unmeasured là null, invalid là timestamp trước created_at. Thiếu cột ở một bảng → available false, con đó null, giữ con còn đo được. |
| `breakdown.moderation` | `{approved,rejected,violation,observations,reviewMedianSec,reviewP90Sec,approveRate,rejectRate,violationRate}`; ba mảng cùng buckets, rate `{num,den}`. Chỉ history ADMIN từ PENDING_REVIEW sang ba đích; latency dùng history.created_at − listings.submitted_at nếu không âm. |
| `breakdown.membershipActive` | `{byTier,exhausted,expiring7d}`; BillingTime snapshot, `start_at<=now<end_at`; hết lượt `quota-used-reserved<=0`, hết hạn `end_at<=now+7 ngày`; ADDON tách riêng. Đây là số period, không phải số chủ nhà. |
| `breakdown.invoices` | `{byStatus,draftOverdue,lateFeeVnd}`; active invoice có due_at trong kỳ, DRAFT due_at<BillingTime.now, cộng late_fee_amount đã lưu (null=0), không dự phóng phí tương lai. |
| `breakdown.paymentEvents` | `{rejectRate:{num,den}}` hoặc null; RECEIPT_REJECTED / TRANSFER_REPORTED theo cửa sổ event, **không phải matched cohort**, có thể vượt 100% khi nhiều lần báo/chuyển kỳ. Cần PAYMENT_VIEW. |
| `breakdown.incidents` | `{byComponent,open,observations,mttrMedianSec,mttrP90Sec,repeatComponents}` hoặc null; cohort opened_at, MTTR chỉ open=false và resolved_at>=opened_at; component lặp ≥2. Cần INCIDENT_VIEW. Không có sourceKey/resourceId. |
| `funnel` | Năm field `registeredToOnboarded`, `registeredToKyc`, `submittedToPublished`, `contractCreatedToSigned`, `signedToPaid`; mỗi field `{num,den}`. Mẫu số 0 vẫn `{num:0,den:0}`; UI hiển thị “—”. Registration chỉ USER active, KYC VERIFIED active qua EXISTS; hợp đồng chỉ root active; signedToPaid dùng INITIAL CONFIRMED active, VND, TENANT_TO_LANDLORD qua EXISTS, không đếm số khoản. |
| `sources` | Map theo tên khối → `{available,source,limit}`. available cho biết nguồn query được, không đảm bảo dữ liệu lịch sử đầy đủ. `earlyTerminations` luôn unavailable; thiếu quyền phụ có limit `PAYMENT_VIEW_REQUIRED`/`INCIDENT_VIEW_REQUIRED`. |
| `degraded` | Tên query lỗi/thiếu schema, không có SQL hay dữ liệu nhạy cảm. Khối lỗi null; thiếu quyền không được coi là sự cố degraded. |

Nguồn timeline và breakdown lỗi trả null thay vì zero giả. Median null khi không có mẫu hợp lệ; p90 null khi <2 mẫu. `observations` đi kèm để UI phân biệt chưa đo được và số 0 thật. Mọi series sẵn có có cùng độ dài buckets; series bị che quyền hoặc unavailable là null.

### Phạm vi, clock, giới hạn nguồn và hiệu năng

- Áp dụng active=true cho các entity có cột active; order/period/event/history/incident không có cột này dùng tập của chính bảng. First-landlord = MIN(created_at) của **tin active**, gồm tin nháp; loại owner đã có tin active trước kỳ trước. Không phải số KYC/role LANDLORD hoặc số chủ nhà còn dùng sản phẩm. Truy vấn chỉ xét cohort thời gian + indexed EXISTS; không tải toàn bộ lịch sử vào Java.
- Hợp đồng không tính appendix (`parent_contract_id IS NOT NULL`). Không loại root DEPOSIT khỏi thống kê hợp đồng; kind null legacy không đoán. Termination rejection là tỷ số event-window; nguồn contracts giữ mốc mới nhất, không phục dựng các lần đề xuất cũ.
- SLA bắt đầu từ phase1 responded_at, không backfill accepted_at/updated_at. Moderation không đo được mẫu cũ đã resubmit làm submitted_at hiện tại sau decision; bỏ mẫu thời gian âm nhưng vẫn đếm decision. Source limit nêu rõ. Không thống kê theo nhân viên/người thuê/chủ nhà.
- Incident source chỉ các component đã có bản ghi từ monitor hiện hữu. Không thêm bảng incident giả, không mở rộng monitor, không ghi job runs, không suy ra trạng thái dịch vụ khỏe khi chưa có nguồn.
- Một transaction chỉ đọc, repeatable-read, grouped native Postgres SQL (~32 query khi có đủ hai quyền phụ, không truy vấn theo từng hàng). Mỗi statement timeout 5s; savepoint cô lập lỗi PostgreSQL/thiếu cột legacy, source `SOURCE_UNAVAILABLE` hoặc `QUERY_FAILED`, phần còn lại tiếp tục.
- Cache nội bộ tối đa 32 snapshot, key gồm toàn bộ DTO, ngày BillingTime và hai quyền phụ; quyền luôn được kiểm trước cache. TTL 60s khi kỳ kết thúc hôm nay, 600s cho kỳ quá khứ; không cache response degraded. Cache dùng giờ thật, snapshot giữ generatedAt; các snapshot hiện tại trong báo cáo quá khứ cũng có thể cũ tối đa 600s.
- `AdminStatisticsIndexMigration` tạo idempotent 16 index `idx_stats_*` trên created/signed/confirmed/paid/verified/due/opened và listing-owner-created; bỏ qua bảng nguồn chưa tồn tại để source unavailable không chặn boot. Không sửa schema entity thuộc module dưới; không ghi/chỉnh dữ liệu nghiệp vụ. Chưa đo mục tiêu 800ms ở 100k dòng hoặc lock khi tạo index trên production; cần kiểm hoạch định triển khai trước production.
- Tất cả số theo trạng thái hiện tại trong cohort, không phải lịch sử trạng thái ở thời điểm kết thúc kỳ. Tài khoản/tin/payment inactive bị loại; PAID không có paid_at legacy không được bịa ngày doanh thu, CONFIRMED thiếu confirmed_at không được bịa bucket.

### Exports và kiểm thử

- CSV đúng thiết kế §5 là **client-side từ JSON vừa tải**, nên backend không thêm endpoint xuất. Frontend phase3 phải giữ quyền/source null, BOM, escaping và chống CSV injection cho nhãn tỉnh; không xuất PII. Thiết kế P5 không có PDF; không thêm PDF backend. Excel vẫn hoãn.
- Tests: `AdminStatisticsQueryTest` (contract module) kiểm mặc định/giới hạn/kỳ trước/bucket MONTH/ngày Việt Nam/query lạ hoặc lặp; `AdminStatisticsControllerTest` (API module) dùng proxy method-security thật + Gateway filter kiểm 401/403, ADMIN + permission, cả hai alias, no-store, 400 query invalid.
- `AdminStatisticsPostgresTest` (API module) dùng schema Hibernate thật trong PostgreSQL disposable, chạy tất cả query trên tập rỗng và hỗn hợp: tiền/refund/direction/currency/inactive, ngày/tháng Việt Nam, membership legacy status, funnel root/appendix, city top cap, responded_at legacy/null/âm, percentile 0/1/3 mẫu, moderation ADMIN/SYSTEM, incident open/closed/âm, ngày hết hạn business vs operational, invoice due cohort, thiếu cột/bảng, idempotent index, cache giới hạn và cô lập quyền, HTTP 401/403/quyền phụ/không lộ dữ liệu.
- Kết quả thật: `mvnw.cmd -Pisolated-build -pl hs-api-service -am -DskipTests compile` → BUILD SUCCESS, compile cả assembly và upstream. `mvnw.cmd -Pisolated-build -pl hs-api-service -am "-Dtest=AdminStatistics*Test" "-Dsurefire.failIfNoSpecifiedTests=false" test` cho 4 query unit + 4 HTTP test PASS; lần gộp này phát hiện lỗi setup PostgreSQL đã sửa, không coi lần gộp đó là BUILD SUCCESS. Giữ lại kết quả 8 test này vì input không đổi.
- Sau sửa setup/fixture và hoàn tất migration: đặt `HS_P5_TEST_PG_URL=jdbc:postgresql://127.0.0.1:51602/hs_statistics_test`, chạy `mvnw.cmd -Pisolated-build -pl hs-api-service -am "-Dtest=AdminStatisticsPostgresTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` → **BUILD SUCCESS, 10 PostgreSQL test, 0 failure/error/skipped**, gồm timeout thật 5s và query tiếp theo sau rollback savepoint. Tổng evidence 18 test PASS (8 kết quả dùng lại + 10 final), không phải full suite. Log cục bộ không commit: `hs-api-service/target/p5-compile.log`, `p5-tests.log`, `p5-pg-final.log`; kết quả chi tiết trong `target/isolated/surefire-reports` của module tương ứng.
- PostgreSQL **16.16**, container riêng `hs-p5-stats-83f370bd`, random loopback port 51602, database riêng `hs_statistics_test`; không dùng DB/service đang chạy, `.env` hay provider thật. Chỉ xóa container/volume do worker này tạo sau kiểm thử. Không benchmark 100k dòng, không thử production/provider hoặc frontend; CSV/Excel theo phạm vi phase3/hoãn nêu trên.
