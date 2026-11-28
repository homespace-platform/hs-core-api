# P3 — Phân tích & Báo cáo chủ nhà (`/dashboard/analytics`)

Trạng thái: ĐÃ DUYỆT (AUDIT_LOG 2026-11-28); API phase 1 được triển khai trong worktree P3. Frontend/mobile là đợt sau.
Rút gọn: **API** = `hs-core-api`, **WEB** = `hs-web-app`, **CS** = `API/hs-contract-service/src/main/java/com/hs/contract`, **LS** = `API/hs-listing-service/src/main/java/com/hs/listing`, **PS** = `API/hs-payment-service/src/main/java/com/hs/payment`, **AC** = `API/hs-api-service/src/main/java/com/hs/api/controller`. Quy ước tiền/giờ/phạm vi như `P1-landlord-dashboard.md` mục 3 (Asia/Ho_Chi_Minh, `BillingTime`, doanh thu theo phiếu `payment_receipts`, cọc không tính doanh thu).

## 0. Thay đổi sau kiểm kê (28/11/2026, đã đối chiếu 5 file `inventory/`)
- Chuỗi doanh thu thêm 3 số theo tháng, tách khỏi đã nhận/phải thu: phí chậm `lateFeeVnd`, nợ chuyển kỳ `rolledOverVnd`, chi phí hội viên `membershipFeeVnd` (3.1; cột CSV `revenue` tổng hợp).
- Bảng hiệu quả tin thêm cột `Lượt lưu` và cờ `Chưa có khách` (3.4; cột CSV `listings`).
- Mục 6 thêm dữ liệu phải ghi trước: thời gian phản hồi của chủ nhà, lịch sử giá tin, cờ tự xác nhận; mục 9 liệt kê tín hiệu không đưa vào.
- Các tín hiệu "hôm nay phải làm gì" (khiếu nại, tất toán, SmartCA...) ở P1, không lặp ở trang phân tích.

## 1. Mục đích & người xem
- Chủ nhà (menu "Phân tích & Báo cáo", `WEB/components/dashboard/dashboard-nav.ts:30-34`; trang hiện trống, `WEB/app/dashboard/analytics/page.tsx`). Khác P1 (việc hôm nay): P3 trả lời "kinh doanh 12 tháng qua tốt lên hay xấu đi, tiền nợ nằm ở đâu, tin nào hiệu quả" và cho **xuất báo cáo** gửi kế toán.
- Trong 10 giây: doanh thu đã nhận so với phải thu theo tháng, tỉ lệ lấp đầy, tổng nợ theo tuổi nợ, tin nào kéo được khách.

## 2. Bố cục (desktop)
```
┌ Phân tích & Báo cáo ──────────────────────────────────────────────────────────────┐
│ Bộ lọc: [Chi nhánh ▾ Tất cả] [Tin ▾ Tất cả] [Từ T12/2025 ▾] [Đến T11/2026 ▾] [Áp dụng]    [⬇ Xuất báo cáo ▾] │
├────────────────────────────────────────┬─────────────────────────────────────────┤
│ Doanh thu theo tháng (cột đôi)          │ Tỉ lệ lấp đầy theo tháng (đường %)       │
│  ▇▆ đã nhận  ▒▒ phải thu  (12 tháng)    │   91% ─╮_╭─ 87%   (hover: 21/24 phòng)   │
├────────────────────────────────────────┼─────────────────────────────────────────┤
│ Công nợ: tổng 14,2tr · 9 khoản          │ Chuyển đổi: Xem → Hẹn xem → Yêu cầu → HĐ │
│  [0-7 ngày 4,1tr] [8-30 ngày 6,0tr]     │  1.2k → 86 (7%) → 31 (36%) → 12 (39%)    │
│  [>30 ngày 4,1tr]  bảng khoản nợ (10) ›  │                                         │
├────────────────────────────────────────┴─────────────────────────────────────────┤
│ Hiệu quả tin đăng (bảng): Tin | Chi nhánh | Lượt xem* | Hẹn xem | Yêu cầu | HĐ | Lượt lưu | CĐ xem→YC │
│ * lượt xem tích lũy; theo ngày chỉ từ ngày bật ghi nhận (ghi chú giới hạn mục 3.4)       │
└──────────────────────────────────────────────────────────────────────────────────┘
```
Màn nhỏ: bộ lọc thu gọn thành nút "Bộ lọc" mở bảng dưới; mỗi biểu đồ 1 cột, cuộn ngang trong thẻ (cột tối thiểu 28px); bảng tin chuyển thành danh sách thẻ; nút xuất ở đầu trang.

## 3. Từng khối
Bộ lọc áp dụng cho mọi khối: `branchId` (tin/hợp đồng/phòng của chi nhánh đó; "Tất cả" gồm cả tin/hợp đồng chưa gán chi nhánh), `listingId` (chỉ tin đó; khi chọn thì khối lấp đầy chuyển sang "phòng gắn với tin" nếu có liên kết `BranchRoomLinkService`, ngược lại ẩn), khoảng `from`..`to` theo tháng (`YYYY-MM`), mặc định 12 tháng gần nhất kể cả tháng hiện tại, tối đa 24 tháng (chống truy vấn lớn). Tất cả nằm trong **một endpoint mới** (mục 4); danh sách chi nhánh/tin cho bộ lọc dùng sẵn `GET /branches` (`AC/listing/PropertyBranchController.java:41`) và `GET /listings/me?branchId=` (`AC/listing/ListingController.java:52`).

