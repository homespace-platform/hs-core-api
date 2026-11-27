# Nhật ký P3-BE-01 (Sonnet)

Hợp đồng: 07 §1-3, §6, §8. Chỉ đọc. Không viết test (Codex). Không commit.

## Trạng thái: XONG (2026-11-27). Bước kế: Codex viết P3-TEST-01.

## File cuối
C = hs-contract-service/src/main/java/com/hs/contract, P = hs-payment-service/.../payment, L = hs-listing-service/.../listing
- Mới: hs-api-service/.../controller/listing/BranchOverviewController; C/service/{BranchOverviewService, BranchBillingPeriods}; C/dto/branch/{BranchOverviewResponse, BranchRentalRow, BranchInvoiceRow}; L/dto/response/BranchOverviewListingRow.
- Sửa: C/dto/branch/BranchLeaseRow (+awaitingHandover, billingMonths(); giữ constructor 10 tham số), C/service/BranchRoomSchedule (+shownRooms dùng chung P2/P3; lease chờ bàn giao vẫn chiếm phòng), C/service/BranchAssignmentService (dùng shownRooms), C/repository/{BranchContractAssignmentRepository, MonthlyInvoiceRepository}, P/repository/{PaymentRequestRepository, PaymentLineItemRepository}, L/repository/BranchRoomListingRepository.

## Điểm vào cho test
- GET /branches/{branchId}/overview?month=YYYY-MM -> BranchOverviewController -> BranchOverviewService.overview(branchId, ownerId, YearMonth|null).
- Hàm thuần (package com.hs.contract.service): BranchOverviewService.{room, invoiceStatus, initialStatus, inMonth, paymentOrder, validFirstRent}; BranchBillingPeriods.of; BranchLeaseRow.{awaitingHandover, endExclusive, billingMonths}; BranchRoomSchedule.{historical, placed, shownRooms}.

## Kiểm (sau lần sửa code cuối)
- `./mvnw -o -B -pl hs-api-service -am test`, BUILD SUCCESS, không bỏ qua lỗi. run/failed/error/skipped: common 21/0/0/0, user 136/0/0/22, storage 71/0/0/0, listing 579/0/0/94, payment 263/0/0/25, contract 1242/0/0/238, api 132/0/0/1. Skip không tính là đạt.
- PostgreSQL (hs-branch-test-pg, DB trống mới, chạy riêng): BranchAssociationsPostgresTest 21/0/0/0; BranchLayoutPostgresTest 4/0/0/0.
- Probe tạm (không nằm trong repo; bản lưu: scratchpad phiên e1a0a164.../BranchOverviewProbeTest.java) 1/0/0/0 trên PostgreSQL: chạy thật query mới + findLeaseRows có cột đơn phương; 13 query (thêm 520 HĐ: 17, do chia lô 500), chỉ SELECT, không FOR UPDATE/SHARE, snapshot mọi bảng không đổi, chủ khác bị chặn.

## Khác thiết kế / lựa chọn nhỏ nhất
1. Dấu chờ bàn giao nằm trong projection chung nên đổi cả P2: HĐ đơn phương TERMINATED chưa bàn giao chiếm phòng (hiện ở assignments, chặn bỏ phòng, bật cờ, gắn trùng lịch). Snapshot hỏng thì coi là lịch sử.
2. Khoản đầu/giữ chỗ khi chưa có HĐ gốc: chỉ hồ sơ active ACCEPTED trên tin active thuộc CN (hồ sơ đã hủy/hết hạn không hiện, kể cả khoản CONFIRMED).
3. Gốc tiền hiệu lực chỉ giữ phòng khi hồ sơ còn active ACCEPTED.
4. Gốc CANCELLED còn khoản đầu hợp lệ: đích H(id)?tab=payment. Một hồ sơ có nhiều gốc: ưu tiên gốc chưa hủy, rồi id nhỏ nhất.
5. Hóa đơn: đọc projection nhẹ mọi row của gốc trong phạm vi (không lọc tháng ở SQL); PaymentRequest chỉ lấy cho row có hạn <= tháng chọn. Slot, Contract, PaymentRequest, line item đọc bằng entity trong transaction read-only (không quan hệ eager).
6. Hạn dự kiến (nháp/chưa có row) = MonthlyBillingSchedule.dueAt như thiết kế. Lúc phát hành thật, code billing có thể dùng quy tắc hạn của HĐ cũ hoặc dời hạn khi phát hành tay trễ, nên tháng thực có thể khác. Không đổi billing.
7. Target khoản dự kiến: kind CONTRACT, id gốc. Khoản không có transfer ghép được: clock BILLING.
8. Chỉ gốc active=true (đúng 07 §2); assignments P2 không đổi bộ lọc này.

