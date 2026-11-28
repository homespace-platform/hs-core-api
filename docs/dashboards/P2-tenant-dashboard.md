# P2 — Dashboard người thuê (`/dashboard/tenant`)

Trạng thái: THIẾT KẾ, chờ chủ dự án duyệt. Web: `hs-web-app/app/dashboard/tenant/page.tsx` (hiện chỉ có dòng "đang được xây dựng", file dòng 1-30). Backend: `hs-core-api`. Mọi `file:line` dưới đây đã đọc ngày 28/11/2026; "(giả định)" = chưa kiểm chứng được bằng code.

## 1. Mục đích & người xem
Người xem: tài khoản đang ở vai "Người đi thuê" (menu `TENANT_NAV`, `components/dashboard/dashboard-nav.ts:78-109`). Trong 10 giây phải trả lời: (a) **mình có phải làm/trả gì gấp không** (quá hạn, hạn chót ký/xác nhận/phản hồi), (b) **sắp đến hạn gì** (tiền, lịch xem, giữ chỗ, hết hợp đồng), (c) **đang thuê ở đâu**. Người thuê có thể có N hợp đồng (nhiều phòng): mọi khối gom theo hợp đồng, không giả định 1 phòng.

## 2. Bố cục
```
[Banner KYC: chỉ hiện khi chưa VERIFIED]                       -> /kyc?returnTo=...
+--------------+--------------+--------------+--------------+
| Đang thuê    | Phải trả     | Việc cần làm | Lịch xem     |   (4 ô KPI, bấm được)
| 2 phòng      | 3,5tr (1 QH) | 4 (1 gấp)    | 2 sắp tới    |
+--------------+--------------+--------------+--------------+
| VIỆC CẦN LÀM (danh sách theo hạn, gấp trước)  | GIỮ CHỖ SẮP HẾT HẠN   |
|  [Gấp] Ký phụ lục HĐ-0012 · còn 5 giờ  [Ký]  |  Phòng A · còn 12:30  |
|  [Sớm] Phản hồi đề nghị đổi giá · 2 ngày      |  [Thanh toán ngay]    |
+-----------------------------------------------+-----------------------+
| KHOẢN PHẢI TRẢ                                | LỊCH XEM SẮP TỚI      |
|  Quá hạn: 1 khoản 3,2tr [Thanh toán]          |  T6 29/11 09:00 ...   |
|  Kế tiếp: 05/12 · 3,5tr · HĐ-0012 [Thanh toán]|                       |
+-----------------------------------------------+-----------------------+
| NƠI ĐANG THUÊ (thẻ cho từng hợp đồng, ≤6, "Xem tất cả")               |
| [ảnh] Tên tin · địa chỉ · Chủ nhà · 01/01/26 → 01/07/26 (còn 215 ngày)|
+-----------------------------------------------+-----------------------+
| CHI TIÊU THEO THÁNG (cột chồng 6/12 tháng)    | YÊU CẦU THUÊ ĐÃ GỬI   |
|                                               | Chờ 2 · Chấp nhận 1.. |
+-----------------------------------------------+ TIN ĐÃ LƯU (4 thẻ)    |
```
Màn nhỏ (<768px): một cột theo thứ tự KPI (2x2) → Việc cần làm → Giữ chỗ → Phải trả → Lịch xem → Nơi đang thuê (vuốt ngang) → Chi tiêu → Yêu cầu → Tin đã lưu. Banner KYC luôn trên cùng. Style: `bg-card rounded-2xl border border-border`, như file hiện tại.

## 3. Từng khối
Quy ước chung: giờ hiển thị `Asia/Ho_Chi_Minh`; **mọi đếm ngược dùng `serverNow`/`realNow` do API trả**, không dùng đồng hồ trình duyệt. Hạn tiền/ký/phản hồi dùng đồng hồ **BillingTime** (`BillingTime.java:61-62`; chọn theo `PaymentTimePolicy.usesBillingTime`, `PaymentTimePolicy.java:6-11`); lịch xem dùng giờ thật (`ViewingAppointmentService.java:94-95`, `LocalDate.now(VIETNAM_ZONE)`). Mỗi mục trả `clock: BILLING|REAL` như `BranchAttentionResponse.Work` (`BranchAttentionResponse.java:24-26`). Mức gấp như `BranchAttentionService.priority` (`:134-139`): URGENT ≤24h hoặc quá hạn, SOON ≤3 ngày hoặc loại luôn-sớm, còn lại NORMAL.

