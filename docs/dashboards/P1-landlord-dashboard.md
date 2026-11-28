# P1 — Dashboard chủ nhà (`/dashboard/landlord`)

Trạng thái: thiết kế đã được duyệt theo AUDIT_LOG ngày 2026-11-28; API đợt 1 được triển khai trong worktree P1. Hợp đồng API thực tế và bằng chứng kiểm thử ở mục 10; frontend/mobile chưa triển khai.
Đường dẫn rút gọn: **API** = `hs-core-api`, **WEB** = `hs-web-app`, **CS** = `API/hs-contract-service/src/main/java/com/hs/contract`, **LS** = `API/hs-listing-service/src/main/java/com/hs/listing`, **PS** = `API/hs-payment-service/src/main/java/com/hs/payment`, **AC** = `API/hs-api-service/src/main/java/com/hs/api/controller`.

## 0. Thay đổi sau kiểm kê (28/11/2026, đã đối chiếu 5 file `inventory/`)
- Bố cục gom thành nhóm, gấp nhất trước: **Thiết lập → Việc cần làm → Tiền → Hợp đồng → Phòng & tin → Khách & lịch hẹn → Khiếu nại** (mục 2); mỗi nhóm có huy hiệu "n việc · m gấp".
- THÊM: thiết lập KYC/onboarding/tài khoản nhận tiền/kênh nhắc (3.11); huy hiệu thông báo/chat (3.12); Tiền: chờ xác nhận có đếm ngược, bị từ chối, phải hoàn, tự xác nhận 30 ngày, phí chậm/nợ chuyển kỳ, phiếu PDF lỗi (3.13); chip Điện nước + Phụ lục (3.5) và 5 loại việc ngoài "Cần chú ý": vi phạm cọc, khiếu nại, tất toán, SmartCA lỗi, hồ sơ cọc (3.14); Hợp đồng theo trạng thái, tất toán/hoàn cọc, SmartCA, hồ sơ cọc (3.15); tin cần sửa, tuổi chờ duyệt, chưa gán chi nhánh, đơn hội viên, lượt lưu và "chưa có khách" (3.2, 3.8, 3.16); yêu cầu cạnh tranh, giữ chỗ (3.17); khiếu nại (3.18).
- KHÔNG đưa vào 7 tín hiệu (mục 9, kèm lý do hoặc dữ liệu phải ghi trước).
- Endpoint tổng hợp thêm `include`, các khóa mới, lỗi từng khối (mục 4); thêm test (mục 7), câu hỏi 6-12 (mục 8).

## 1. Mục đích & người xem
- Người xem: chủ nhà (menu "Dashboard", `WEB/components/dashboard/dashboard-nav.ts:28`). Trang hiện chỉ có dòng "đang được xây dựng" (`WEB/app/dashboard/landlord/page.tsx`).
- Quyết định trong 10 giây: (1) hôm nay phải làm gì trước (việc gấp, tiền chờ xác nhận, hợp đồng chờ ký); (2) tháng này thu được bao nhiêu so với dự kiến, ai đang nợ; (3) hợp đồng/tin nào sắp hết; (4) còn bao nhiêu lượt đăng tin.

## 2. Bố cục (desktop): nhóm gấp lên trước
```
┌ Dashboard chủ nhà ───────────── [Tháng 11/2026] (cập nhật hh:mm)      Thông báo 3 · Tin nhắn 2 ┐
│ 0 THIẾT LẬP (chỉ hiện khi thiếu): [KYC chưa xong] [Chưa có TK nhận tiền] [Kênh nhắc đang tắt]    │
│ [Phòng đang thuê 18/24] [Phòng trống 4] [Tin đang đăng 6] [Thu T11: 52,3tr / 68tr] [Quá hạn 3 · 9,6tr] │ KPI
├──────────────────────────────────────────────┬───────────────────────────────────┤
│ 1 VIỆC CẦN LÀM (12 · 3 gấp)                   │ Gói hội viên: Pro · còn 7 lượt     │
│  chips: Yêu cầu thuê | Lịch hẹn | Ký | Xác nhận │   hạn gói 20/12 · [Mua thêm lượt]  │
│  tiền | Quá hạn | Điện nước | Phụ lục | Tất toán│  đơn chưa trả 1 · phí T11 0đ       │
│  | Vi phạm cọc | Khiếu nại | Khác              │                                   │
│  ▸ [GẤP] Xác nhận thanh toán P203 · còn 3h  ›  │                                   │
├──────────────────────────────────────────────┴───────────────────────────────────┤
│ 2 TIỀN: chờ tôi xác nhận · bị từ chối · tôi phải hoàn · tự xác nhận 30 ngày · phí chậm/nợ chuyển kỳ · phiếu PDF lỗi │
│ 3 HỢP ĐỒNG: theo trạng thái · sắp hết 60/30 ngày · tất toán/hoàn cọc · SmartCA · hồ sơ cọc                     │
├──────────────────────────────────────────────┬───────────────────────────────────┤
│ 4 PHÒNG & TIN: tin sắp hết · tin cần sửa ·     │ 5 KHÁCH & LỊCH HẸN: lịch hẹn sắp tới │
│   chờ duyệt · chưa gán chi nhánh               │   · yêu cầu cạnh tranh · giữ chỗ    │
├──────────────────────────────────────────────┴───────────────────────────────────┤
│ 6 KHIẾU NẠI đang mở · Lối tắt: [Đăng tin] [Chi nhánh] [Yêu cầu thuê] [Lịch xem nhà] [Hợp đồng] [Thanh toán] [Phân tích] │
└──────────────────────────────────────────────────────────────────────────────────┘
```
Màn nhỏ (<768px): KPI cuộn ngang 2 cột; nhóm xếp 1 cột theo thứ tự Thiết lập → Việc cần làm → Tiền → Hợp đồng → Phòng & tin → Khách & lịch hẹn → Khiếu nại → Gói hội viên → Lối tắt. Tiêu đề nhóm bấm thu gọn, kèm huy hiệu "n việc · m gấp"; nhóm không có gì cần xử lý tự thu gọn thành một dòng xanh. Danh sách tối đa 5 dòng + "Xem thêm". Card bo `rounded-2xl`, token Tailwind sẵn có như các trang dashboard khác.

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
- Bổ sung (C4): `oldestPendingSince = min(submitted_at)` của tin PENDING_REVIEW (`Listing.java:65`); thẻ ghi "chờ duyệt n (lâu nhất 2 ngày)", vàng/đỏ theo câu hỏi 7.

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
- Nhóm chip (map `WorkType`): **Yêu cầu thuê chờ phản hồi** = RENTAL_REQUEST_PENDING (:843-849, còn hạn `respondBy`); **Lịch hẹn cần xác nhận** = VIEWING_PENDING (PENDING chưa quá giờ, hoặc đã xác nhận nhưng khách xin đổi giờ, :852-863); **Hợp đồng cần ký** = OWNER_SIGN + SIGNING_REOPEN (:622, :616); **Xác nhận tiền** = PAYMENT_CONFIRM + PAYMENT_REVIEW + OWNER_REFUND; **Quá hạn** = PAYMENT_OVERDUE + PAYMENT_EXTENSION_REQUEST + TERMINATION_RESPONSE; **Điện nước** (tách khỏi "Khác", L4) = METER_UPCOMING + METER_TODAY + DRAFT_MISSED + METER_AUTOFILLED (hạn chốt chỉ số, trễ thì hệ thống tự điền; `monthly_invoices.meter_*_logged_at`); **Phụ lục/đề nghị** (L5) = AMENDMENT_RESPONSE (`respondBy`, `signBy`); **Tất toán / Vi phạm cọc / Khiếu nại** = việc ngoài attention (3.14); **Khác** = còn lại (LEASE_EXPIRING, tin hết hạn/bị từ chối, UNPLACED_*…).
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
- Công thức: `listings` của owner, hiệu lực PUBLISHED, `expires_at` trong (realNow, realNow + 7 ngày], sắp tăng dần, 5 dòng (index `idx_listings_status_expires`, `Listing.java:17`). Hiện `title`, `expiresAt`, ngày còn lại, `viewCount` (`Listing.java:119`), `favorites` = số lượt lưu (`select listing_id,count(*) from listing_favorites where listing_id in (...) group by listing_id`, `idx_favorite_listing`; chỉ đếm, không lộ người) và cờ `noLeads` (C18: đăng ≥7 ngày, 0 hẹn xem và 0 yêu cầu thuê → gợi ý đổi giá/ảnh khi gia hạn). Tin đã hết hạn nằm ở việc LISTING_EXPIRED của 3.5 (:912-916), không lặp lại ở đây.
- Click: `/dashboard/properties/view?id={id}` (href đã có ở :915). Trống: ẩn khối, hiện "Không có tin nào sắp hết hạn".