### 3.1 Doanh thu theo tháng: đã nhận vs phải thu
- **Đã nhận (tháng m)** = tiền thuê + phí của phiếu `payment_receipts` có `landlord_id = owner`, `issued_at` thuộc tháng m (Asia/Ho_Chi_Minh), qua `payment_requests` (join `payment_request_id`): `direction = TENANT_TO_LANDLORD`, `type IN (INITIAL, MONTHLY_RENT, UTILITY, OTHER_CHARGE)`, trừ dòng `payment_line_items.type='DEPOSIT'` của INITIAL; trừ ròng INITIAL_PAYMENT_REFUND (`LANDLORD_TO_TENANT`). Loại cọc: RESERVATION_DEPOSIT, DEPOSIT_ADJUSTMENT, DEPOSIT_REFUND. Chi tiết như P1 mục 3.3. `PaymentReceipt` không có cột tiền (`PS/receipt/PaymentReceipt.java:8-65`, tiền chỉ trong `snapshot_json` TEXT và `payment_requests.total_amount`), nên join bắt buộc.
- **Phải thu (tháng m)** = tổng cùng phần tiền của `payment_requests` có `payee_id = owner`, `direction = TENANT_TO_LANDLORD`, `active=true`, `due_at` thuộc tháng m, status KHÁC CANCELLED/EXPIRED (kể cả đã CONFIRMED). Tỉ lệ thu = đã nhận của khoản đến hạn tháng m / phải thu (cohort theo `due_at`; một cột phụ "thu trễ" = nhận trong tháng m nhưng đến hạn tháng trước).
- Vì cả hai cột dùng hai trục thời gian khác nhau (nhận theo `issued_at`, phải thu theo `due_at`), UI ghi rõ chú thích dưới biểu đồ.
- **Thiếu dữ liệu quá khứ:** phiếu chỉ có từ khi tính năng chạy, không backfill (`docs/PAYMENT_RECEIPT_COVERAGE.md` §3 mục 3; `PaymentReceiptService.capture` chỉ gọi khi xác nhận, `PS/service/PaymentRequestService.java:574,719`). Tháng trước ngày bật có thể thấp giả; UI hiện "Dữ liệu phiếu bắt đầu từ {ngày phiếu đầu tiên của chủ nhà}" (MIN(`issued_at`)). Đề xuất job backfill một lần (mục 8, câu 2). Phải thu của kỳ chưa phát hành hóa đơn (PROJECTED) chưa có bản ghi nên chưa tính.
- **Phí chậm, nợ chuyển kỳ, chi phí hội viên (L12, L11)**: một hàng số theo tháng dưới biểu đồ, **không cộng vào đã nhận/phải thu**. `lateFeeVnd(m)` = Σ `monthly_invoices.late_fee_amount` của kỳ đến hạn trong m (HĐ của owner); `rolledOverVnd(m)` = Σ nợ `ROLLED_OVER` có `deferred_at` trong m (cột tiền kiểm khi code); `membershipFeeVnd(m)` = Σ tiền `membership_orders` PAID có `paid_at` trong m (chi phí của chủ nhà, bảng riêng, không lẫn `payment_receipts`, không trừ vào doanh thu). Trống: hàng ẩn khi cả 3 bằng 0.
- Click cột tháng m → bảng chi tiết phiếu của tháng đó (cùng nguồn xuất CSV); liên kết "Xem phiếu" → `/dashboard/invoices`. Trống: "Chưa có phiếu thu trong khoảng này".

### 3.2 Tỉ lệ lấp đầy theo tháng
- Công thức: `lấp đầy(m) = Σ ngày-phòng có người ở trong m / (số phòng × số ngày của m)`. Ngày-phòng có người ở: hợp đồng gốc quản lý bởi chi nhánh với `moveInDate ≤ ngày < endExclusive`, `endExclusive` theo `ContractLeaseCalc.end(...)` (`CS/service/ContractLeaseCalc.java:33-38`, ngày cuối = end-1), kết thúc sớm khi `handedOverAt`, loại hợp đồng CANCELLED/chưa hiệu lực (`BranchOverviewService.billable`, :566-569; `BranchRoomSchedule.living/historical`, `BranchAttentionService.java:895-896`); phòng của hợp đồng suy ra như `BranchRoomSchedule.shownRooms` (:268). Tháng hiện tại tính đến hôm nay (`BillingTime`).
- **Mẫu số:** số phòng hiện có (`branch_slots` có `roomCode` của chi nhánh active); **không có lịch sử số phòng** (phòng thêm/xóa không để lại ảnh chụp) → tháng cũ dùng số phòng hiện tại. Ghi chú trên biểu đồ. Cần ghi từ nay (mục 6): bảng `branch_occupancy_monthly` (xem mục 4).
- Hiển thị: 12 điểm %, hover "21/24 phòng, 652/720 ngày-phòng". Click điểm → `/dashboard/branches/{id}?month=YYYY-MM` nếu chọn 1 chi nhánh, ngược lại `/dashboard/branches`. Trống: chưa có phòng/chi nhánh → "Tạo chi nhánh để theo dõi lấp đầy".

### 3.3 Công nợ theo tuổi nợ
- Công thức: khoản nợ = `payment_requests` `payee_id = owner`, `TENANT_TO_LANDLORD`, `active=true`, trạng thái chưa thu: AWAITING_TRANSFER, TRANSFER_REPORTED (đã báo, chờ xác nhận, gắn nhãn riêng), REJECTED, OVERDUE (`BranchAttentionService.java:66-67` đã dùng bộ này) với `due_at < billingNow` (đã quá hạn); phần tiền trừ cọc như P1. **Tuổi nợ** = số ngày nguyên giữa `due_at` và `BillingTime.now()` theo Asia/Ho_Chi_Minh: bucket 0-7, 8-30, >30. Hạn/quá hạn do `PaymentTimePolicy` quyết định, không tự tính lại (`PS/service/PaymentTimePolicy`).
- Hiển thị: tổng + 3 thẻ bucket (số khoản, tiền) + bảng 10 khoản lớn nhất (mã phòng/hợp đồng, người thuê, hạn, số ngày trễ, số tiền, trạng thái). Click dòng → `target.href` (hóa đơn/hợp đồng, dạng `BranchOverviewService.invoiceTarget`, `CS/service/BranchOverviewService`) hoặc `/dashboard/invoices`. Phí chậm thanh toán đã nằm trong dòng hóa đơn kỳ sau, không cộng hai lần (`PAYMENT_RECEIPT_COVERAGE.md` bảng 1).
- Công nợ là **ảnh chụp tại thời điểm hiện tại**, không lọc theo `from..to` (chỉ lọc `branchId`/`listingId`); UI ghi "tính đến {billingNow}". Lịch sử công nợ qua các tháng chưa có (cần ghi, mục 6).