**W0. Banner KYC** — Nguồn (có sẵn): `profile.kycVerified` đã nạp trong `useAuth` (`types/user.type.ts:18`, `lib/kyc-gate.ts:14-16 isKycSatisfied`), khi chưa xác minh gọi `GET /users/me/kyc` (`KycController.java:25-31`) lấy `status` (`KycStatus`: NOT_VERIFIED/PENDING/REVIEW_REQUIRED/VERIFIED/REJECTED/EXPIRED) và `rejectionReason`. Nội dung: NOT_VERIFIED/EXPIRED "Xác minh danh tính để gửi yêu cầu thuê, đặt lịch xem" (backend chặn qua `kycGateService.requireVerifiedIdentity`, `RentalRequestController.java:77`, `ViewingAppointmentController.java:63`); PENDING/REVIEW_REQUIRED "Đang chờ duyệt" (không nút); REJECTED hiện lý do + "Thử lại". Bấm → `kycHref()`. Ẩn khi VERIFIED. Lỗi tải: không hiện banner (không chặn trang).

**W1. KPI (4 ô)** — lấy từ response tổng hợp (mục 4), không gọi thêm. `leases.activeCount`; `payables.overdueAmount + dueSoonAmount` (dueSoon = đến hạn trong 7 ngày); `tasks.total` (+ số URGENT); `visits.upcomingCount`. Bấm ô cuộn tới khối tương ứng.

**W2. Nơi đang thuê (N hợp đồng)** — Định nghĩa: hợp đồng gốc `tenantId = user`, `active`, `parentContractId is null`, không phải hồ sơ cọc (`agreementKind` ≠ DEPOSIT), `status = ACTIVE` (`ContractStatus.java:10`; cùng bộ lọc `ContractServiceImpl.java:400-406`). Trường mỗi thẻ: `contractId, contractNumber, listingId, listingTitle, listingAddress, listingThumbnail, landlordName` (chỉ tên hiển thị, không SĐT/email/CCCD), `startsOn` (= `rental.moveInDate`), `endExclusive`, `daysRemaining`, `monthlyRent` (= `rental.effectiveMonthlyRent`, `RentalRequestResponse.java:29`), `expiry` (WITHIN_30/WITHIN_60/LONG như `BranchMonitoringService.expiry`, dùng ở `BranchAttentionService.java:808-812`). **Ngày hết hạn tính đúng một công thức**: `ContractLeaseCalc.end(agreedEarlyEndExclusive, agreedEarlyBillingMonths, effectiveLeaseMonths, rental.leaseMonths, rental.moveInDate)` (`ContractLeaseCalc.java:33-38`) — ưu tiên ngày kết thúc sớm đã thoả thuận, sau đó tháng đang hiệu lực, sau cùng tháng yêu cầu ban đầu; **ngày kết thúc là exclusive** (`:12`). Web hiển thị cùng giá trị như `ContractDetailView.tsx:488` (`startsOn – endExclusive`); không tự cộng tháng ở frontend. Bấm thẻ → `/dashboard/tenant/contracts/{id}` (`lib/contract-routes.ts:3-9`). Trống: "Bạn chưa thuê phòng nào" + nút "Tìm phòng" (`/rent`, giả định route). Hiển thị ≤6 thẻ, sắp theo `endExclusive` tăng; thừa → link `/dashboard/tenant/contracts`. HĐ chưa hiệu lực (đang ký, chờ cọc) không ở đây mà ở W4 (việc cần làm).