### 3.9 Gói hội viên (lượt đăng còn lại)
- Dùng lại `GET /membership/me` (`AC/payment/MembershipController.java:61-63` → `LS/membership/MembershipService.View`, :43-45, `view()` :80-90): `tier`, `remainingUses`, `reservedUses`, `expiresAt` (hạn gói trả phí; null nếu gói FREE), `quotaResetsAt` (FREE), `freeUsed`, `unlimited`, `addonRemaining`, `addonReserved`, `addonExpiresAt`. Thời gian membership theo `BillingTime` (`MembershipService.java:23`). Có sẵn `WEB/services/membership.service.ts`; không cần endpoint mới (khối tải độc lập, lỗi không kéo theo khối khác).
- Hiển thị: "Còn R lượt (đang giữ H)" = `remainingUses` + lượt thêm `addonRemaining`; hạn `expiresAt` (hoặc "Làm mới ngày quotaResetsAt"); cảnh báo vàng khi còn ≤1 lượt hoặc hạn ≤7 ngày. Click `/dashboard/membership`. Trống/lỗi: ẩn số, nút "Xem gói".
- Bổ sung đơn hội viên (L9, L11): xem 3.16.

### 3.10 Lối tắt
Tĩnh, không gọi API: Đăng tin `/dashboard/properties/new`, Chi nhánh `/dashboard/branches`, Yêu cầu thuê `/dashboard/rental-requests`, Lịch xem nhà `/dashboard/viewing-schedules`, Hợp đồng `/dashboard/landlord/contracts`, Thanh toán `/dashboard/invoices`, Phân tích `/dashboard/analytics`. Mỗi nút có badge số từ chip 3.5 khi >0.

### 3.11 Thiết lập tài khoản (nhóm 0, chỉ hiện khi thiếu)
- **KYC**: `GET /users/me/kyc` (`KycController.java:25`), gọi riêng, lỗi không chặn trang. Khác VERIFIED → thanh ngang: NOT_VERIFIED/EXPIRED "Xác minh danh tính để đăng tin và ký hợp đồng" (chưa KYC thì chặn đăng tin/ký: `KycGateService.requireVerifiedIdentity`), PENDING/REVIEW_REQUIRED "Đang chờ duyệt", REJECTED kèm `rejectionReason`. Bấm → `kycHref()` (`WEB/lib/kyc-gate.ts`). Không dùng `/users/me/profile` (có CCCD).
- **Onboarding và tài khoản nhận tiền**: `GET /users/me/onboarding-status` (`UserController.java:39`) chưa xong → "Hoàn tất hồ sơ để nhận tiền"; `setup.hasReceivingAccount` (summary: tồn tại `bank_accounts` của user, mặc định nhận, chưa xóa; không trả số tài khoản). Thiếu → "Chưa có tài khoản nhận tiền: không phát hành được yêu cầu thanh toán/QR". Bấm → trang hồ sơ/ngân hàng có sẵn (route kiểm khi code).
- **Kênh nhắc**: `GET /notifications/preferences` (`notification.controller.ts:75`); vàng khi email PAYMENT/CONTRACT hoặc push tắt ("có thể bỏ lỡ hạn xác nhận tiền/ký"). Chỉ đọc cờ, không đọc địa chỉ; không có API liệt kê thiết bị push nên không báo "chưa có thiết bị".
- Trống: không hiện gì. Một nguồn lỗi: bỏ dòng đó.

### 3.12 Huy hiệu thông báo và tin nhắn (tiêu đề trang)
Thông báo chưa đọc: `GET /notifications/unread-count` (`notification.controller.ts:55`) → trung tâm thông báo. Tin nhắn chưa đọc: `GET /chat/conversations` (`chat.controller.ts:34`), **chưa kiểm chứng** có trả số chưa đọc; nếu không thì ẩn (câu hỏi 9). Số 0 hoặc lỗi: ẩn.

### 3.13 Nhóm Tiền: dòng phụ (khóa `revenue` của summary; tiền chỉ đọc `payment_requests`, không đọc `snapshot_json`)
| Dòng | Định nghĩa | Click / trống |
|---|---|---|
| Chờ tôi xác nhận (L1) | `payee_id=owner`, `TENANT_TO_LANDLORD`, `TRANSFER_REPORTED`, active: số, Σ `total_amount`, `confirmation_due_at` sớm nhất (đếm ngược theo `PaymentTimePolicy`). Hết hạn thì **hệ thống tự xác nhận** (`autoConfirmOverdueReceipt`, job 5 phút); ngưỡng cảnh báo lấy từ cấu hình `rental.receipt-auto-confirm-warning-hours`, không hardcode ở web | `/dashboard/payments?paymentId=` · "Không có khoản chờ xác nhận" |
| Bị tôi từ chối, chờ người thuê khai lại (L6) | `payee_id=owner`, `REJECTED`: số, Σ, `due_at` sớm nhất (không hiện `rejected_reason`) | `/dashboard/invoices` · ẩn khi 0 |
| Tôi phải hoàn (L4) | `payer_id=owner`, `LANDLORD_TO_TENANT` (DEPOSIT_REFUND, INITIAL_PAYMENT_REFUND), `AWAITING_TRANSFER`/`REJECTED`: số, Σ, hạn sớm nhất; quá hạn tô đỏ, ghi hạn xử lý của hệ thống, không hứa gia hạn (bên trễ bị hệ thống tự xử lý) | `/dashboard/payments?paymentId=` · ẩn khi 0 |
| Tự xác nhận 30 ngày (L5) | số `payment_events` `RECEIPT_CONFIRMED` có `actor_id='SYSTEM'` trong 30 ngày của khoản `payee_id=owner`; >0 = chủ nhà bỏ sót thông báo. Hiện nhận biết bằng chuỗi `RECEIPT_AUTO_CONFIRM_REASON` (`PaymentRequestService.java:637`, dễ vỡ, câu hỏi 8) | `/dashboard/invoices` · ẩn khi 0 |
| Phí chậm, nợ chuyển kỳ (L12) | `Σ monthly_invoices.late_fee_amount` kỳ đến hạn trong tháng (HĐ của owner) và `Σ` nợ `ROLLED_OVER` chưa thu (`deferred_at`; tên cột tiền kiểm khi code). Phí chậm đã nằm trong hóa đơn kỳ sau nên **tách riêng, không cộng vào "Đã nhận/Dự kiến"** | `/dashboard/invoices` · ẩn khi 0 |
| Phiếu thu PDF lỗi (L7) | `payment_receipts` `landlord_id=owner`, `pdf_status=FAILED` hoặc `PENDING` quá 5 phút, 90 ngày gần nhất: số | `/dashboard/invoices` (nút thử lại `POST /payment-receipts/{id}/retry`) · ẩn khi 0 |
| Tiền cọc nhận trong tháng | `depositReceivedVnd`: RESERVATION_DEPOSIT + dòng `DEPOSIT` đã CONFIRMED trong tháng; dòng phụ (câu hỏi 1) | không bấm |

