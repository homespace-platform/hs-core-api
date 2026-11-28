# P1 — Dashboard chủ nhà (`/dashboard/landlord`)

Trạng thái: THIẾT KẾ, chờ chủ dự án duyệt (chưa code). Ngày 2026-11-28. Chỉ đọc code để viết.
Đường dẫn rút gọn: **API** = `hs-core-api`, **WEB** = `hs-web-app`, **CS** = `API/hs-contract-service/src/main/java/com/hs/contract`, **LS** = `API/hs-listing-service/src/main/java/com/hs/listing`, **PS** = `API/hs-payment-service/src/main/java/com/hs/payment`, **AC** = `API/hs-api-service/src/main/java/com/hs/api/controller`.

## 1. Mục đích & người xem
- Người xem: chủ nhà (menu "Dashboard", `WEB/components/dashboard/dashboard-nav.ts:28`). Trang hiện chỉ có dòng "đang được xây dựng" (`WEB/app/dashboard/landlord/page.tsx`).
- Quyết định trong 10 giây: (1) hôm nay phải làm gì trước (việc gấp, tiền chờ xác nhận, hợp đồng chờ ký); (2) tháng này thu được bao nhiêu so với dự kiến, ai đang nợ; (3) hợp đồng/tin nào sắp hết; (4) còn bao nhiêu lượt đăng tin.

## 2. Bố cục (desktop)
```
┌ Dashboard chủ nhà ───────────────────────────── [Tháng 11/2026]  (cập nhật hh:mm) ┐
│ [Phòng đang thuê 18/24] [Phòng trống 4] [Tin đang đăng 6] [Thu T11: 52,3tr / 68tr] [Quá hạn 3 khoản · 9,6tr] │  KPI (5 thẻ)
├──────────────────────────────────────────────┬───────────────────────────────────┤
│ Việc cần làm hôm nay (12 · 3 gấp)            │ Gói hội viên: Pro · còn 7 lượt     │
│  chips: Yêu cầu thuê 2 | Lịch hẹn 3 | Ký 1 |  │   hạn gói 20/12/2026 · lượt thêm 2 │
│         Xác nhận tiền 4 | Quá hạn 3 | Khác 5   │   [Mua thêm lượt]                  │
│  ▸ [GẤP] Xác nhận thanh toán P203 · còn 3h  ›  ├───────────────────────────────────┤
│  ▸ [Sắp tới] Ký hợp đồng HD-0042 · 2 ngày   ›  │ Lịch hẹn xem nhà sắp tới (5)       │
│  … (10 dòng) [Xem tất cả → /dashboard/branches] │  Thứ 6 14:00 · Nguyễn A · Tin X  ›│
├──────────────────────────────────────────────┼───────────────────────────────────┤
│ Hợp đồng sắp hết hạn (≤60 ngày)               │ Tin sắp hết hạn hiển thị (≤7 ngày) │
│  P101 · Trần B · còn 24 ngày [30]  ›          │  Tin Y · hết 02/12 · 134 lượt xem ›│
│  P205 · Lê C · còn 51 ngày [60]    ›          │  [Gia hạn/đăng lại]                │
├──────────────────────────────────────────────┴───────────────────────────────────┤
│ Lối tắt: [Đăng tin] [Chi nhánh] [Yêu cầu thuê] [Lịch xem nhà] [Hợp đồng] [Thanh toán] [Phân tích] │
└──────────────────────────────────────────────────────────────────────────────────┘
```
Màn nhỏ (<768px): KPI cuộn ngang 2 cột; các khối xếp 1 cột theo thứ tự: Việc cần làm → Thu tiền/Quá hạn → Hợp đồng sắp hết → Lịch hẹn → Tin sắp hết → Gói hội viên → Lối tắt; danh sách tối đa 5 dòng + "Xem thêm". Card bo `rounded-2xl`, token Tailwind sẵn có như các trang dashboard khác.

