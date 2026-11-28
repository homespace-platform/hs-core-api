# P2 — Dashboard người thuê (`/dashboard/tenant`)

Trạng thái: ĐÃ DUYỆT theo AUDIT_LOG ngày 28/11/2026; triển khai API phase 1. Mục 10 là hợp đồng API thực tế, hiệu chỉnh các giả định bên dưới theo schema. Web/mobile ở đợt sau. Mọi `file:line` dưới đây là tham chiếu thiết kế ngày 28/11/2026; "(giả định)" không thay thế schema thực tế.

## 0. Thay đổi sau kiểm kê (28/11/2026, đã đối chiếu 5 file `inventory/`)
- Bố cục gom thành nhóm, gấp nhất trước: **Thiết lập → Việc cần làm/hạn chót → Tiền → Hợp đồng → Khách & lịch hẹn → Khiếu nại** (mục 2); mỗi nhóm có huy hiệu "n việc · m gấp".
- THÊM: onboarding, kênh nhắc, huy hiệu thông báo/chat (W0b); 3 loại việc mới: đổi ngày vào ở, ký SmartCA chờ xác nhận, khiếu nại (W4); Tiền: khoản đã báo chờ chủ nhà xác nhận kèm giờ tự xác nhận, khoản bị từ chối kèm lý do, hoàn tiền sắp nhận (W3b), phiếu thu chưa có PDF (W7); tiền cọc đang giữ + tiến độ hoàn cọc (W10); đang chờ chủ nhà phản hồi (W11); khiếu nại của tôi (W12); trạng thái tin đã lưu (W9).
- KHÔNG đưa vào 4 tín hiệu (mục 9).
- `GET /dashboard/tenant/summary` thêm khóa `setup`, `deposits`, `waiting`, `complaints`, mở rộng `payables`/`spending` và 5 truy vấn (mục 4); thêm test (mục 7), câu hỏi 6-10 (mục 8).

## 1. Mục đích & người xem
Người xem: tài khoản đang ở vai "Người đi thuê" (menu `TENANT_NAV`, `components/dashboard/dashboard-nav.ts:78-109`). Trong 10 giây phải trả lời: (a) **mình có phải làm/trả gì gấp không** (quá hạn, hạn chót ký/xác nhận/phản hồi), (b) **sắp đến hạn gì** (tiền, lịch xem, giữ chỗ, hết hợp đồng), (c) **đang thuê ở đâu**. Người thuê có thể có N hợp đồng (nhiều phòng): mọi khối gom theo hợp đồng, không giả định 1 phòng.

## 2. Bố cục: nhóm gấp lên trước
```
[Banner thiết lập: KYC / hồ sơ-ngân hàng / kênh nhắc đang tắt, chỉ hiện khi thiếu]   Thông báo 3 · Tin nhắn 1
+--------------+--------------+--------------+--------------+
| Đang thuê    | Phải trả     | Việc cần làm | Lịch xem     |   (4 ô KPI, bấm được)
| 2 phòng      | 3,5tr (1 QH) | 4 (1 gấp)    | 2 sắp tới    |
+--------------+--------------+--------------+--------------+
| 1 VIỆC CẦN LÀM (theo hạn, gấp trước) W4       | GIỮ CHỖ SẮP HẾT HẠN W5 |
|  [Gấp] Ký phụ lục HĐ-0012 · còn 5 giờ  [Ký]  |  Phòng A · còn 12:30   |
+-----------------------------------------------+-----------------------+
| 2 TIỀN: KHOẢN PHẢI TRẢ W3 (+ chờ chủ nhà xác  | TIỀN CỌC & HOÀN CỌC W10|
|  nhận, bị từ chối, hoàn tiền sắp nhận W3b)    |                       |
|  Quá hạn: 1 khoản 3,2tr [Thanh toán]          | CHI TIÊU THEO THÁNG W7 |
+-----------------------------------------------+-----------------------+
| 3 HỢP ĐỒNG: NƠI ĐANG THUÊ W2 (≤6 thẻ)         | ĐANG CHỜ CHỦ NHÀ W11   |
| [ảnh] Tên tin · Chủ nhà · 01/01/26 → 01/07/26 |                       |
+-----------------------------------------------+-----------------------+
| 4 KHÁCH & LỊCH HẸN: LỊCH XEM W6 | YÊU CẦU THUÊ ĐÃ GỬI W8 | TIN ĐÃ LƯU W9   |
| 5 KHIẾU NẠI CỦA TÔI W12 (chỉ hiện khi có)                              |
+-----------------------------------------------------------------------+
```
Màn nhỏ (<768px): một cột theo thứ tự Banner (luôn trên cùng) → KPI (2x2) → Việc cần làm → Giữ chỗ → Khoản phải trả → Tiền cọc → Nơi đang thuê (vuốt ngang) → Đang chờ chủ nhà → Lịch xem → Yêu cầu → Khiếu nại → Chi tiêu → Tin đã lưu. Tiêu đề nhóm bấm thu gọn, kèm huy hiệu "n việc · m gấp"; nhóm không có gì cần xử lý tự thu gọn thành một dòng xanh. Style: `bg-card rounded-2xl border border-border`, như file hiện tại.

## 3. Từng khối
Quy ước chung: giờ hiển thị `Asia/Ho_Chi_Minh`; **mọi đếm ngược dùng `serverNow`/`realNow` do API trả**, không dùng đồng hồ trình duyệt. Hạn tiền/ký/phản hồi dùng đồng hồ **BillingTime** (`BillingTime.java:61-62`; chọn theo `PaymentTimePolicy.usesBillingTime`, `PaymentTimePolicy.java:6-11`); lịch xem dùng giờ thật (`ViewingAppointmentService.java:94-95`, `LocalDate.now(VIETNAM_ZONE)`). Mỗi mục trả `clock: BILLING|REAL` như `BranchAttentionResponse.Work` (`BranchAttentionResponse.java:24-26`). Mức gấp như `BranchAttentionService.priority` (`:134-139`): URGENT ≤24h hoặc quá hạn, SOON ≤3 ngày hoặc loại luôn-sớm, còn lại NORMAL.

**W0. Banner KYC** — Nguồn (có sẵn): `profile.kycVerified` đã nạp trong `useAuth` (`types/user.type.ts:18`, `lib/kyc-gate.ts:14-16 isKycSatisfied`), khi chưa xác minh gọi `GET /users/me/kyc` (`KycController.java:25-31`) lấy `status` (`KycStatus`: NOT_VERIFIED/PENDING/REVIEW_REQUIRED/VERIFIED/REJECTED/EXPIRED) và `rejectionReason`. Nội dung: NOT_VERIFIED/EXPIRED "Xác minh danh tính để gửi yêu cầu thuê, đặt lịch xem" (backend chặn qua `kycGateService.requireVerifiedIdentity`, `RentalRequestController.java:77`, `ViewingAppointmentController.java:63`); PENDING/REVIEW_REQUIRED "Đang chờ duyệt" (không nút); REJECTED hiện lý do + "Thử lại". Bấm → `kycHref()`. Ẩn khi VERIFIED. Lỗi tải: không hiện banner (không chặn trang).