**W3. Khoản phải trả** — Nguồn hiện có KHÔNG đủ: `GET /payment-requests/mine` (`PaymentRequestController.java:47-53`) trả toàn bộ lịch sử không phân trang, kèm snapshot tài khoản ngân hàng (`PaymentRequestResponse.java:28-29`); `GET /monthly-invoices/contracts/{id}` (`MonthlyInvoiceController.java:55-59`) gọi `findByIdForUpdate`+`syncContract` (`MonthlyBillingService.java:431-438`), tức một lần GET có khoá ghi cho mỗi hợp đồng → không dùng cho N hợp đồng. Dùng endpoint mới (mục 4). Định nghĩa khoản phải trả: `payment_requests` với `payer_id = user`, `direction = TENANT_TO_LANDLORD`, `active`, `total_amount > 0`, `status ∈ {AWAITING_TRANSFER, REJECTED, OVERDUE}` (bộ `NOT_REPORTED`, `BranchAttentionService.java:68-70`), `opensAt` null hoặc ≤ now. Loại: INITIAL, RESERVATION_DEPOSIT, MONTHLY_RENT, UTILITY, OTHER_CHARGE, DEPOSIT_ADJUSTMENT (`PaymentType.java`); không gồm `*_REFUND`. Quá hạn khi `dueAt < now(clock)` (suy ra, không tin cột `OVERDUE` vì nó cập nhật bằng job; cùng logic `BranchOverviewService.invoiceStatus`, `:501-518`). `TRANSFER_REPORTED` (đã báo chuyển, chờ chủ nhà xác nhận) hiện ở dòng riêng "Chờ xác nhận: n khoản", không tính vào số phải trả. Tổng quá hạn = Σ `totalAmount` quá hạn (đã gồm phí trễ nếu có, `MonthlyInvoice.lateFeeAmount`; giả định `totalAmount` là số phải chuyển). Kế tiếp = khoản chưa quá hạn có `dueAt` nhỏ nhất, theo từng hợp đồng + toàn cục. Mỗi dòng: `paymentId, type, contractId, contractNumber, listingTitle, invoiceId, periodIndex, amount, dueAt, clock, overdue, rejectedReason, href`. Link: hoá đơn tháng → `/dashboard/tenant/contracts/{contractId}?tab=payment&section=monthly&invoice={invoiceId}` (mẫu `BranchOverviewService.invoiceTarget`, `:579-582`, đổi sang đường dẫn tenant); loại khác → `/dashboard/payments?paymentId={id}` (`BranchOverviewService.java:584-586`; trang đọc `paymentId`, `payments/page.tsx:134`). Mọi lần thanh toán (QR, báo chuyển khoản) làm ở trang đích, dashboard không chứa số tài khoản. Trống: "Không có khoản nào cần thanh toán". Lưu ý quy tắc hạn: bên trễ bị hệ thống tự xử lý (memory deadline-default-resolution) → dòng quá hạn phải hiện hạn xử lý, không hứa gia hạn.

