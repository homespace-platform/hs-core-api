# R2-PARKING · R-A, R-B, R-C (04-cau-hoi.md, lượt 2)
## Tiến độ
- [x] Đọc tài liệu
- [x] R-A: 2 test mới đỏ (giữ kỳ gia hạn; kiểm sức chứa kỳ gia hạn) -> xanh. Sửa `ContractAmendmentService.proposedEnd`: chỉ lấy ngày kết thúc trong điều khoản khi phụ lục đổi thời hạn, ngược lại `end(c)` (đã gồm gia hạn). Một chỗ dùng chung cho gửi/duyệt/ký. `master`: lấy ngày từ bản chụp cũ (lỗi có sẵn, #1 EXT-BE-03).
- [~] R-B: 4 test đỏ (PropertyBranchServiceTest 1, ParkingReservationServiceTest 3) -> xanh. `updateBranch` khóa chi nhánh (findByIdForUpdate) trước khi đọc/đếm. `activateReservationsForRequest` (ký HĐ gốc, 2 caller) và `validateRenewalHold`/`activateRenewalHold` khóa + đọc lại chi nhánh (`deletedUnderLock`, thứ tự id); đã xóa -> bãi độc lập, kiểm sức chứa tin (`validateStandalone`, khóa lạc quan tin như lockSelectedPool vì Listing khóa trước Rental). Phụ lục đã dùng lockSelectedPool từ trước. `master`: không khóa, kích hoạt nguyên branchId. Còn: PG race test.
- [x] R-C: test đỏ (A->B, A còn hoạt động: bị kiểm sức chứa B) -> xanh. `ContractAmendmentService.keepsRecordedBranch` (chung gửi/duyệt/ký) giữ A khi tin sang B nếu A còn, không đổi xe/thời hạn. A đã xóa: giữ như `master` (sang B, kiểm sức chứa B; 2 test đối chứng xanh từ đầu). Chỉ đường phụ lục; đồng bộ nháp (`ContractListingSync`) giữ như `master`. `master`: chuyển chỗ sang B. PG race test `BranchCapacityRacePostgresTest` đã viết, chưa chạy.
- [ ] Unit, PostgreSQL

## Kết quả
- Test cũ đổi: không có. Test mới: ContractAmendmentServiceTest +5 (R-A 2, R-C 3 gồm 2 ca đối chứng A đã xóa xanh từ đầu), ParkingReservationServiceTest +3, PropertyBranchServiceTest +1, `BranchCapacityRacePostgresTest` (opt-in `HS_TEST_PG_URL`, DB `hs_r2_parking_test_*`; đỏ khi bỏ dòng khóa, xanh khi có).
- Unit (chạy/fail/error/skip): common 21/0/0/0, user 136/0/0/22, storage 71/0/0/0, listing 592/0/0/95, payment 263/0/0/25, contract 1382/0/0/249, api 157/0/0/1. BUILD SUCCESS.
- PostgreSQL (container `hs-r2-parking-pg` mới, đã xóa; mỗi class một DB trống): BranchCapacityRacePostgresTest 1/0/0/0, ContractLifecyclePhase03PostgresTest 31/0/0/0, RentalWorkflowHttpSystemTest 1/0/0/0.
- Còn mở: đồng bộ nháp tin A->B vẫn như `master`; đường khóa thật của ký HĐ gốc chưa có test PostgreSQL riêng (chỉ unit).