**W0b. Thiết lập và huy hiệu (bổ sung)** — (a) Onboarding: `GET /users/me/onboarding-status` (`UserController.java:39`) chưa xong → "Hoàn tất hồ sơ và tài khoản ngân hàng để thanh toán/nhận hoàn tiền"; (b) Kênh nhắc: `GET /notifications/preferences` (`notification.controller.ts:75`), vàng khi email PAYMENT/CONTRACT hoặc push tắt ("có thể bỏ lỡ hạn ký/thanh toán"), chỉ đọc cờ; (c) huy hiệu tiêu đề: `GET /notifications/unread-count` (`notification.controller.ts:55`) và số tin nhắn chưa đọc từ `GET /chat/conversations` (`chat.controller.ts:34`, **chưa kiểm chứng** có trả số chưa đọc; không có thì ẩn, câu hỏi 9). Mỗi nguồn gọi riêng, lỗi thì bỏ dòng đó; trống thì không hiện.

**W1. KPI (4 ô)** — lấy từ response tổng hợp (mục 4), không gọi thêm. `leases.activeCount`; `payables.overdueAmount + dueSoonAmount` (dueSoon = đến hạn trong 7 ngày); `tasks.total` (+ số URGENT); `visits.upcomingCount`. Bấm ô cuộn tới khối tương ứng.

**W2. Nơi đang thuê (N hợp đồng)** — Định nghĩa: hợp đồng gốc `tenantId = user`, `active`, `parentContractId is null`, không phải hồ sơ cọc (`agreementKind` ≠ DEPOSIT), `status = ACTIVE` (`ContractStatus.java:10`; cùng bộ lọc `ContractServiceImpl.java:400-406`). Trường mỗi thẻ: `contractId, contractNumber, listingId, listingTitle, listingAddress, listingThumbnail, landlordName` (chỉ tên hiển thị, không SĐT/email/CCCD), `startsOn` (= `rental.moveInDate`), `endExclusive`, `daysRemaining`, `monthlyRent` (= `rental.effectiveMonthlyRent`, `RentalRequestResponse.java:29`), `expiry` (WITHIN_30/WITHIN_60/LONG như `BranchMonitoringService.expiry`, dùng ở `BranchAttentionService.java:808-812`). **Ngày hết hạn tính đúng một công thức**: `ContractLeaseCalc.end(agreedEarlyEndExclusive, agreedEarlyBillingMonths, effectiveLeaseMonths, rental.leaseMonths, rental.moveInDate)` (`ContractLeaseCalc.java:33-38`) — ưu tiên ngày kết thúc sớm đã thoả thuận, sau đó tháng đang hiệu lực, sau cùng tháng yêu cầu ban đầu; **ngày kết thúc là exclusive** (`:12`). Web hiển thị cùng giá trị như `ContractDetailView.tsx:488` (`startsOn – endExclusive`); không tự cộng tháng ở frontend. Bấm thẻ → `/dashboard/tenant/contracts/{id}` (`lib/contract-routes.ts:3-9`). Trống: "Bạn chưa thuê phòng nào" + nút "Tìm phòng" (`/rent`, giả định route). Hiển thị ≤6 thẻ, sắp theo `endExclusive` tăng; thừa → link `/dashboard/tenant/contracts`. HĐ chưa hiệu lực (đang ký, chờ cọc) không ở đây mà ở W4 (việc cần làm).

**W3. Khoản phải trả** — Nguồn hiện có KHÔNG đủ: `GET /payment-requests/mine` (`PaymentRequestController.java:47-53`) trả toàn bộ lịch sử không phân trang, kèm snapshot tài khoản ngân hàng (`PaymentRequestResponse.java:28-29`); `GET /monthly-invoices/contracts/{id}` (`MonthlyInvoiceController.java:55-59`) gọi `findByIdForUpdate`+`syncContract` (`MonthlyBillingService.java:431-438`), tức một lần GET có khoá ghi cho mỗi hợp đồng → không dùng cho N hợp đồng. Dùng endpoint mới (mục 4). Định nghĩa khoản phải trả: `payment_requests` với `payer_id = user`, `direction = TENANT_TO_LANDLORD`, `active`, `total_amount > 0`, `status ∈ {AWAITING_TRANSFER, REJECTED, OVERDUE}` (bộ `NOT_REPORTED`, `BranchAttentionService.java:68-70`), `opensAt` null hoặc ≤ now. Loại: INITIAL, RESERVATION_DEPOSIT, MONTHLY_RENT, UTILITY, OTHER_CHARGE, DEPOSIT_ADJUSTMENT (`PaymentType.java`); không gồm `*_REFUND`. Quá hạn khi `dueAt < now(clock)` (suy ra, không tin cột `OVERDUE` vì nó cập nhật bằng job; cùng logic `BranchOverviewService.invoiceStatus`, `:501-518`). `TRANSFER_REPORTED` (đã báo chuyển, chờ chủ nhà xác nhận) hiện ở dòng riêng "Chờ xác nhận: n khoản", không tính vào số phải trả. Tổng quá hạn = Σ `totalAmount` quá hạn (đã gồm phí trễ nếu có, `MonthlyInvoice.lateFeeAmount`; giả định `totalAmount` là số phải chuyển). Kế tiếp = khoản chưa quá hạn có `dueAt` nhỏ nhất, theo từng hợp đồng + toàn cục. Mỗi dòng: `paymentId, type, contractId, contractNumber, listingTitle, invoiceId, periodIndex, amount, dueAt, clock, overdue, rejectedReason, href`. Link: hoá đơn tháng → `/dashboard/tenant/contracts/{contractId}?tab=payment&section=monthly&invoice={invoiceId}` (mẫu `BranchOverviewService.invoiceTarget`, `:579-582`, đổi sang đường dẫn tenant); loại khác → `/dashboard/payments?paymentId={id}` (`BranchOverviewService.java:584-586`; trang đọc `paymentId`, `payments/page.tsx:134`). Mọi lần thanh toán (QR, báo chuyển khoản) làm ở trang đích, dashboard không chứa số tài khoản. Trống: "Không có khoản nào cần thanh toán". Lưu ý quy tắc hạn: bên trễ bị hệ thống tự xử lý (memory deadline-default-resolution) → dòng quá hạn phải hiện hạn xử lý, không hứa gia hạn.

