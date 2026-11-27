# Độ phủ chứng từ thanh toán (payment receipt) - audit chỉ đọc

Phạm vi: `hs-core-api` + trang web `/dashboard/invoices` (`hs-web-app`). Ngày: 2026-11-28. Không sửa code.
Đường dẫn rút gọn: **PS** = `hs-payment-service/src/main/java/com/hs/payment`, **CS** = `hs-contract-service/src/main/java/com/hs/contract/service`.

## 0. Kết luận gốc rễ

- Chứng từ chỉ được tạo ở **một chỗ duy nhất**: `PaymentReceiptService.capture(PaymentRequest)` (PS/receipt/PaymentReceiptService.java:42), gọi từ `PaymentRequestService.confirmLocked` (PS/service/PaymentRequestService.java:719) và nhánh idempotent `confirmReceipt` (:574). Không nơi nào khác gọi (grep toàn repo).
- `PaymentReceipt` gắn cứng `payment_request_id NOT NULL` + unique (PaymentReceipt.java:9-17,24), `landlordId/tenantId NOT NULL` (:32-36). Snapshot cũng chỉ có `paymentId/rentalId/PaymentType/PaymentDirection` (PaymentReceiptSnapshot.java:10-24).
- Đơn membership (`MembershipOrder`) là bảng riêng, **không** có `PaymentRequest`, nên không đi qua `capture`: SePay IPN đặt PAID tại MembershipCheckoutService.java:219-224, ETH settle tại EthPaymentService.java:176-182; cả hai chỉ gọi `notifications.enqueueTransition` (thông báo), không tạo chứng từ.
- Mọi dòng tiền thuê nhà đều đi qua `PaymentRequest` -> có chứng từ khi bên nhận xác nhận (hoặc hệ thống tự xác nhận). Chỉ membership là ngoài luồng.

## 1. Bảng dòng tiền

Quy ước: PR = `PaymentRequest`; "Xác nhận" = `confirmLocked` (PaymentRequestService.java:681-719) -> `capture`. Cột phải: Rec = bản ghi `payment_receipts`; PDF = Gotenberg worker; LS = hiện ở "Lịch sử thanh toán".