## Chưa kiểm
- Chưa chạy app, chưa gọi HTTP/Gateway thật (JSON ISO, Cache-Control qua security filter, 400/401/404), chưa đo 1500 phòng.
- DB thử còn trong container: hs_branch_associations_test_p3probe, hs_branch_associations_test_p3assoc, hs_branch_layout_test_p3.

## Sửa theo P3-REVIEW (1-3)
- Code đã sửa. Bước kế: unit test, rồi 3 lớp PostgreSQL trên DB mới.

### Kết quả (thay mục "Khác thiết kế" 6 ở trên)
- [x] R1: kind SETTLEMENT_BALANCE (quyết định P3-R1). Nguồn ContractClosure.balancePaymentId của gốc trong phạm vi; OTHER_CHARGE active, đúng HĐ và hai bên, còn mở (đã CONFIRMED thì rời lớp); màu và lọc tháng như hóa đơn; đích PAYMENT /dashboard/payments?paymentId={id}; ở phòng đã xếp hoặc khay.
- [x] R2: tách MonthlyBillingSchedule.signedDueAt khỏi MonthlyBillingService.issue (cùng phép tính, cùng lỗi); overview chọn revision như billing (costTermsRevisionId của row, phụ lục đã ký hiệu lực tại đầu kỳ, currentRevisionId). Gốc không có revision nào: dùng cửa sổ chuẩn dueAt. Revision được trỏ tới nhưng thiếu hoặc hỏng: bỏ khoản xám. Vẫn chỉ để hiển thị: phát hành tay trễ có thể dời hạn +1 ngày.
- [x] R3: findLeaseRows chỉ trả tên khi rental.ownerId = contract.landlordId (đổi cả nhãn ở P2); findHolds yêu cầu rental.ownerId = listing.ownerId (đổi cả guard P2 với dữ liệu lệch).
- R4 thuộc web, không sửa ở đây.
- 07 §1 đã thêm kind mới. Không sửa test. File mới: dto/branch/BranchRevisionTerms; sửa thêm MonthlyBillingSchedule, MonthlyBillingService.

### Nguồn còn thiếu (báo điều phối, chưa thêm)
- DEPOSIT_ADJUSTMENT người thuê -> chủ nhà (nộp bổ sung cọc theo phụ lục): không có ở lớp Thu tiền, và 07 §4 không có việc chờ xác nhận/quá hạn nào nhận nó (OWNER_REFUND chỉ chiều chủ trả).
- SETTLEMENT_BALANCE chưa có trong định nghĩa PAYMENT_CONFIRM / PAYMENT_OVERDUE của P4 (07 §4).
- Các khoản chủ phải hoàn (DEPOSIT_REFUND, INITIAL_PAYMENT_REFUND, DEPOSIT_ADJUSTMENT) theo 07 §3 không lên lớp Thu tiền, chỉ ở P4 OWNER_REFUND; concept §6 có, Q7 "kể cả hoàn cọc" cần P4 mới đủ.
- UTILITY và OTHER_CHARGE ngoài quyết toán: không thấy code nào tạo. Hóa đơn bổ sung kỳ cuối là MonthlyInvoice, đã có.

### Kiểm (sau lần sửa cuối)
- `./mvnw -o -B -pl hs-api-service -am test` BUILD SUCCESS. run/failed/error/skip: common 21/0/0/0, user 136/0/0/22, storage 71/0/0/0, listing 579/0/0/94, payment 263/0/0/25, contract 1280/0/0/243, api 145/0/0/1.
- PostgreSQL DB trống mới, từng lớp: BranchOverviewPostgresTest 5/0/0/0, BranchAssociationsPostgresTest 21/0/0/0, ContractLifecyclePhase03PostgresTest 31/0/0/0 (lần đầu 31 lỗi vì mật khẩu role test postgres; đặt theo giá trị trong test, chạy lại đạt, rồi đặt lại giá trị của RentalWorkflowHttpSystemTest). DB mới: hs_branch_overview_test_p3fix, hs_branch_associations_test_p3fix, hs_phase03_test (bản cũ đổi tên hs_phase03_test_old_p3fix).
- Chưa chạy: HTTP/Gateway thật, BranchLayoutPostgresTest lại (listing không đổi sau lần đạt 4/4).