## 3. Từng khối
Quy ước chung: múi giờ `Asia/Ho_Chi_Minh` (`BillingTime.ZONE`, `API/hs-common-service/.../time/BillingTime.java`). "Hôm nay"/"tháng này"/hạn hợp đồng/hạn thanh toán dùng đồng hồ nghiệp vụ `BillingTime.now()` (có `homespace.time.simulated-at`, `application.properties:6`); hạn của lịch hẹn và tin đăng dùng đồng hồ thật (`realNow`), giống `BranchAttentionService` (`CS/service/BranchAttentionService.java:96-97`, Clock.REAL ở :862, :888). Tất cả lấy từ **một endpoint mới** (mục 4), trả `billingNow`/`realNow` để UI hiển thị đếm ngược.

### 3.1 KPI Phòng đang thuê / Phòng trống
- Công thức: duyệt mọi phòng (`BranchSlot` có `roomCode`) của chi nhánh active của chủ nhà; trạng thái từng phòng do `BranchOverviewService.room(...)` quyết định (đã dùng ở `BranchAttentionService.java:883-884`; enum `RoomStatus` ở `CS/dto/branch/BranchOverviewResponse.java:15`). **Đang thuê** = OCCUPIED. **Trống** = VACANT + UNLINKED + ADVERTISED (chưa có người ở/giữ chỗ). Tách riêng: Giữ chỗ/sắp vào = RESERVED + MOVING_IN; Khác = EXTERNAL_RENT + REPAIR. Mẫu số thẻ 1 = tổng phòng.
- Nguồn: MỚI (mục 4). Hiện `BranchAttentionService.addRoomAndDataWork` đã tính `status` cho từng phòng nhưng chỉ dùng cho việc ROOM_VACANT_UNLISTED; đề xuất tách đếm thành `Map<RoomStatus,Integer>` trả cùng lần `collect` (không tính lại).
- Click: `/dashboard/branches` (nếu 1 chi nhánh: `/dashboard/branches/{id}`).
- Trống: chủ nhà chưa có chi nhánh → thẻ hiện "Chưa có chi nhánh" + nút tạo (`/dashboard/branches`), KPI tin vẫn hiện. Giới hạn: tin/hợp đồng không thuộc chi nhánh không được tính vào "phòng" (Listing.branchId nullable, `LS/model/Listing.java:31`) — hiện ở thẻ tin (3.2).

### 3.2 KPI Tin đang đăng
- Công thức: số `listings` của owner, `active=true`, trạng thái hiệu lực (`status`, hoặc `reviewOperationalStatus` khi PENDING_REVIEW, `Listing.java:57`) = PUBLISHED **và** `expires_at > realNow` (đúng điều kiện `publiclyListed`, xem `BranchAttentionService.java:875,907-908`). Phụ: chờ duyệt = PENDING_REVIEW; nháp = DRAFT.
- Cảnh báo lệch số: `GET /listings/me/counts` (`AC/listing/ListingController.java:62` → `LS/service/ListingQueryService.java:75`) đếm PUBLISHED cả tin đã quá `expires_at` nên **không dùng** cho thẻ này.
- Query: `select status, count(*) ... group by` có thêm điều kiện hết hạn; dùng index `idx_listings_owner_status` (`Listing.java:15`).
- Click: `/dashboard/properties`. Trống: 0 tin → "Đăng tin đầu tiên" (`/dashboard/properties/new`).

### 3.3 KPI Doanh thu tháng này: đã nhận vs dự kiến (theo phiếu thu)
- **Đã nhận** = tổng tiền thuê/phí của phiếu xác nhận thanh toán (`payment_receipts`, `PS/receipt/PaymentReceipt.java:8-19`) có `landlord_id = owner` và `issued_at` trong [đầu tháng, đầu tháng sau) theo giờ Asia/Ho_Chi_Minh. `issued_at` = `PaymentRequest.confirmedAt` (`PaymentReceiptService.java:66`). **Không** dùng hóa đơn `monthly_invoices` (chủ dự án chốt).
  - Lấy số tiền: join `payment_requests p on p.id = r.payment_request_id` (cột tiền không nằm trong receipt, chỉ có trong `snapshot_json` TEXT nên không SUM bằng SQL tốt được).
  - Tính: `p.direction = TENANT_TO_LANDLORD` và `p.type IN (INITIAL, MONTHLY_RENT, UTILITY, OTHER_CHARGE)`; riêng INITIAL trừ phần dòng `payment_line_items.type = 'DEPOSIT'` (`PS/service/PaymentRequestService.java:207,218`) vì cọc là tiền giữ hộ, không phải doanh thu. **Loại**: RESERVATION_DEPOSIT, DEPOSIT_ADJUSTMENT, DEPOSIT_REFUND (cọc). **Trừ** INITIAL_PAYMENT_REFUND (hoàn tiền thuê đầu kỳ, `LANDLORD_TO_TENANT`) vào cùng tháng phát hành phiếu hoàn (hiển thị "ròng"). (giả định: chủ chốt cách xử lý cọc — câu hỏi 1.)
  - Membership KHÔNG nằm trong `payment_receipts` (bảng riêng `MembershipReceipt`, `PS/receipt/MembershipReceipt.java:8`) → đúng, đó là chi phí của chủ nhà.