**W4. Việc cần làm (tenant là người phải hành động)** — Mỗi mục `{key,type,contractId|rentalRequestId,contextLabel,dueAt,clock,priority,href}`, sắp theo priority, thời gian còn lại, key (như `BranchAttentionService.java:176-179`), tối đa 20 (còn lại: "Xem thêm"). Một mục chỉ tồn tại khi điều kiện nguồn còn đúng (không lưu bảng riêng). Các loại:
| type | điều kiện / nguồn | hạn | đích |
|---|---|---|---|
| PAY_DEPOSIT, PAY_FIRST_RENT | `RentalOnboardingService.NextAction` actor TENANT (`RentalOnboardingService.java:85,120,127`) | `nextAction.dueAt` | tab `payment` |
| SIGN_DEPOSIT, SIGN_LEASE | NextAction `SIGN_DEPOSIT_TENANT`/`SIGN_LEASE_TENANT` (`:123-132`) | `signingDueAt` | `?tab=sign` |
| RESPOND_DEFAULT_NOTICE | NextAction (`:118`) | `defaultRespondBy` | trang yêu cầu |
| CONFIRM_REFUND | NextAction `CONFIRM_REFUND` (`:139`) hoặc `payment_requests` payee=user, `LANDLORD_TO_TENANT`, `TRANSFER_REPORTED` | `confirmationDueAt` | `/dashboard/payments?paymentId=` |
| SIGN_ANNEX | hợp đồng con (phụ lục gia hạn/điều chỉnh) `TENANT_SIGNATURE_PENDING` còn trước `signBy` (`ContractAmendment.signBy`, `ContractRenewal.signBy`; mẫu `BranchAttentionService.java:636-690`) | `signBy` | `/dashboard/contracts/{childId}?tab=sign` |
| AMENDMENT_RESPONSE | `ContractAmendment` đang chờ tenant: REQUESTED/TENANT_REQUESTED/TENANT_ACCEPTED với `recipientId=user`, CHANGES_REQUESTED với `proposerId=user` (đối xứng `waitsForLandlord`, `:827-832`) | `respondBy` | `?tab=end&amendmentId=` |
| TERMINATION_RESPONSE | early-exit `PROPOSED` bởi chủ nhà (`RentalWorkflowRecord.proposedBy≠user`, `respondBy`); đề nghị chấm dứt tháng `MonthlyInvoice`/`Contract.terminationProposedAt` còn mở | `respondBy` | `?tab=end&focus=early-exit` |
| CLOSURE_RESPONSE | `ContractClosure` do chủ nhà đề xuất, `respondBy` (`ContractClosure.java:26`; giá trị `status` "chờ tenant" chưa đọc: giả định) | `respondBy` | `?tab=end` |
| LEASE_EXPIRING | HĐ ACTIVE còn ≤30 ngày (`ContractLeaseCalc.renewalOpens`, `:46-48`) | `endExclusive` | `?tab=end` |
Khoản tiền quá hạn/đến hạn KHÔNG lặp ở đây (đã ở W3); PAY_* chỉ vào danh sách khi nằm trước hạn mở thanh toán/đang chờ bước trước. Cách lấy NextAction: gọi `RentalOnboardingService.get(id, userId)` (read-only, `:197-201`) cho mỗi yêu cầu `ACCEPTED` của user, **giới hạn 10**; vì `view` dựng nhiều dữ liệu, về sau thêm bản "twin" `tenantSigningStep` như `landlordSigningStep` (`:144-160`) nếu cần giảm truy vấn. Trống: "Không có việc nào cần làm". Lỗi: cả trang báo lỗi + "Thử lại".

**W5. Giữ chỗ sắp hết hạn** — Định nghĩa: `rental_requests` của user, `status = ACCEPTED`, `holdExpiresAt != null`, `holdExpiresAt > now`, và tiền chưa xác nhận (NextAction PAY_DEPOSIT/PAY_FIRST_RENT). `holdExpiresAt` đặt khi chấp nhận (`RentalRequestService.java:321-324`), thời lượng mặc định 60 phút (`RentalRequestController.java:40-41`, `GET /rental-requests/policy`), gia hạn khi tenant thanh toán/proof bị từ chối (`RentalOnboardingService.java:541`), xoá khi huỷ/ký xong. Hết hạn thì job chuyển `EXPIRED`/huỷ (`RentalRequestService.java:575-580`). Đồng hồ: `businessTime` (giả định = BillingTime). Hiện tất cả giữ chỗ còn hiệu lực, đỏ khi còn ≤15 phút; ô đếm ngược theo `serverNow`. Trường: `rentalRequestId, listingTitle, thumbnail, holdExpiresAt, amountDue, payHref`. Bấm → `/dashboard/rental-requests/my-requests?requestId={id}` (`useRentalRequestDeepLink.ts:8`). Trống: ẩn khối.

**W6. Lịch xem sắp tới** — Nguồn mới (mục 4) vì `GET /appointments/my-bookings` chỉ lọc `status` hoặc đúng 1 `date` và sắp **giảm dần** (`ViewingAppointmentController.java:70-78`, `ViewingAppointmentService.java:548-573`), không có "từ hôm nay". Định nghĩa: `viewing_appointments` `renter_id = user`, `active`, `status ∈ {PENDING, CONFIRMED}` (`AppointmentStatus.java`), `appointmentDate > today` hoặc (`= today` và `startTime ≥ now`) giờ VN thật; sắp tăng; 5 mục + tổng. Trường: `id, listingId, listingTitle, listingAddress, thumbnail, appointmentDate, startTime, endTime, status, rescheduleRequested, proposedDate/StartTime` (không SĐT/email người thuê, `AppointmentResponse.java:21-23` có nên bỏ). PENDING gắn nhãn "Chờ chủ nhà xác nhận". Bấm → `/dashboard/viewing-schedules/my-bookings` (không có deep-link theo id: giả định, kiểm lại khi code). Trống: "Chưa có lịch xem" + "Tìm phòng".

