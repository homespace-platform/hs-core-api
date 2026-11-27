# PRE-MERGE-FIX · Sửa 4 hồi quy so với `master`

2026-11-27 · `feature/branch-monitoring` @ `a6272c8` · L3 · Không commit/push, không đọc `.env`.
Phát hiện: [PRE-MERGE-REVIEW.md](PRE-MERGE-REVIEW.md). Mỗi mục: test đỏ trước, sửa nhỏ nhất, ghi hành vi `master`.

## Tiến độ
- [x] Đọc tài liệu, so mã với `master`
- [x] Test tái hiện (đỏ): 9 ca đỏ trước khi sửa (phụ lục 5, bãi xe 2, publisher 1, auto link 1); ca đối chứng "master chuyển pool" xanh từ đầu
- [x] Sửa 1-4 (chi tiết dưới)
- [x] Unit listing/contract/payment/api
- [x] PostgreSQL container `hs-branch-verify-pg`

## 1 · Ký phụ lục làm mất chỗ ở chi nhánh cũ
- Đỏ: `ContractAmendmentServiceTest.listingLeaving…`, `amendmentApprovedBeforeDeploy…`, `replacingAScheduledListingSync…`. Đối chứng `syncStillMovesTheHold…`.
- `master`: `parkingChanged` so `contract.branchId` với đích đồng bộ. Gốc null, đích null thì không gọi bãi xe; gốc X, đích khác X thì chuyển. Riêng ca thay phụ lục đã đồng bộ, `master` dựng lại theo `refreshParkingPool` và ghi sang pool null.
- Sửa: `parkingChanged` trở về đúng phép so của `master` (không so với pool đang chiếm). Thêm `parkingPool`: khi không đổi xe/thời hạn, gốc null và đích null thì dựng lại tại pool đang chiếm (chỉ xảy ra ở ca thay phụ lục; khác `master`, theo Q15). `branchId` ghi trên phụ lục/hợp đồng giữ theo đích đồng bộ.
- Phụ lục duyệt trên `master` (không snapshot đích, không hold) ký sau deploy: không đụng bãi xe.
- Phụ lục có đổi xe/thời hạn: không đổi trong lần sửa này.

## 2 · Chi nhánh giữ chỗ đã xóa mềm
- Đỏ: `softDeletedReservationBranch…`, `branchSoftDeletedBetweenApprovalAndSigning…`, `ParkingReservationServiceTest.draftEdit…SoftDeleted…`, `renewal…SoftDeleted…` (đều `NoSuchElementException`).
- `master`: gốc có `branch_id = null` nên dùng pool độc lập, không tra chi nhánh đã xóa. Nếu chính `contract.branchId` hoặc dòng gia hạn trỏ chi nhánh đã xóa thì `master` cũng `orElseThrow`; nay cũng hết lỗi.
- Sửa: một hàm `lockBranchPool` (không còn hoạt động thì null) cho sửa nháp, kiểm/giữ/kích hoạt phụ lục, gia hạn; dòng mới ghi `branch_id = null`. `amendmentPool` trả null cho chi nhánh đã xóa (`isSoftDeleted`, truy vấn vô hướng).
- Giới hạn của tin áp cho nháp và gia hạn; phụ lục tin độc lập không kiểm sức chứa (quy tắc sẵn có).
- Không đổi: `lockAmendmentPool` (không còn nơi gọi trong mã chạy), nhận yêu cầu thuê (như `master`, không thuộc nháp/phụ lục).

## 3 · Auto link xin kết nối thứ hai
- Đỏ: `BranchContractAutoLinkTest.tenConcurrentCreations…` (pool giả 10 permit).
- `master`: không có auto link.
- Sửa: `afterCommit` chỉ xếp hàng; 1 luồng `branch-auto-link`, hàng đợi 200; đầy hoặc đang tắt thì log `BRANCH_AUTO_LINK_REJECTED`; `@PreDestroy` chờ tối đa 10 s. Lỗi liên kết không tới nơi tạo hợp đồng. Liên kết xuất hiện sau phản hồi tạo hợp đồng một chút; mất nếu tiến trình chết (gắn tay).

## 4 · Digest chậm ACK chặn thông báo hợp đồng
- Đỏ: `BranchDigestPublisherTest.aSlowDigestAckMustNotDelayTheContractPoll`.
- `master`: một vòng hợp đồng tối đa 20 dòng, lỗi thì dừng.
- Sửa: `publishPending` trở lại đúng vòng của `master` (khác duy nhất: câu claim loại dòng digest). `publishDigests` là `@Scheduled` riêng; giữ cooldown, ngân sách 2 s. Pool scheduler 4 → 6 (thêm 1 cho publisher digest, 1 cho job 08:00).

## Test cũ phải cập nhật (hành vi đổi có chủ đích)
- `BranchDigestPublisherTest` 2 ca cũ và `BranchOverviewPostgresTest` 2 ca digest: gọi `publishDigests` vì digest không còn chạy trong `publishPending`. Ca một digest lỗi nay khẳng định 1 lần gửi mỗi lượt digest (trước là 2 do mock trả digest cho cả hai câu claim).
- `BranchAssociationsPostgresTest` 2 ca tạo hợp đồng: chờ hàng đợi auto link rồi mới kiểm (khẳng định giữ nguyên).

## Kiểm chứng
Unit: `mvnw -o -B -pl hs-api-service -am test`, không đặt biến PostgreSQL/SmartCA. BUILD SUCCESS. Skip không tính là đạt.

| Module | Chạy | Fail | Error | Skip |
|---|---|---|---|---|
| common | 21 | 0 | 0 | 0 |
| user | 136 | 0 | 0 | 22 |
| storage | 71 | 0 | 0 | 0 |
| listing | 581 | 0 | 0 | 94 |
| payment | 263 | 0 | 0 | 25 |
| contract | 1338 | 0 | 0 | 249 |
| api | 157 | 0 | 0 | 1 |

PostgreSQL: container mới `hs-branch-verify-pg` (`postgres:16-alpine`, 127.0.0.1, tmpfs, mật khẩu giả, user `postgres` đổi mật khẩu giữa hai class cố định mật khẩu), mỗi class một DB trống mới. Đã xóa container sau khi chạy.

| Class | Chạy | Fail | Error | Skip |
|---|---|---|---|---|
| ContractLifecyclePhase03PostgresTest | 31 | 0 | 0 | 0 |
| RentalWorkflowHttpSystemTest | 1 | 0 | 0 | 0 |
| BranchAssociationsPostgresTest | 21 | 0 | 0 | 0 |
| BranchOverviewPostgresTest | 10 | 0 | 0 | 0 |
| MonthlyInvoiceNotificationPostgresTest | 12 | 0 | 0 | 0 |
| AgreementSigningNotificationPostgresTest | 5 | 0 | 0 | 0 |

Chưa kiểm: `isSoftDeleted` chưa chạy thật trên PostgreSQL (chỉ được Hibernate kiểm cú pháp lúc khởi động; test chi nhánh đã xóa dùng repository giả); đua giữa xóa chi nhánh và ký; Kafka thật; pool Hikari thật dưới tải (6 luồng scheduler + 2 luồng nearby dùng chung 10 kết nối với HTTP); fat jar.
