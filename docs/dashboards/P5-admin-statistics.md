# P5 · Thống kê (admin) — `hs-admin-portal` `/analytics/statistics`

File hiện tại: `src/pages/analytics/StatisticsPage.tsx` (7 dòng, trang trắng). Route có sẵn (`router/index.tsx`, trong `<Route path="analytics">`), chưa gắn quyền; sidebar "Báo cáo & Phân tích → Thống kê số liệu" (`layouts/Sidebar.tsx:90`). Trạng thái: **THIẾT KẾ, chờ duyệt**. Không làm "Dự báo AI" (trang `ai-forecast` giữ nguyên).

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
| (8) KYC theo nhà cung cấp (bảng)     | (9) Tỉ lệ chuyển đổi (4 thanh %)  |
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
| 2 | Tin đăng theo trạng thái | Tin có `submitted_at` trong kỳ (đã gửi duyệt; loại `DRAFT` thuần chưa gửi), nhóm theo `status` hiện tại (10 giá trị `ListingStatus`: DRAFT, PENDING_REVIEW, PUBLISHED, RESERVED, RENTED, RENTED_EXTERNALLY, EXPIRED, REJECTED, HIDDEN, VIOLATION; `hs-listing-service/.../ListingStatus.java:3-14`). Kèm số tin gửi mới theo bucket (cột). `active = true`. | `listings.status`, `submitted_at` (index `idx_listings_status_submitted`, `Listing.java:16`) | `/properties/all` |
| 3 | Tin đăng theo tỉnh | Cùng tập tin như (2) nhưng chỉ `status IN (PUBLISHED, RESERVED, RENTED)` ("đang/đã hoạt động"), nhóm theo `listing_addresses.city` (cột `city_id`, `city`; `AddressFields.java:19-20`; 1-1 với tin qua `listing_id` unique). Top 10, phần còn lại gộp "Khác". Tin chưa có địa chỉ → "Chưa rõ". | `listing_addresses`⋈`listings` | `/properties/all` |
| 4 | Hợp đồng theo trạng thái | Hợp đồng có `created_at` trong kỳ, nhóm theo `contracts.status` (`ContractStatus`: DRAFT, PENDING_REVIEW, LANDLORD_SIGNATURE_PENDING, TENANT_SIGNATURE_PENDING, ACTIVE, EXPIRED, CLOSED, TERMINATED; `Contract.java:63`). Cộng số HĐ đã ký đủ trong bucket theo `signed_at` (`Contract.java:83`). Chỉ tính HĐ gốc `parent_contract_id IS NULL` (loại phụ lục/gia hạn để không đếm trùng một quan hệ thuê). | `contracts` | `/contracts/all` |
| 5 | GMV tiền thuê đã xác nhận | `SUM(total_amount)` của `payment_requests` có `status=CONFIRMED`, `direction=TENANT_TO_LANDLORD`, `type IN (INITIAL, RESERVATION_DEPOSIT, MONTHLY_RENT, UTILITY, OTHER_CHARGE)`, theo bucket `confirmed_at` (`PaymentRequest.java:63-78,126-127`). Tách cột chồng theo `type`. Loại các khoản hoàn (`DEPOSIT_REFUND`, `DEPOSIT_ADJUSTMENT`, `INITIAL_PAYMENT_REFUND`). Đơn vị VND (`currency='VND'`). Chỉ số tổng và số giao dịch; **không** có người nộp/người nhận, mã chuyển khoản, tham chiếu ngân hàng. | `payment_requests` | không (tổng hợp) |
| 6 | Doanh thu hội viên theo gói | `SUM(amount_vnd)` của `membership_orders` `status=PAID`, `paid_at` trong kỳ, cột chồng theo `tier` (FREE/STARTER/PRO/BUSINESS; `MembershipTier.java:4`) cho `kind=PLAN` và một nhóm "Mua thêm lượt" cho `kind=ADDON` (`MembershipOrder.java:19-22`). Nguồn là `membership_orders`, **không** phải `membership_receipts` (bảng này không có cột tiền, chỉ `snapshot_json`; xem P4 khối D). | `membership_orders` | không |
| 7 | Doanh thu hội viên theo cổng | Như (6) nhưng nhóm theo `provider` (`SEPAY`, `ETH`, `VNPAY` lịch sử; `MembershipOrder.java:45`): số đơn, tổng VND, tỉ trọng %. Đơn ETH chỉ cộng VND quy đổi, không hiện giá trị ETH/ví. | `membership_orders` | không |
| 8 | KYC theo nhà cung cấp | Bản ghi `kyc_verifications` tạo trong kỳ, nhóm `provider` (`VNPT`, `DIDIT` lịch sử, `ADMIN` nhập tay; `KycProvider.java`) × `status` (NOT_VERIFIED… EXPIRED; `KycStatus.java`). Cột: tổng, VERIFIED, REJECTED, đang chờ, tỉ lệ thành công = VERIFIED/(VERIFIED+REJECTED) (loại đang chờ khỏi mẫu số). | `kyc_verifications` (`KycVerification.java:23-40`) | `/users` |
| 9 | Tỉ lệ chuyển đổi (theo nhóm đối tượng tạo trong kỳ, không phải tỉ lệ hai tổng khác nhau) | (a) **Đăng ký → KYC**: trong số người dùng USER tạo trong kỳ, % có ít nhất một `kyc_verifications.status=VERIFIED`. (b) **Tin gửi duyệt → được đăng**: trong số tin `submitted_at` trong kỳ, % có `published_at IS NOT NULL` (`Listing.java:68`). (c) **HĐ tạo → ký đủ**: trong số HĐ gốc tạo trong kỳ, % có `signed_at IS NOT NULL`. (d) **HĐ ký đủ → có thanh toán xác nhận**: trong số HĐ gốc ký trong kỳ, % có ít nhất một `payment_requests` `CONFIRMED` loại `INITIAL` (`contract_id` có index `idx_payment_request_contract`, `PaymentRequest.java:20`). Mỗi thanh hiển thị "x / y (z%)"; nhóm mẫu số = 0 → "—". | nhiều bảng | không |