| Dòng tiền | Payer -> Payee | Nơi xác nhận (file:line) | Rec | PDF | LS | Gap |
|---|---|---|---|---|---|---|
| Cọc giữ chỗ (RESERVATION_DEPOSIT) | Thuê -> Chủ nhà | confirmLocked :681/:719 (tạo ở RentalOnboardingService.java:117) | Có | Có | Có | Không (trừ khi tổng = 0, xem dưới) |
| Tiền tháng đầu (INITIAL) | Thuê -> Chủ nhà | như trên; period ở ContractPaymentReceiptContext.java:88-93 | Có | Có | Có | Không |
| Hóa đơn tháng: thuê + điện/nước + phí (MONTHLY_RENT) | Thuê -> Chủ nhà | createMonthlyPayment PS/service/PaymentRequestService.java:337,351 (gọi MonthlyBillingService.java:994) -> confirmLocked | Có | Có | Có | Gộp 1 phiếu/hóa đơn, dòng chi tiết lấy từ PaymentLineItem (receipt Service.java:84-105) |
| Phí chậm thanh toán / phạt | Thuê -> Chủ nhà | Là dòng `LATE_FEE` trong chính hóa đơn (MonthlyBillingService.java:1262-1329), chốt vào PR của hóa đơn kỳ sau (:952) | Có (trong phiếu hóa đơn) | Có | Có | Không có phiếu riêng; nếu dòng LATE_FEE không khớp tổng line-item thì phiếu rơi về 1 dòng chung chung (Service.java:84-95) |
| Gia hạn / hoãn hạn thanh toán (PaymentExtension) | Không có tiền | MonthlyBillingService.java:155 (chỉ đổi hạn, dừng/tiếp tục tính phạt) | Không cần | - | - | Không phải dòng tiền |
| Chênh lệch cọc theo phụ lục (DEPOSIT_ADJUSTMENT) | Thuê -> Chủ (bổ sung) hoặc Chủ -> Thuê (hoàn) | createDepositAdjustment PaymentRequestService.java:287-302 -> confirmLocked (cập nhật cọc :697-705) | Có | Có | Có | Phiếu hiển thị REFUND/PAYMENT theo direction (Service.java:290-298) |
| Hoàn tiền thanh toán đầu kỳ (INITIAL_PAYMENT_REFUND: hủy trước thuê, chấm dứt thỏa thuận) | Chủ -> Thuê | tạo :1001; auto-confirm sau hạn: autoConfirmStaleRefund :596-606 | Có | Có | Có | Bên nhận im lặng vẫn có phiếu do SYSTEM xác nhận |
| Hoàn cọc cuối hợp đồng (DEPOSIT_REFUND) | Chủ -> Thuê | createContractSettlement :254-259 -> confirmLocked; context bắt buộc có closure (ContractPaymentReceiptContext.java:98-100) | Có | Có | Có | Context ném IllegalStateException nếu closure lệch -> **rollback cả xác nhận** (rủi ro) |
| Số dư quyết toán Thuê phải trả (OTHER_CHARGE từ closure) | Thuê -> Chủ | createContractSettlement :259 (type OTHER_CHARGE) -> confirmLocked; settlement ở Context.java:100-125 | Có | Có | Có | Không |
| Hóa đơn bổ sung kỳ cuối (final cost supplement) | Thuê -> Chủ | ContractLifecycleService.java:323 createMonthlyPayment | Có | Có | Có | Không |
| Bồi thường chấm dứt sớm / khấu trừ cọc | Không phải PR riêng | Khấu trừ nằm trong closure deductions (ContractClosure) -> thể hiện trong phiếu hoàn cọc/số dư (Context.java:101-125). Cọc bị giữ (RETAINED_BY_LANDLORD, PreTenancyCancellation.java:43) không có giao dịch tiền | Gián tiếp | Gián tiếp | Gián tiếp | Không có phiếu riêng cho "bồi thường"; chỉ là dòng trong phiếu quyết toán (assumption: không có PaymentType riêng, enum chỉ 8 giá trị, PaymentType.java:3-10) |
| Hoàn tiền do khiếu nại | Chủ -> Thuê | ComplaintCaseService.java:38 ghi rõ "never turns a finding into a receipt"; tiền chỉ di chuyển qua PR hoàn cọc/số dư thường | Chỉ qua PR thường | Như PR | Như PR | Khiếu nại không tự phát sinh phiếu (chủ đích) |
| Phí chi nhánh / bãi đỗ xe | - | Không tìm thấy luồng thu phí riêng (grep parking chỉ ở metadata; branch chỉ gán hợp đồng, BranchContractAssignment) | - | - | - | Không có dòng tiền riêng (assumption: dựa trên grep, chưa đọc từng file branch) |
| PR tổng tiền = 0 (miễn thanh toán) | - | Tạo CONFIRMED sẵn (PaymentRequestService.java:185-186) | Không | Không | Không | Cố ý: capture bỏ qua tổng <= 0 (Service.java:44-47) |
| **Membership gói qua SePay** (Starter 49.000...) | Chủ nhà -> HomeSpace | MembershipCheckoutService.sepayIpn :175-226 (PAID :219) | **Không** | **Không** | **Không** | **Gap chính** |
| **Membership qua ETH (crypto)** | Chủ nhà -> HomeSpace | EthPaymentService.settle :141-187 (PAID :177) | **Không** | **Không** | **Không** | Như trên; thêm tx hash (transactionNo) và tỷ giá ETH/VND (EthPaymentQuote) |
| Membership nâng cấp (upgrade, trả phần chênh) | Chủ nhà -> HomeSpace | checkout :60-70, amount = giá mới - giá đã trả (MembershipService.java:104); cờ `upgrade` | **Không** | **Không** | **Không** | Cần ghi rõ "Nâng cấp từ X lên Y" |
| Mua thêm lượt (ADDON) | Chủ nhà -> HomeSpace | addonCheckout :74-84; PAID cùng đường SePay/ETH | **Không** | **Không** | **Không** | Cùng gap |
| Hoàn tiền membership | HomeSpace -> Chủ nhà | Không có luồng (grep refund trong membership/ và MembershipController: 0 kết quả). Đơn trả 2 lần -> REVIEW (:204-212) xử lý tay | Không | Không | Không | Chưa có nghiệp vụ; nếu thêm cần phiếu hoàn |

