# P3 — Phân tích & Báo cáo chủ nhà (`/dashboard/analytics`)

Trạng thái: THIẾT KẾ, chờ chủ dự án duyệt (chưa code). Ngày 2026-11-28. Chỉ đọc code để viết.
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