Trạng thái: đang tải = skeleton theo từng thẻ/biểu đồ; trống (mọi series bằng 0) = khung biểu đồ kèm "Không có dữ liệu trong khoảng này"; lỗi = banner có nút thử lại, giữ nguyên bộ lọc; khoảng không hợp lệ = lỗi tại ô nhập, không gọi API.

## 5. API mới tổng hợp & biểu đồ
`GET /api/v1/admin/statistics?from=2026-11-01&to=2026-11-28&granularity=DAY` — `AdminStatisticsController` (cùng package với `ComplaintAdminController`, mẫu `@RequestMapping({"/admin/statistics","/api/v1/admin/statistics"})`).
- Auth: `@PreAuthorize("hasAuthority('ADMIN') and hasAuthority('STATISTICS_VIEW')")` (cùng mẫu `OperationalIncidentController.java:12`). `STATISTICS_VIEW` là quyền mới (thêm vào `Permissions.java` + `CATALOG`; ADMIN tự nhận ở `PermissionDataInitializer.java:55`; phản chiếu ở `config/permissions.ts` và thêm `"/analytics/statistics": { permission: PERMISSIONS.STATISTICS_VIEW }` vào `ROUTE_PERMISSIONS`; router bọc bằng `guard("/analytics/statistics", …)`; sidebar tự ẩn theo `useCanAccessPath`). Không nhận userId từ tham số.
- Tham số kiểm ở `@Validated`: `from`/`to` `LocalDate`, `granularity` enum; vi phạm giới hạn → `AppException INVALID_REQUEST`.
- Response:
```json
{ "code":1000, "result": {
  "range":{"from":"2026-11-01","to":"2026-11-28","granularity":"DAY","timezone":"Asia/Ho_Chi_Minh"},
  "kpi":{ "newUsers":{"value":412,"previous":381}, "submittedListings":{...}, "signedContracts":{...},
          "gmvVnd":{"value":1820000000,"previous":1733000000}, "membershipRevenueVnd":{...} },
  "series":{ "buckets":["2026-11-01","2026-11-02"],
    "newUsers":[12,9], "newLandlords":[3,2], "submittedListings":[8,6], "signedContracts":[2,3],
    "gmvByType":{"INITIAL":[..],"MONTHLY_RENT":[..],"UTILITY":[..],"OTHER_CHARGE":[..],"RESERVATION_DEPOSIT":[..]},
    "membershipByTier":{"STARTER":[..],"PRO":[..],"BUSINESS":[..],"ADDON":[..]} },
  "breakdown":{ "listingsByStatus":{"PUBLISHED":120,"REJECTED":7},
                "listingsByCity":[{"city":"Hà Nội","count":88},{"city":"Khác","count":31}],
                "contractsByStatus":{"ACTIVE":40,"DRAFT":9},
                "membershipByProvider":[{"provider":"SEPAY","orders":31,"amountVnd":9100000}],
                "kycByProvider":[{"provider":"VNPT","total":210,"verified":150,"rejected":20,"pending":40}] },
  "funnel":{ "registeredToKyc":{"num":150,"den":412}, "submittedToPublished":{...}, "contractCreatedToSigned":{...}, "signedToPaid":{...} } } }
```
- Cài đặt: một `@Transactional(readOnly=true)` service, native SQL Postgres, mỗi khối một câu `GROUP BY` (≈14 câu, chạy tuần tự trong một transaction; không tải thực thể). Index mới cần: `users(created_at)`, `listings(created_at)`, `contracts(created_at)` (chưa có; `Contract.java:15-20` chỉ có theo `contract_number`, `landlord_id`…), `payment_requests(status, confirmed_at)`, `membership_orders(status, paid_at)`, `kyc_verifications(created_at)`.
- Cache: 60 giây theo `(from,to,granularity)`; kỳ có `to = hôm nay` TTL 60s, kỳ kết thúc trước hôm nay TTL 10 phút.
- **Biểu đồ:** `package.json` không có thư viện biểu đồ. Khuyến nghị mặc định (xem Câu hỏi 1): **thêm `recharts`** (đã quen với React 19, tree-shake, hỗ trợ cột chồng/đường/thanh ngang/tooltip/legend) vì trang này cần 9 biểu đồ có tooltip, chú giải và trục; viết tay SVG sẽ tốn và khó kiểm tra. Phương án thay thế không thêm phụ thuộc: tái dùng `MiniBars` của P4 cho cột/thanh, bỏ đường chuỗi (thay bằng cột) — kém hơn. Màu lấy từ token `--chart-1..5` (`index.css:85-89`), nhãn có chữ (không chỉ màu), trục Y bắt đầu từ 0, định dạng `vi-VN` (VND: `1,82 tỷ`, `12,3 tr`). Loại biểu đồ: (1) đường 2 series theo kỳ; (2)(3)(4) thanh ngang theo hạng mục; (5)(6) cột chồng theo kỳ; (7) bảng + thanh tỉ trọng; (8) bảng; (9) 4 thanh phần trăm.
- **Xuất CSV:** thực hiện ở client từ chính JSON đã tải (không thêm endpoint, nên quyền và số liệu đúng bằng màn hình). Một nút → một file `thong-ke_{from}_{to}_{granularity}.csv` UTF-8 có BOM (Excel đọc đúng tiếng Việt), mỗi khối một đoạn có dòng tiêu đề, tiền là số nguyên VND không dấu phân cách. Chống CSV injection: ô văn bản bắt đầu bằng `= + - @` thì thêm `'` đứng trước; tên tỉnh đến từ dữ liệu người dùng nên bắt buộc làm.