## 2. Trang web `/dashboard/invoices`

- Trang `hs-web-app/app/dashboard/invoices/page.tsx` render `PaymentReceiptsPanel` (components/dashboard/invoices/PaymentReceiptsPanel.tsx) theo `role` đang chọn (landlord/tenant); URL cũ `/invoices/receipts` chỉ redirect.
- Panel gọi `paymentReceiptService.list` -> `GET /api/v1/payment-receipts?role=LANDLORD|TENANT&contractId=` (hs-web-app/services/payment-receipt.service.ts:32), controller `PaymentReceiptController.list` (hs-api-service/.../PaymentReceiptController.java:30-37). Nguồn dữ liệu duy nhất: bảng `payment_receipts`. Nên đơn membership không bao giờ hiện, đúng như chủ báo ("Chưa có chứng từ thanh toán").
- Cả hai bên đều thấy: lọc theo `landlordId` hoặc `tenantId` (Service.java:166-176); `party()` cho phép cả hai tải/xem PDF (:208). Mỗi giao dịch 1 phiếu dùng chung, mỗi bên xem qua vai trò của mình (không có "bản sao" riêng).
- UI phân loại chỉ có `kind: PAYMENT | REFUND` (payment-receipt.service.ts:9); tiêu đề "Phiếu xác nhận thanh toán/hoàn tiền" (Service.java:293).
- Danh sách có bộ lọc join `PaymentRequest` khi dùng `contractId/rentalId` (Service.java:177-190): với phiếu membership (không có PR) filter hợp đồng sẽ loại ra, đúng ý.

## 3. Gap xếp hạng

1. **Membership SePay/ETH PAID không có chứng từ/PDF/lịch sử** (đơn PLAN, upgrade, ADDON): mất khả năng đối chiếu, thiếu chứng từ cho chủ nhà (và hóa đơn VAT nếu cần - assumption, cần xác nhận pháp lý).
2. Không có endpoint liệt kê đơn membership (MembershipController chỉ có `GET /orders/{id}`, :79) -> web không có "lịch sử mua gói".
3. Không backfill: đơn PAID cũ và cả PR CONFIRMED trước khi có tính năng phiếu không có phiếu (không thấy migration/backfill; `capture` chỉ chạy lại khi gọi `confirmReceipt` trên PR đã CONFIRMED, :573-576). Assumption: dữ liệu prod cũ có thể thiếu phiếu thuê nhà.
4. Đơn REVIEW (thanh toán trùng/hết hạn mà tiền đã vào) không có chứng từ cũng không có hoàn tiền (MembershipCheckoutService.java:204-217): rủi ro khiếu nại.
5. Phiếu thuê: `capture` có thể lỗi làm rollback xác nhận (Context ném IllegalStateException cho DEPOSIT_REFUND lệch closure, Context.java:98-125; `capture` ở MANDATORY transaction, Service.java:40-41). Chủ đích để không mất chứng từ, nhưng là điểm chặn nghiệp vụ.
6. Phạt/bồi thường không có phiếu riêng (chỉ là dòng trong phiếu hóa đơn/quyết toán); chấp nhận được, ghi nhận.
7. PR tổng 0 không có phiếu (cố ý).

## 4. Thiết kế đề xuất: phiếu membership dùng lại hạ tầng hiện có

Nguyên tắc ít code nhất: giữ 1 bảng `payment_receipts`, 1 worker PDF, 1 API list; thêm "nguồn" tổng quát.

