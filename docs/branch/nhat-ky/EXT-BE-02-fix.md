# EXT-BE-02 · Sửa pool chỗ xe (EXT-BE-01) và giới hạn sơ đồ (Q12)

2026-11-26 · `hs-core-api@925158e` · L3 · Người sửa: Claude Sonnet.
Phạm vi ghi: `hs-listing-service/src/main`, `hs-contract-service/src/main`, file này. Không sửa test, chưa commit.

## Tiến độ
- [x] Đọc tài liệu, kiểm phân tích EXT-BE-01 trong code
- [x] Sửa chọn pool (nháp, phụ lục)
- [x] Giới hạn Q12
- [x] Chạy test thường và PostgreSQL opt-in

## Quy tắc chọn pool (null = pool của tin độc lập)
- Nháp: đích đồng bộ trong chính lần lưu; nếu không thì `HELD` của hồ sơ thuê; đích đã đồng bộ trước đó; dòng cũ của hồ sơ; cuối cùng (cấp chỗ lần đầu) là chi nhánh của tin.
- Phụ lục có nguồn tin đã duyệt: dùng đích trong nguồn, kể cả null (rời chi nhánh).
- Phụ lục đổi xe/thời hạn không đồng bộ: segment `ACTIVE` của gốc tại ngày trước ngày hiệu lực (ngày hiệu lực có thể đang thuộc phụ lục bị thay thế); đích đã đồng bộ; segment cũ gần nhất của gốc; chi nhánh của tin.
- Phụ lục không đụng xe/thời hạn: giữ nguyên `contract.branchId`, không gọi bãi xe.
- Ký: dùng `branchId` đã lưu trên văn bản phụ lục khi duyệt (không đọc proxy `revision.contract`).
- Không đổi quy tắc sức chứa, khóa pool, kiểm chủ sở hữu. Không backfill, không SQL.

Khác đề xuất EXT-BE-01 / lần sửa đầu: bản đầu tra pool cho mọi phụ lục và coi mọi phụ lục `APPLIED` là đã chốt đích. Test `occupancyRecalculatesOnlyFuturePerPersonFees` đỏ, và phụ lục chỉ đổi giá có thể làm mất chi nhánh ở lần cấp chỗ đầu. Đã sửa theo quy tắc trên.

## Còn mở (chưa sửa, cần quyết)
- Nháp đồng bộ khi tin đã **rời** chi nhánh mà không đổi số người/xe: `ContractListingSync.applyDraftTerm` so `contract.branchId` (null ở gốc) với đích null nên không chuyển hold, hold vẫn ở chi nhánh cũ (hành vi có sẵn). Sau bản sửa này, lần đổi xe sau đó cũng giữ chi nhánh cũ (trước đây chuyển sang null). Chuyển hold ngay khi đồng bộ sẽ kiểm sức chứa tin độc lập và có thể từ chối lần đồng bộ.
- Hồ sơ chưa từng có xe, tin đã đổi chi nhánh sau khi nhận yêu cầu: không còn dấu vết chi nhánh lúc nhận, lần cấp đầu dùng chi nhánh hiện tại của tin.
- Reservation/phụ lục đã tạo sai trước bản sửa không được sửa lại (xem EXT-BE-01).
- Chưa có test riêng cho: rời chi nhánh, thay thế phụ lục đã đồng bộ, xe 2 → 0 → 2, cấp chỗ lần đầu (không được sửa test trong task này).

## Q12
`BranchLayoutService.checkSize`: 30 tầng/khu, 10 dãy/tầng, 100 ô/dãy, 1.500 ô/chi nhánh (tính cả lối đi). Lỗi 400/4073 kèm thông báo tiếng Việt nêu giới hạn bị vượt. Chạy sau kiểm chủ sở hữu và revision (giữ 404/409), trước khi tải cây cũ.
Web (chỉ đọc): `BranchLayoutEditor` đã hiện thông báo của server khi lỗi 400. Phía web mới chặn trước 30 tầng; chặn trước 10 dãy, 100 ô/dãy, 1.500 ô là tùy chọn.