**W3b. Dòng phụ của khoản tiền (bổ sung)** — cùng nguồn `payment_requests` của W3, trả trong `payables`:
- *Đã báo chuyển, chờ chủ nhà xác nhận (T3)*: `payer_id=user`, `TENANT_TO_LANDLORD`, `TRANSFER_REPORTED`: `awaiting{count, amount, items[≤5: paymentId, contractNumber, amount, payerReportedAt, confirmationDueAt]}`. Hiện "Chủ nhà chưa xác nhận; hệ thống tự xác nhận lúc {confirmationDueAt}" (giờ do API trả, theo cấu hình `rental.receipt-auto-confirm-*`, không hardcode ở web). Vẫn không tính vào "Phải trả". Click `/dashboard/payments?paymentId=`.
- *Bị chủ nhà từ chối, cần khai lại (T2)*: REJECTED đã nằm trong W3; hiển thị thêm nhãn "Bị từ chối", `rejectedReason` (chủ nhà nhập, cắt 160 ký tự, chỉ người trả xem), hạn còn lại, nút "Khai lại" → trang thanh toán.
- *Hoàn tiền sắp nhận (T4)*: `payee_id=user`, `LANDLORD_TO_TENANT` (DEPOSIT_REFUND, INITIAL_PAYMENT_REFUND), `AWAITING_TRANSFER` (chủ nhà chưa chuyển) hoặc `TRANSFER_REPORTED` (cần tôi xác nhận, tự xác nhận sau `rental.refund-auto-confirm-hours`=72h, cảnh báo 24h): `refundsIncoming{count, amount, items[≤5: paymentId, contractNumber, type, status, amount, dueAt, autoConfirmAt]}`. Việc xác nhận vẫn ở W4 (CONFIRM_REFUND); đây là số tiền + trạng thái.

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
| START_CHANGE_RESPONSE (T5) | đề nghị đổi ngày vào ở do chủ nhà gửi, `rental_start_changes` đang chờ tenant (đã có trong `View.startChanges` của lần gọi `RentalOnboardingService.get`, không thêm truy vấn) | `rental.start-change-response-hours`=24h | `/dashboard/rental-requests/my-requests?requestId=` |
| SIGN_CONFIRM_APP (T8) | `signature_requests` của tôi `PENDING_USER_CONFIRMATION` còn trước `expires_at`; hoặc FAILED/EXPIRED/REJECTED khi HĐ còn chờ ký ("Ký lại") | `expires_at` | `?tab=sign` |
| COMPLAINT_RESPOND (T5) | `dispute_cases` chưa RESOLVED, `response_due_for = user` (W12) | `respond_by` | khiếu nại (W12) |
Khoản tiền quá hạn/đến hạn KHÔNG lặp ở đây (đã ở W3); PAY_* chỉ vào danh sách khi nằm trước hạn mở thanh toán/đang chờ bước trước. Cách lấy NextAction: gọi `RentalOnboardingService.get(id, userId)` (read-only, `:197-201`) cho mỗi yêu cầu `ACCEPTED` của user, **giới hạn 10**; vì `view` dựng nhiều dữ liệu, về sau thêm bản "twin" `tenantSigningStep` như `landlordSigningStep` (`:144-160`) nếu cần giảm truy vấn. Trống: "Không có việc nào cần làm". Lỗi: cả trang báo lỗi + "Thử lại".

**W5. Giữ chỗ sắp hết hạn** — Định nghĩa: `rental_requests` của user, `status = ACCEPTED`, `holdExpiresAt != null`, `holdExpiresAt > now`, và tiền chưa xác nhận (NextAction PAY_DEPOSIT/PAY_FIRST_RENT). `holdExpiresAt` đặt khi chấp nhận (`RentalRequestService.java:321-324`), thời lượng mặc định 60 phút (`RentalRequestController.java:40-41`, `GET /rental-requests/policy`), gia hạn khi tenant thanh toán/proof bị từ chối (`RentalOnboardingService.java:541`), xoá khi huỷ/ký xong. Hết hạn thì job chuyển `EXPIRED`/huỷ (`RentalRequestService.java:575-580`). Đồng hồ: `businessTime` (giả định = BillingTime). Hiện tất cả giữ chỗ còn hiệu lực, đỏ khi còn ≤15 phút; ô đếm ngược theo `serverNow`. Trường: `rentalRequestId, listingTitle, thumbnail, holdExpiresAt, amountDue, payHref`. Bấm → `/dashboard/rental-requests/my-requests?requestId={id}` (`useRentalRequestDeepLink.ts:8`). Trống: ẩn khối.

**W6. Lịch xem sắp tới** — Nguồn mới (mục 4) vì `GET /appointments/my-bookings` chỉ lọc `status` hoặc đúng 1 `date` và sắp **giảm dần** (`ViewingAppointmentController.java:70-78`, `ViewingAppointmentService.java:548-573`), không có "từ hôm nay". Định nghĩa: `viewing_appointments` `renter_id = user`, `active`, `status ∈ {PENDING, CONFIRMED}` (`AppointmentStatus.java`), `appointmentDate > today` hoặc (`= today` và `startTime ≥ now`) giờ VN thật; sắp tăng; 5 mục + tổng. Trường: `id, listingId, listingTitle, listingAddress, thumbnail, appointmentDate, startTime, endTime, status, rescheduleRequested, proposedDate/StartTime` (không SĐT/email người thuê, `AppointmentResponse.java:21-23` có nên bỏ). PENDING gắn nhãn "Chờ chủ nhà xác nhận". Bấm → `/dashboard/viewing-schedules/my-bookings` (không có deep-link theo id: giả định, kiểm lại khi code). Trống: "Chưa có lịch xem" + "Tìm phòng".

**W7. Chi tiêu theo tháng** — Định nghĩa: tổng `payment_requests.total_amount` của các khoản **đã xác nhận có biên nhận**: `payment_receipts r` JOIN `payment_requests p` ON `p.id = r.payment_request_id`, `p.payer_id = user`, `p.direction = TENANT_TO_LANDLORD`. Biên nhận chỉ sinh khi `CONFIRMED` và tiền > 0 (`PaymentReceiptService.capture`, `:44-49`), `issuedAt = confirmedAt`. Loại hoàn tiền chủ nhà→tenant (payer là chủ nhà, `:60-63`). Tháng = `issuedAt` quy về `Asia/Ho_Chi_Minh`, cửa sổ 12 tháng kết thúc ở tháng hiện tại theo **BillingTime**, lấp 0 cho tháng trống; mặc định hiện 6 tháng, nút chuyển 12. 3 nhóm cột chồng: "Cọc & tháng đầu" (INITIAL, RESERVATION_DEPOSIT, DEPOSIT_ADJUSTMENT), "Tiền thuê hàng tháng" (MONTHLY_RENT), "Điện nước & phí khác" (UTILITY, OTHER_CHARGE). Trường: `months[{month:"2026-11", total, byGroup{...}}], currency:"VND", total12m`. Bấm cột → `/dashboard/invoices/receipts` (`app/dashboard/invoices/receipts`, có sẵn; giả định hỗ trợ lọc theo tháng, không thì mở không lọc). Trống (không có biên nhận 12 tháng): "Chưa có khoản thanh toán nào được xác nhận".
- Bổ sung (T8): dòng dưới biểu đồ "n biên nhận chưa tải được PDF" = `payment_receipts` `tenant_id=user`, `pdf_status=FAILED` hoặc `PENDING` quá 5 phút, 90 ngày gần nhất (`spending.receiptPdfIssues`); bấm → `/dashboard/invoices/receipts` (có nút thử lại `POST /payment-receipts/{id}/retry`). Ẩn khi 0.