- **Dự kiến** = tổng phần tiền (cùng quy tắc trừ cọc) của `payment_requests` có `payee_id = owner`, `direction = TENANT_TO_LANDLORD`, `active = true`, `due_at` trong tháng, trạng thái KHÁC CANCELLED/EXPIRED (gồm cả đã CONFIRMED). Hiển thị thanh tiến độ `đã nhận / dự kiến`. Lưu ý: phiếu đã nhận trong tháng nhưng đến hạn tháng trước nằm ở "đã nhận" mà không nằm ở "dự kiến" (UI ghi chú nhỏ "gồm khoản trả trễ của kỳ trước: x").
- Giới hạn: kỳ chưa phát hành hóa đơn (PaymentKind.PROJECTED, `BranchOverviewResponse.java:21`) chưa có `payment_requests` nên chưa tính vào dự kiến; `BranchOverviewResponse.Payment` không có số tiền (:63-64). Ghi chú tooltip "Chưa gồm hóa đơn chưa phát hành".
- **Lưu ý dữ liệu cũ:** `capture` chỉ tạo phiếu khi xác nhận từ sau khi có tính năng, không backfill (`docs/PAYMENT_RECEIPT_COVERAGE.md` §3 mục 3, `PaymentReceiptService.java:45-50`). Tháng cũ có thể thiếu phiếu; tháng hiện tại ổn. Tổng tiền = 0 không có phiếu (cố ý, :46-49).
- Click: `/dashboard/invoices` (Lịch sử thanh toán, `PaymentReceiptsPanel`).

### 3.4 KPI Khoản quá hạn
- Công thức: số khoản và tổng tiền của `payment_requests` với `payee_id = owner`, `direction = TENANT_TO_LANDLORD`, `active = true`, `status = OVERDUE`, loại REFUND/cọc như 3.3. Số khoản cross-check với số item `PAYMENT_OVERDUE` của attention (`BranchAttentionService.java:454,462`): hai số phải bằng nhau (test).
- Hạn do `PaymentTimePolicy` quyết định, không tự tính lại (`PS/service/PaymentTimePolicy`; AGENTS payment). Có thể bấm vào: `/dashboard/invoices` lọc (giai đoạn 1 chỉ mở trang); trống: "Không có khoản quá hạn" màu xanh.