### 3.4 Hiệu quả tin đăng (lượt xem)
- **Kiểm tra dữ liệu lượt xem hiện có (kết luận):**
  - `listings.view_count` (`LS/model/Listing.java:119`) là bộ đếm **tích lũy duy nhất**, không có thời gian; tăng bởi `ListingViewService.recordView` (`LS/service/ListingViewService.java:30-80`): chỉ tin PUBLISHED, chống spam 1 phút/khách bằng Redis (dev thì tăng thẳng).
  - `listing_view_histories` (`LS/model/ListingViewHistory.java:10-16`) chỉ để "Tin đã xem" của **người dùng đăng nhập**: ràng buộc duy nhất `(user_id, listing_id)` nên mỗi người một dòng, ghi đè `viewed_at` lần xem gần nhất (`ListingViewHistoryService.java:49-60`); khách chưa đăng nhập không ghi (`ListingViewService.java:35-40`); và bị xóa sau 30 ngày bởi `ListingViewHistoryScheduler.purgeExpiredViewHistory` (`:19-22`, cron `listing.view-history-cleanup-cron=0 0 3 * * ?`, `application.properties:234`).
  - → **Không có chuỗi lượt xem theo ngày/tháng, không có số người xem duy nhất.** Dùng bảng lịch sử xem làm nguồn thống kê là SAI (mất sau 30 ngày, một dòng/người, thiếu khách vãng lai).
- Hiển thị v1: bảng theo tin: **lượt xem tích lũy** (`view_count`, ghi "từ khi đăng"), số hẹn xem, số yêu cầu thuê, số hợp đồng, tỉ lệ xem→yêu cầu (= yêu cầu / lượt xem tích lũy, ghi "ước tính tích lũy"). Sắp theo lượt xem giảm dần, phân trang 10. Sau khi bật ghi nhận mới (mục 4.2) thêm cột "Lượt xem trong khoảng" và đường xu hướng theo ngày; trước ngày bật ghi "chưa có dữ liệu".
- Bổ sung sau kiểm kê: cột `Lượt lưu` = `count(*)` `listing_favorites` theo tin (tích lũy, `idx_favorite_listing`; chỉ là số, không trả danh sách người; tín hiệu cầu thuê) và cờ `Chưa có khách` (C18) = tin đăng ≥7 ngày, 0 hẹn xem và 0 yêu cầu thuê (suy ra từ `viewing_appointments` + `rental_requests`; tin hết hạn mà cờ bật → gợi ý đổi giá/ảnh khi đăng lại). Có bộ lọc nhanh "Chưa có khách".
- Click dòng → `/dashboard/properties/view?id={id}`. Trống: "Chưa có tin nào"; tin `view_count = 0` hiện "—".

### 3.5 Tỉ lệ chuyển đổi xem → yêu cầu → hợp đồng
- Phễu 4 bước, trong khoảng `from..to` và bộ lọc:
  1. **Lượt xem**: v1 = `SUM(listings.view_count)` tích lũy (không theo khoảng, ghi rõ); từ khi có `listing_view_daily` = tổng trong khoảng.
  2. **Hẹn xem**: `viewing_appointments` của owner tạo trong khoảng (`created_at`), mọi trạng thái trừ CANCELLED (giả định).
  3. **Yêu cầu thuê**: `rental_requests` của owner `created_at` trong khoảng (mọi trạng thái trừ CANCELLED_BY_SYSTEM do hệ thống dọn) (giả định).
  4. **Hợp đồng ký**: `contracts` gốc có `rental_request_id` thuộc các yêu cầu bước 3 và `signed_at` không null (hiệu lực, `ContractStatus` ACTIVE/EXPIRED/CLOSED/TERMINATED).
- Tỉ lệ từng bước = bước sau / bước trước (cohort theo yêu cầu tạo trong khoảng cho bước 3→4). Bước 1→3 chỉ là tỉ lệ ước tính vì mẫu số tích lũy; UI gắn nhãn "ước tính". Ngoài ra "Thời gian từ yêu cầu đến hợp đồng" (trung vị ngày) là chỉ số tùy chọn, chưa làm ở v1.
- Click từng bước: Hẹn xem → `/dashboard/viewing-schedules`, Yêu cầu → `/dashboard/rental-requests`, Hợp đồng → `/dashboard/landlord/contracts`. Trống: tất cả 0 → hiển thị "Chưa có dữ liệu trong khoảng".

## 4. API mới tổng hợp
### 4.1 Đọc
`GET /dashboard/analytics?from=YYYY-MM&to=YYYY-MM&branchId=&listingId=&include=revenue,occupancy,receivables,listings,funnel` (một endpoint, `AC/listing/LandlordAnalyticsController.java` mới; service ở `hs-api-service` vì cần cả contract/payment/listing). Auth: `CurrentUser.requireUserId()`; `branchId`/`listingId` phải thuộc owner (kiểm `branches.findByIdAndOwnerIdAndActiveTrue`, như `BranchAttentionService.java:93-95`; sai → 404, không lộ tồn tại của người khác). Validate: định dạng tháng như `BranchOverviewController.java:16,43-50`, `to ≥ from`, ≤ 24 tháng, tham số lặp/rỗng → `ErrorCode.INVALID_REQUEST`. `Cache-Control: private, no-store`; cache server 60 giây theo `(userId, params)` (giả định).
```json
{ "result": { "billingNow":"…","from":"2025-12","to":"2026-11","receiptsSince":"2026-09-02",
 "revenue":[{"month":"2026-11","receivedVnd":52300000,"refundedVnd":0,"dueVnd":68000000,"dueCollectedVnd":49000000,"lateCollectedVnd":3300000,"lateFeeVnd":0,"rolledOverVnd":0,"membershipFeeVnd":0}],
 "occupancy":[{"month":"2026-11","rooms":24,"occupiedRoomDays":652,"roomDays":720,"rate":0.906}],
 "receivables":{"asOf":"…","totalVnd":14200000,"count":9,"buckets":[{"key":"D0_7","count":4,"vnd":4100000},{"key":"D8_30","count":3,"vnd":6000000},{"key":"D31_PLUS","count":2,"vnd":4100000}],"top":[{"paymentId":"…","contractNumber":"HD-0042","roomCode":"P203","tenantName":"…","dueAt":"…","daysLate":12,"amountVnd":3000000,"status":"OVERDUE","href":"…"}]},
 "listings":{"viewsSince":null,"items":[{"id":"…","title":"…","branchName":"…","viewCountTotal":1204,"viewsInRange":null,"appointments":9,"requests":4,"contracts":2,"favorites":7,"noLeads":false}]},
 "funnel":{"views":{"value":1204,"cumulative":true},"appointments":86,"requests":31,"contracts":12}}}
```
### 4.2 Dữ liệu CHƯA CÓ, phải ghi trước (xem mục 6)
- `listing_view_daily(listing_id, day DATE, views INT, unique_users INT, PRIMARY KEY(listing_id, day))`; tăng trong `ListingViewService.recordView` khi lượt xem được tính (sau chống spam, cùng chỗ gọi `incrementViewCount`, `ListingViewService.java:~74-80`); giữ ≥ 24 tháng, không dính job xóa 30 ngày. Hệ quả: bật ghi rồi mới có số liệu, không khôi phục được quá khứ.
- `branch_occupancy_monthly(branch_id, month, rooms, occupied_room_days, room_days)`: job đầu tháng chốt tháng trước (thay cho tính ngược bằng số phòng hiện tại).
- `receivables_snapshot` (tùy chọn): cuối ngày/tuần chụp tổng nợ theo bucket để vẽ xu hướng công nợ.
- Backfill một lần `payment_receipts` cho `payment_requests` CONFIRMED cũ (xem 3.1).
- Index: `payment_receipts (landlord_id, issued_at DESC)`; `payment_requests (payee_id, status, due_at)`; `rental_requests (owner_id, created_at)`; `viewing_appointments (owner_id, created_at)`; `contracts (rental_request_id)` đã có `idx_contract_rental_request`. Các `@Index` khai báo trên entity (hibernate `ddl-auto=${JPA_DDL_AUTO}`, `application.properties:46`).