## Kết quả test
Test thường: `.\mvnw.cmd -o -B -pl hs-contract-service -am '-Dmaven.test.failure.ignore=true' test`, không đặt biến PostgreSQL/SmartCA.

| Module | Chạy | Lỗi | Skip |
|---|---|---|---|
| common | 21 | 0 | 0 |
| user | 136 | 0 | 22 |
| storage | 71 | 0 | 0 |
| listing | 569 | 0 | 94 |
| payment | 263 | 0 | 25 |
| contract | 1212 | 0 | 217 |

Skip không tính là đạt. Lần chạy đầu có đúng 1 lỗi, do bản sửa đầu gây ra (nêu trên); lần chạy cuối 0 lỗi.

Theo class: ContractDraftResidents 4, ContractServiceImpl 43, ContractAmendmentService 49, ContractListingSync 20, ContractLifecycleService 55, MonthlyBilling 114, ParkingReservation 26, BranchLayoutService 17: đều đạt, 0 skip. Hai test đỏ của EXT-BE-01 nằm trong số này và đã xanh. `BranchLayoutApiTest` (api, chạy riêng): 12/12.

PostgreSQL opt-in trên container bỏ đi `hs-branch-test-pg` (127.0.0.1:55432), biến môi trường chỉ đặt cho từng lệnh, chạy từng class riêng:

| Class | Chạy | Đạt | Lỗi | Skip |
|---|---|---|---|---|
| BranchLayoutPostgresTest | 4 | 4 | 0 | 0 |
| ContractLifecyclePhase03PostgresTest | 31 | 31 | 0 | 0 |
| RentalWorkflowHttpSystemTest | 1 | 1 | 0 | 0 |

Hai test sau cố định user `postgres`, nên đã tạo role `postgres` (superuser) trong container này và đổi mật khẩu test giữa hai lần chạy. Không đụng DB/container khác, không xóa container. Các test PostgreSQL khác chưa chạy. Truy vấn chọn pool mới chưa có test PostgreSQL riêng.

## 2026-11-27 · Sửa phát hiện 5 và 6 của EXT-BE-03
Phát hiện 1–4 chưa sửa, chờ chủ dự án. Không đổi quy tắc sức chứa, không viết SQL.

- **5:** đã xác nhận `activateInPool` đặt `RELEASED` cho segment tương lai bị phụ lục sau thay thế, và `amendmentPool` vẫn đọc nó làm lịch sử. Sửa: bỏ qua dòng gốc `RELEASED`. `draftPool` giữ nguyên (nháp 2 → 0 → 2 cần dòng đã nhả).
- **6:** đã xác nhận `propose` không lưu đích. Sửa: thêm cột nullable `ContractAmendment.parkingTargetSnapshot` (`{"branchId":...}`, phân biệt được đích null), lưu lúc gửi; duyệt dùng đích này, ký dùng `branchId` của văn bản phụ lục. Sức chứa vẫn kiểm lại lúc duyệt và ký. Đề nghị gửi trước bản sửa (cột null) tính đích như cũ. Cột do Hibernate `ddl-auto` tạo; môi trường dùng `validate`/`none` sẽ thiếu cột.
- Test thêm: `ParkingReservationServiceTest.zeroVehicleRentalIgnoresThePoolOfAReplacedAmendmentSegment`, `ContractAmendmentServiceTest.firstAllocationKeepsTheTargetCheckedAtSubmitThroughApprovalAndSigning`. Lần chạy đầu test sau đỏ do assert của test (kiểm sức chứa gọi 2 lần: gửi và duyệt), đã sửa thành `times(2)`.
- Kết quả cuối (chạy/lỗi/skip): common 21/0/0, user 136/0/22, storage 71/0/0, listing 570/0/94, payment 263/0/25, contract 1213/0/217.
- `ContractLifecyclePhase03PostgresTest` trên `hs_phase03_test` tạo lại mới trong `hs-branch-test-pg`: 31 chạy, 0 lỗi, 0 skip.
- Không chạy module api; lỗi `UserScopePermissionSecurityTest` từ Phase 2 nằm ngoài phạm vi này.