### 3.5 Việc cần làm hôm nay
- Dùng lại API "Cần chú ý" của chi nhánh: `GET /branches/{branchId}/attention` (`AC/listing/BranchAttentionController.java:23-30`) và `GET /branches/attention-summary` (:32-35, chỉ đếm theo chi nhánh). Cả hai đã gộp công việc của chủ nhà qua `BranchAttentionService.collect` (`:212-229`) với 22 loại việc (`BranchAttentionResponse.java:15-23`), mức ưu tiên URGENT/SOON/NORMAL (`:136-140`), `dueAt`, `clock`, `target.href`. Không gọi N lần theo chi nhánh: endpoint mới gọi `collect` một lần cho mọi chi nhánh (như `summary()` :107-112 đang làm rồi bỏ danh sách).
- Nhóm chip (map `WorkType`): **Yêu cầu thuê chờ phản hồi** = RENTAL_REQUEST_PENDING (:843-849, còn hạn `respondBy`); **Lịch hẹn cần xác nhận** = VIEWING_PENDING (PENDING chưa quá giờ, hoặc đã xác nhận nhưng khách xin đổi giờ, :852-863); **Hợp đồng cần ký** = OWNER_SIGN + SIGNING_REOPEN (:622, :616); **Xác nhận tiền** = PAYMENT_CONFIRM + PAYMENT_REVIEW + OWNER_REFUND; **Quá hạn** = PAYMENT_OVERDUE + PAYMENT_EXTENSION_REQUEST + TERMINATION_RESPONSE; **Khác** = còn lại (đồng hồ điện nước METER_*, DRAFT_MISSED, AMENDMENT_RESPONSE, LEASE_EXPIRING, tin hết hạn/bị từ chối…).
- "Hôm nay" = toàn bộ việc đang tồn tại (mỗi item chỉ tồn tại khi điều kiện nguồn còn đúng, `BranchAttentionService.java:55-58`); tiêu đề "n việc · m gấp" (m = priority URGENT). Danh sách 10 dòng đầu theo thứ tự có sẵn (ưu tiên, còn lại, key). Dòng gồm nhãn việc (`workLabel` ở `WEB/services/branch-overview.service.ts:359`), mã phòng/ngữ cảnh, đếm ngược theo đúng đồng hồ (`workDeadline` :370), click = `target.href`.
- Hạn chế & bù: attention chỉ gom tin/hợp đồng thuộc chi nhánh active của owner (`reads.findListings(ids, ownerId)`, :245). Yêu cầu thuê/lịch hẹn của tin **không có `branch_id`** không có trong danh sách → endpoint mới cộng thêm đếm riêng: `rental_requests` PENDING còn hạn và `viewing_appointments` PENDING chưa quá giờ có `listing.branch_id is null` (index `idx_rental_request_owner_status`, `idx_appointment_owner_status`), hiện thành dòng "Chưa thuộc chi nhánh" trỏ `/dashboard/rental-requests`, `/dashboard/viewing-schedules`.
- Trống: "Không có việc cần làm" kèm biểu tượng tích xanh. Lỗi: thẻ lỗi cục bộ, nút "Thử lại" (các khối khác vẫn hiện nhờ `Promise.allSettled` — xem mục 4 về tách phần lỗi).

### 3.6 Hợp đồng sắp hết hạn (60/30 ngày)
- Công thức: đúng `BranchMonitoringService.expiry(lease, today)` (`CS/service/BranchMonitoringService.java:111-119`): bỏ hợp đồng không "billable" (`BranchOverviewService.java:566-569`: chưa bàn giao và ACTIVE/EXPIRED), `end` = `BranchLeaseRow.endExclusive()` (`CS/dto/branch/BranchLeaseRow.java:57-58`) = `ContractLeaseCalc.end(agreedEnd, agreedMonths, effectiveMonths, requestedMonths, moveIn)` (`CS/service/ContractLeaseCalc.java:33-38`: ngày kết thúc thỏa thuận sớm, nếu không thì `moveIn + số tháng hiệu lực`; **ngày kết thúc là exclusive**, ngày thuê cuối = end-1). `days = DAYS.between(today, end)`; band WITHIN_30 nếu ≤30, WITHIN_60 nếu 31-60; chỉ hiện `days >= 1` (đúng `BranchAttentionService.java:809-813` để khớp mục "Cần chú ý"). Đã hết hạn chưa bàn giao (`days ≤ 0`) hiện thành dòng đỏ riêng "Đã hết hạn, chưa bàn giao" (giả định, thêm cho đủ ý; không làm tăng số "gấp").
- Hiển thị: mã phòng, tên người thuê (`BranchLeaseRow.tenantName`), hạn (`expiryText`/`lastDay` đã có ở `WEB/services/branch-overview.service.ts:128-136`), thẻ 30/60. Sắp tăng dần theo ngày còn lại, 10 dòng đầu. Click `expiry.target.href` = `/dashboard/landlord/contracts/{id}?tab=end`.
- Nguồn: MỚI (mục 4), dùng `s.leases` trong cùng `collect`. Giới hạn: hợp đồng chưa gán chi nhánh (`findManagedRoots`) không nằm trong `s.leases` (câu hỏi 3).