### 3.14 Việc cần làm ngoài "Cần chú ý" (nhóm 1)
`collect` của attention không có WorkType cho 5 loại dưới đây. Service dashboard truy vấn riêng, trả cùng dạng item `{key,type,contextLabel,dueAt,clock,priority,href}`, trộn vào `todo.items` theo ưu tiên/hạn và cộng vào `todo.total/urgent`. Không sửa `BranchAttentionService`; vì vậy số của `GET /branches/attention-summary` KHÔNG gồm các loại này (câu hỏi 6). Mỗi mục chỉ tồn tại khi điều kiện nguồn còn đúng.
| type | điều kiện / nguồn | hạn | đích |
|---|---|---|---|
| DEFAULT_NOTICE (C11) | `rental_requests` của owner có `default_notice_at`, chưa `default_disputed_at`, chưa kết thúc (cột kết thúc kiểm khi code) | `default_respond_by` | `/dashboard/rental-requests?requestId=` |
| COMPLAINT_RESPOND (L8) | `dispute_cases` chưa RESOLVED, `response_due_for = owner` | `respond_by` | khiếu nại (3.18) |
| CLOSURE_ACTION (L7) | `contract_closures` của HĐ owner ở `WAITING_HANDOVER`/`PROPOSED`/`REFUND_PENDING`/`BALANCE_PENDING`; hoặc HĐ hết kỳ chưa có closure (NORMAL, không hạn) | `refund_due_at` | `…/contracts/{id}?tab=end` |
| SIGNATURE_FAILED (L10) | `signature_requests` của HĐ chờ ký ở `FAILED`/`EXPIRED`/`REJECTED`, hoặc `PENDING_USER_CONFIRMATION` sắp hết hạn | `expires_at` | `…?tab=sign` |
| DEPOSIT_SIGN (L11) | hồ sơ cọc (`agreement_kind=DEPOSIT`) chờ ký/bị chặn | `tenant_signing_due_at` | `…?tab=sign` |
Priority theo `BranchAttentionService.priority` (URGENT ≤24h hoặc quá hạn). Chip: DEFAULT_NOTICE → "Vi phạm cọc"; COMPLAINT_RESPOND → "Khiếu nại"; CLOSURE_ACTION → "Tất toán"; SIGNATURE_FAILED, DEPOSIT_SIGN → "Hợp đồng cần ký".

### 3.15 Nhóm Hợp đồng: bổ sung cạnh 3.6 (L9, L7, L10, L11)
- **Theo trạng thái**: `select status,count(*) from contracts where landlord_id=owner and active and parent_contract_id is null and agreement_kind<>'DEPOSIT' group by status` (`idx_contract_landlord_status`) → 3 số: Đang hiệu lực (ACTIVE), Chờ ký (`*_SIGNATURE_PENDING`), Nháp/chờ duyệt (DRAFT, PENDING_REVIEW). Không dùng `/contracts/ai/summary` (nạp tới 5000 hàng vào bộ nhớ). Click `/dashboard/landlord/contracts`. Trống: "Chưa có hợp đồng".
- **Tất toán và hoàn cọc**: `contract_closures` của HĐ owner theo `status` (`WAITING_HANDOVER`, `PROPOSED`, `REFUND_PENDING`, `BALANCE_PENDING`, `DISPUTED`; `ContractClosure.java:26-39`), `endedNotClosed` (HĐ `days ≤ 0` chưa có closure, trùng dòng đỏ ở 3.6), `refundVnd`/`nextRefundDueAt` (`refund_amount`, `refund_due_at`; quá hạn tô đỏ, ghi hạn xử lý của hệ thống). Click `…/contracts/{id}?tab=end`. Ẩn khi mọi số bằng 0.
- **Ký số SmartCA**: số `signature_requests` của HĐ đang chờ ký ở `FAILED`/`EXPIRED`/`REJECTED` (cần ký lại) và `PENDING_USER_CONFIRMATION` (kèm `expires_at` gần nhất). Chỉ đếm; không gọi VNPT (`/signatures/certificates`, `refresh`). Click `…?tab=sign`.
- **Hồ sơ cọc chờ ký**: HĐ `agreement_kind=DEPOSIT` đang chờ ký: số + `tenant_signing_due_at` gần nhất; `blockedReason` chỉ xem khi mở HĐ (`GET /rental-requests/{id}/deposit-agreement` theo từng yêu cầu, không gọi hàng loạt). Click `…?tab=sign`.

### 3.16 Nhóm Phòng & tin: bổ sung (C3, C15, L9, L11)
- **Tin cần sửa**: `listings` của owner, `active`, trạng thái hiệu lực REJECTED/VIOLATION/HIDDEN: số theo trạng thái + 5 dòng mới nhất (`title`, `status`, `status_reason` cắt 120 ký tự, `updated_at`). Gồm cả tin ngoài chi nhánh (attention chỉ báo REJECTED/EXPIRED của tin thuộc chi nhánh; việc vẫn ở 3.5, khối này là danh sách đủ). Click `/dashboard/properties/view?id=`. Trống: ẩn.
- **Chưa gán chi nhánh**: `listings` active `branch_id is null` + HĐ gốc chưa gán phòng/khay (`BranchContractAssignment`, `findManagedRoots`; cách đếm kiểm khi code) → "n tin, m hợp đồng chưa gán chi nhánh" → `/dashboard/branches` (`assignment-candidates`). Ẩn khi 0.
- **Đơn hội viên** (bổ sung 3.9): `membership_orders` của owner: PENDING còn `expires_at` (không có job hết hạn đơn nên luôn lọc `expires_at > now`; chưa trả thì mất quyền đăng), FAILED/REVIEW trong 30 ngày, và `paidVndMonth` = Σ PAID có `paid_at` trong tháng (chi phí hội viên, tách khỏi doanh thu). Click `/dashboard/membership`. Ẩn khi 0.