### 4.3 Xuất báo cáo
`GET /dashboard/analytics/export?report=revenue|receivables|occupancy|listings|funnel&format=csv|pdf&from=&to=&branchId=&listingId=` (cùng quy tắc quyền/validate). Chỉ đọc, bất đồng bộ không cần: tối đa 24 tháng × số khoản của một chủ nhà (đặt trần 20.000 dòng, vượt → 422 "Thu hẹp khoảng thời gian"). Response `Content-Disposition: attachment`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` (như `AC/payment/PaymentReceiptController.java:63-78`). Tên file `homespace-{report}-{from}_{to}.csv|pdf`.
- **CSV (v1, bắt buộc):** UTF-8 **có BOM** (Excel Việt mở đúng dấu), dấu phân cách `,`, số không có dấu nghìn (VND nguyên, `.` thập phân nếu có), ngày `dd/MM/yyyy`, thời điểm `dd/MM/yyyy HH:mm` giờ Asia/Ho_Chi_Minh; ô bắt đầu bằng `= + - @` được tiền tố `'` (chống CSV injection, vì có tên người thuê).
- **PDF (v1.1):** HTML (Thymeleaf như `PaymentReceiptPdfRenderer.html`) → Gotenberg `POST {url}/forms/chromium/convert/html` (`PS/receipt/PaymentReceiptPdfRenderer.java:36-52,107-125`; cấu hình `payment.receipts.gotenberg.*` fallback `contract.gotenberg.*`, `application.properties:188-189`), kiểm đầu ra bắt đầu `%PDF-` và ≤25MB (:121-124); khi Gotenberg tắt → 503 "Chưa hỗ trợ xuất PDF" (hiện `renderHtml` ném `IllegalStateException`, :108). Mỗi báo cáo = bảng như CSV + trang tóm tắt (tổng, khoảng, bộ lọc, ngày xuất).
- **Excel (.xlsx): chưa làm.** Repo chưa có thư viện ghi xlsx trực tiếp (chỉ `poi-tl 1.12.2`, `hs-api-service/pom.xml:49`, dùng sinh DOCX hợp đồng; POI đi kèm gián tiếp là giả định chưa kiểm). Đề xuất CSV mở bằng Excel thay thế; xlsx là câu hỏi 4.
- **Cột chính xác theo báo cáo:**
  1. `revenue` (mỗi phiếu một dòng, theo `issued_at`): `Số phiếu` (receipt_number) | `Ngày phát hành` | `Chi nhánh` | `Phòng` | `Số hợp đồng` | `Người thuê` | `Loại khoản` (Tiền thuê tháng / Tiền thuê đầu kỳ / Điện nước / Khoản khác / Hoàn tiền) | `Kỳ thanh toán` (context.periodLabel) | `Số tiền (VND)` | `Tiền cọc loại trừ (VND)` | `Mã chuyển khoản` (transferReference). Dòng cuối: `Tổng`. Nguồn: `payment_receipts` ⨝ `payment_requests` ⨝ `snapshot_json` (số hợp đồng, tên) qua `PaymentReceiptService.view` (`:317-337`); **không** xuất số tài khoản/ngân hàng.
  2. `revenue` (tổng hợp tháng, khi `format` kèm `granularity=month`): `Tháng` | `Đã nhận (VND)` | `Hoàn tiền (VND)` | `Phải thu đến hạn (VND)` | `Đã thu của kỳ đến hạn (VND)` | `Thu trễ kỳ trước (VND)` | `Tỉ lệ thu (%)` | `Phí chậm (VND)` | `Nợ chuyển kỳ (VND)` | `Phí hội viên (VND)`.
  3. `receivables`: `Chi nhánh` | `Phòng` | `Số hợp đồng` | `Người thuê` | `Hạn thanh toán` | `Số ngày trễ` | `Nhóm tuổi nợ` | `Số tiền (VND)` | `Trạng thái` | `Mã chuyển khoản`.
  4. `occupancy`: `Tháng` | `Chi nhánh` | `Số phòng` | `Ngày-phòng có người ở` | `Tổng ngày-phòng` | `Tỉ lệ lấp đầy (%)`.
  5. `listings`: `Tin` | `Chi nhánh` | `Trạng thái` | `Ngày đăng` | `Ngày hết hạn` | `Lượt xem tích lũy` | `Lượt xem trong khoảng` | `Hẹn xem` | `Yêu cầu thuê` | `Hợp đồng` | `Lượt lưu` | `Chưa có khách` | `Tỉ lệ xem→yêu cầu (%)`.
  6. `funnel`: `Bước` | `Số lượng` | `Tỉ lệ so với bước trước (%)`.