### 3.7 Lịch hẹn sắp tới
- Công thức: `viewing_appointments` của owner, `active=true`, `status IN (PENDING, CONFIRMED)`, thời điểm hẹn (`appointment_date` + `start_time`, hoặc ngày đề xuất nếu khách xin đổi giờ) ≥ bây giờ (đồng hồ thật), sắp tăng dần, lấy 5. Tên khách `renterName`; **không trả** `renterPhone/renterEmail` (có trong `LS/dto/response/AppointmentResponse.java` nhưng dashboard không cần).
- Nguồn: dùng `GET /appointments/owner?status=CONFIRMED&date=...` (`AC/listing/ViewingAppointmentController.java:116-125`) chỉ khi gọi riêng; để tránh 3 lần gọi, endpoint mới truy vấn trực tiếp (sort `appointmentDate asc, startTime asc`, index `idx_appointment_owner_status`). Số "chờ xác nhận" đã nằm ở chip 3.5 (VIEWING_PENDING).
- Click: `/dashboard/viewing-schedules?appointmentId={id}` (cùng href đã dùng ở :863). Trống: "Chưa có lịch hẹn sắp tới".

### 3.8 Tin đăng sắp hết hạn hiển thị
- Công thức: `listings` của owner, hiệu lực PUBLISHED, `expires_at` trong (realNow, realNow + 7 ngày], sắp tăng dần, 5 dòng (index `idx_listings_status_expires`, `Listing.java:17`). Hiện `title`, `expiresAt`, ngày còn lại, `viewCount` (`Listing.java:119`). Tin đã hết hạn nằm ở việc LISTING_EXPIRED của 3.5 (:912-916), không lặp lại ở đây.
- Click: `/dashboard/properties/view?id={id}` (href đã có ở :915). Trống: ẩn khối, hiện "Không có tin nào sắp hết hạn".

### 3.9 Gói hội viên (lượt đăng còn lại)
- Dùng lại `GET /membership/me` (`AC/payment/MembershipController.java:61-63` → `LS/membership/MembershipService.View`, :43-45, `view()` :80-90): `tier`, `remainingUses`, `reservedUses`, `expiresAt` (hạn gói trả phí; null nếu gói FREE), `quotaResetsAt` (FREE), `freeUsed`, `unlimited`, `addonRemaining`, `addonReserved`, `addonExpiresAt`. Thời gian membership theo `BillingTime` (`MembershipService.java:23`). Có sẵn `WEB/services/membership.service.ts`; không cần endpoint mới (khối tải độc lập, lỗi không kéo theo khối khác).
- Hiển thị: "Còn R lượt (đang giữ H)" = `remainingUses` + lượt thêm `addonRemaining`; hạn `expiresAt` (hoặc "Làm mới ngày quotaResetsAt"); cảnh báo vàng khi còn ≤1 lượt hoặc hạn ≤7 ngày. Click `/dashboard/membership`. Trống/lỗi: ẩn số, nút "Xem gói".

### 3.10 Lối tắt
Tĩnh, không gọi API: Đăng tin `/dashboard/properties/new`, Chi nhánh `/dashboard/branches`, Yêu cầu thuê `/dashboard/rental-requests`, Lịch xem nhà `/dashboard/viewing-schedules`, Hợp đồng `/dashboard/landlord/contracts`, Thanh toán `/dashboard/invoices`, Phân tích `/dashboard/analytics`. Mỗi nút có badge số từ chip 3.5 khi >0.

