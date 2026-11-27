# RECHECK-FIX · Sửa phát hiện 1–4 của [RECHECK](RECHECK.md) phần Core

2026-11-27 · `feature/branch-monitoring` @ `c753458` · L3 · Không commit/push, không đọc `.env`, không SQL.

## Tiến độ
- [x] Đọc tài liệu, mã và caller của `ParkingReservationService`
- [x] Nhận quyết định chủ dự án cho phát hiện 3 (giữ chỗ ở A)
- [x] Ghi quyết định vào `04-cau-hoi.md`
- [x] Test tái hiện (đỏ) cho 1, 2, 3, 4
- [x] Sửa
- [x] Unit toàn bộ module
- [x] PostgreSQL container bỏ đi, đã xóa

## Đỏ trước khi sửa
`ContractAmendmentServiceTest` 75 ca: 14 fail, 1 error (error do dữ liệu test ca thay phụ lục viết sai; sửa test rồi chạy riêng: đỏ đúng chỗ). `ParkingReservationServiceTest` 32 ca: 3 fail. Các ca đối chứng (sync xác nhận rời chi nhánh vẫn sang bãi độc lập; A bị xóa vẫn chuyển bãi) xanh từ đầu. Ca kiểm trước khi ký gia hạn thêm cùng bản sửa (hàm chưa tồn tại nên không chạy đỏ).

## 1 · Rơi về bãi độc lập phải có giới hạn tin
- Phụ lục của tin độc lập vẫn không kiểm sức chứa (như `master`). Kiểm khi: chi nhánh được yêu cầu đã xóa mềm; hoặc chỗ `ACTIVE` của HĐ trong kỳ (gồm đoạn kết thúc đúng ngày hiệu lực, cho ca thay phụ lục) còn ghi chi nhánh đã xóa.
- HĐ không có chỗ nào nhưng ghi chi nhánh đã xóa: `parkingPool` truyền ID đó để service bãi xe nhận ra.
- Chỗ đang giữ của tin = dòng `branch_id = null` hoặc còn ghi chi nhánh đã xóa. Áp cho nháp, phụ lục, gia hạn.
- Giới hạn tin để trống = 0 (như nháp và gia hạn hiện có).

## 2 · Phụ lục duyệt trên `master`, `branch_id = null`
Chỉ khi không có `parkingTargetSnapshot`, văn bản ghi null, có đổi xe/thời hạn và không có sync quyết đích: lấy bãi đang giữ trước ngày hiệu lực (`amendmentPool`), kiểm sức chứa bãi đó. Không giữ chỗ nào, tin độc lập, hoặc sync đã xác nhận rời: vẫn null.

## 3 · Quyết định chủ dự án 27/11
- HĐ ghi A, tin rời A, không đổi xe/thời hạn: đích = A, văn bản và HĐ giữ A, không gọi bãi xe. Xét lại lúc gửi, duyệt, ký.
- A đã xóa ở bất kỳ mốc nào: đích null, chuyển sang bãi độc lập có kiểm giới hạn; văn bản ghi null để HĐ sau hiệu lực khớp nơi giữ chỗ.
- Phụ lục kiểu này duyệt trên `master`: lúc ký nhả dòng `HELD` bãi độc lập mà `master` đã tạo, ghi lại A trên văn bản.
- Có đổi xe hoặc thời hạn: như `master`.

## 4 · Ký gia hạn khi chi nhánh bị xóa sau duyệt
`activateRenewalHold` kiểm giới hạn tin (cùng hàm với phụ lục) rồi kích hoạt với `branch_id = null`. Thêm `validateRenewalHold` vào `validateAgreementSigning` để từ chối trước khi nhà cung cấp ký, tránh kẹt ở bước hoàn tất chữ ký.

## Test cũ đã đổi
- `syncStillMovesTheHoldWhenTheContractBranchDiffersFromTheSyncedTarget` → `syncStillMovesTheHoldWhenVehiclesChangeAndTheContractBranchDiffersFromTheSyncedTarget`: payload thêm `motorbikeCount: 1`, thêm A còn hoạt động; ba assert giữ nguyên. Ca cũ (không đổi xe/thời hạn) nay là `syncLeavingTheRecordedBranchWithoutVehicleOrTermChangeKeepsTheHoldAndTheRecordedBranch`, khẳng định ngược lại.
- Fixture `realParking()`: tin có `maxMotorbikeCount = 5` (trước để trống) và repository giả trả lịch chồng nhau. Hai ca chi nhánh đã xóa cần giới hạn này; assert không đổi.

## Kiểm chứng
Unit: `.\mvnw.cmd -o -B -pl hs-api-service -am '-Dmaven.test.failure.ignore=true' test`, không đặt biến PostgreSQL/SmartCA. BUILD SUCCESS. Skip không tính là đạt.