- Quyền: cùng chủ nhà đang đăng nhập; mọi phạm vi lọc theo `owner`; ghi log kiểm toán `userId, report, from, to, rows` (không ghi nội dung/PII).

## 5. Biểu đồ
- `WEB/package.json` **không cài thư viện biểu đồ** (không recharts, chart.js, d3, visx; có `lucide-react`, `date-fns`, `tailwindcss` v4). Đề xuất không thêm dependency: component SVG nhỏ dùng lại token Tailwind (`WEB/components/ui`, màu theo theme sáng/tối): (a) cột đôi 12 tháng (trục X: tháng `MM/yy`; trục Y: VND, đơn vị triệu, tick 4-5 mức làm tròn); (b) đường lấp đầy 0-100% (Y cố định 0-100); (c) thanh phễu ngang; (d) 3 thanh ngăn xếp tuổi nợ. Có `aria-label` và bảng dữ liệu ẩn để đọc bằng trình đọc màn hình, tooltip bàn phím được. Nếu chủ muốn thư viện (recharts), cần duyệt thêm dependency (câu 3).
- Phạm vi: mặc định 12 tháng, tối đa 24; tháng trống vẫn vẽ cột 0; trục Y lấy `max(đã nhận, phải thu)` làm tròn lên.

## 6. Dữ liệu chưa có, phải ghi trước khi báo cáo đầy đủ
| Chỉ số | Hiện trạng | Phải làm | Ảnh hưởng |
|---|---|---|---|
| Lượt xem theo ngày | Chỉ `view_count` tích lũy; lịch sử xem 30 ngày, 1 dòng/người, chỉ đăng nhập | Bảng `listing_view_daily` + ghi trong `ListingViewService` | Có số theo khoảng chỉ từ ngày bật |
| Lượt xem duy nhất | Không có | `unique_users` (đăng nhập) + Redis HLL/ip băm cho khách (giả định) | Cần quyết định quyền riêng tư (câu 5) |
| Lấp đầy lịch sử | Tính ngược từ hợp đồng; thiếu lịch sử số phòng | `branch_occupancy_monthly` job đầu tháng | Tháng cũ dùng số phòng hiện tại |
| Phiếu thu cũ | Không backfill | Backfill `payment_receipts` hoặc chấp nhận bắt đầu từ ngày bật | Doanh thu tháng cũ thấp giả |
| Công nợ theo thời gian | Chỉ hiện tại | Chụp theo ngày/tuần | Xu hướng nợ |
| Phải thu kỳ chưa phát hành | Không có `payment_requests` | (tùy) ước tính từ giá thuê hợp đồng | "Phải thu" thấp hơn thực tế |
| Thời gian phản hồi của chủ nhà (C17) | Không ghi: lịch hẹn chỉ có `completedAt`, yêu cầu thuê chỉ có `acceptedAt` (từ chối/hủy chỉ có `updatedAt`) | Thêm `approved_at/rejected_at/cancelled_at` (lịch hẹn) và `responded_at` (yêu cầu thuê), rồi mới tính trung vị | Có số từ ngày bật; thêm khối "Phản hồi" sau |
| Lịch sử giá tin, gia hạn `expires_at` | Không ghi | Bảng `listing_price_history` | Chưa cảnh báo đổi giá (P2 mục 9) |
| Khoản tự xác nhận bởi hệ thống | Chỉ nhận biết qua chuỗi lý do trong `payment_events` | Cờ hoặc `PaymentEventType` riêng | P1 §3.13 |

## 7. Hiệu năng & bảo mật; Kiểm thử
- Hiệu năng (10k tin/100k thanh toán): mỗi khối 1 truy vấn gộp `GROUP BY date_trunc('month', issued_at AT TIME ZONE 'Asia/Ho_Chi_Minh')` có `landlord_id` + khoảng `issued_at` dùng index mới; phải thu `GROUP BY` theo `due_at`; funnel là 3 `COUNT` có chỉ mục; bảng tin phân trang (10-50), join `listings` theo `idx_listings_owner_status`. Không N+1 (không gọi `PaymentReceiptService.view` từng dòng cho tổng hợp; chỉ dùng cho CSV chi tiết, tải theo lô 500 và đọc `snapshot_json` một lần). Lấp đầy tính trong bộ nhớ từ danh sách hợp đồng của chủ (nhỏ). Trần 24 tháng + 20.000 dòng xuất.
- Bảo mật: chỉ dữ liệu của chủ nhà gọi; tên người thuê là khách của chính họ; không xuất CCCD, số/tên ngân hàng, token; chống CSV injection; `branchId/listingId` của người khác → 404 không phân biệt; không nhận `userId` từ request.
- Backend test: biên tháng 23:59:59/00:00:00 Asia/Ho_Chi_Minh; cọc loại trừ và hoàn tiền ròng; phiếu membership không lẫn vào; `receiptsSince`; lấp đầy với hợp đồng kết thúc sớm/`handedOverAt`/kéo dài qua nhiều tháng; bucket tuổi nợ ở biên 7/8, 30/31 ngày; phễu cohort; lọc `branchId`/`listingId` đúng; **từ chối**: owner B dùng `branchId` của A → 404, chưa đăng nhập 401, `from > to`/`>24 tháng`/tham số lặp → INVALID_REQUEST, `report` lạ → INVALID_REQUEST, vượt 20.000 dòng → 422; CSV có BOM, cột đúng thứ tự mục 4.3, ô `=cmd` được vô hiệu; PDF khi Gotenberg tắt trả 503 (không gọi dịch vụ thật trong test, mock `RestTemplate` như `PaymentReceiptRenderTest`).
- Sau kiểm kê: `lateFeeVnd`/`rolledOverVnd`/`membershipFeeVnd` đúng tháng (biên 23:59:59/00:00:00 Asia/Ho_Chi_Minh), phí hội viên không lẫn vào doanh thu, `favorites` đúng và không lộ người, cờ `noLeads` đúng biên 7 ngày, cột CSV mới đúng thứ tự và owner B không thấy dữ liệu của A.
- Frontend: mỗi khối 3 trạng thái, bộ lọc đổi → gọi lại, nút xuất tải đúng file (mock), màn nhỏ; `npm run lint`, `next build`.