## 4. API mới tổng hợp
`GET /dashboard/landlord/summary` (qua Gateway: `/api/v1/dashboard/landlord/summary`, cùng kiểu `BranchAttentionController`). Controller `AC/listing/LandlordDashboardController.java` (mới), service mới `LandlordDashboardService` trong `hs-api-service` (module lắp ráp đã phụ thuộc mọi module, AGENTS.md; contract→payment→listing nên service này gọi được cả `BranchAttentionService`, `PaymentRequestRepository`, `ListingRepository`, `MembershipService`).
- Auth/phạm vi: `CurrentUser.requireUserId()` (như `BranchAttentionController.java:26`); mọi truy vấn lọc `owner/landlord/payee = userId`, không nhận userId/branchId từ request. Không `@PreAuthorize` mới (giống endpoint "Cần chú ý"; dữ liệu chỉ của chính người gọi). Header `Cache-Control: private, no-store` (:37-41); cache phía server 30 giây theo userId nếu cần (Redis `StringRedisTemplate` đã dùng ở `ListingViewService`; giả định).
- Query: `?month=YYYY-MM` (mặc định tháng hiện tại theo BillingTime; định dạng kiểm như `BranchOverviewController.java:16,43-50`; lỗi → `ErrorCode.INVALID_REQUEST`).
- Response (tất cả khóa luôn có, null khi không có):
```json
{ "result": { "billingNow":"…Z","realNow":"…Z","month":"2026-11",
 "rooms":{"total":24,"occupied":18,"vacant":4,"reserved":1,"other":1,"byStatus":{"OCCUPIED":18,"VACANT":3,"UNLINKED":1}},
 "listings":{"published":6,"pendingReview":1,"draft":2},
 "revenue":{"receivedVnd":52300000,"refundedVnd":0,"expectedVnd":68000000,"receiptCount":14,"lateFromPreviousVnd":4000000,"overdueCount":3,"overdueVnd":9600000},
 "todo":{"total":12,"urgent":3,"groups":{"RENTAL_REQUEST":2,"VIEWING":3,"SIGN":1,"PAYMENT_CONFIRM":4,"OVERDUE":3,"OTHER":5,"UNBRANCHED_RENTAL":0,"UNBRANCHED_VIEWING":0},
   "items":[{"key":"…","type":"PAYMENT_CONFIRM","roomCode":"P203","contextLabel":"…","dueAt":"…","clock":"BILLING","priority":"URGENT","href":"/dashboard/…"}]},
 "expiringContracts":[{"contractId":"…","contractNumber":"HD-0042","roomCode":"P101","tenantName":"…","endDateExclusive":"2027-01-16","daysRemaining":24,"band":"WITHIN_30","href":"…?tab=end"}],
 "appointments":[{"id":"…","at":"…Z","listingTitle":"…","renterName":"…","status":"CONFIRMED","href":"…"}],
 "expiringListings":[{"id":"…","title":"…","expiresAt":"…Z","daysRemaining":4,"viewCount":134,"href":"…"}] } }
```
- Triển khai ít code nhất: (a) thêm `BranchAttentionService.overviewForOwner(ownerId)` trả `{Map<String,List<Work>>, Map<RoomStatus,Integer>, List<Expiry>}` từ cùng `Scope` (thay vì viết lại ~700 dòng); `attention()`/`summary()` giữ nguyên hợp đồng (test `BranchAttentionTest`, `BranchAttentionPostgresTest`, `BranchAttentionApiTest` phải vẫn xanh). (b) các số tiền, tin, lịch hẹn là truy vấn gộp mới (JPQL/native), mỗi loại 1 query.
- Index cần: `payment_receipts (landlord_id, issued_at DESC)` (hiện chỉ có `idx_receipt_pdf_queue`, không có chỉ mục theo chủ nhà, `PaymentReceipt.java:15`); `payment_requests (payee_id, status, due_at)` (hiện `idx_payment_request_payee_status (payee_id, status)`, `PaymentRequest.java:22`); `viewing_appointments (owner_id, status, appointment_date)`.
- Lỗi từng phần: service bọc mỗi khối trong try/catch, thêm `errors:{"revenue":true}` thay vì 500 cả trang (giả định, cần duyệt).

## 5. Biểu đồ
Không có biểu đồ ở P1 (chỉ thẻ KPI + thanh tiến độ `đã nhận/dự kiến` bằng div). Ghi nhận cho P3: `WEB/package.json` **không có thư viện biểu đồ** (không recharts/chart.js; chỉ `lucide-react`, `date-fns`…).