**4.1 Schema / entity (`PaymentReceipt`)**
- Thêm `sourceType` (enum `PAYMENT_REQUEST` | `MEMBERSHIP_ORDER`, NOT NULL, default `PAYMENT_REQUEST`) và `sourceId` (36, NOT NULL). Với dòng cũ: `sourceId = payment_request_id`.
- Đổi `payment_request_id` thành NULLable (giữ cột cho tương thích), bỏ unique cũ `uq_receipt_payment`, thêm unique `(source_type, source_id)` -> idempotency theo đơn.
- Với MEMBERSHIP: `tenantId = ownerId` (người trả, cột đang tên "tenant"), `landlordId` = hằng `HOMESPACE` hoặc sentinel (cột NOT NULL, :32-36). Phương án sạch hơn: thêm `payerId`/`payeeId` rồi để `landlordId/tenantId` dẫn xuất (đổi lớn hơn, không khuyến nghị). `list()` lọc theo `tenantId = actor` nên chủ nhà vẫn thấy phiếu ở vai nào đang chọn: cần quyết định vai (xem 4.4).
- `PaymentReceiptSnapshot`: schemaVersion 2, thêm `sourceType`, `sourceId`, `membership` (tier, kind PLAN/ADDON, quantity, upgrade, provider, transactionNo, ETH: txHash + `amountEth` + tỷ giá, `paidAt`). `paymentId/rentalId/paymentType/direction` cho phép null (record hiện không cưỡng ép null, nhưng `view()` đọc `s.direction()` ở Service.java:291 -> cần xử lý null). `payee` = thông tin pháp nhân HomeSpace (tên công ty, MST, địa chỉ lấy từ config, không hard-code); `bank` null cho SePay/ETH.

**4.2 Phát hành (capture)**
- Thêm `PaymentReceiptService.captureMembership(MembershipOrder o)` (`Propagation.MANDATORY`), gọi ngay sau `o.setStatus(PAID)` trong cùng giao dịch: SePay :219-224 và ETH :177-182, **chỉ khi status == PAID** (bỏ qua REVIEW; khi REVIEW được duyệt thành PAID thì gọi tại điểm duyệt, chưa có code - assumption).
- Idempotent: kiểm `findBySource(MEMBERSHIP_ORDER, orderId)` như :46, cộng unique DB làm lưới an toàn (IPN SePay có thể bắn lại; code đã return sớm khi đã PAID :204, nhưng giữ unique).
- Số phiếu: `HS-MB-yyyyMMdd-<16 hex>` cùng định dạng hiện tại (Service.java:48-55; "MB" thay HT/TT), theo giờ Asia/Ho_Chi_Minh; unique `uq_receipt_number` có sẵn. Lưu ý: chuỗi hex ngẫu nhiên, không tuần tự; nếu cần số hóa đơn liên tục theo luật thì phải thêm sequence (assumption, cần xác nhận kế toán).
- Dòng: 1 dòng "Gói Starter 30 ngày" / "Nâng cấp Starter -> Pro (phần chênh)" / "Mua thêm N lượt đăng tin"; `amountWords` qua VietnameseCurrencyTextConverter (đang nằm ở contract module -> dời/gọi qua interface như PaymentReceiptContextProvider, vì payment không phụ thuộc contract).

**4.3 PDF**
- Worker/renderer/S3 dùng nguyên; chỉ thêm nhánh template theo `sourceType` (tiêu đề "Phiếu xác nhận thanh toán gói thành viên", bên nhận = HomeSpace, ẩn khối hợp đồng/địa chỉ). Hàm `pdf()` đã kiểm `referenceType=PAYMENT_RECEIPT` và `referenceId=r.id` (Service.java:232-236), dùng lại được.

**4.4 API + web**
- `PaymentReceiptView` thêm `sourceType` (+ `kind` = `MEMBERSHIP`), `membershipOrderId`; `paymentRequestId` nullable. `list()` giữ nguyên: lọc `tenantId = actor` cho vai "tenant", `landlordId = actor` cho "landlord". Vì chủ nhà mua membership, đề xuất đặt `landlordId = ownerId` (người mua là chủ nhà) và `tenantId = sentinel HomeSpace`; khi đó vai LANDLORD thấy phiếu, phiếu không lẫn vào vai Tenant. Điều kiện `party()` (:208) đã đúng. Filter `contractId/rentalId` tự loại phiếu membership (sub-query PR).
- Thêm `GET /payment-receipts/by-source/{type}/{id}` (hoặc `by-membership-order/{id}`) để trang kết quả thanh toán membership mở phiếu ngay; giữ `by-payment`.
- Web: `PaymentReceiptsPanel` thêm nhãn/icon cho `kind === "MEMBERSHIP"` (hiện chỉ PAYMENT/REFUND, :139), ẩn cột hợp đồng/địa chỉ, hiển thị "Chủ nhà -> HomeSpace". Nên thêm bộ lọc loại (Thuê nhà / Gói thành viên).