## 8. Câu hỏi mở (mặc định đề xuất)
1. Doanh thu có gồm tiền cọc và phí chậm thanh toán riêng? Đề xuất: không gồm cọc; phí chậm nằm trong tiền thuê (đã gộp trong phiếu hóa đơn).
2. Backfill `payment_receipts` cho thanh toán CONFIRMED trước khi có tính năng? Đề xuất: có, chạy một lần có kiểm đếm, rồi mới công bố "12 tháng".
3. Thêm thư viện biểu đồ (recharts) hay tự vẽ SVG? Đề xuất: tự vẽ SVG, không thêm dependency.
4. Xuất Excel (.xlsx) thật? Đề xuất: v1 CSV (có BOM) + PDF; xlsx để sau nếu kế toán cần.
5. Ghi lượt xem duy nhất của khách chưa đăng nhập (IP băm)? Đề xuất: chỉ đếm lượt xem và người dùng đăng nhập; không lưu IP.

6. Ngưỡng cờ "Chưa có khách" (3.4): đề xuất tin đăng ≥7 ngày, 0 hẹn, 0 yêu cầu.
7. Chi phí hội viên (3.1) hiện thành hàng riêng "Chi phí" cạnh doanh thu hay ẩn? Đề xuất: hiện hàng riêng, không trừ vào doanh thu. Cách hiện nợ chuyển kỳ theo P1 câu 10.

## 9. Không đưa vào (lý do)
| Tín hiệu (mã kiểm kê) | Lý do |
|---|---|
| Thời gian phản hồi trung bình (C17) | Chưa có dữ liệu: ghi các mốc ở mục 6 trước, sau đó thêm khối vào P3 |
| Lịch sử giá tin / cảnh báo đổi giá (T6) | Chưa có dữ liệu: `listing_price_history` ở mục 6 |
| Sổ dùng lượt hội viên (C6) | Dùng để đối soát, không phải số liệu kinh doanh; thuộc `/dashboard/membership`; chưa có endpoint đọc |
| Mẫu HĐ riêng (L13), bãi xe (C16) | Giá trị thấp ở trang phân tích; thuộc trang mẫu hợp đồng và trang chi nhánh |


## 10. Hợp đồng API triển khai — phase 1 (Codex, 2026-11-28)

Phạm vi thay đổi: controller API; DTO/service/query tổng hợp ở contract (đã có dependency listing/payment); entity/index listing/payment; ghi view và phản hồi trong các write path đang có; backfill phiếu; snapshot lấp đầy; unit/MockMvc/PostgreSQL tests. Không đổi trạng thái nghiệp vụ, không đọc `.env`, không gọi DB/dịch vụ ứng dụng hiện có.

### Đọc

`GET /dashboard/analytics` — `CurrentUser.requireUserId()` sau `UserContextFilter`, không nhận owner từ tham số. Cùng một người có quyền chủ nhà chỉ đọc dữ liệu có owner/payee/landlord tương ứng; không có quyền admin vượt qua scope. Bộ lọc chi nhánh active/tin active của người khác hoặc không tồn tại trả 404.

| Tham số | Giá trị chính xác |
|---|---|
| `from`, `to` | Tháng `YYYY-MM` (0001–9999); mặc định `to` = tháng của BillingTime tại Asia/Ho_Chi_Minh, `from` = to−11; tối đa 24 tháng kể cả hai đầu, from≤to |
| `branchId`, `listingId` | ID `[A-Za-z0-9_-]{1,36}`, tùy chọn; áp dụng đồng thời |
| `include` | Danh sách không lặp, phân cách dấu phẩy: `revenue,occupancy,receivables,listings,funnel`; bỏ qua = cả 5; block không chọn = null |
| `listingPage` | 1–10000, mặc định 1 |
| `listingSize` | 1–50, mặc định 10 |
| `noLeadsOnly` | `true` hoặc `false`, mặc định false; lọc bảng tin và xuất bảng tin |

Tham số được hỗ trợ lặp/rỗng/sai định dạng trả INVALID_REQUEST (400); page/size được truyền bằng 0 cũng không hợp lệ. Bộ lọc không khớp nhau trả tập rỗng sau khi xác nhận quyền trên từng ID. Response giữ envelope `ApiResponse.result`, `Cache-Control: private, no-store`. Không thêm cache server vì tiền/công nợ cần dữ liệu hiện tại (cache 60 giây ở thiết kế ban đầu là giả định).

DTO `LandlordAnalyticsResponse` (tên field JSON chính xác):

- `billingNow: Instant`, `from/to: String YYYY-MM`, `receiptsSince: LocalDate|null` (phiếu đầu tiên của chính owner, không phụ thuộc bộ lọc).
- `revenue[]`: `month`, `receivedVnd`, `refundedVnd`, `netVnd`, `dueVnd`, `dueCollectedVnd`, `lateCollectedVnd`, `lateFeeVnd`, `rolledOverVnd`, `membershipFeeVnd`. Mọi giá trị tiền là **BigDecimal**, giữ precision/scale từ nguồn, không đổi sang long/double. `netVnd=receivedVnd-refundedVnd`; INITIAL_PAYMENT_REFUND trừ **DEPOSIT_REFUND** trước khi trừ ròng. Phí hội viên là chi phí của owner, không có phân bổ giả cho chi nhánh/tin.
- `occupancy[]`: `month`, `rooms:int`, `occupiedRoomDays:int`, `roomDays:int`, `rate:Double|null` (tỉ lệ 0..1, 3 chữ số); mẫu số 0 → null; tin không liên kết phòng → mảng rỗng.
- `receivables`: `asOf:Instant`, `totalVnd:BigDecimal`, `count:int`, `buckets[] {key:D0_7|D8_30|D31_PLUS,count:int,vnd:BigDecimal}`, `top[] {paymentId,contractNumber,roomCode,tenantName,dueAt:Instant,daysLate:int,amountVnd:BigDecimal,status,href}`. Top tối đa 10, tiền giảm dần rồi hạn/ID. Tuổi nợ theo ngày lịch Việt Nam; due_at phải nhỏ hơn billingNow, không đổi status của khoản để tính báo cáo.
- `listings`: `viewsSince:LocalDate|null`, `page,size:int`, `total:long`, `items[] {id,title,branchName,status,publishedAt,expiresAt,viewCountTotal:long,viewsInRange:Long|null,appointments:long,requests:long,contracts:long,favorites:long,noLeads:boolean,viewToRequestRate:Double|null}`, `dailyViews[] {day:LocalDate,views:long}`. Trend là tổng theo ngày của phạm vi lọc, chỉ có ngày đã ghi view (ngày vắng không tạo dòng giả); tối đa khoảng 24 tháng. `viewsSince` là ngày **đầu tiên có quan sát trong scope**, không phải mốc bật toàn hệ thống. Favorites chỉ trả số lượng, không trả user. NoLeads xét >=7×24h từ published_at và không có hẹn/yêu cầu suốt đời tin, kể cả tin hết hạn; bộ đếm lead hiển thị vẫn theo khoảng lọc.
- `funnel`: `views {value:long,cumulative:boolean,since:LocalDate|null}`, `appointments,requests,contracts:long`. Khi có daily data trong scope, chỉ đếm view đã ghi trong khoảng và trả since; khi chưa có thì dùng view_count tích lũy và cumulative=true. Hẹn CANCELLED và yêu cầu CANCELLED_BY_SYSTEM bị loại; các dòng inactive bị loại. HĐ là root lease signed, ACTIVE/EXPIRED/CLOSED/TERMINATED, đúng landlord, không phải DEPOSIT hay phụ lục; cohort theo ngày tạo yêu cầu, không theo ngày ký.