## 6. Hiệu năng & bảo mật
- Chi phí: `collect` nạp theo lô (`batched`) phòng, tin, hợp đồng, hóa đơn, yêu cầu thanh toán, phụ lục của MỌI chi nhánh trong một transaction `REPEATABLE_READ` (`BranchAttentionService.java:106-112`); `GET /branches/attention-summary` hiện đã chạy y hệt ở trang Chi nhánh, nên P1 không nặng hơn một lần tải trang đó. Ước lượng 10k tin/100k thanh toán: số tiền dùng 2-3 truy vấn có chỉ mục trên (`landlord_id, issued_at`), không quét bảng; cache 30 giây chống F5 liên tục. Chưa đo (giả định) → test hiệu năng bằng container PostgreSQL tạm.
- N+1: không gọi `/overview` theo từng chi nhánh (mỗi lần 1 tháng, `BranchOverviewController.java:27`); không gọi `GET /listings/me` rồi cộng ở client.
- Rò rỉ: trả tên người thuê và mã phòng của chính chủ nhà; không trả SĐT/email khách hẹn, CCCD, số tài khoản (`payer/payee_bank_account_snapshot`, `snapshot_json`). Log chỉ ghi id, không ghi tên/tiền theo người.
- Dữ liệu tiền chỉ đọc `BigDecimal`, không float (`PS/AGENTS.md`).

## 7. Kiểm thử cần viết
Backend (`hs-api-service`, `hs-contract-service`):
- Số liệu: dữ liệu mẫu với 2 chi nhánh, 1 tin không chi nhánh → `rooms`, `listings` (tin PUBLISHED quá `expires_at` KHÔNG tính "đang đăng"), `todo.groups` bằng tổng `/branches/attention-summary` + nhóm không-chi-nhánh.
- Doanh thu: phiếu cuối ngày 30/11 23:59:59 và 00:00:00 01/12 (biên tháng, Asia/Ho_Chi_Minh); INITIAL có dòng DEPOSIT bị trừ; RESERVATION_DEPOSIT và DEPOSIT_REFUND bị loại; INITIAL_PAYMENT_REFUND trừ ròng; phiếu membership không lọt vào; tổng 0 không phiếu; một phiếu không tính hai lần (unique `uq_receipt_payment`).
- Quá hạn: `overdueCount` == số item PAYMENT_OVERDUE của attention.
- Hạn hợp đồng: biên 30/31/60/61 ngày, hợp đồng có `agreedEarlyEndExclusive`, `effectiveLeaseMonths` (khớp `BranchLeaseEndParityTest` hiện có), exclusive end (end-1 là ngày cuối).
- **Từ chối/cô lập**: user B gọi không thấy dữ liệu chủ A (hai owner trong cùng DB); chưa đăng nhập → 401 (`ErrorCode.UNAUTHENTICATED`); `month` sai định dạng → INVALID_REQUEST; response không chứa `renterPhone`, `renterEmail`.
- `BranchAttentionTest`/`BranchAttentionPostgresTest`/`BranchAttentionApiTest` giữ xanh sau khi tách `Scope`.
Frontend: render từng khối ở 3 trạng thái (tải/trống/lỗi), một khối lỗi không làm mất khối khác, link đúng route, màn <768px; chạy `npm run lint` và `next build` (repo không có test runner cho component ngoài vài `*.test.ts` trong `lib/`).

## 8. Câu hỏi mở (mặc định đề xuất)
1. Tiền cọc có tính vào doanh thu không? Đề xuất: KHÔNG (cọc giữ hộ); "Đã nhận" = tiền thuê + phí, trừ hoàn tiền thuê đầu kỳ; cọc hiện ở dòng phụ "Tiền cọc nhận trong tháng".
2. "Dự kiến" chỉ gồm hóa đơn đã phát hành; có cần cộng thêm kỳ chưa phát hành (ước từ giá thuê hợp đồng)? Đề xuất: chưa, ghi chú "chưa gồm kỳ chưa phát hành".
3. Hợp đồng/tin chưa gán chi nhánh có hiện trong "sắp hết hạn" không? Đề xuất: v1 chỉ hợp đồng đã gán chi nhánh (khớp mục "Cần chú ý"); hợp đồng chưa gán nhắc ở khối việc "Chưa thuộc chi nhánh".
4. Ngưỡng "tin sắp hết hạn"? Đề xuất 7 ngày.
5. Tháng cũ thiếu phiếu (không backfill): cần backfill `payment_receipts` cho `payment_requests CONFIRMED` cũ không? Đề xuất: không bắt buộc cho P1 (chỉ tháng hiện tại); quyết định riêng cho P3 (báo cáo 12 tháng).