**W8. Yêu cầu thuê đã gửi theo trạng thái** — Đếm theo `RentalRequestStatus` (`RentalRequestStatus.java`) của `renter_id = user`, `active`: nhóm "Chờ phản hồi" (PENDING, kèm `respondBy`), "Đã chấp nhận" (ACCEPTED), "Bị từ chối" (REJECTED), "Đã huỷ" (CANCELLED_BY_RENTER/OWNER/SYSTEM), "Hết hạn" (EXPIRED), "Hoàn tất" (COMPLETED). Hiện hiện chỉ có danh sách phân trang theo 1 status (`RentalRequestController.java:80-88`) → đếm cần group-by mới (index `idx_rental_request_renter_status`, `RentalRequest.java:14`). Kèm 3 yêu cầu gần nhất còn mở (PENDING/ACCEPTED): `id, listingTitle, thumbnail, status, createdAt, respondBy, holdExpiresAt`. Bấm chip → `/dashboard/rental-requests/my-requests` (chưa có lọc status trong URL: giả định, chip chỉ điều hướng); bấm mục → `?requestId=`. Trống: "Bạn chưa gửi yêu cầu thuê nào".

**W9. Tin đã lưu** — Có API: `GET /favorites?page=1&size=4` trả `PageResponse<PublicListingSummaryResponse>` có `totalElements` (`FavoriteController.java:58-64`, `PageResponse.java:11-13`), client `services/favorite.service.ts`; gọi riêng, độc lập lỗi với response tổng hợp. Hiện 4 thẻ + "Xem tất cả (n)" → `/favorites` (`app/favorites/page.tsx`). Trống: "Chưa lưu tin nào". Lỗi: ẩn khối kèm "Không tải được". Bổ sung (T6): thẻ hiện nhãn trạng thái của tin (đã cho thuê/đang giữ chỗ/hết hạn/bị ẩn) lấy từ `PublicListingSummaryResponse` (trường kiểm khi code) để người thuê biết tin đã lưu không còn thuê được; **không** cảnh báo đổi giá (chưa có lịch sử giá, mục 9).

**W10. Tiền cọc đang giữ và tiến độ hoàn cọc (T6, T7)** — Nguồn: `deposit_records` (`DepositStatus`: HELD / REFUND_DUE / REFUNDED; cột tiền và khóa HĐ kiểm khi code, giả định `amount`, `contract_id`) JOIN `contracts.tenant_id=user`, gom theo hợp đồng; `contract_closures` của HĐ đó (`WAITING_HANDOVER`, `PROPOSED`, `REFUND_PENDING`, `BALANCE_PENDING`, `DISPUTED`; `refund_due_at`, `refund_amount`). Hiện tổng đang giữ và mỗi HĐ một dòng: "Cọc 7tr · đang giữ" / "Chủ nhà phải hoàn 6,5tr trước {refund_due_at}" / "Đang tranh chấp quyết toán". Tiền hoàn thật đi qua W3b "Hoàn tiền sắp nhận". Click `/dashboard/tenant/contracts/{id}?tab=end`. Trống: "Chưa có tiền cọc". Không hiện số tài khoản.

**W11. Đang chờ chủ nhà phản hồi (T6)** — Việc tôi đã gửi và đang chờ bên kia, ≤10, mỗi mục `{type, contractId, contractNumber, sentAt, respondBy, clock, href}`: RENEWAL_REQUESTED (`contract_renewals` `REQUESTED`, người xin = user, `respond_by`, `ContractRenewal.java:18`), AMENDMENT_WAITING (`ContractAmendment`, `proposerId=user`, đối xứng điều kiện chờ chủ nhà, `respondBy`), EARLY_EXIT_PROPOSED (`RentalWorkflowRecord.proposedBy=user`, `respondBy`), CLOSURE_DISPUTED (closure `DISPUTED` do tôi phản đối). Yêu cầu thuê đang PENDING đã ở W8, không lặp. Hiện đếm ngược `respondBy`; nếu chủ nhà trễ thì hệ thống xử lý theo quy tắc mặc định của loại việc đó (nội dung từng loại do chủ dự án chốt, câu 8), nên dashboard chỉ ghi hạn, không hứa kết quả. Click → tab liên quan của hợp đồng. Trống: ẩn khối.

**W12. Khiếu nại của tôi (T5)** — Nguồn mới (chưa có `GET /complaints/mine`; `ComplaintController` chỉ liệt kê theo từng yêu cầu thuê, `:18`): `dispute_cases` mà tôi là một bên (`payer_id`/`payee_id`, tên cột kiểm khi code), `status != RESOLVED`: `openCount`, `dueFromMeCount` (`response_due_for = user`), ≤5 dòng `{id, contractNumber, category, status, respondBy, responseDueFor: YOU|OTHER, href}`; **không trả `reason`/`decision`/id bên kia** (văn bản tự do, có thể chứa PII). Vụ tôi phải trả lời vào W4 (COMPLAINT_RESPOND). Click → khiếu nại của yêu cầu thuê/HĐ (route kiểm khi code). Trống: ẩn khối. Index mới `dispute_cases (payer_id,status)`, `(payee_id,status)` (hiện chỉ `idx_dispute_queue(status,opened_at)`).