## 6. Hiệu năng & bảo mật
- Chi phí ở 10k tin / 100k thanh toán / 100k người dùng: các `GROUP BY` trên cột có index trong khoảng ≤ 92 ngày quét < 50k hàng/câu → mục tiêu tổng < 500 ms; (9a)(9d) dùng `EXISTS`/`LEFT JOIN` theo tập kỳ, không tương quan từng hàng; (3) `JOIN` 1-1 qua `listing_id` unique. Tránh N+1: không dùng JPA entity. Kỳ 36 tháng ở MONTH chỉ quét thêm, vẫn có index; vượt giới hạn bị chặn ở validation (không để admin tự DoS bằng khoảng 10 năm).
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
7. Không rò rỉ: JSON response không chứa khoá `ownerId/payerId/email/...` (so khớp danh sách khoá cho phép).
Frontend (`node --test`): hàm `buildCsv()` — BOM, escape dấu phẩy/ngoặc kép, chống injection (`=cmd` → `'=cmd`), tiền là số nguyên; `previousPeriod(from,to)`; `clampGranularity`; bộ lọc không hợp lệ không gọi API; thiếu `STATISTICS_VIEW` → route bị `RequirePermission` chặn.

## 8. Câu hỏi mở
1. Thêm `recharts` hay tự vẽ SVG? Mặc định: thêm `recharts` (một phụ thuộc, dùng cho cả P5); P4 vẫn dùng `MiniBars` thuần.
2. "GMV tiền thuê" có gồm tiền cọc giữ chỗ (`RESERVATION_DEPOSIT`) và khoản đầu kỳ `INITIAL` (có thể chứa tiền cọc) không? Mặc định: có (là tiền khách thực nộp qua nền tảng), nhưng hiển thị tách theo `type` để chủ dự án loại bớt; nếu cần "chỉ tiền thuê", chỉ lấy `MONTHLY_RENT`.
3. "Chủ nhà mới" = người có tin đăng đầu tiên trong kỳ, hay = người đã KYC + có tin? Mặc định: tin đăng đầu tiên (đơn giản, kiểm chứng được); không dùng KYC để tránh phụ thuộc vào luồng xác minh.
4. Có cần lưu cấu hình bộ lọc theo từng admin? Mặc định: không; ghi nhớ lựa chọn gần nhất trong `localStorage` (bọc `try/catch`), không phải dữ liệu máy chủ.
5. Thời gian giữ dữ liệu mô phỏng/seed có làm méo thống kê? Mặc định: thống kê tính trên mọi hàng `active=true`; môi trường production không có dữ liệu seed; không thêm cờ "loại dữ liệu demo".