Khoảng tháng dùng `[đầu tháng, đầu tháng kế)` tại Asia/Ho_Chi_Minh. Due/aging/default month dùng BillingTime; lượt xem, ngày đăng và SLA phản hồi dùng đồng hồ thực. SQL đã đối chiếu entity: `listing_favorites.active` kế thừa BaseEntity; `monthly_invoices.active` có thật; membership `amount_vnd:int` được SUM thành BigDecimal; branch scope ưu tiên assignment → contract.branch_id → listing.branch_id. Không tạo API framework hay module dependency mới.

### Xuất

`GET /dashboard/analytics/export` dùng cùng auth, ownership và validation. Bắt buộc `report=revenue|receivables|occupancy|listings|funnel`, `format=csv|pdf`; tùy chọn `granularity=receipt|month` (mặc định receipt, month chỉ hợp lệ cho revenue), cùng from/to/branchId/listingId/noLeadsOnly. Report/format/granularity lạ → 400. Bộ lọc trang không làm mất dòng khi xuất: xuất cả bảng trong scope. Tối đa **20.000 dòng dữ liệu**; truy vấn receipt/debt chỉ đọc tối đa 20.001 để phát hiện tràn rồi trả 422. Footer Tổng của revenue chi tiết không tính trong trần dữ liệu. Occupancy xuất series tổng của phạm vi đang chọn, cột Chi nhánh là ID được chọn hoặc `Tất cả`.

CSV dùng đúng thứ tự cột §4.3, UTF-8 BOM, CRLF, mọi ô quote/escape dấu quote, BigDecimal.toPlainString, ngày/giờ Việt Nam. Ô text bắt đầu bằng công thức (kể cả có whitespace đứng trước) hoặc tab/CR/LF được thêm dấu nháy đơn; số âm kiểu numeric vẫn là số. Revenue chi tiết chỉ lấy các field context/name cần thiết từ snapshot, không xuất bank snapshot/CCCD. Nhãn loại khoản hiện dùng mã nghiệp vụ `INITIAL`, `MONTHLY_RENT`, `UTILITY`, `OTHER_CHARGE`, `INITIAL_PAYMENT_REFUND`. Tổng là tiền ròng đã loại cọc. Revenue tháng không cộng riêng phí chậm/nợ chuyển kỳ/hội viên vào doanh thu.

PDF dùng lại `PaymentReceiptPdfRenderer.renderHtml` (được mở public cho service nội bộ), cùng kiểm chữ ký `%PDF-`, giới hạn 25MB và timeout đang có. HTML escape mọi ô/bộ lọc, có khoảng/bộ lọc/ngày xuất/số dòng. Converter tắt/lỗi/đầu ra sai → 503; test không gọi Gotenberg thật. Header: `Content-Disposition: attachment; filename="homespace-{report}-{from}_{to}.{format}"`, `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff`. Audit log chỉ user/report/from/to/số dòng, không log nội dung báo cáo.

### Dữ liệu và tương thích tích hợp