## 4. API mới tổng hợp
Một endpoint cho W1-W8, W10, W11, W12: **`GET /dashboard/tenant/summary`** (thêm `/api/v1/dashboard/tenant/summary` theo kiểu `ContractController.java:42`). Controller `TenantDashboardController` (hs-api-service/.../controller/contract) → `TenantDashboardService` (hs-contract-service; module này phụ thuộc listing/payment/user nên đọc được mọi bảng, `AGENTS.md` mục Maven Modules). Auth: chỉ cần đã đăng nhập; `userId = CurrentUser.requireUserId()` (mẫu `BranchAttentionController.java:22-27`), **không nhận userId/role trong query**, tham số lạ → 400 (dùng `MultiValueMap` như controller trên). Không cần permission mới (mọi API tenant hiện tại như `/rental-requests/my-requests` cũng không có `@PreAuthorize`). Query: `spendMonths` (6|12, mặc định 12, ngoài khoảng → 400). `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`, **không** sync, không khoá ghi (như `BranchAttentionService.java:93-94`). Không cache (repo chưa có `@Cacheable`/`@EnableCaching`; đủ nhanh với truy vấn có index). Frontend: nạp khi vào trang, nút "Làm mới", tự nạp lại khi tab hiện lại và mỗi 60 giây.
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
Khóa thêm sau kiểm kê (luôn có mặt, rỗng khi không có; mọi `href` như các khóa trên):
```json
{ "setup":{"onboarded":true},
  "payables":{ "...":"như trên", "awaiting":{"count":1,"amount":3500000,"items":[{"paymentId":"","contractNumber":"","amount":0,"payerReportedAt":"…","confirmationDueAt":"…","href":""}]},
     "refundsIncoming":{"count":1,"amount":7000000,"items":[{"paymentId":"","contractNumber":"","type":"DEPOSIT_REFUND","status":"AWAITING_TRANSFER","amount":0,"dueAt":"…","autoConfirmAt":null,"href":""}]} },
  "deposits":{"heldTotal":7000000,"items":[{"contractId":"","contractNumber":"","amount":7000000,"status":"HELD","closure":{"status":"REFUND_PENDING","refundAmount":6500000,"refundDueAt":"…"},"href":""}]},
  "waiting":{"count":1,"items":[{"type":"RENEWAL_REQUESTED","contractId":"","contractNumber":"","sentAt":"…","respondBy":"…","clock":"BILLING","href":""}]},
  "complaints":{"openCount":1,"dueFromMeCount":1,"items":[{"id":"","contractNumber":"","category":"","status":"","respondBy":"…","responseDueFor":"YOU","href":""}]},
  "spending":{ "...":"như trên", "receiptPdfIssues":0 } }
```
(Mỗi phần tử `payables.items[]` có thêm `rejectedReason` cắt 160 ký tự; `tasks.items[]` có thêm các `type` mới của W4.)
Truy vấn (tất cả theo lô, không N+1; mẫu `BranchAttentionService.load`, `:160-250` và `BranchAttentionReadRepository`; thêm `TenantDashboardReadRepository`):
1. `contracts` WHERE `tenant_id=:u AND active AND parent_contract_id IS NULL` (idx `idx_contract_tenant_status`, `Contract.java:19`) → lease ids (cap 50); `rental_requests` theo `rentalRequestId IN`; tên chủ nhà theo `landlordId IN` (chỉ cột tên).
2. `monthly_invoices` WHERE `contract_id IN :ids AND status IN (UNPAID,OVERDUE)` (idx `idx_monthly_invoice_contract`, `MonthlyInvoice.java:17`).
3. `payment_requests` WHERE `payer_id=:u AND status IN (AWAITING_TRANSFER,REJECTED,OVERDUE,TRANSFER_REPORTED) AND active` (idx `idx_payment_request_payer_status`, `PaymentRequest.java:21`), thêm nhánh payee=user cho CONFIRM_REFUND (idx payee_status).
4. Phụ lục/đề nghị: `findAmendments(ids, statuses)`, `contract_renewals`, early-exit, `contract_closures`, hợp đồng con theo `parent_contract_id IN`.
5. Lịch xem: `renter_id=:u AND status IN (...)` + điều kiện ngày/giờ (idx `idx_appointment_renter_status`, `ViewingAppointment.java:18`), `ORDER BY appointment_date, start_time LIMIT 5`, count riêng.
6. Yêu cầu: `SELECT status, count(*) FROM rental_requests WHERE renter_id=:u AND active GROUP BY status` + 3 bản ghi mở gần nhất.
7. Chi tiêu: `SELECT to_char(r.issued_at AT TIME ZONE 'Asia/Ho_Chi_Minh','YYYY-MM') m, p.type, sum(p.total_amount) FROM payment_receipts r JOIN payment_requests p ON p.id=r.payment_request_id WHERE p.payer_id=:u AND p.direction='TENANT_TO_LANDLORD' AND r.issued_at >= :from AND r.issued_at < :to GROUP BY m, p.type`; **index mới cần thêm** `payment_receipts(tenant_id, issued_at)` (hiện chỉ có unique `payment_request_id`, `receipt_number` và `idx_receipt_pdf_queue`, `PaymentReceipt.java:12-15`) và lọc thêm `r.tenant_id=:u`. Migration theo mẫu các lớp `*SchemaMigration` (`AGENTS.md`).
8. Tiền cọc/hoàn cọc: `deposit_records` JOIN `contracts` (`tenant_id=:u`, cap 50 HĐ) và `contract_closures WHERE contract_id IN :ids AND status IN (...)`; thêm index `contract_closures (contract_id)` và `(status, refund_due_at)`.
9. Hoàn tiền sắp nhận: `payment_requests WHERE payee_id=:u AND direction='LANDLORD_TO_TENANT' AND status IN (AWAITING_TRANSFER,TRANSFER_REPORTED) AND active` (idx `idx_payment_request_payee_status`) + khoản `TRANSFER_REPORTED` của payer=user (gộp vào truy vấn 3).
10. Khiếu nại: `dispute_cases WHERE (payer_id=:u OR payee_id=:u) AND status<>'RESOLVED' ORDER BY respond_by LIMIT 5` + count (tách thành hai nhánh UNION để dùng 2 index mới).
11. Ký SmartCA: `signature_requests JOIN contracts` (`tenant_id=:u`, HĐ đang chờ ký) `status IN (PENDING_USER_CONFIRMATION,FAILED,EXPIRED,REJECTED)`; index `signature_requests (contract_id,status)`. Chỉ đọc cột, không gọi VNPT (`/signatures/certificates`, `refresh`).
12. Phiếu thu lỗi: `payment_receipts WHERE tenant_id=:u AND pdf_status IN (FAILED,PENDING)` trong 90 ngày (dùng index mới `(tenant_id, issued_at)`); `users.on_boarded` của chính user (một dòng).
Hợp đồng/hạn dùng đúng `ContractLeaseCalc`; hạn tiền dùng `PaymentTimePolicy.usesBillingTime`.

## 5. Biểu đồ
Quyết định D4 đã duyệt thay thế đề xuất SVG/CSS ban đầu: dùng thư viện biểu đồ ở đợt frontend. API trả 6 hoặc 12 tháng và 3 nhóm tiền; chưa cài thư viện hay làm frontend trong phase 1.

## 6. Hiệu năng & bảo mật
- Chi phí: ~12 truy vấn có index, mọi tập theo `userId` nên độc lập với 10k tin đăng (chỉ W9 đụng `listing_favorites`, idx `idx_favorite_user`). Với 100k thanh toán: W3 theo `idx_payment_request_payer_status` (chỉ hàng chưa hoàn tất), W7 giới hạn cửa sổ 12 tháng nhờ index mới trên biên nhận; thiếu index này W7 quét toàn bảng biên nhận. N+1: chỉ `RentalOnboardingService.get` cho ≤10 yêu cầu ACCEPTED (chấp nhận, ghi chú tối ưu ở W4). Giới hạn cứng: 50 hợp đồng, 20 việc, 5 lịch, 3 yêu cầu mở, 10 mục chờ chủ nhà, 5 khiếu nại, 5 hoàn tiền, 5 khoản chờ xác nhận. Sau kiểm kê thêm 5 truy vấn có chỉ mục; `RentalOnboardingService.get` ≤10 vẫn là N+1 duy nhất (cũng cấp `startChanges`).
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
- **Nhóm sau kiểm kê**: (1) `awaiting` đúng số/tiền/`confirmationDueAt` và biến mất khi CONFIRMED; REJECTED có `rejectedReason` cắt 160 ký tự và chỉ người trả thấy; `refundsIncoming` đúng hướng `LANDLORD_TO_TENANT`, không lẫn vào "phải trả". (2) `tasks`: START_CHANGE_RESPONSE, SIGN_CONFIRM_APP, COMPLAINT_RESPOND có/biến mất theo điều kiện. (3) `deposits`: HELD/REFUND_DUE/REFUNDED, closure đi kèm, cọc của HĐ người khác không lẫn. (4) `waiting`: 4 loại, loại bỏ khi bên kia đã phản hồi; không lặp yêu cầu PENDING của W8. (5) `complaints` chỉ của chính user, không có `reason/decision`. (6) `receiptPdfIssues` (FAILED, PENDING >5 phút). (7) lộ dữ liệu: JSON không có `reason`, `decision`, `BankAccountSnapshot`, `phone`, `email`. (8) cô lập user B; số câu SQL không đổi khi tăng 3→30 hợp đồng (trừ onboarding ≤10).
Frontend (`hs-web-app/lib/*.test.ts` — repo chỉ có test lib, ví dụ `appointment-calendar.test.ts`; giả định chưa có test component): `lib/tenant-dashboard.test.ts` cho hàm thuần: lấp tháng trống, định dạng VND rút gọn, nhóm yêu cầu theo trạng thái, đếm ngược theo `serverNow` lệch giờ máy, chọn "kế tiếp", ánh xạ priority → màu/nhãn, ẩn banner KYC khi VERIFIED. Trường hợp denied: 401 → chuyển đăng nhập, 403/400 → thông báo lỗi chung; mất mạng → trạng thái lỗi + nút thử lại, banner KYC và W9 vẫn độc lập.

