# P4 · Tổng quan (admin) — `hs-admin-portal` `/dashboard`

File hiện tại: `src/pages/dashboard/DashboardPage.tsx` (7 dòng, trang trắng). Route đã có ở `src/router/index.tsx` (`<Route path="/dashboard" element={<DashboardPage />} />`), không gắn `RequirePermission`; sidebar mục "Tổng quan" (`layouts/Sidebar.tsx:44`). Trạng thái: **THIẾT KẾ, chờ chủ dự án duyệt**.

## 1. Mục đích & người xem
- Người xem: quản trị viên (role ADMIN), mỗi người thấy các khối khớp quyền mình có.
- 10 giây phải trả lời: "Hôm nay có việc gì đang chờ tôi xử lý và hệ thống có đang lỗi không?" → hàng đợi cần xử lý (tin chờ duyệt, KYC chờ, khiếu nại mở, sự cố mở) đứng trên cùng; số liệu tăng trưởng và tiền ở dưới; lối tắt ở cuối.
- Trang này là "nhìn nhanh, hôm nay". Xu hướng theo thời gian nằm ở P5 (`/analytics/statistics`), không lặp lại ở đây.

## 2. Bố cục (desktop, lưới 12 cột)
```
+--------------------------------------------------------------------------+
| Tổng quan                                   Cập nhật 14:05 [Tải lại]     |
+--------------------------------------------------------------------------+
| [A] CẦN XỬ LÝ (4 thẻ lớn, màu theo mức khẩn)                              |
| [Tin chờ duyệt 12] [KYC chờ 5] [Khiếu nại mở 3 (1 quá hạn)] [Sự cố mở 0]  |
+-----------------------------------------+--------------------------------+
| [B] NGƯỜI DÙNG                          | [C] HỢP ĐỒNG & THUÊ            |
| Mới hôm nay 8 | 7 ngày 41 (sparkline 7) | GD thuê đã xác nhận hôm nay 6  |
| KYC đã xác minh 1.204 / tổng 3.310      | HĐ đang hiệu lực 212           |
+-----------------------------------------+--------------------------------+
| [D] DOANH THU GÓI HỘI VIÊN              | [E] CẢNH BÁO HỆ THỐNG          |
| Hôm nay 490.000đ | Tháng này 12.3tr     | Outbox thông báo: 8 bảng       |
| (thanh 7 ngày gần nhất)                 | trễ lớn nhất 12s  [OK]         |
+-----------------------------------------+--------------------------------+
| [F] LỐI TẮT: Tin chờ duyệt | Khiếu nại | Cảnh báo vận hành | Người dùng  |
|              Hợp đồng | Blockchain | Thống kê | Thông báo hệ thống         |
+--------------------------------------------------------------------------+
```
Màn nhỏ (<768px): một cột theo thứ tự A → E → B → C → D → F; 4 thẻ A thành lưới 2x2; sparkline ẩn nếu hẹp hơn 120px. Chỉ cuộn dọc.

## 3. Từng khối
Quy ước: "hôm nay" = `[00:00, 24:00)` ngày `BillingTime.today()` theo múi `Asia/Ho_Chi_Minh` (`hs-common-service/.../time/BillingTime.java:20,54`); "7 ngày" = 7 ngày lịch kết thúc hôm nay (gồm hôm nay); "tháng này" = từ mùng 1 đến hết hôm nay. Dùng `BillingTime.now()` để cùng đồng hồ với hạn thanh toán; khi `homespace.time.simulated-at` rỗng (production) thì là giờ thật. Chỉ loại hàng `active=true` khi bảng có `BaseEntity.active` (`hs-common-service/.../persistence/BaseEntity.java`, cột `created_at`, `active`).

Mọi khối đọc từ **một** endpoint mới `GET /api/v1/admin/dashboard/overview` (mục 4). Không có endpoint hiện có nào trả đủ số liệu này; các endpoint hiện có chỉ dùng cho lối tắt.