**W7. Chi tiêu theo tháng** — Định nghĩa: tổng `payment_requests.total_amount` của các khoản **đã xác nhận có biên nhận**: `payment_receipts r` JOIN `payment_requests p` ON `p.id = r.payment_request_id`, `p.payer_id = user`, `p.direction = TENANT_TO_LANDLORD`. Biên nhận chỉ sinh khi `CONFIRMED` và tiền > 0 (`PaymentReceiptService.capture`, `:44-49`), `issuedAt = confirmedAt`. Loại hoàn tiền chủ nhà→tenant (payer là chủ nhà, `:60-63`). Tháng = `issuedAt` quy về `Asia/Ho_Chi_Minh`, cửa sổ 12 tháng kết thúc ở tháng hiện tại theo **BillingTime**, lấp 0 cho tháng trống; mặc định hiện 6 tháng, nút chuyển 12. 3 nhóm cột chồng: "Cọc & tháng đầu" (INITIAL, RESERVATION_DEPOSIT, DEPOSIT_ADJUSTMENT), "Tiền thuê hàng tháng" (MONTHLY_RENT), "Điện nước & phí khác" (UTILITY, OTHER_CHARGE). Trường: `months[{month:"2026-11", total, byGroup{...}}], currency:"VND", total12m`. Bấm cột → `/dashboard/invoices/receipts` (`app/dashboard/invoices/receipts`, có sẵn; giả định hỗ trợ lọc theo tháng, không thì mở không lọc). Trống (không có biên nhận 12 tháng): "Chưa có khoản thanh toán nào được xác nhận".

**W8. Yêu cầu thuê đã gửi theo trạng thái** — Đếm theo `RentalRequestStatus` (`RentalRequestStatus.java`) của `renter_id = user`, `active`: nhóm "Chờ phản hồi" (PENDING, kèm `respondBy`), "Đã chấp nhận" (ACCEPTED), "Bị từ chối" (REJECTED), "Đã huỷ" (CANCELLED_BY_RENTER/OWNER/SYSTEM), "Hết hạn" (EXPIRED), "Hoàn tất" (COMPLETED). Hiện hiện chỉ có danh sách phân trang theo 1 status (`RentalRequestController.java:80-88`) → đếm cần group-by mới (index `idx_rental_request_renter_status`, `RentalRequest.java:14`). Kèm 3 yêu cầu gần nhất còn mở (PENDING/ACCEPTED): `id, listingTitle, thumbnail, status, createdAt, respondBy, holdExpiresAt`. Bấm chip → `/dashboard/rental-requests/my-requests` (chưa có lọc status trong URL: giả định, chip chỉ điều hướng); bấm mục → `?requestId=`. Trống: "Bạn chưa gửi yêu cầu thuê nào".

**W9. Tin đã lưu** — Có API: `GET /favorites?page=1&size=4` trả `PageResponse<PublicListingSummaryResponse>` có `totalElements` (`FavoriteController.java:58-64`, `PageResponse.java:11-13`), client `services/favorite.service.ts`; gọi riêng, độc lập lỗi với response tổng hợp. Hiện 4 thẻ + "Xem tất cả (n)" → `/favorites` (`app/favorites/page.tsx`). Trống: "Chưa lưu tin nào". Lỗi: ẩn khối kèm "Không tải được".