**4.5 Backfill**
- Job một lần (chạy lúc khởi động có cờ, hoặc script admin), idempotent theo `(source_type, source_id)`: với mọi `MembershipOrder` status=PAID, `paidAt != null`, gọi `captureMembership` với `issuedAt = paidAt` (không dùng now). Đơn REVIEW và FAILED/EXPIRED/CANCELLED: bỏ qua. Dùng snapshot hiện tại của đơn (giá lúc đó = `amountVnd`, không tính lại). Worker PDF tự xếp hàng (`nextAttemptAt=now`, Service.java:134). Batch nhỏ + rate-limit Gotenberg.
- Cùng đợt có thể backfill PR CONFIRMED thiếu phiếu bằng cách gọi `capture` lại (an toàn do idempotent :46) - ngoài yêu cầu, chỉ đề xuất.

**4.6 Rủi ro**
- DB: ddl-auto lấy từ env `JPA_DDL_AUTO` (application.properties:46); không có Flyway. `update` **không** bỏ NOT NULL/unique cũ -> cần DDL tay trên prod (bỏ NOT NULL `payment_request_id`, thay unique, điền `source_*` cho dòng cũ) trước khi deploy.
- Cột `landlordId/tenantId` mang nghĩa hợp đồng; gán sentinel dễ gây nhầm ở báo cáo/thống kê hiện có (cần grep các query dùng hai cột này trước khi làm).
- Phụ thuộc module: `hs-payment-service` đã chứa membership + receipt nên ít vướng; helper chữ số tiền nằm ở contract module -> cần port/interface.
- Pháp lý: đây là "phiếu xác nhận", không phải hóa đơn điện tử VAT; nếu cần e-invoice phải tích hợp nhà cung cấp riêng (ngoài phạm vi).
- Lỗi capture trong giao dịch IPN: làm hỏng cả việc kích hoạt gói. Nên để capture không ném lỗi chặn kích hoạt (try/catch + log + job vá), khác với phiếu thuê nhà là cố ý rollback.
- ETH: số tiền chuẩn là VND (`amountVnd`); cần ghi thêm ETH/tx hash vào snapshot để đối chiếu, không dùng làm tổng.
- Snapshot bất biến + hash (Service.java:62,142): đổi schema phải giữ đọc được schemaVersion 1.

## Quyết định điều phối (28/11/2026)
Chọn **bảng riêng `membership_receipts`** thay vì mở rộng `payment_receipts`:
- Không phải bỏ NOT NULL / đổi unique của `payment_receipts` trên prod (không có Flyway; `ddl-auto=update` không tự làm được).
- Không gán người mua vào `landlordId`/`tenantId` (nghĩa hợp đồng) nên không làm lệch báo cáo/quyền hiện có.
- Dùng lại renderer PDF (Gotenberg), lưu S3 và cơ chế tải có kiểm tra toàn vẹn của biên nhận hiện có; số phiếu `HS-MB-yyyyMMdd-xxxx`; bên nhận là HomeSpace; tạo đúng 1 phiếu khi đơn chuyển PAID (SePay, ETH, gói/nâng cấp/addon), unique theo `order_id`; backfill các đơn PAID cũ với ngày phát hành = `paidAt`.
- Web "Lịch sử thanh toán" có 2 nhóm: **Thuê nhà** (như cũ) và **Gói hội viên** (chỉ người mua thấy).
- Ngoài phạm vi lần này: đơn REVIEW (trả trùng/hết hạn) và hoàn tiền gói; hoá đơn điện tử VAT.