| # | Khối | Định nghĩa chính xác | Nguồn (bảng/cột) | Quyền cần (ngoài ADMIN) | Bấm vào |
|---|---|---|---|---|---|
| A1 | Tin chờ duyệt | Số `listings` có `status = PENDING_REVIEW` và `active = true`. Giống hệt số hàng đợi hiện có (`ListingRepository.countAllGroupedByStatus`, `hs-listing-service/.../ListingRepository.java:35-36`, trả qua `GET /api/v1/admin/listings/counts`, `ListingAdminController.java:44`; frontend đã dùng ở `features/admin-queue/queueController.ts:61-72`). Cộng thêm `edits` chờ duyệt: `countByActiveTrueAndPendingEditIdIsNotNull` (`ListingRepository.java:38`) hiển thị phụ "+N yêu cầu sửa". | `listings.status`, `listings.pending_edit_id` | `LISTING_VIEW` | `/properties/pending` (route có sẵn) |
| A2 | KYC chờ | Số người dùng mà trạng thái KYC hiện tại là `PENDING` hoặc `REVIEW_REQUIRED`. "Hiện tại" lấy đúng quy tắc `KycServiceImpl.getCurrentStatus` (`hs-user-service/.../KycServiceImpl.java:55-69`): ưu tiên bản `VERIFIED` mới nhất, nếu không có thì bản tạo mới nhất. Vì vậy truy vấn: với mỗi `user_id` lấy hàng `kyc_verifications` mới nhất (`DISTINCT ON (user_id) ... ORDER BY user_id, (status='VERIFIED') DESC, created_at DESC`) rồi đếm `status IN ('PENDING','REVIEW_REQUIRED')`. | `kyc_verifications` (entity `KycVerification.java:23-40`) | `KYC_VIEW` | `/users` (chưa có trang danh sách KYC; xem Câu hỏi 3) |
| A3 | Khiếu nại mở | `dispute_cases` có `subject_type='RENTAL'` và `status IN ('OPEN','ESCALATED')` (khớp bộ lọc `ComplaintCaseService.adminList`, `hs-contract-service/.../ComplaintCaseService.java:211-216`). Dòng phụ "quá hạn" = trong số đó `respond_by <= BillingTime.now()` (cùng điều kiện tham số `overdue`, dòng 216). Chỉ đếm khiếu nại thuê, không đếm tranh chấp thanh toán loại khác. | `dispute_cases` (`DisputeCase.java:12-46`, index `idx_dispute_queue(status,opened_at)`) | `COMPLAINT_VIEW` | `/operations/complaints` |
| A4 | Sự cố vận hành mở | `operational_incidents.open = true` (`OperationalIncident.java:7-16`; thành phần chỉ `BLOCKCHAIN` hoặc `GATEWAY`, `OperationalIncidentService.java:23`). Màu đỏ nếu > 0. | `operational_incidents` | `INCIDENT_VIEW` | `/operations/system-alerts` |
| B1 | Người dùng mới | Số `users` có `created_at` trong hôm nay / 7 ngày, `role` = USER (loại tài khoản ADMIN). Sparkline 7 cột = số mới mỗi ngày. | `users.created_at`, `users.role_id` (`User.java:24`) | `USER_VIEW` | `/users` |
| B2 | KYC đã xác minh | Tử số: số người dùng có trạng thái hiện tại `VERIFIED` và chưa hết hạn (cùng quy tắc `getCurrentStatus`: VNPT hết hạn khi `expires_at <= now`, nhà cung cấp khác `DIDIT/ADMIN` vẫn tính theo quy tắc `REUSABLE` ở dòng 67-69 — dùng đúng hàm đó cho khối này, không viết lại điều kiện). Mẫu số: tổng người dùng USER. Hiển thị "1.204 / 3.310 (36%)". | `kyc_verifications`, `users` | `KYC_VIEW` | `/users` |
| C1 | Giao dịch thuê đã xác nhận (hôm nay) | Số `payment_requests` có `status = CONFIRMED`, `direction = TENANT_TO_LANDLORD`, `type IN (INITIAL, RESERVATION_DEPOSIT, MONTHLY_RENT, UTILITY, OTHER_CHARGE)` và `confirmed_at` trong hôm nay (`PaymentRequest.java:63-71,126-127`; enum `PaymentStatus`/`PaymentType` ở `hs-payment-service/.../model/constant/`). Loại `DEPOSIT_REFUND`, `DEPOSIT_ADJUSTMENT`, `INITIAL_PAYMENT_REFUND` (tiền hoàn). Chỉ là **số lượng**, không hiện số tiền ở trang tổng quan (tiền nằm ở P5). | `payment_requests` | `STATISTICS_VIEW` (xem mục 4) | `/analytics/statistics` |
| C2 | HĐ đang hiệu lực | `contracts.status = ACTIVE` (`Contract.java:63`; enum `ContractStatus`: DRAFT…ACTIVE, EXPIRED, CLOSED, TERMINATED). | `contracts` | `CONTRACT_VIEW` | `/contracts/all` |
| D | Doanh thu gói hội viên | `SUM(amount_vnd)` của `membership_orders` có `status = PAID` và `paid_at` trong hôm nay / tháng này; thanh 7 ngày = tổng mỗi ngày. Gồm cả đơn `PLAN` lẫn `ADDON` (cột `kind`), mọi cổng (`SEPAY`, `ETH`, `VNPAY` lịch sử). **Lưu ý khác với yêu cầu ban đầu:** `membership_receipts` KHÔNG có cột số tiền (chỉ `snapshot_json`, `receipt_number`, `issued_at`; `MembershipReceipt.java` ~dòng 28-45), nên không tính trực tiếp trên bảng này. Mỗi đơn PAID có đúng một biên lai (`uq_membership_receipt_order`) nên dùng `membership_orders` (`MembershipOrder.java:10-45`) làm nguồn, cho kết quả tương đương. Đơn ETH ghi `amount_vnd` quy đổi tại thời điểm báo giá. Không có hoàn tiền trong mã hiện tại (giả định); nếu sau này có, phải trừ. | `membership_orders` | `STATISTICS_VIEW` | `/analytics/statistics` |
| E | Cảnh báo hệ thống | Backlog outbox thông báo: với mỗi bảng `*_notification_outbox` (8 bảng: `contract_`, `listing_`, `payment_`, `membership_`, `account_`, `appointment_`, `rental_request_`, `notification_broadcast_`; ví dụ `ContractNotificationOutbox.java:17-38` có `created_at`, `sent_at`) tính `COUNT(*) WHERE sent_at IS NULL` và tuổi bản ghi cũ nhất `now() - MIN(created_at)` (giờ thật, không dùng giờ mô phỏng). Trạng thái: OK < 60 giây; CHÚ Ý 60–300 giây; LỖI > 300 giây (ngưỡng giả định, cấu hình được). Hiển thị chỉ bảng tệ nhất + tổng số chưa gửi; chi tiết mở rộng bằng accordion. **Không đo được:** độ trễ consumer Kafka (cần `AdminClient`; repo không có) — chỉ ghi nhận đầu ra outbox, ghi chú rõ trên giao diện "Độ trễ phát sự kiện (outbox)". | 8 bảng outbox | `INCIDENT_VIEW` | `/operations/system-alerts` |
| F | Lối tắt | Liên kết tới trang đã có, ẩn theo `ROUTE_PERMISSIONS` (`config/permissions.ts`, `useCanAccessPath`) hoặc `<Can>` (`components/common/Can.tsx`). | — | theo từng trang | các route đã có |