### 3.17 Nhóm Khách & lịch hẹn: bổ sung cạnh 3.7 (C8, C10)
- **Yêu cầu cạnh tranh**: `select listing_id,count(*),min(respond_by) from rental_requests where owner_id=:u and status='PENDING' and respond_by>now group by listing_id having count(*)>=2 order by min(respond_by) limit 5` (+ tiêu đề tin). Không dùng `GET /rental-requests/owner/conflicts` (nạp mọi yêu cầu PENDING, truy vấn địa chỉ từng tin). Hiện "Tin X: 3 yêu cầu đang chờ" → `/dashboard/rental-requests` (lọc theo tin: kiểm khi code). Không trả SĐT/email khách. Ẩn khi trống.
- **Giữ chỗ đang chạy**: `rental_requests` owner, `ACCEPTED`, `hold_expires_at > now` (`RentalRequest.java:120-126`), ≤10; dòng: tin/phòng, tên người thuê, đếm ngược `holdExpiresAt`, `waitingFor` (TENANT/LANDLORD từ `nextAction.actor` của `RentalOnboardingService.get`, như P2 W4). Việc chủ nhà phải làm trong giữ chỗ vẫn ở 3.5. Click `/dashboard/rental-requests?requestId=`. Ẩn khi trống.

### 3.18 Nhóm Khiếu nại (L8)
Nguồn mới (chưa có `GET /complaints/mine`; `ComplaintController` chỉ liệt kê theo từng yêu cầu thuê, `:18`): `dispute_cases` mà owner là một bên (`payer_id`/`payee_id`; tên cột kiểm khi code), `status != RESOLVED`: `openCount`, `dueFromMeCount` (`response_due_for = owner`), 5 dòng `{id, contractNumber, category, status, respondBy, responseDueFor: YOU|OTHER, href}`. **Không trả `reason`/`decision`/id bên kia** (văn bản tự do, có thể chứa PII). Vụ cần mình trả lời cũng nằm ở todo (COMPLAINT_RESPOND). Click → khiếu nại của yêu cầu thuê/HĐ (route kiểm khi code). Trống: "Không có khiếu nại đang mở". Index mới `dispute_cases (payer_id,status)`, `(payee_id,status)` (hiện chỉ `idx_dispute_queue(status,opened_at)`).

## 4. API mới tổng hợp
`GET /dashboard/landlord/summary` (qua Gateway: `/api/v1/dashboard/landlord/summary`, cùng kiểu `BranchAttentionController`). Controller `AC/listing/LandlordDashboardController.java` (mới), service mới `LandlordDashboardService` trong `hs-api-service` (module lắp ráp đã phụ thuộc mọi module, AGENTS.md; contract→payment→listing nên service này gọi được cả `BranchAttentionService`, `PaymentRequestRepository`, `ListingRepository`, `MembershipService`).
- Auth/phạm vi: `CurrentUser.requireUserId()` (như `BranchAttentionController.java:26`); mọi truy vấn lọc `owner/landlord/payee = userId`, không nhận userId/branchId từ request. Không `@PreAuthorize` mới (giống endpoint "Cần chú ý"; dữ liệu chỉ của chính người gọi). Header `Cache-Control: private, no-store` (:37-41); cache phía server 30 giây theo userId nếu cần (Redis `StringRedisTemplate` đã dùng ở `ListingViewService`; giả định).
- Query: `?month=YYYY-MM` (mặc định tháng hiện tại theo BillingTime; định dạng kiểm như `BranchOverviewController.java:16,43-50`; lỗi → `ErrorCode.INVALID_REQUEST`); `include=` danh sách khóa nhóm `setup,money,todo,contracts,rooms,leads,complaints,membership` (mặc định tất cả; web gọi lần 1 `include=setup,money,todo,rooms` cho nửa trên màn hình, lần 2 phần còn lại khi cuộn; khóa lạ → INVALID_REQUEST).
- Response (khóa của nhóm được `include` luôn có, null/0 khi không có):
```json
{ "result": { "billingNow":"…Z","realNow":"…Z","month":"2026-11","errors":{"revenue":false},
 "setup":{"hasReceivingAccount":true},
 "rooms":{"total":24,"occupied":18,"vacant":4,"reserved":1,"other":1,"byStatus":{"OCCUPIED":18,"VACANT":3,"UNLINKED":1}},
 "listings":{"published":6,"pendingReview":1,"draft":2,"oldestPendingSince":"…Z","unplaced":{"listings":1,"contracts":0},
   "needsFix":{"REJECTED":1,"VIOLATION":0,"HIDDEN":2,"items":[{"id":"…","title":"…","status":"REJECTED","reason":"…","updatedAt":"…Z","href":"…"}]}},
 "revenue":{"receivedVnd":52300000,"refundedVnd":0,"expectedVnd":68000000,"receiptCount":14,"lateFromPreviousVnd":4000000,"overdueCount":3,"overdueVnd":9600000,"depositReceivedVnd":0,
   "awaitingConfirm":{"count":4,"vnd":14000000,"nextDueAt":"…Z"},"rejected":{"count":1,"vnd":3500000,"oldestDueAt":"…Z"},"refundsOwed":{"count":1,"vnd":7000000,"nextDueAt":"…Z"},
   "autoConfirmed30d":0,"lateFeeVnd":0,"rolledOverVnd":0,"receiptPdfIssues":0},
 "todo":{"total":12,"urgent":3,"groups":{"RENTAL_REQUEST":2,"VIEWING":3,"SIGN":1,"PAYMENT_CONFIRM":4,"OVERDUE":3,"METER":2,"AMENDMENT":1,"CLOSURE":0,"DEFAULT":0,"COMPLAINT":0,"OTHER":5,"UNBRANCHED_RENTAL":0,"UNBRANCHED_VIEWING":0},
   "items":[{"key":"…","type":"PAYMENT_CONFIRM","roomCode":"P203","contextLabel":"…","dueAt":"…","clock":"BILLING","priority":"URGENT","href":"/dashboard/…"}]},
 "contracts":{"byStatus":{"ACTIVE":18,"SIGNING":2,"DRAFT":1},"closures":{"WAITING_HANDOVER":1,"REFUND_PENDING":0,"DISPUTED":0,"endedNotClosed":1,"refundVnd":0,"nextRefundDueAt":null},
   "signatureIssues":{"failed":0,"pendingConfirm":1,"nearestExpiresAt":"…Z"},"depositAgreements":{"waitingSign":1,"nextDueAt":"…Z"}},
 "expiringContracts":[{"contractId":"…","contractNumber":"HD-0042","roomCode":"P101","tenantName":"…","endDateExclusive":"2027-01-16","daysRemaining":24,"band":"WITHIN_30","href":"…?tab=end"}],
 "appointments":[{"id":"…","at":"…Z","listingTitle":"…","renterName":"…","status":"CONFIRMED","href":"…"}],
 "expiringListings":[{"id":"…","title":"…","expiresAt":"…Z","daysRemaining":4,"viewCount":134,"favorites":3,"noLeads":false,"href":"…"}],
 "competing":[{"listingId":"…","title":"…","pendingCount":3,"nextRespondBy":"…Z","href":"…"}],
 "holds":[{"rentalRequestId":"…","listingTitle":"…","renterName":"…","holdExpiresAt":"…Z","waitingFor":"TENANT","href":"…"}],
 "complaints":{"openCount":1,"dueFromMeCount":1,"items":[{"id":"…","contractNumber":"HD-0042","category":"…","status":"…","respondBy":"…Z","responseDueFor":"YOU","href":"…"}]},
 "membership":{"pendingOrders":1,"nextOrderExpiresAt":"…Z","failedOrders30d":0,"paidVndMonth":0} } }
```
- Triển khai ít code nhất: (a) thêm `BranchAttentionService.overviewForOwner(ownerId)` trả `{Map<String,List<Work>>, Map<RoomStatus,Integer>, List<Expiry>}` từ cùng `Scope` (thay vì viết lại ~700 dòng); `attention()`/`summary()` giữ nguyên hợp đồng (test `BranchAttentionTest`, `BranchAttentionPostgresTest`, `BranchAttentionApiTest` phải vẫn xanh). (b) các số tiền, tin, lịch hẹn là truy vấn gộp mới (JPQL/native), mỗi loại 1 query. (c) Các nhóm bổ sung sau kiểm kê đọc qua `LandlordDashboardReadRepository` (~18 truy vấn gộp có chỉ mục), mọi danh sách có `LIMIT` (việc 10, hợp đồng 10, tin 5, hẹn 5, cạnh tranh 5, giữ chỗ 10, khiếu nại 5); `holds` dùng `RentalOnboardingService.get` ≤10 như P2 W4.
- Index cần: `payment_receipts (landlord_id, issued_at DESC)` (hiện chỉ có `idx_receipt_pdf_queue`, không có chỉ mục theo chủ nhà, `PaymentReceipt.java:15`); `payment_requests (payee_id, status, due_at)` (hiện `idx_payment_request_payee_status (payee_id, status)`, `PaymentRequest.java:22`); `viewing_appointments (owner_id, status, appointment_date)`; mới sau kiểm kê: `dispute_cases (payer_id,status)` và `(payee_id,status)`, `contract_closures (status, refund_due_at)`, `signature_requests (contract_id,status)`, `membership_orders (owner_id,status)`.
- Lỗi từng phần: service bọc mỗi khối trong try/catch, thêm `errors:{"revenue":true}` thay vì 500 cả trang (giả định, cần duyệt).