| Module | Chạy | Fail | Error | Skip |
|---|---|---|---|---|
| common | 21 | 0 | 0 | 0 |
| user | 136 | 0 | 0 | 22 |
| storage | 71 | 0 | 0 | 0 |
| listing | 584 | 0 | 0 | 94 |
| payment | 263 | 0 | 0 | 25 |
| contract | 1358 | 0 | 0 | 249 |
| api | 157 | 0 | 0 | 1 |

PostgreSQL: container mới `hs-branch-verify-pg` (`postgres:16-alpine`, 127.0.0.1, tmpfs, mật khẩu giả), mỗi class một DB trống, chạy từng class.

| Class | Chạy | Fail | Error | Skip |
|---|---|---|---|---|
| ContractLifecyclePhase03PostgresTest | 31 | 0 | 0 | 0 |
| RentalWorkflowHttpSystemTest | 1 | 0 | 0 | 0 |
| BranchAssociationsPostgresTest | 21 | 0 | 0 | 0 |

## Còn mở, chưa sửa
- **Tin chuyển sang chi nhánh B, không đổi xe/thời hạn:** quyết định chỉ nói tin rời A; hiện vẫn như `master` (chuyển chỗ sang B, kiểm sức chứa B). Cần chủ dự án chốt.
- **Đồng bộ tin trên HĐ nháp** (`applyDraftTerm`) khi HĐ ghi A và tin rời A: vẫn chuyển hold sang bãi độc lập như `master`. Quyết định lần này chỉ cho phụ lục.
- **Ký HĐ gốc** sau khi chi nhánh giữ chỗ bị xóa (`activateReservationsForRequest`): vẫn kích hoạt với ID đã xóa, không kiểm giới hạn tin (như `master`).
- Sau khi đã rơi về bãi độc lập, phụ lục sau chỉ còn bị giới hạn nếu HĐ vẫn ghi chi nhánh đã xóa (xảy ra khi xóa giữa gửi và ký phụ lục không thuộc mục 3). Nếu không, theo quy tắc tin độc lập.
- Trong lúc phụ lục chờ ký, hồ sơ khác của cùng tin thấy cả dòng cũ (chi nhánh đã xóa) lẫn dòng `HELD` mới: có thể từ chối dư.
- Phụ lục rơi về bãi độc lập chỉ khóa lạc quan trên tin: hai lượt ghi đồng thời chưa được tuần tự hóa. Chưa kiểm đua xóa chi nhánh với ký.
- Cấp chỗ lần đầu khi HĐ ghi null, chưa có dòng nào và tin (đã gỡ) còn trỏ chi nhánh đã xóa: chưa nhận ra là rơi bãi.
- Chưa có test PostgreSQL riêng cho các truy vấn rơi bãi. Dữ liệu đã chuyển sai bãi trước bản sửa không được sửa lại.

## 2026-11-27 · Sửa theo [RECHECK-2](RECHECK-2.md)
- **Q15 không kèm đồng bộ.** HĐ ghi A, A xóa mềm, phụ lục chỉ đổi nội dung khác (vd tiền thuê): `targetBranch` là điểm chung của gửi, duyệt, ký; đọc chi nhánh đã ghi mà đã xóa thành null, dù đến từ snapshot lúc gửi, văn bản hay HĐ. Từ ngày hiệu lực chỗ chuyển sang bãi độc lập có kiểm giới hạn tin; phụ lục và HĐ (sau hiệu lực) ghi null. Snapshot lúc gửi giữ giá trị lúc đó nếu A xóa sau khi gửi.
- **Đếm đôi.** `holdInPool` ở bãi độc lập nhận diện phần đã giữ bằng `inListingPool` như phép kiểm sức chứa, nên `HELD` chỉ còn phần tăng.
- Đỏ trước: `ContractAmendmentServiceTest` 81 chạy, 6 fail (đúng 6 ca không đồng bộ của `keptBranchDeleted…`, nay chạy cả có và không đồng bộ); `ParkingReservationServiceTest` 33 chạy, 1 fail.
- Unit (chạy/fail/error/skip): common 21/0/0/0, user 136/0/0/22, storage 71/0/0/0, listing 585/0/0/94, payment 263/0/0/25, contract 1364/0/0/249, api 157/0/0/1.
- PostgreSQL: `ContractLifecyclePhase03PostgresTest` 31/0/0/0 trên DB trống trong container `hs-branch-verify-pg` mới, đã xóa. Hai class PostgreSQL còn lại không chạy lại.

Cập nhật mục "Còn mở" ở trên:
- Mục từ chối dư khi phụ lục chờ ký: đã sửa.
- Mục giới hạn sau khi rơi bãi: HĐ ghi chi nhánh đã xóa nay được ghi null khi phụ lục có hiệu lực, sau đó theo quy tắc tin độc lập. Còn ca HĐ ghi null nhưng giữ chỗ ở A, A xóa sau khi gửi: văn bản có thể vẫn ghi A.
- **Q13 chạm thêm ca (chưa sửa):** rơi bãi dựng lại chỗ đến ngày kết thúc trong điều khoản phụ lục. HĐ đã gia hạn mà điều khoản còn ngày cũ thì đoạn giữ chỗ của kỳ gia hạn bị nhả; nay phụ lục chỉ đổi tiền thuê cũng đi đường này khi chi nhánh đã ghi bị xóa. Suy từ mã, chưa có test.