## 8. Câu hỏi mở cho chủ dự án
1. Chi tiêu có tính tiền cọc (hoàn lại được) vào tổng không? Mặc định: có, nhưng tách nhóm "Cọc & tháng đầu" màu nhạt kèm chú thích; tổng 12 tháng hiện cả hai số.
2. Phí thành viên (`MembershipReceipt`) có tính vào chi tiêu không? Mặc định: không (đó là phí nền tảng, không phải tiền thuê nhà).
3. Hiển thị "hết hạn" là `endExclusive` (như `ContractDetailView.tsx:488`) hay ngày thuê cuối (`endExclusive − 1`, theo chú thích `ContractLeaseCalc.java:12`)? Mặc định: giữ `endExclusive` cho đồng nhất, thêm tooltip "ngày thuê cuối cùng là hôm trước".
4. Ngưỡng báo giữ chỗ "sắp hết hạn" (hold mặc định 60 phút)? Mặc định: hiện mọi giữ chỗ còn hiệu lực, đỏ khi còn ≤15 phút.
5. Có cần nhắc ngoài trang (thông báo/email) cho việc URGENT của dashboard không? Mặc định: không trong P2; dùng hệ thống thông báo hiện có (đã có `PaymentDueReminderScheduler`, `AgreementSigningReminderScheduler`), dashboard chỉ đọc.

6. W10: cột/ý nghĩa thật của `deposit_records` (HĐ cũ có đủ bản ghi không?). Đề xuất: kiểm khi code; thiếu thì suy từ `payment_requests` loại cọc đã CONFIRMED.
7. Hiện `rejectedReason` (W3b) cho người thuê: đồng ý? (hiện chỉ người trả xem được).
8. W11: khi chủ nhà trễ hạn, kết quả mặc định của từng loại (gia hạn tự hết, phụ lục, chấm dứt sớm, tất toán) là gì? Quy tắc "bên trễ thua" cần chủ dự án chốt từng loại; hiện chỉ hiện hạn.
9. Chat chưa đọc (W0b): nếu API không trả số chưa đọc, bỏ huy hiệu ở v1? Đề xuất: bỏ.
10. Cảnh báo "kênh nhắc đang tắt" (W0b): hiện luôn hay chỉ khi có việc gấp? Đề xuất: luôn hiện dòng nhỏ, ẩn được 7 ngày. W12 chỉ trả danh mục + hạn, không trả lý do khiếu nại: đồng ý?

## 9. Không đưa vào (lý do)
| Tín hiệu (mã kiểm kê) | Lý do |
|---|---|
| Phiên upload chứng từ từ điện thoại đang mở/hết hạn (T7 thanh toán) | Phiên sống ngắn, xử lý ngay tại trang thanh toán; giá trị thấp ở tổng quan; link token công khai không đưa vào thống kê |
| Cảnh báo tin đã lưu đổi giá/hạ tin (T6 tin) | Chưa có dữ liệu: ghi `listing_price_history` (giá, thời điểm đổi) và lịch sử `expires_at` trước; W9 chỉ hiện trạng thái hiện tại |
| Tin đã xem gần đây (T7 tin, `/history/count`) | Tiện ích, tự xóa sau 30 ngày; thuộc trang lịch sử xem |
| Quyền hiện có (`/users/me/permissions`) | Giá trị thấp, chỉ để giải thích tính năng bị ẩn |

## 10. Hợp đồng API phase 1 thực tế (Codex, 28/11/2026)

`GET /dashboard/tenant/summary` và alias `GET /api/v1/dashboard/tenant/summary` trả `ApiResponse<TenantDashboardResponse>` trong `result`, header `Cache-Control: private, no-store`. Chỉ đọc trong transaction `REPEATABLE_READ`; không gọi refresh/sync, provider, ngân hàng hoặc storage API. Danh tính chỉ từ `CurrentUser` sau `UserContextFilter` kiểm shared-secret của gateway và role USER/ADMIN hợp lệ. Không thêm permission mới cho việc đọc dữ liệu của chính mình (đúng mục 4); ADMIN cũng chỉ đọc phạm vi cá nhân của mình ở endpoint này.

Tham số duy nhất: `spendMonths=6|12`, mặc định `12`. Giá trị rỗng, lặp tham số, số ngoài hai giá trị này, dạng `06`, và mọi tham số lạ (kể cả `userId`, `role`) trả HTTP 400 / code 1001. Thiếu danh tính đã xác thực, header giả mạo hoặc role không hợp lệ trả HTTP 401 / code 1002; không gọi service.

DTO bên dưới ghi đầy đủ tên trường; `Instant` là ISO-8601 UTC, ngày `YYYY-MM-DD`, giờ local ISO, tiền là JSON number từ `BigDecimal` (VND). Mọi khối/list có mặt, list rỗng/tiền 0 khi không có dữ liệu; giá trị không tồn tại là null.