## 4. API mới tổng hợp
Một endpoint cho W1-W8: **`GET /dashboard/tenant/summary`** (thêm `/api/v1/dashboard/tenant/summary` theo kiểu `ContractController.java:42`). Controller `TenantDashboardController` (hs-api-service/.../controller/contract) → `TenantDashboardService` (hs-contract-service; module này phụ thuộc listing/payment/user nên đọc được mọi bảng, `AGENTS.md` mục Maven Modules). Auth: chỉ cần đã đăng nhập; `userId = CurrentUser.requireUserId()` (mẫu `BranchAttentionController.java:22-27`), **không nhận userId/role trong query**, tham số lạ → 400 (dùng `MultiValueMap` như controller trên). Không cần permission mới (mọi API tenant hiện tại như `/rental-requests/my-requests` cũng không có `@PreAuthorize`). Query: `spendMonths` (6|12, mặc định 12, ngoài khoảng → 400). `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`, **không** sync, không khoá ghi (như `BranchAttentionService.java:93-94`). Không cache (repo chưa có `@Cacheable`/`@EnableCaching`; đủ nhanh với truy vấn có index). Frontend: nạp khi vào trang, nút "Làm mới", tự nạp lại khi tab hiện lại và mỗi 60 giây.
```json
{ "billingNow":"2026-11-28T03:00:00Z", "realNow":"…", "timezone":"Asia/Ho_Chi_Minh",
  "leases":{"activeCount":2,"items":[{"contractId":"","contractNumber":"","listingId":"","listingTitle":"","listingAddress":"","listingThumbnail":"",
     "landlordName":"","startsOn":"2026-01-01","endExclusive":"2026-07-01","daysRemaining":215,"expiry":"LONG","monthlyRent":3500000,"href":""}]},
  "payables":{"overdueCount":1,"overdueAmount":3200000,"dueSoonAmount":0,"awaitingConfirmationCount":0,
     "next":{...Payable},"items":[{"paymentId":"","type":"MONTHLY_RENT","contractId":"","contractNumber":"","listingTitle":"","invoiceId":"","periodIndex":3,
        "amount":3500000,"dueAt":"…","clock":"BILLING","overdue":false,"rejectedReason":null,"href":""}]},
  "tasks":{"total":4,"urgentCount":1,"items":[{"key":"SIGN_ANNEX:<id>","type":"SIGN_ANNEX","contractId":"","rentalRequestId":null,"contextLabel":"HĐ-0012",
        "dueAt":"…","clock":"BILLING","priority":"URGENT","href":""}]},
  "holds":[{"rentalRequestId":"","listingTitle":"","thumbnail":"","holdExpiresAt":"…","amountDue":0,"href":""}],
  "visits":{"upcomingCount":2,"items":[{"id":"","listingId":"","listingTitle":"","listingAddress":"","thumbnail":"","appointmentDate":"2026-11-29","startTime":"09:00",
        "endTime":"09:30","status":"CONFIRMED","rescheduleRequested":false}]},
  "requests":{"counts":{"PENDING":2,"ACCEPTED":1,"REJECTED":0,"CANCELLED":3,"EXPIRED":0,"COMPLETED":1},"open":[{"id":"","listingTitle":"","thumbnail":"","status":"PENDING","createdAt":"…","respondBy":"…","holdExpiresAt":null}]},
  "spending":{"currency":"VND","total":0,"months":[{"month":"2026-11","total":0,"byGroup":{"DEPOSIT_FIRST":0,"RENT":0,"UTILITY_OTHER":0}}]} }
```
Truy vấn (tất cả theo lô, không N+1; mẫu `BranchAttentionService.load`, `:160-250` và `BranchAttentionReadRepository`; thêm `TenantDashboardReadRepository`):
1. `contracts` WHERE `tenant_id=:u AND active AND parent_contract_id IS NULL` (idx `idx_contract_tenant_status`, `Contract.java:19`) → lease ids (cap 50); `rental_requests` theo `rentalRequestId IN`; tên chủ nhà theo `landlordId IN` (chỉ cột tên).
2. `monthly_invoices` WHERE `contract_id IN :ids AND status IN (UNPAID,OVERDUE)` (idx `idx_monthly_invoice_contract`, `MonthlyInvoice.java:17`).
3. `payment_requests` WHERE `payer_id=:u AND status IN (AWAITING_TRANSFER,REJECTED,OVERDUE,TRANSFER_REPORTED) AND active` (idx `idx_payment_request_payer_status`, `PaymentRequest.java:21`), thêm nhánh payee=user cho CONFIRM_REFUND (idx payee_status).
4. Phụ lục/đề nghị: `findAmendments(ids, statuses)`, `contract_renewals`, early-exit, `contract_closures`, hợp đồng con theo `parent_contract_id IN`.
5. Lịch xem: `renter_id=:u AND status IN (...)` + điều kiện ngày/giờ (idx `idx_appointment_renter_status`, `ViewingAppointment.java:18`), `ORDER BY appointment_date, start_time LIMIT 5`, count riêng.
6. Yêu cầu: `SELECT status, count(*) FROM rental_requests WHERE renter_id=:u AND active GROUP BY status` + 3 bản ghi mở gần nhất.
7. Chi tiêu: `SELECT to_char(r.issued_at AT TIME ZONE 'Asia/Ho_Chi_Minh','YYYY-MM') m, p.type, sum(p.total_amount) FROM payment_receipts r JOIN payment_requests p ON p.id=r.payment_request_id WHERE p.payer_id=:u AND p.direction='TENANT_TO_LANDLORD' AND r.issued_at >= :from AND r.issued_at < :to GROUP BY m, p.type`; **index mới cần thêm** `payment_receipts(tenant_id, issued_at)` (hiện chỉ có unique `payment_request_id`, `receipt_number` và `idx_receipt_pdf_queue`, `PaymentReceipt.java:12-15`) và lọc thêm `r.tenant_id=:u`. Migration theo mẫu các lớp `*SchemaMigration` (`AGENTS.md`).
Hợp đồng/hạn dùng đúng `ContractLeaseCalc`; hạn tiền dùng `PaymentTimePolicy.usesBillingTime`.