## 5. Biểu đồ
Không có biểu đồ ở P1 (chỉ thẻ KPI + thanh tiến độ `đã nhận/dự kiến` bằng div). Ghi nhận cho P3: `WEB/package.json` **không có thư viện biểu đồ** (không recharts/chart.js; chỉ `lucide-react`, `date-fns`…).

## 6. Hiệu năng & bảo mật
- Chi phí: `collect` nạp theo lô (`batched`) phòng, tin, hợp đồng, hóa đơn, yêu cầu thanh toán, phụ lục của MỌI chi nhánh trong một transaction `REPEATABLE_READ` (`BranchAttentionService.java:106-112`); `GET /branches/attention-summary` hiện đã chạy y hệt ở trang Chi nhánh, nên P1 không nặng hơn một lần tải trang đó. Ước lượng 10k tin/100k thanh toán: số tiền dùng 2-3 truy vấn có chỉ mục trên (`landlord_id, issued_at`), không quét bảng; cache 30 giây chống F5 liên tục. Chưa đo (giả định) → test hiệu năng bằng container PostgreSQL tạm.
- N+1: không gọi `/overview` theo từng chi nhánh (mỗi lần 1 tháng, `BranchOverviewController.java:27`); không gọi `GET /listings/me` rồi cộng ở client.
- Rò rỉ: trả tên người thuê và mã phòng của chính chủ nhà; không trả SĐT/email khách hẹn, CCCD, số tài khoản (`payer/payee_bank_account_snapshot`, `snapshot_json`). Log chỉ ghi id, không ghi tên/tiền theo người.
- Dữ liệu tiền chỉ đọc `BigDecimal`, không float (`PS/AGENTS.md`).
- Sau kiểm kê: `include` cho tải hai lần (nửa trên trước), cache 30 giây theo `(userId, month, include)`; N+1 duy nhất là `holds` (≤10). Không trả `renterPhone/Email`, `reason/decision` của khiếu nại, `rejected_reason`, số tài khoản.

## 7. Kiểm thử cần viết
Backend (`hs-api-service`, `hs-contract-service`):
- Số liệu: dữ liệu mẫu với 2 chi nhánh, 1 tin không chi nhánh → `rooms`, `listings` (tin PUBLISHED quá `expires_at` KHÔNG tính "đang đăng"), `todo.groups` bằng tổng `/branches/attention-summary` + nhóm không-chi-nhánh.
- Doanh thu: phiếu cuối ngày 30/11 23:59:59 và 00:00:00 01/12 (biên tháng, Asia/Ho_Chi_Minh); INITIAL có dòng DEPOSIT bị trừ; RESERVATION_DEPOSIT và DEPOSIT_REFUND bị loại; INITIAL_PAYMENT_REFUND trừ ròng; phiếu membership không lọt vào; tổng 0 không phiếu; một phiếu không tính hai lần (unique `uq_receipt_payment`).
- Quá hạn: `overdueCount` == số item PAYMENT_OVERDUE của attention.
- Hạn hợp đồng: biên 30/31/60/61 ngày, hợp đồng có `agreedEarlyEndExclusive`, `effectiveLeaseMonths` (khớp `BranchLeaseEndParityTest` hiện có), exclusive end (end-1 là ngày cuối).
- **Từ chối/cô lập**: user B gọi không thấy dữ liệu chủ A (hai owner trong cùng DB); chưa đăng nhập → 401 (`ErrorCode.UNAUTHENTICATED`); `month` sai định dạng → INVALID_REQUEST; response không chứa `renterPhone`, `renterEmail`.
- `BranchAttentionTest`/`BranchAttentionPostgresTest`/`BranchAttentionApiTest` giữ xanh sau khi tách `Scope`.
- **Nhóm sau kiểm kê**: (1) `awaitingConfirm`/`rejected`/`refundsOwed` đúng số, tiền, hướng `direction` và biến mất khi CONFIRMED; `autoConfirmed30d` chỉ tính `actor_id='SYSTEM'` trong 30 ngày; `lateFeeVnd`/`rolledOverVnd` đúng tháng; `receiptPdfIssues` (FAILED, PENDING >5 phút). (2) `todo`: 5 loại ngoài attention có/biến mất theo điều kiện, `todo.total` = attention + loại thêm, METER/AMENDMENT tách khỏi OTHER, `/branches/attention-summary` không đổi. (3) `contracts.byStatus` bằng đếm trực tiếp, loại hồ sơ cọc/phụ lục; `closures`, `signatureIssues`, `depositAgreements`. (4) `needsFix` gồm tin ngoài chi nhánh, `reason` cắt 120 ký tự; `oldestPendingSince`; `unplaced`; `favorites` chỉ là số; `noLeads`. (5) `competing` (≥2 PENDING còn hạn), `holds`, `complaints` chỉ của chính owner, không có `reason/decision`. (6) cô lập owner B không thấy dữ liệu của A; `include` lạ → INVALID_REQUEST; `include=setup` không chạy truy vấn nhóm khác (đếm SQL); số câu SQL không đổi khi tăng 3→30 chi nhánh (trừ `holds`).
Frontend: render từng khối ở 3 trạng thái (tải/trống/lỗi), một khối lỗi không làm mất khối khác, link đúng route, màn <768px; chạy `npm run lint` và `next build` (repo không có test runner cho component ngoài vài `*.test.ts` trong `lib/`).