| DTO / khối | Trường |
|---|---|
| `TenantDashboardResponse` | `billingNow, realNow, timezone, setup, leases, payables, tasks, holds, visits, requests, spending, deposits, waiting, complaints, saved` |
| `setup` | `onboarded` (boolean từ `users.on_boarded`, thiếu user = false) |
| `leases` | `activeCount, items[]` |
| `Lease` | `contractId, contractNumber, listingId, listingTitle, listingAddress, listingThumbnail, landlordName, startsOn, endExclusive, daysRemaining, expiry, monthlyRent, nextPayment, href` |
| `payables` | `overdueCount, overdueAmount, dueSoonAmount, next, items[], awaiting, refundsIncoming` |
| `Payable` | `paymentId, type, contractId, contractNumber, listingTitle, invoiceId, periodIndex, amount, dueAt, clock, overdue, rejectedReason, href` |
| `awaiting` | `count, amount, items[{paymentId, contractNumber, amount, payerReportedAt, confirmationDueAt, href}]` |
| `refundsIncoming` | `count, amount, items[{paymentId, contractNumber, type, status, amount, dueAt, autoConfirmAt, href}]` |
| `tasks` | `total, urgentCount, items[{key, type, contractId, rentalRequestId, contextLabel, dueAt, clock, priority, href}]` |
| `holds[]` | `rentalRequestId, listingTitle, thumbnail, holdExpiresAt, remainingSeconds, critical, amountDue, href` |
| `visits` | `upcomingCount, items[{id, listingId, listingTitle, listingAddress, thumbnail, appointmentDate, startTime, endTime, status, rescheduleRequested, proposedDate, proposedStartTime}]` |
| `requests` | `counts{PENDING, ACCEPTED, REJECTED, CANCELLED, EXPIRED, COMPLETED}, open[{id, listingTitle, thumbnail, status, createdAt, respondBy, holdExpiresAt, href}]` |
| `spending` | `currency, total, totalExcludingDeposit, total12m, total12mExcludingDeposit, months[{month, total, byGroup{DEPOSIT_FIRST, RENT, UTILITY_OTHER}}], receiptPdfIssues` |
| `deposits` | `heldTotal, items[{contractId, contractNumber, amount, status, closure, href}]`; `closure` = null hoặc `{status, refundAmount, refundDueAt}` |
| `waiting` | `count, items[{type, contractId, contractNumber, sentAt, respondBy, clock, href}]` |
| `complaints` | `openCount, dueFromMeCount, items[{id, contractNumber, category, status, respondBy, responseDueFor, href}]` |
| `saved` | `total, available, unavailable` (đếm favorite active / listing active, available khi PUBLISHED; 4 thẻ vẫn lấy API favorites riêng) |

Quy tắc đã đối chiếu schema/code:

- `billingNow` là tên thực tế thay cho `serverNow` trong ví dụ thiết kế; `timezone=Asia/Ho_Chi_Minh`. Hạn tiền theo `PaymentTimePolicy.BILLING_TYPES` và OTHER_CHARGE có contract; utility theo giờ thật. Lịch xem dùng **ngày và giờ thật chính xác đến giây**, không ngày BillingTime hay cắt về phút. SmartCA `expiresAt` dùng giờ thật, hạn ký hợp đồng dùng BillingTime. Biên nhận được nhóm ngay trong PostgreSQL theo tháng VN, tối đa 12 × 6 nhóm loại tiền, không tải từng biên nhận.
- `total` và `totalExcludingDeposit` ứng với cửa sổ `spendMonths`; `total12m` và `total12mExcludingDeposit` luôn ứng với 12 tháng, kể cả khi chọn 6. Không cộng hội viên, tiền hoàn hoặc khoản mình là bên nhận. Cửa sổ từ đầu tháng thứ 12 (inclusive) đến đầu tháng sau (exclusive). `receiptPdfIssues` dùng cửa sổ 90 ngày BillingTime, FAILED hoặc PENDING **hơn** 5 phút.
- Lease gốc ACTIVE, không DEPOSIT/phụ lục; `ContractLeaseCalc.end` giữ ưu tiên early-end/ngày-tháng đã thoả thuận/effective/original, ngày kết thúc exclusive. `nextPayment` của từng lease và `payables.next` bỏ qua khoản quá hạn, đã báo hoặc chưa mở. OVERDUE lưu cũ nhưng hạn tương lai không bị tính quá hạn. Tổng tiền/count được aggregate trước cap chi tiết, bao gồm đủ cả khoản ngoài danh sách hiển thị.
- `DepositRecord` thực tế có `heldAmount`, `originalAmount`, `refundableAmount`, `contractId`, `rentalRequestId`, `tenantId`. `deposits.amount` là **heldAmount hiện tại**, kể cả 0 khi đã REFUNDED. Không suy số đang giữ từ tổng INITIAL vì tổng đó có cả tiền thuê. Bản ghi cũ không có ledger chỉ được bổ sung từ dòng `payment_line_items.type=DEPOSIT` của tiền đã CONFIRMED, nhãn `CONFIRMED_PAYMENT`; số đó là nguyên gốc đã thu, **không vào heldTotal**, vì không có bằng chứng số dư cọc hiện tại. Ledger và contract đều phải thuộc tenant hiện tại. Closure thuộc cả hợp đồng EXPIRED, không chỉ những lease còn ACTIVE.
- `CLOSURE_RESPONSE` chỉ khi closure PROPOSED của hợp đồng EXPIRED; DISPUTED chuyển sang `CLOSURE_DISPUTED` đang chờ chủ nhà. Waiting còn ba loại `RENEWAL_REQUESTED`, `AMENDMENT_WAITING`, `EARLY_EXIT_PROPOSED`; không lặp yêu cầu thuê PENDING. Schema workflow không có cột `proposedAt`, closure không có `disputedAt`, nên `sentAt=null` cho hai loại tương ứng thay vì bịa ngày; ngày gửi renewal/amendment lấy `requestedAt/submittedAt`.
- `RentalOnboardingService.get` tái sử dụng NextAction của tối đa 10 ACCEPTED: PAY_DEPOSIT, PAY_FIRST_RENT, SIGN_DEPOSIT, SIGN_LEASE, RESPOND_DEFAULT_NOTICE; PAY không lặp tiền đã ở payables. Refund confirmation lấy một lần từ payment truth. **Hiệu chỉnh giả định START_CHANGE_RESPONSE:** code hiện tại chỉ cho tenant yêu cầu đổi ngày, và chỉ owner được quyết định (`RentalOnboardingService.requestEarlierStart/decideStartChange`); không có yêu cầu do owner gửi tới tenant, không tạo nhiệm vụ giả cho tenant. `startChanges` không được trả nguyên văn vì có reason/responseNote.
- `SIGN_ANNEX` lấy signBy từ amendment/renewal APPROVED. `SIGN_CONFIRM_APP` chỉ đọc signature của chính tenant trên current revision của hợp đồng còn TENANT_SIGNATURE_PENDING; pending chưa hết hạn hoặc lần ký mới nhất FAILED/EXPIRED/REJECTED để thử lại. Không đọc CCCD/certificate hay gọi VNPT. `TERMINATION_RESPONSE` có cả early-exit và đề nghị tháng còn mở; NOTICE còn hạn và chưa trả được đọc từ lịch sử thực, REVIEW_REQUESTED không còn việc của tenant. Legacy mutual proposal không có hạn thì dueAt=null, không bịa SLA.
- Complaint chỉ khi active, một bên là user hiện tại, chưa RESOLVED; chỉ trả category/status/deadline, `responseDueFor=YOU|OTHER`. Không trả reason/decision, bank/QR/snapshot, phone/email/CCCD hoặc ID người đối diện. `rejectedReason` duy nhất là lý do payment REJECTED của **payer hiện tại**, cắt 160 ký tự. `ContractServiceImpl.getContract` đã xác nhận gọi `findContractAndCheckAccess` → `checkContractAccess` kiểm landlord/tenant hoặc admin có quyền; không cần đổi endpoint đích.
- Cap đầu ra: 50 leases/cọc, 20 tasks/payables/holds, 5 lịch/complaints/refunds/awaiting, 3 yêu cầu mở, 10 waiting; UI tự giới hạn lease cards 6. Nguồn ký/closure/renewal/waiting 50 mỗi loại, amendment/early-exit theo tối đa 50 lease, complaints 100, onboarding 10. Monetary totals, số lịch, số lease và số complaint là query aggregate độc lập; task/waiting total đếm nguồn đã nạp trong giới hạn thiết kế. Open payment detail đọc 200, next đọc tối đa 50 lease hiện tại + 1 khoản kế tiếp toàn cục, refunds 50; aggregate tiền không bị các cap này cắt. Batch metadata không tạo query theo từng lease; ngoại lệ onboarding ≤10 như thiết kế.