- Backfill: `PaymentReceiptBackfill` chạy startup, batch 50 theo ID, transaction riêng mỗi khoản và khóa payment theo đúng thứ tự confirmation. Chỉ CONFIRMED + confirmed_at không null + tiền >0 + chưa có receipt; kiểm lại status/receipt dưới khóa rồi gọi capture hiện có, giữ issued_at=confirmed_at. Unique receipt/payment giữ nguyên; chạy lại hoặc chạy đồng thời không tạo trùng. Lỗi item tăng failed và đi tiếp; lỗi quét cũng tăng failed, log kiểm đếm; khoản lỗi có thể retry ở lần chạy sau. Không render PDF/call network trong backfill. Property nội bộ `payment.receipts.backfill-enabled` mặc định true có thể tắt khi cần điều hành, không thêm env/dependency.
- Daily views: `listing_view_daily` PK `(listing_id,view_date)` với `views`, `unique_logged_in_viewers`; `listing_view_daily_viewers` PK `(listing_id,view_date,viewer_id)` để uniqueness chính xác cho người đăng nhập. Một CTE `INSERT ... ON CONFLICT` ghi unique marker và upsert daily counters, cùng transaction increment view_count hiện có. Giữ nguyên chống spam 1 phút, dev/fallback và điều kiện PUBLISHED; không lưu IP trong bảng mới, không dùng lịch sử xem 30 ngày. Total/daily rollback cùng nhau và không mất increment khi concurrent. Các bảng mới không tham gia purge history, giữ dữ liệu từ khi bật; không backfill lượt xem quá khứ.
- Lấp đầy: `branch_occupancy_monthly` PK `(branch_id,month_start)` đóng băng rooms/occupied_room_days/room_days. Job 00:10 Việt Nam mỗi ngày chụp tháng **trước**, batch 500 chi nhánh; retry các snapshot thiếu, `ON CONFLICT DO NOTHING` giữ snapshot cũ khi nhiều instance/lặp. Query thường dùng snapshot theo owner/branch cho tháng đã có, tính từ phòng hiện có khi chưa có; lọc riêng tin dùng phòng liên kết và không áp snapshot cả chi nhánh. Tháng trước lần đầu ghi snapshot vẫn dùng phòng hiện có; không thể khôi phục room history đã mất.
- SLA: thêm nullable `responded_at` và `response_type` (ACCEPTED/REJECTED/CANCELLED) cho rental/viewing. Rental accept/reject và appointment approve/reject/owner-cancel ghi **lần đầu** bằng Instant.now trong transaction hiện có; reschedule/hủy sau đó giữ lần đầu. System expiry, hủy do renter, bulk từ chối contender không ghi là owner response. Không backfill từ accepted_at/updated_at theo mặc định đã duyệt của P4 §5.4b/P5 §8; mốc billing acceptedAt và deadline vẫn giữ nguyên. Migration `AnalyticsResponseSchemaMigration` ADD COLUMN IF NOT EXISTS, không sửa dữ liệu cũ.
- DDL theo quy ước Hibernate của repo (`JPA_DDL_AUTO` update/create); các cột mới nullable tương thích dòng cũ. Khi vận hành dùng validate/none phải áp dụng DDL bảng/cột/index mới **trước** khởi động như mọi thay đổi entity khác; startup migration không chạy trước Hibernate validation. Test tạo schema bằng entity thật, chạy migration lặp trên bảng thiếu cột.
- **Hợp nhất P1/P2:** giữ tất cả index cũ; thêm receipt landlord/tenant issued DESC; payment `(payee_id,status,due_at)` và `(payer_id,status,due_at)`; appointment owner_created và owner_status_date; rental owner_created; daily date. Không thay thế index queue/reminder/status. Khi cherry-pick cần hợp union annotation index ở PaymentReceipt, PaymentRequest, ViewingAppointment; service capture/unique receipt không thay đổi. P1 cần cùng quy tắc trừ DEPOSIT_REFUND khi tính INITIAL_PAYMENT_REFUND.
- Để sau đúng phạm vi: Excel, frontend/mobile/chart library theo quyết định D4, năm sửa lỗi operations P4, lịch sử giá tin và trend receivables/ước tính hóa đơn chưa phát hành. Không thêm block SLA chưa có dữ liệu lịch sử vào P3.

### Kiểm thử thực chạy

L3: kiểm tiền, quyền/phạm vi, persistence/concurrency và migration. Lệnh reactor chọn lọc đã chạy thành công (exit 0):

```powershell
$env:HS_P3_TEST_PG_URL='jdbc:postgresql://127.0.0.1:64352/hs_analytics_test'
.\mvnw.cmd -Pisolated-build -pl hs-api-service -am '-Dtest=LandlordAnalytics*Test,OccupancyCalcTest,ViewingAppointmentServiceTest,RentalRequestServiceTest,PaymentReceiptServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

**132 tests, 0 failure, 0 error, 0 skip**: listing 51 (rental + appointment lifecycle), payment 14 (receipt service), contract 45 (analytics/export/backfill/snapshot units + 9 PostgreSQL cases + occupancy), API 22 (MockMvc HTTP + filter xác thực thật). Tất cả 8 module trong reactor compile/test target thành công. Log cục bộ không commit: `hs-api-service/target/p3-unit-http-validation.log`.

Các lần đầu phát hiện lỗi **fixture test**: tên biến fixture trong assert sai gây testCompile lỗi (đã sửa), export MockMvc thiếu X-User-Role nên filter trả 401 thay vì 200 (đã bổ sung header đúng). Lệnh thành công trên là lần chạy sau sửa các lỗi này; không bỏ qua failure hay tắt auth.

Container riêng được tạo bởi worker: `hs-p3-codex-20261128-a71f`, image `postgres:16-alpine`, PostgreSQL 16.16, chỉ bind `127.0.0.1` qua port ngẫu nhiên Docker cấp 64352, database riêng `hs_analytics_test`. Không truy cập DB/dịch vụ ứng dụng, Redis/SmartCA/chain/Gotenberg/object storage thật.

Unit/HTTP đã chứng minh: zero-fill 12–24 tháng theo clock và biên midnight Việt Nam; BigDecimal không mất phần thập phân; bucket 7/8 và 30/31; end-exclusive/handover/overlap phòng; owner/admin không vượt scope; forged/missing identity →401, foreign scope →404, tham số sai/lặp/rỗng →400; CSV BOM/quote/formula/whitespace và số âm; disabled PDF →503; response_type/timestamp chỉ ghi lần đầu trong write paths; failed backfill item/enumeration tăng failed và không ngăn cursor/branch tiếp theo.

PostgreSQL chạy entity schema thật (Hibernate create-drop): trừ cọc INITIAL và cọc hoàn; ròng hoàn; receipt 23:59:59.999/00:00:00 Việt Nam, late-collection/due cohorts; membership và invoice late/rollover tách đúng tháng; CANCELLED/EXPIRED/unpaid không sinh phiếu; backfill đồng thời + chạy lại + snapshot hash/issued_at bất biến; concurrent lifetime/daily/unique counters + rollback + guest + không count DRAFT; scope owner/branch/listing; favorites/noLeads đúng biên 7 ngày; funnel cohort loại system cancellation/DEPOSIT/appendix/CANCELLED contracts; snapshot tháng bất biến và không lộ owner khác; migration ADD COLUMN lặp, dòng accepted/rejected cũ vẫn null; xuất 20.001 phiếu thật trả422.

Không chạy full test suite, frontend/mobile hay provider thật; không benchmark tải production. Dữ liệu view/SLA chỉ có từ ngày ghi, thiếu lịch sử số phòng trước snapshot là giới hạn dữ liệu có thật; không phục dựng bằng ước lượng. Vòng PostgreSQL cuối sau khi thêm kiểm tra occupancy với assignment/handover thật và chặn legacy link lộ tên tenant khác owner:

```powershell
.\mvnw.cmd -Pisolated-build -pl hs-contract-service -am '-Dtest=LandlordAnalyticsPostgresTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

**10 tests, 0 failure, 0 error, 0 skip, exit 0** (9 ca trước chạy lại + 1 ca occupancy mới). Log cục bộ: `hs-api-service/target/p3-postgres-final-validation.log`. Container trên đã được `docker rm --force hs-p3-codex-20261128-a71f` xóa thành công; không xóa container nào khác.