## 8. Câu hỏi mở (mặc định đề xuất)
1. Tiền cọc có tính vào doanh thu không? Đề xuất: KHÔNG (cọc giữ hộ); "Đã nhận" = tiền thuê + phí, trừ hoàn tiền thuê đầu kỳ; cọc hiện ở dòng phụ "Tiền cọc nhận trong tháng".
2. "Dự kiến" chỉ gồm hóa đơn đã phát hành; có cần cộng thêm kỳ chưa phát hành (ước từ giá thuê hợp đồng)? Đề xuất: chưa, ghi chú "chưa gồm kỳ chưa phát hành".
3. Hợp đồng/tin chưa gán chi nhánh có hiện trong "sắp hết hạn" không? Đề xuất: v1 chỉ hợp đồng đã gán chi nhánh (khớp mục "Cần chú ý"); hợp đồng chưa gán nhắc ở khối việc "Chưa thuộc chi nhánh".
4. Ngưỡng "tin sắp hết hạn"? Đề xuất 7 ngày.
5. Tháng cũ thiếu phiếu (không backfill): cần backfill `payment_receipts` cho `payment_requests CONFIRMED` cũ không? Đề xuất: không bắt buộc cho P1 (chỉ tháng hiện tại); quyết định riêng cho P3 (báo cáo 12 tháng).

6. Việc ngoài attention (3.14) làm `todo.total` lệch số "Cần chú ý" của trang Chi nhánh. Đề xuất: chấp nhận ở v1 (ghi chú trên UI), thêm WorkType vào attention sau.
7. Ngưỡng tin chờ duyệt lâu (3.2): đề xuất vàng >24 giờ, đỏ >72 giờ (chưa có SLA chính thức).
8. "Tự xác nhận 30 ngày" (3.13) nhận biết bằng chuỗi lý do: thêm `PaymentEventType` riêng trước khi code? Đề xuất: có (migration nhỏ); chưa kịp thì ẩn dòng.
9. Route chưa kiểm: trang hồ sơ/ngân hàng, xem khiếu nại, lọc `listingId` ở trang yêu cầu thuê (thiếu thì mở trang cha). Chat chưa đọc (3.12): nếu API không trả số chưa đọc thì bỏ huy hiệu ở v1.
10. Nợ chuyển kỳ (3.13): hiện tổng chưa thu hay chỉ phát sinh trong tháng? Đề xuất: tổng chưa thu; phí chậm: trong tháng.
11. Khiếu nại (3.18) chỉ trả danh mục + hạn, không trả lý do/quyết định (có thể chứa PII): đồng ý?
12. Hạn xử lý của hệ thống với bên trễ (hoàn tiền, xác nhận, tất toán): chủ dự án chốt nội dung hiển thị cho từng loại; hiện chỉ ghi hạn, không hứa kết quả.

## 9. Không đưa vào (lý do)
| Tín hiệu (mã kiểm kê) | Lý do |
|---|---|
| Mẫu HĐ riêng: số mẫu, phiên bản lỗi (L13) | Giá trị thấp ở tổng quan, thao tác hiếm; thuộc trang mẫu (`/contracts/templates/mine`) |
| Sổ dùng lượt hội viên (C6, `membership_events`) | Dùng để đối soát, không cần giám sát; thuộc `/dashboard/membership`; chưa có endpoint đọc |
| Lượt xem theo ngày/người xem duy nhất (C13) | Chưa có dữ liệu: ghi `listing_view_daily` trước (P3 §4.2); hiển thị ở P3 |
| Thời gian phản hồi trung bình của chủ nhà (C17) | Chưa có dữ liệu: ghi `approved_at/rejected_at/cancelled_at` (lịch hẹn) và `responded_at` (yêu cầu thuê) trước; hiển thị ở P3 §6 |
| Bãi xe: sức chứa so với đặt chỗ (C16) | Thuộc trang chi nhánh; chưa có API tổng hợp; không ảnh hưởng việc hôm nay |
| Quyền hiện có (`/users/me/permissions`) | Giá trị thấp, chỉ để giải thích tính năng bị ẩn |

## 10. Hợp đồng API đợt 1 (triển khai 2026-11-28)

Mục này ghi hình dạng DTO thực tế, thay cho JSON minh họa và các giả định chưa đối chiếu ở trên.

### Endpoint, quyền và tham số

- Core `GET /dashboard/landlord/summary`; qua gateway `/api/v1/dashboard/landlord/summary`.
- Danh tính duy nhất là `CurrentUser.requireUserId()` sau `UserContextFilter`: phải có secret gateway hợp lệ, user ID không rỗng và role USER/ADMIN hợp lệ. Không cần quyền admin để đọc dữ liệu của chính mình (giữ chính sách của `BranchAttentionController`); ADMIN cũng chỉ đọc phạm vi ID của chính mình. `ownerId`, `userId`, `branchId` không đổi phạm vi.
- `month`: đúng một giá trị `YYYY-MM`, năm `0001`–`9999`, tháng `01`–`12`; bỏ qua thì tháng hiện tại của BillingTime tại `Asia/Ho_Chi_Minh`. Giá trị rỗng, lặp hoặc sai định dạng trả HTTP 400 / `INVALID_REQUEST` (`code=1001`).
- `include`: đúng một chuỗi phân cách bằng dấu phẩy với các khóa `setup,money,todo,contracts,rooms,leads,complaints,membership`; bỏ qua thì tất cả. Khóa rỗng/lạ, sai hoa thường hoặc tham số lặp trả HTTP 400; khóa lặp bên trong một chuỗi được gộp. `include=setup` chỉ kiểm tài khoản nhận tiền, không quét attention hoặc chạy truy vấn của nhóm khác.
- Không có danh tính đã xác minh: HTTP 401 / `UNAUTHENTICATED` (`code=1002`). Response thành công có envelope `ApiResponse.result` và `Cache-Control: private, no-store`.
- `errors` có một cờ cho mỗi khóa include (ví dụ `money`, không phải `revenue`); cờ true khi nhóm lỗi. Trường của nhóm không được yêu cầu hoặc phép đọc lỗi là null; các phép đọc còn lại vẫn được thử. Không có cache server mới.

### DTO chính xác

Các số tiền là `BigDecimal` VND; các thời điểm là ISO-8601 Instant; trường nullable được trả null. Mọi response có `billingNow`, `realNow`, `month`, `errors` và các trường nhóm sau:

| include | Trường trong result và các trường con |
|---|---|
| setup | `setup{hasReceivingAccount}` (boolean, không có số tài khoản) |
| rooms | `rooms{total,occupied,vacant,reserved,other,byStatus}`; `listings{published,pendingReview,draft,oldestPendingSince,unplaced{listings,contracts},needsFix{rejected,violation,hidden,items}}`; `expiringListings[]` |
| money | `revenue{receivedVnd,refundedVnd,expectedVnd,receiptCount,overdueCount,overdueVnd,depositReceivedVnd,awaitingConfirm,rejected,refundsOwed,lateFromPreviousVnd,autoConfirmed30d,lateFeeVnd,rolledOverVnd,receiptPdfIssues}` |
| todo | `todo{total,urgent,groups,items}`; groups gồm `RENTAL_REQUEST,VIEWING,SIGN,PAYMENT_CONFIRM,OVERDUE,METER,AMENDMENT,OTHER,UNBRANCHED_RENTAL,UNBRANCHED_VIEWING,DEFAULT,COMPLAINT,CLOSURE` |
| contracts | `contracts{byStatus{ACTIVE,SIGNING,DRAFT},closures{byStatus,endedNotClosed,refundVnd,nextRefundDueAt},signatureIssues{failed,pendingConfirm,nearestExpiresAt},depositAgreements{waitingSign,nextDueAt}}`; `expiringContracts[]` |
| leads | `appointments[]`, `competing[]`, `holds[]` |
| complaints | `complaints{openCount,dueFromMeCount,items}` |
| membership | `membership{tier,remainingUses,reservedUses,expiresAt,quotaResetsAt,addonRemaining,addonReserved,addonExpiresAt,unlimited,pendingOrders,nextOrderExpiresAt,failedOrders30d,paidVndMonth}` |

Các DTO dòng và giới hạn cố định:

| Danh sách/DTO | Trường và giới hạn |
|---|---|
| todo.items | `{key,type,roomCode,contextLabel,dueAt,clock,priority,href}`, tối đa 10, gộp/sắp theo priority rồi thời gian còn lại đúng clock rồi key. `type` gồm 22 giá trị WorkType hiện có + `DEFAULT_NOTICE,COMPLAINT_RESPOND,CLOSURE_ACTION,SIGNATURE_FAILED,DEPOSIT_SIGN,UNBRANCHED_RENTAL,UNBRANCHED_VIEWING`. Hai loại UNBRANCHED là dòng điều hướng tổng hợp, tổng/count vẫn đếm số hồ sơ. |
| expiringContracts | `{contractId,contractNumber,roomCode,tenantName,endDateExclusive,daysRemaining,band,href}`, tối đa 10; lấy trực tiếp từ cùng scope attention, ngày 1–60, band WITHIN_30/WITHIN_60. |
| expiringListings | `{id,title,expiresAt,daysRemaining,viewCount,href,favorites,noLeads}`, tối đa 5; hiệu lực PUBLISHED (gồm PENDING_REVIEW có reviewOperationalStatus=PUBLISHED), `(realNow,realNow+7 ngày]`. |
| listings.needsFix.items | `{id,title,status,reason,updatedAt,href}`, tối đa 5, reason cắt tại SQL 120 ký tự; count theo trạng thái hiệu lực REJECTED/VIOLATION/HIDDEN. |
| appointments | `{id,at,listingTitle,renterName,status,href}`, tối đa 5; lọc/sắp tại DB theo thời gian đề xuất hợp lệ nếu có, kể cả ngày hẹn ban đầu đã qua. Chỉ PENDING/CONFIRMED; không giới hạn tạm 50 dòng rồi lọc trong Java. |
| competing | `{listingId,title,pendingCount,nextRespondBy,href}`, tối đa 5; ≥2 PENDING chưa đến hạn, cả owner của yêu cầu và owner của tin phải khớp caller. |
| holds | `{rentalRequestId,listingTitle,renterName,holdExpiresAt,waitingFor,href}`, tối đa 10 ACCEPTED chưa hết hạn; `waitingFor` lấy từ `RentalOnboardingService.get(...).nextAction.actor` (LANDLORD/TENANT/SYSTEM/NONE), chỉ dùng phương thức đọc không sync/ghi. |
| complaints.items | `{id,contractNumber,category,status,respondBy,responseDueFor,href}`, tối đa 5; chỉ `subject_type=RENTAL`, chưa RESOLVED, caller là payer/payee; status là OPEN/ESCALATED, responseDueFor là YOU/OTHER. Không trả reason/decision/ID bên kia/contact. |
| Pending | `{count,vnd,nextDueAt}`, dùng cho cả awaitingConfirm/rejected/refundsOwed; rejected dùng `nextDueAt` thay vì tên minh họa `oldestDueAt`. |
| closures.byStatus | Các khóa `WAITING_HANDOVER,PROPOSED,REFUND_PENDING,BALANCE_PENDING,DISPUTED`; không đặt trực tiếp vào closures như JSON minh họa. |

### Quy tắc đã đối chiếu schema và các khác biệt được chốt khi triển khai

- `receivedVnd` là **ròng** (thu thuê/phí trừ hoàn INITIAL_PAYMENT_REFUND sau khi loại dòng DEPOSIT_REFUND); `refundedVnd` vẫn trả riêng để giải thích. `receiptCount` chỉ đếm phiếu thu thuê/phí, không đếm phiếu cọc/hoàn. `expectedVnd` không có CANCELLED/EXPIRED; không ước tính kỳ chưa phát hành. `lateFromPreviousVnd` là thu thuê/phí của phiếu trong tháng nhưng due_at trước đầu tháng.
- `overdueCount` đếm đúng các work PAYMENT_OVERDUE của scope attention hiện tại, không viết lại điều kiện hạn/đối soát/gia hạn/thay thế việc. `overdueVnd` lấy khoản tương ứng bằng payment ID hoặc invoice ID, kiểm payee/direction/status rồi trừ cọc INITIAL; tiền cọc điều chỉnh không cộng vào số tiền thuê/phí. Như expiringContracts, phạm vi này là các chi nhánh active của caller, khác với doanh thu/expected bao phủ toàn bộ caller.
- `BranchAttentionService.attention()` và `summary()` giữ nguyên DTO, loại việc và count; chỉ thêm `overview()` dùng cùng scan/scope để lấy phòng và hạn hợp đồng. Năm loại bổ sung không được đưa vào attention-summary. Hợp đồng hết hạn chưa có closure chỉ đếm khi được quản lý trong chi nhánh active của caller; SQL ngày kết thúc dùng đúng precedence của ContractLeaseCalc, có test ngày exclusive, agreedEarlyEndExclusive và effectiveLeaseMonths.
- `unplaced.contracts` là số lease gốc active chưa có assignment tới chi nhánh active của chính caller (không cộng lại các hợp đồng đã ở khay của chi nhánh). `unplaced.listings` là tin active branch_id null.
- Phiếu thu không có cột tiền; các truy vấn luôn join payment_requests. Cả landlord trên receipt và phía chủ nhà trên payment phải khớp caller, kể cả khi dữ liệu liên kết cũ bị sai. Không đọc snapshot_json để cộng tiền.
- `autoConfirmed30d` đếm distinct payment được xác nhận bởi event `RECEIPT_CONFIRMED` / actor SYSTEM, hướng TENANT_TO_LANDLORD, cửa sổ 30 ngày theo **đồng hồ thật của PaymentEvent.createdAt**. Không phụ thuộc chuỗi reason và không cần thêm enum/migration event.
- Phí chậm dùng due_at trong tháng và loại DRAFT/CANCELLED. Nợ chuyển kỳ cộng total_amount của invoice ROLLED_OVER khi invoice đích còn UNPAID/OVERDUE; không đếm hai lần các mắt xích đã được chuyển tiếp. Trả 0 khi đích đã PAID.
- `receiptPdfIssues` dùng cửa sổ issued_at 90 ngày theo BillingTime (cùng đồng hồ với confirmedAt/issuedAt của phiếu); tuổi PENDING >5 phút dùng created_at theo thời gian thật, fallback issued_at cho hàng legacy thiếu created_at. Vì vậy receipt phát hành ở ngày mô phỏng tương lai vẫn báo đúng PDF worker bị chậm. Các phép đọc JDBC nhiều truy vấn trong một khối dùng transaction riêng read-only REPEATABLE_READ; lỗi khối đó không làm hỏng transaction của khối khác.
- Membership quota/lease/deposit/default/complaint deadlines theo BillingTime. Đơn membership có createdAt/expiresAt/paidAt theo Instant.now của checkout/applier; pending/failed30d dùng realNow và chi phí PAID dùng biên tháng VN. SmartCA expiresAt là thời gian thật: SIGNATURE_FAILED có clock REAL, còn các loại việc bổ sung khác có clock BILLING. Signature chỉ xét request active của currentRevisionId, không báo lỗi của bản revision cũ.
- KYC/onboarding, cờ thông báo và unread/chat vẫn gọi API riêng như mục 3.11–3.12; summary không gọi notification/chat/provider ngoài. Các link khiếu nại mở hồ sơ yêu cầu thuê có sẵn. Không tạo framework, dependency, job, cache, Excel hoặc frontend.
- Index P1: `idx_appointment_owner_status_date(owner_id,status,appointment_date)`, `idx_payment_request_payee_status_due(payee_id,status,due_at)`, `idx_receipt_landlord_issued(landlord_id,issued_at)`, `idx_dispute_payer_status(payer_id,status)`, `idx_dispute_payee_status(payee_id,status)`, `idx_closure_status_refund_due(status,refund_due_at)`, `idx_sig_req_contract_status(contract_id,status)`. MembershipOrder đã có owner/status index nên không thêm bản trùng. Theo quy ước repo, index nằm trên @Table/@Index, không thêm một hệ migration riêng.
- Tích hợp P3: ba file chung ViewingAppointment.java / PaymentRequest.java / PaymentReceipt.java ở P1 **chỉ** đổi index; giữ toàn bộ field/lifecycle/backfill của P3, gộp annotation theo tên/cột và chỉ giữ một index tương đương (index dài hơn cùng prefix có thể thay index P1 tương ứng).