## 2026-11-27 · Rơi bãi không được cắt chỗ kỳ gia hạn
- Lỗi so với `master` do vòng 2: phụ lục không đổi xe/thời hạn, chỉ rơi bãi vì chi nhánh đã ghi bị xóa, dựng lại chỗ đến ngày kết thúc trong điều khoản nên nhả đoạn kỳ gia hạn.
- Sửa: `fallbackOnly` (HĐ ghi chi nhánh đã xóa, không đổi xe/thời hạn, đích null). Gửi, duyệt, ký gọi `validateFallbackMove`: kiểm giới hạn tin cho từng đoạn `ACTIVE` từ ngày hiệu lực đến ngày kết thúc thật của đoạn. Ký gọi `moveToListingPool`: giữ nguyên số xe và ngày kết thúc, chỉ đổi sang bãi độc lập (đoạn vắt qua ngày hiệu lực được tách), nhả `HELD` cũ của phụ lục. Duyệt không tạo `HELD` vì dòng cũ đã tính vào bãi tin. Áp cho cả ca có đồng bộ tin (mục 3, A đã xóa).
- Q13 với phụ lục có đổi xe hoặc thời hạn: không đổi.
- Đỏ trước: `ContractAmendmentServiceTest` 93 chạy, 12 fail (12 ca mới: HĐ đã gia hạn, xóa A trước gửi/duyệt/ký, có và không đồng bộ; giữ đủ đoạn và kiểm sức chứa trong kỳ gia hạn).
- Unit (chạy/fail/error/skip): common 21/0/0/0, user 136/0/0/22, storage 71/0/0/0, listing 585/0/0/94, payment 263/0/0/25, contract 1376/0/0/249, api 157/0/0/1.
- PostgreSQL: `ContractLifecyclePhase03PostgresTest` 31/0/0/0 trên DB trống trong container `hs-branch-verify-pg` mới, đã xóa. Truy vấn rơi bãi chưa có test PostgreSQL riêng.

Còn mở (mục "Q13 chạm thêm ca" ở trên đã sửa):
- HĐ ghi null nhưng giữ chỗ ở A, A xóa sau khi gửi: văn bản phụ lục vẫn có thể ghi A, HĐ nhận A khi hiệu lực. **Không sửa:** cùng một giá trị đang vừa chọn bãi để kiểm giới hạn vừa ghi lên văn bản, tách ra không gọn trong vài dòng. Chỗ xe vẫn vào bãi độc lập có kiểm; phụ lục kế tiếp ghi lại null.
- Đổi bãi vì lý do khác (tin chuyển sang B, thay phụ lục đã đồng bộ trên HĐ ghi null) vẫn dựng lại đến ngày trong điều khoản (Q13).

## 2026-11-27 · Sửa lỗi chặn của [RECHECK-3](RECHECK-3.md)
- Thay phụ lục đã ký chờ hiệu lực, chỉ đổi nội dung, bật `refreshParkingPool` nên dựng lại chỗ đến ngày trong điều khoản và mất đoạn kỳ gia hạn.
- Sửa tại `parkingChanged` (chung cho gửi, duyệt, ký): không đổi xe/thời hạn, từ ngày hiệu lực đang có chỗ và mọi đoạn đã ở đúng bãi đích (`holdsOnlyIn`) thì không đụng bãi xe, dù lý do dựng lại là gì. Lúc ký nhả `HELD` của phụ lục đã tạo khi duyệt để dựng lại (kể cả duyệt trên `master`). Đổi xe, thời hạn hoặc đổi bãi thật: như trước. Không giữ chỗ nào: như trước.
- Test đỏ của Codex `replacingAListingSyncWithoutVehicleOrTermChangesKeepsTheRenewedParkingInTheLiveBranch` xanh, không sửa. `ContractAmendmentServiceTest` 94/94, `ContractLifecycleServiceTest` 56/56, `ParkingReservationServiceTest` 35/35.
- Unit (chạy/fail/error/skip): common 21/0/0/0, user 136/0/0/22, storage 71/0/0/0, listing 587/0/0/94, payment 263/0/0/25, contract 1377/0/0/249, api 157/0/0/1.
- PostgreSQL: `ContractLifecyclePhase03PostgresTest` 31/0/0/0 trên DB trống trong container `hs-branch-verify-pg` mới, đã xóa.
- Thay cho dòng "Đổi bãi vì lý do khác…" ở trên: dựng lại đến ngày trong điều khoản (Q13) nay chỉ còn khi đổi xe/thời hạn hoặc bãi đích khác bãi đang giữ (gồm HĐ ghi null có chỗ ở chi nhánh đã xóa khi thay phụ lục đã đồng bộ).