`TenantDashboardIndexMigration` chạy idempotent theo convention ApplicationReadyEvent: `idx_receipt_tenant_issued`, `idx_dispute_payer_status`, `idx_dispute_payee_status`, `idx_closure_status_refund_due`, `idx_sig_contract_status`. `contract_closures.contract_id` đã là primary key, không thêm index trùng. **Điều phối cần hoà giải PaymentReceipt/index này với P3/P1:** thay đổi annotation `idx_receipt_tenant_issued` là phần có sẵn của worker trước, được giữ lại; không tạo hai index cùng cột với tên khác khi gộp.

KYC, notification preferences/unread-count và chat vẫn dùng API độc lập đã chỉ định ở W0/W0b, không tạo phụ thuộc ngược tới notification/chat. Chưa có frontend/mobile/Excel trong phase 1. Không đọc `.env`, không truy cập DB/service của ứng dụng.

### Bằng chứng kiểm thử thực chạy — L3

Lượt cuối trên đúng source cuối: PowerShell, cwd `D:/Projects/homespace/.wt-dash-p2`:

```powershell
$env:HS_TENANT_TEST_PG_URL='jdbc:postgresql://127.0.0.1:64304/hs_tenant_dashboard_test_fd274c3f'
.\mvnw.cmd -Pisolated-build -pl hs-api-service -am '-Dtest=TenantDashboardServiceTest,TenantDashboardControllerTest,TenantDashboardPostgresTest,BranchLeaseEndParityTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Kết quả **exit 0 / BUILD SUCCESS, 47 tests, 0 failures, 0 errors, 0 skipped**:

| Test | Thực chạy / kết quả |
|---|---|
| `TenantDashboardServiceTest` | 22 passed: phân nhóm/lấp tháng, tổng 6/12 tháng, BillingTime khác giờ thật, OVERDUE cũ đã gia hạn, loại reported/refund khỏi phải trả, cắt rejectedReason, giữ chỗ ngưỡng 15 phút, lease end parity/tháng đã thoả thuận, next riêng theo lease, ưu tiên/de-duplicate/cap task, amendment/waiting, complaint/refund/signature/expiry/default notice/NextAction và monthly termination NOTICE → REVIEW_REQUESTED |
| `TenantDashboardControllerTest` | 13 passed qua MockMvc + UserContextFilter + GlobalException: caller được xác thực, cache-private/JSON, anonymous, header giả/secret sai/role sai, userId/role injection, tham số lạ/rỗng/sai/lặp; denied không gọi service |
| `TenantDashboardPostgresTest` | 9 passed trên **PostgreSQL 16.16**, entity/schema thật: cross-tenant contracts/payables/deposits/complaints (cả tài khoản vừa là landlord vừa là tenant), JSON không lộ bank/reason/PII; tháng VN 23:59:59→00:00, biên dưới/trên exclusive, cancelled/refund/payee loại khỏi chi tiêu; PDF PENDING đúng biên 5 phút; lịch thật chính xác giây và trạng thái cancelled/rejected/expired/completed; renewal/early exit/closure phản hồi và biến mất; số SQL bằng nhau khi tăng 3→30 leases và chỉ SELECT/CTE, không DML/lock; migration chạy 2 lần; **205 khoản quá hạn, 6 reported, 55 refunds** vẫn tổng đúng ngoài cap; SmartCA đúng tenant/current revision/latest request và biến mất sau SIGNED/CANCELLED; legacy deposit line-item đúng principal; annex chỉ hiện khi source APPROVED và parent ACTIVE |
| `BranchLeaseEndParityTest` (có sẵn) | 3 passed: giữ công thức ngày kết thúc dùng chung |

Container do worker tạo duy nhất: `hs-p2-codex-fd274c3f`, image `postgres:16-alpine`, bind localhost random port do Docker cấp (64304), database chỉ dùng test có regex guard. Container được xoá sau kiểm thử; không dừng/xoá container của worker khác. Log các lượt nằm trong `target/p2-evidence/` (không commit). Hai lỗi đã xử lý trong quá trình chạy: PowerShell tách `-Dsurefire.failIfNoSpecifiedTests=false` khi không quote; guard SQL ban đầu chỉ nhận SELECT nên loại nhầm CTE chỉ đọc. Lượt cuối quote đúng tham số, guard nhận SELECT/CTE và vẫn cấm INSERT/UPDATE/DELETE/MERGE/row lock; không còn validation lỗi chưa giải quyết.

10 đường dẫn thay đổi để điều phối tích hợp:

- `docs/dashboards/P2-tenant-dashboard.md`
- `hs-api-service/src/main/java/com/hs/api/controller/contract/TenantDashboardController.java`
- `hs-api-service/src/test/java/com/hs/api/controller/contract/TenantDashboardControllerTest.java`
- `hs-contract-service/src/main/java/com/hs/contract/config/TenantDashboardIndexMigration.java`
- `hs-contract-service/src/main/java/com/hs/contract/dto/tenant/TenantDashboardResponse.java`
- `hs-contract-service/src/main/java/com/hs/contract/repository/TenantDashboardReadRepository.java`
- `hs-contract-service/src/main/java/com/hs/contract/service/TenantDashboardService.java`
- `hs-contract-service/src/test/java/com/hs/contract/service/TenantDashboardServiceTest.java`
- `hs-contract-service/src/test/java/com/hs/contract/service/TenantDashboardPostgresTest.java`
- `hs-payment-service/src/main/java/com/hs/payment/receipt/PaymentReceipt.java`

Chưa chạy full suite, frontend/mobile/Excel hoặc dịch vụ ngoài; không ghi AUDIT_LOG/master và không push. Điều phối cần hoà giải index PaymentReceipt trùng phạm vi P1/P3. Giới hạn nguồn task/waiting, START_CHANGE_RESPONSE không tồn tại trong nghiệp vụ hiện tại và số dư legacy thiếu ledger đã nêu rõ ở trên, không che bằng dữ liệu ước lượng.