Trạng thái chung mỗi khối: đang tải = `Skeleton` (`components/ui/skeleton.tsx`) cùng kích thước thẻ; trống = hiển thị `0` (không phải "không có dữ liệu") vì số 0 là thông tin; lỗi cả endpoint = banner "Không tải được tổng quan [Thử lại]" giữ lối tắt; một khối bị thiếu quyền = server trả `null` cho khối đó, UI ẩn khối (không hiện "lỗi quyền"); mất kết nối giữ số cũ + nhãn "Cập nhật lúc …" màu vàng.

## 4. API mới tổng hợp
`GET /api/v1/admin/dashboard/overview` — controller mới `hs-api-service/.../controller/admin/AdminDashboardController.java` (cùng quy ước `@RequestMapping({"/admin/dashboard","/api/v1/admin/dashboard"})` như `ComplaintAdminController.java:17`). Là module lắp ráp nên được phép đọc repository của mọi module (`AGENTS.md`: hs-api-service phụ thuộc tất cả).

- Auth: `@PreAuthorize("hasAuthority('ADMIN')")` ở lớp; **từng khối** kiểm thêm quyền trong service bằng `Authentication.getAuthorities()` (không tin request): `LISTING_VIEW`, `KYC_VIEW`, `COMPLAINT_VIEW`, `INCIDENT_VIEW`, `USER_VIEW`, `CONTRACT_VIEW`, `STATISTICS_VIEW`. Thiếu quyền → khối là `null`, không phải 403 (admin chỉ có một quyền vẫn xem được trang). Không nhận userId/role từ tham số.
- Quyền mới (thêm vào `Permissions.java` rồi `CATALOG`, dòng 42 và 69-98): `STATISTICS_VIEW` "Xem thống kê doanh thu và tăng trưởng toàn hệ thống" (admin scope, không thêm vào `USER_SCOPE`). `PermissionDataInitializer.java:55` tự gán mọi quyền trong `CATALOG` cho ADMIN khi khởi động. Phản chiếu sang `hs-admin-portal/src/config/permissions.ts` (`PERMISSIONS`).
- Response (envelope `ApiResponse`, như các controller admin khác):
```json
{ "code":1000, "result": {
  "generatedAt":"2026-11-28T14:05:11+07:00", "businessDate":"2026-11-28",
  "attention": { "pendingListings":12, "pendingListingEdits":2, "pendingKyc":5,
                 "openComplaints":3, "overdueComplaints":1, "openIncidents":0 },
  "users":     { "newToday":8, "new7d":41, "daily7d":[3,5,7,6,4,8,8], "kycVerified":1204, "totalUsers":3310 },
  "rentals":   { "confirmedPaymentsToday":6, "activeContracts":212 },
  "membership":{ "revenueToday":490000, "revenueMonth":12300000, "daily7d":[...7 số VND...] },
  "system":    { "outboxPending":4, "worstOutbox":"payment_notification_outbox", "worstLagSeconds":12, "level":"OK",
                 "tables":[{"name":"payment_notification_outbox","pending":3,"lagSeconds":12}] } } }
```
Khối thiếu quyền → giá trị `null`. Tiền là số nguyên VND (cột `amount_vnd` kiểu int; dùng `long` khi cộng).
- Truy vấn: một `@Service` đọc-only (`@Transactional(readOnly=true)`) dùng `NamedParameterJdbcTemplate`/`EntityManager` native (Postgres, đã dùng `ON CONFLICT` ở `OperationalIncidentRepository.java:10`). Mỗi khối là một câu `GROUP BY`/`FILTER` gộp (ví dụ `COUNT(*) FILTER (WHERE created_at >= :todayStart)` và một câu `date_trunc('day', created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')` cho 7 ngày), tổng cộng ~12 câu, không vòng lặp theo bản ghi.
- Index cần: `users(created_at)` (chưa có), `payment_requests(status, confirmed_at)` (chưa có; hiện chỉ có `idx_payment_request_payer_status`… tại `PaymentRequest.java:19-24`), `membership_orders(status, paid_at)` (chưa có; hiện chỉ `idx_membership_orders_owner_status`, `MembershipOrder.java:11`). Có sẵn: `idx_dispute_queue`, `idx_listings_status_submitted`, `idx_contract_outbox_pending(sent_at,id)`. Thêm bằng `@Index` trên entity (ddl-auto=update theo `docs/BLOCKCHAIN_INTEGRATION.md` §4).
- Cache: 15 giây trong bộ nhớ theo tổ hợp quyền của người gọi (khóa = tập quyền đã dùng), để nhiều admin mở trang cùng lúc không nhân truy vấn; nút "Tải lại" bỏ qua cache bằng `?fresh=true` giới hạn 1 lần/10 giây/người. Frontend tự làm mới mỗi 60 giây khi tab hiển thị (dùng `document.visibilityState`).