## 5. Biểu đồ
`hs-web-app/package.json` (mục dependencies) **không có thư viện biểu đồ** (không recharts/chart.js/visx; chỉ `date-fns`, `lucide-react`). Không thêm thư viện: vẽ cột chồng bằng SVG/CSS thuần (≤12 cột x 3 nhóm). Trục X: tháng `MM/YY` (6 hoặc 12); trục Y: VND, rút gọn "tr"/"k", đường lưới 3 mức, tối thiểu 0. Màu: 3 token Tailwind sẵn có, nhóm "Cọc & tháng đầu" nhạt hơn (có thể hoàn lại); có `<title>`/bảng ẩn cho trình đọc màn hình, tooltip hiện số đúng từng nhóm. Không dùng biểu đồ khác.

## 6. Hiệu năng & bảo mật
- Chi phí: ~12 truy vấn có index, mọi tập theo `userId` nên độc lập với 10k tin đăng (chỉ W9 đụng `listing_favorites`, idx `idx_favorite_user`). Với 100k thanh toán: W3 theo `idx_payment_request_payer_status` (chỉ hàng chưa hoàn tất), W7 giới hạn cửa sổ 12 tháng nhờ index mới trên biên nhận; thiếu index này W7 quét toàn bảng biên nhận. N+1: chỉ `RentalOnboardingService.get` cho ≤10 yêu cầu ACCEPTED (chấp nhận, ghi chú tối ưu ở W4). Giới hạn cứng: 50 hợp đồng, 20 việc, 5 lịch, 3 yêu cầu mở.
- Dữ liệu: scope bằng `CurrentUser`; **không** trả `payer/payeeBankAccountSnapshot`, `qrImageUrl`, `breakdownSnapshot`, SĐT/email/CCCD của chủ nhà hay bất kỳ ai; chủ nhà chỉ có tên hiển thị. Chủ nhà A không thấy dữ liệu của tenant B và ngược lại; tài khoản vừa là chủ nhà vừa là người thuê chỉ thấy phần `tenant_id/payer_id/renter_id = mình` ở trang này.
- Cần kiểm thêm (không dựa vào ở thiết kế này): `GET /contracts/{id}` ở controller chỉ `requireUserId()` (`ContractController.java:174-180`); phải xác nhận service kiểm bên tham gia hợp đồng.
- Đồng hồ: không dùng `Instant.now()` cho hạn tiền/ký (đã nêu), tránh lệch khi `homespace.time.simulated-at` bật ở dev (`BillingTime.java:32-37`).