### Verification (L3)

Không đọc .env, không truy cập database/dịch vụ ứng dụng sẵn có; PostgreSQL 16.16 là container riêng `hs-p1-codex-978ced4b`, random localhost port 64512, database `hs_branch_attention_test_p1_978ced4b` có guard localhost + tên trước khi chạy create-drop. Container/volume tạm được xóa sau kiểm thử; không xóa container khác.

Lượt cuối kết thúc thành công (exit 0, BUILD SUCCESS) bằng PowerShell trong `D:/Projects/homespace/.wt-dash-p1`:

```powershell
$env:HS_TEST_PG_URL='jdbc:postgresql://127.0.0.1:64512/hs_branch_attention_test_p1_978ced4b'
$env:HS_TEST_PG_USER='p1_test'
$env:HS_TEST_PG_PASSWORD='p1_disposable' # chỉ credential container tạm
.\mvnw.cmd -pl hs-api-service -am '-Dtest=LandlordDashboardPostgresTest,LandlordDashboardServiceTest,LandlordDashboardApiTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

| Test thực sự chạy | Kết quả |
|---|---|
| LandlordDashboardServiceTest | 14 passed, 0 failures/errors/skipped |
| LandlordDashboardApiTest | 19 passed, 0 failures/errors/skipped; MockMvc chạy UserContextFilter và GlobalException thật; thiếu identity, header giả, secret sai, role lạ, đổi owner/branch qua query, month/include sai/lặp đều được kiểm |
| LandlordDashboardPostgresTest | 10 passed, 0 failures/errors/skipped; schema tạo từ entity thật; toàn bộ SQL native mới được thực thi |

Các test hồi quy đã chạy và pass, code của chúng không thay đổi sau lượt đó:

```powershell
.\mvnw.cmd -pl hs-api-service -am '-Dtest=LandlordDashboardServiceTest,LandlordDashboardApiTest,BranchAttentionTest,BranchLeaseEndParityTest,BranchAttentionApiTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 31 contract + 29 API tests passed ở lượt đầu; P1 sau đó được mở rộng và kiểm lại như bảng trên.
.\mvnw.cmd -pl hs-contract-service -am '-Dtest=LandlordDashboardPostgresTest,BranchAttentionPostgresTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# BranchAttentionPostgresTest: 1 passed; P1 trong lượt này có lỗi SQL/fixture được sửa và kiểm lại thành công ở lượt cuối.
```

Riêng hồi quy hiện có: BranchAttentionTest **17 passed**, BranchLeaseEndParityTest **3 passed**, BranchAttentionApiTest **12 passed**, BranchAttentionPostgresTest **1 passed** (kiểm cô lập, không ghi/lock và số query không tăng khi tăng phòng). Tổng **76 test case khác nhau** có bằng chứng pass: 43 P1 ở lượt cuối + 33 hồi quy giữ nguyên; không coi lượt fail là pass.

Bằng chứng PostgreSQL P1 gồm biên đầu/cuối tháng VN (23:59:59 / 00:00), tiền thập phân chính xác, loại cọc/hoàn cọc/cancelled/expired, phiếu khác owner và liên kết receipt hỏng, nguồn membership không lọt vào thu, count quá hạn bằng PAYMENT_OVERDUE, ngày exclusive 0/1/30/31/60/61, agreedEarlyEndExclusive/effectiveLeaseMonths, lịch đề xuất có ngày gốc đã qua, danh sách 5/10 dòng, complaint resolved và nguồn việc hoàn tất biến mất, revision ký cũ không báo lỗi, nợ chuyển kỳ hết khi PAID, dual clock của event/SmartCA/membership/PDF.

- Fixture **100.000 payment_requests + 10.000 listings**: truy vấn expected/needsFix/extraWork hoàn tất trong **111 ms** ở container này, mọi câu có statement_timeout 10 giây; đây là benchmark aggregation, không phải cam kết latency production/toàn endpoint.
- Tăng **3 → 30 chi nhánh**: cùng **16 Hibernate statements**; các projection JDBC chạy bộ truy vấn gộp cố định, không vòng lặp theo chi nhánh. Hibernate statistics không đo số statement JDBC; holds là ngoại lệ ≤10 lần gọi onboarding theo thiết kế.
- Lỗi tìm được rồi sửa: quote tham số Maven có dấu chấm trên PowerShell; ép timestamptz cho CASE tham số native PostgreSQL; kỳ vọng fixture receipt liên kết owner sai; bulk fixture cần version=0 theo DDL Hibernate. Không còn validation exit khác 0 chưa giải quyết.
- Không chạy toàn bộ reactor suite, không gọi live gateway/provider/database, không kiểm frontend/mobile, không đo production latency. Còn việc tích hợp P3/cherry-pick của điều phối; phạm vi overdue/expiry theo chi nhánh và các DTO khác JSON minh họa đã ghi rõ ở trên.
| Số tin đã xem (`/history/count`) | Dành cho người thuê; tự xóa sau 30 ngày; thuộc trang lịch sử xem |