## 5. Biểu đồ
`hs-admin-portal/package.json` **không có thư viện biểu đồ** (không recharts/chart.js/visx; dependencies gồm radix-ui, lucide-react, tailwind 4, date-fns). Token màu biểu đồ đã có sẵn trong `src/index.css` (`--chart-1..5`, dòng 33-37, 85-89). Đề xuất: không thêm thư viện cho trang này; sparkline 7 điểm và thanh 7 ngày vẽ bằng SVG/`div` Tailwind trong một component nhỏ dùng chung `components/common/MiniBars.tsx` (≈40 dòng, tái dùng ở P5 nếu chủ dự án chọn không thêm thư viện, xem P5 §5). Trục: X = 7 ngày (nhãn `dd/MM`), Y bắt đầu từ 0; mỗi cột có `title`/`aria-label` "28/11: 8 người dùng mới" để đọc được bằng bàn phím và trình đọc màn hình.

## 6. Hiệu năng & bảo mật
- Chi phí ở 10k tin / 100k thanh toán: tất cả là `COUNT/SUM` có index (sau khi thêm 3 index trên) → mục tiêu < 150 ms tổng; câu KYC `DISTINCT ON` quét `kyc_verifications` (≤ vài chục nghìn hàng, có `idx_kyc_user_status`) chấp nhận được, cache 15 giây che phần còn lại. Không N+1 (không tải thực thể).
- Chỉ trả số tổng hợp; không có tên, email, CCCD, số tài khoản, `payer_id/payee_id`. Không trả danh sách; số liệu tiền chỉ cho `STATISTICS_VIEW`.
- Không dùng quyền suy ra từ role: kiểm `hasAuthority` ở backend; frontend chỉ ẩn khối. `Cache-Control: no-store`.
- Lỗi một nguồn (ví dụ bảng outbox không truy vấn được) không làm hỏng cả trang: bắt riêng từng khối, khối đó trả `null` + cờ `"degraded":["system"]`.