## 7. Kiểm thử cần viết
Backend (`hs-contract-service/src/test`, kiểu `BranchAttentionTest` + một bản Postgres như `BranchAttentionPostgresTest`, DB tạm theo README):
- Scope: user B có hợp đồng/khoản trả/lịch/yêu cầu/biên nhận → summary của A rỗng; tài khoản vừa là chủ nhà vừa tenant chỉ thấy phía tenant; user chưa đăng nhập → 401; `?userId=` hoặc tham số lạ → 400 (denied).
- N hợp đồng: 3 hợp đồng ACTIVE khác phòng, mỗi cái có khoản riêng, đủ 3 thẻ + khoản "kế tiếp" đúng toàn cục và theo hợp đồng; hợp đồng CANCELLED/TERMINATED/hồ sơ cọc/phụ lục không vào W2.
- Ngày hết hạn: bảng giá trị song song `BranchLeaseEndParityTest` (kết thúc sớm / tháng hiệu lực / tháng yêu cầu) → cùng `endExclusive`.
- Quá hạn: `dueAt` trước/sau `billingNow` khi BillingTime ≠ giờ thật; trạng thái `OVERDUE` lưu cũ nhưng `dueAt` tương lai (gia hạn) → không quá hạn; `TRANSFER_REPORTED` không tính vào phải trả; hoàn tiền (LANDLORD_TO_TENANT) không vào W3, mà vào CONFIRM_REFUND.
- Việc cần làm: từng `type` có và biến mất khi điều kiện hết (đã ký, quá `signBy`, đã phản hồi); thứ tự URGENT/SOON/NORMAL; trùng key không nhân đôi.
- Giữ chỗ: ACCEPTED còn hạn hiện; hết hạn/đã thanh toán/đã huỷ không hiện.
- Lịch xem: hôm nay đã qua giờ bắt đầu → loại; hôm nay chưa tới → có; CANCELLED/REJECTED/EXPIRED/COMPLETED → loại; sắp tăng dần.
- Chi tiêu: biên nhận 23:59 và 00:01 ngày cuối tháng giờ VN (khác UTC) vào đúng tháng; hoàn tiền và biên nhận do mình là bên nhận không tính; tháng trống = 0; nhóm đúng theo `PaymentType`; biên nhận của tenant khác không lẫn; `spendMonths=13` → 400.
- Lộ dữ liệu: serialize JSON và khẳng định không có khoá `BankAccountSnapshot`, `qrImageUrl`, `phone`, `email`, `idNumber`.
- Hiệu năng: đếm số câu SQL (Hibernate statistics) không đổi khi tăng số hợp đồng 3→30 (trừ phần onboarding ≤10).
Frontend (`hs-web-app/lib/*.test.ts` — repo chỉ có test lib, ví dụ `appointment-calendar.test.ts`; giả định chưa có test component): `lib/tenant-dashboard.test.ts` cho hàm thuần: lấp tháng trống, định dạng VND rút gọn, nhóm yêu cầu theo trạng thái, đếm ngược theo `serverNow` lệch giờ máy, chọn "kế tiếp", ánh xạ priority → màu/nhãn, ẩn banner KYC khi VERIFIED. Trường hợp denied: 401 → chuyển đăng nhập, 403/400 → thông báo lỗi chung; mất mạng → trạng thái lỗi + nút thử lại, banner KYC và W9 vẫn độc lập.

## 8. Câu hỏi mở cho chủ dự án
1. Chi tiêu có tính tiền cọc (hoàn lại được) vào tổng không? Mặc định: có, nhưng tách nhóm "Cọc & tháng đầu" màu nhạt kèm chú thích; tổng 12 tháng hiện cả hai số.
2. Phí thành viên (`MembershipReceipt`) có tính vào chi tiêu không? Mặc định: không (đó là phí nền tảng, không phải tiền thuê nhà).
3. Hiển thị "hết hạn" là `endExclusive` (như `ContractDetailView.tsx:488`) hay ngày thuê cuối (`endExclusive − 1`, theo chú thích `ContractLeaseCalc.java:12`)? Mặc định: giữ `endExclusive` cho đồng nhất, thêm tooltip "ngày thuê cuối cùng là hôm trước".
4. Ngưỡng báo giữ chỗ "sắp hết hạn" (hold mặc định 60 phút)? Mặc định: hiện mọi giữ chỗ còn hiệu lực, đỏ khi còn ≤15 phút.
5. Có cần nhắc ngoài trang (thông báo/email) cho việc URGENT của dashboard không? Mặc định: không trong P2; dùng hệ thống thông báo hiện có (đã có `PaymentDueReminderScheduler`, `AgreementSigningReminderScheduler`), dashboard chỉ đọc.