## 7. Kiểm thử cần viết
Backend (`hs-api-service/src/test/.../controller/admin/AdminDashboardControllerTest`, theo mẫu `ContractBlockchainProofControllerTest`):
1. Không đăng nhập → 401; USER không có `ADMIN` → 403.
2. ADMIN chỉ có `LISTING_VIEW` → chỉ khối `pendingListings` khác `null`, mọi khối còn lại `null` (denied case); ADMIN có `STATISTICS_VIEW` mới thấy `membership`/`confirmedPaymentsToday`.
3. Dữ liệu nền (Postgres test container tạm): ranh giới ngày 23:59:59/00:00:00 giờ Việt Nam; đơn `PAID` ngày hôm qua không vào "hôm nay"; `payment_requests` loại `DEPOSIT_REFUND` không đếm; user ADMIN không đếm vào "mới"; KYC có cả bản `VERIFIED` cũ và bản `PENDING` mới → tính `VERIFIED` (đúng `getCurrentStatus`).
4. Outbox: bản ghi `sent_at IS NULL` cũ 400 giây → `level = "ERROR"`; không bản ghi → `OK`, `worstLagSeconds = 0`.
5. Ngưỡng cache: hai lần gọi liên tiếp chỉ chạy truy vấn một lần.
Frontend (`node --test`, theo `tests/*.test.mjs` hiện có): hàm thuần `overviewToCards()` ẩn khối `null`; định dạng VND/ngày; render lỗi toàn trang giữ lối tắt; mỗi lối tắt ẩn đúng khi thiếu quyền (`hasPermission`).

## 8. Câu hỏi mở
1. Doanh thu có nên trừ đơn đã hoàn/hủy sau khi `PAID`? Mặc định: không (hiện không có luồng hoàn); thêm cột trừ khi có luồng hoàn.
2. Ngưỡng cảnh báo outbox 60/300 giây có phù hợp? Mặc định: 60/300, cấu hình qua `homespace.dashboard.outbox-warn-seconds` / `-error-seconds` (theo quy ước env: không giá trị mặc định trong compose, `${VAR}` trong properties — cần thêm đủ mọi file env).
3. KYC chờ bấm vào đâu? Chưa có trang danh sách KYC admin (chỉ `GET/PUT /admin/users/{userId}/kyc`, `AdminKycController.java`). Mặc định: dẫn tới `/users`; làm bộ lọc "KYC chờ" ở trang người dùng là việc riêng, ngoài phạm vi.
4. Có cần quyền riêng `DASHBOARD_VIEW`? Mặc định: không; trang mở cho mọi ADMIN, từng khối theo quyền miền (tránh admin vừa đăng nhập bị chuyển vào trang cấm).
5. Dọn trang thừa C1: **`src/pages/users/AdminsPage.tsx` và `MembersPage.tsx` không có route** (chỉ còn `<Route path="members" element={<Navigate to="/users" replace />} />` ở router; grep toàn `src` chỉ thấy hai file tự định nghĩa, không nơi nào import; cả hai chỉ là trang trắng 7 dòng). Khuyến nghị **xoá cả hai file** (không ảnh hưởng build/route). Mặc định: xoá cùng commit dọn dẹp C1, ghi vào `AUDIT_LOG.md`.
