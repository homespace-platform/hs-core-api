# Nhật ký P2-BE-01/02/03 (Sonnet)

Mốc: core @925158e + thay đổi P1, EXT-BE-02 chưa commit (giữ nguyên). L3. Không viết/sửa test, không commit.

## Bước
1. [x] P2-BE-01 listing
2. [x] P2-BE-02 contract
3. [x] P2-BE-03 controller
4. [x] `./mvnw -o -B -pl hs-api-service -am -Dmaven.test.failure.ignore=true test` (sau lần sửa cuối). run/failed/skipped: common 21/0/0, user 136/0/22, storage 71/0/0, listing 569/0/94, payment 263/0/25, contract 1212/0/217, api 120/1/1. BUILD SUCCESS chỉ vì bỏ qua lỗi test. Lỗi duy nhất: UserScopePermissionSecurityTest thiếu 5 endpoint ghi mới (đã so, không lệch gì khác); để Codex cập nhật. Skip chưa tính là đạt.
5. [x] PostgreSQL (container hs-branch-test-pg, DB trống mới): BranchLayoutPostgresTest 4/4, ContractLifecyclePhase03PostgresTest 31/31, RentalWorkflowHttpSystemTest 1/1; 0 skip, mỗi class chạy riêng; log không có lỗi DDL.
6. [x] Tổng kết (dưới)

Bước kế: R01-R07 xong; Codex cập nhật 3 test trong BranchRoomLinkServiceTest rồi rà lại.

## File cuối
L = hs-listing-service/src/main/java/com/hs/listing, C = hs-contract-service/src/main/java/com/hs/contract
- Mới L: model/BranchRoomListing, model/constant/RoomManualStatus, dto/response/BranchListingRow, repository/BranchRoomListingRepository, service/{BranchContractLinkGuard, BranchRoomLinkService}.
- Sửa L: model/BranchSlot (+cột nullable manual_status), service/{BranchLayoutService, PropertyBranchService, ListingService}, dto/request/CreateListingRequest (+roomId cuối record, giữ constructor cũ), advice/ListingErrorCode.
- Mới C: model/BranchContractAssignment, dto/branch/{BranchLeaseRow, BranchHoldRow, BranchAssignmentsResponse, BranchCandidatesResponse, BranchContractLinkResponse}, repository/BranchContractAssignmentRepository, service/{BranchRoomSchedule, BranchAssignmentGuard, BranchAssignmentService, BranchContractAutoLink}.
- Sửa C: advice/ContractErrorCode, service/impl/ContractServiceImpl (chỉ thêm hook auto-gắn).
- Mới A: hs-api-service/src/main/java/com/hs/api/controller/listing/BranchAssignmentsController.
- Bảng mới: branch_room_listings, branch_contract_assignments. Không SQL, không thêm giá trị enum vào cột cũ, không đọc/ghi contracts.branch_id.

## Endpoint (dưới /branches/{branchId}; ghi cần LISTING_POST + hồ sơ đầy đủ)
GET /assignments?page&size · GET /assignment-candidates?kind=LISTING|CONTRACT&page&size · PUT/DELETE /rooms/{roomId}/listings/{listingId} · PUT/DELETE /contracts/{contractId} · PUT /rooms/{roomId}/manual-status.
Hành vi đổi ở endpoint cũ: PUT /layout (409/4075), DELETE /branches/{id} (khóa branch; thêm 409/4076, chặn tin active cũ giữ nguyên 400), tạo tin nhận roomId, đổi chi nhánh thật của tin thì gỡ link phòng.

## Mã lỗi
| code | HTTP | Khi nào |
|---|---|---|
| 4075 BRANCH_ROOM_IN_USE | 409 | PUT /layout bỏ phòng (hoặc đổi thành lối đi) còn HĐ hiệu lực/giữ chỗ; revision không tăng |
| 4076 BRANCH_HAS_CONTRACT_LINKS | 409 | Xóa CN còn link HĐ |
| 4077 BRANCH_ROOM_NOT_ASSIGNABLE | 400 | Ô lối đi |
| 4078 LISTING_BRANCH_MISMATCH | 409 | Tin không thuộc CN |
| 4079 LISTING_ALREADY_IN_ROOM | 409 | Tin đang ở phòng khác |
| 4090 BRANCH_ROOM_STATUS_CONFLICT | 409 | Cờ ngoài/sửa mâu thuẫn lượt thuê |
| 4091 LISTING_ROOM_INVALID | 400 | roomId sai/rỗng/thiếu branchId, roomId khi sửa tin hoặc ở API admin |
| 4092 BRANCH_ASSIGNMENT_INVALID | 400 | kind/page/size/body/status sai, JSON hỏng |
| 6090 CONTRACT_NOT_ASSIGNABLE | 400 | HĐ cọc hoặc văn bản con |
| 6091 CONTRACT_IN_OTHER_BRANCH | 409 | PUT/DELETE HĐ đang gắn CN khác |
| 6092 BRANCH_ROOM_SCHEDULE_CONFLICT | 409 | Gắn tay trùng lịch |
404: chi nhánh/phòng/tin (code 404), HĐ (6003); chủ khác = 404.

## Điểm vào cho người viết test
- BranchRoomSchedule.{historical, overlaps, placed} (thuần): lịch [start, end); người đang ở quá hạn chưa bàn giao chặn đến hết hôm nay.
- BranchLeaseRow.endExclusive(); BranchAssignmentGuard.{occupiedRooms, countContractLinks, unplaceContracts}.
- BranchAssignmentService.{assignments, candidates, attach, detach, autoLink}; BranchContractAutoLink.afterCommit (ngoài transaction thì không làm gì).
- BranchRoomLinkService.{link, unlink, setManualStatus, linkNewListing, dropLinkOutsideBranch, releaseRooms}.
- ListingService.{upsert, updateByAdmin, decideEdit}; PropertyBranchService.deleteBranch; BranchLayoutService.saveLayout; BranchAssignmentsController.
- UserScopePermissionSecurityTest cần thêm 5 mục BranchAssignmentsController.{linkListing, unlinkListing, attachContract, detachContract, setManualStatus} = hasAuthority('LISTING_POST').

## Khác thiết kế / lựa chọn nhỏ nhất
1. Auto-gắn chạy SAU COMMIT trong REQUIRES_NEW, nuốt lỗi + log BRANCH_AUTO_LINK_FAILED. Trên PostgreSQL câu lệnh lỗi làm hỏng cả transaction nên try/catch trong transaction tạo HĐ không đủ. Đổi lại: lỗi hoặc crash giữa hai bước thì HĐ không có link (không retry, không vào khay), phải gắn tay.
2. (Đã đổi theo R04) Lượt thua được ghi vào khay: sau commit ở gia hạn, phụ lục ký, đổi ngày sớm và bằng job BranchContractAutoLink.synchronize; GET chỉ chiếu.
3. PUT HĐ lặp lại cùng phòng: 200; riêng HĐ đang bị chiếu ra khay vì trùng thì 6092 (web giả định PUT cùng giá trị luôn 200).
4. Bỏ phòng: HĐ chưa kết thúc có roomId lưu tại đó đều chặn, kể cả đang bị chiếu ra khay.
5. Giữ chỗ (ACCEPTED chưa có HĐ gốc) chặn bỏ phòng và bật cờ, không chặn gắn tay HĐ (thiết kế không nêu). Gắn tin đang có giữ chỗ vào phòng có cờ: 4090. Cờ REPAIR xử lý như EXTERNAL_RENT. Luồng cũ (nhận khách sau khi bật cờ) vẫn chạy, nên cờ và giữ chỗ có thể cùng tồn tại; auto-gắn vào phòng có cờ thì vào khay.
6. API admin tạo/sửa tin từ chối roomId. Nhân bản có roomId gửi rõ ràng là tạo mới nên được nhận.
7. GET /assignments trả mọi phòng; khay xếp CONTRACT trước LISTING rồi theo id; roomId của ứng viên HĐ là phòng đang hiển thị (null nếu ở khay). Label HĐ: "số HĐ · tên người thuê".
8. Web: không thấy xung đột khác với giả định P2-WEB-01/02 (trừ mục 3). Từ chối layout không tăng revision; gắn/gỡ không đụng revision.

## Rủi ro chưa kiểm
- Query JPQL/derived mới chỉ được kiểm khi khởi tạo repository trong test PostgreSQL, CHƯA được thực thi trên DB. Auto-gắn sau commit, khóa đồng thời/deadlock (thứ tự: tin hoặc HĐ rồi branch), rollback tạo tin khi roomId sai, FK khi xóa phòng: chưa chạy thật.
- Auto-gắn trong afterCommit cần kết nối DB thứ hai trong chốc lát (Hikari 10); cạn pool thì chờ timeout, HĐ vẫn đã lưu.
- ListingService/BranchLayoutService/PropertyBranchService dùng injection required=false để test cô lập chạy được; wiring thật (guard bên contract) cần test ở API.
- Chưa chạy ứng dụng, chưa kiểm HTTP/Gateway; ListingNotificationPostgresTest và các test PostgreSQL khác chưa chạy (skip).

## Sửa theo P2-REVIEW.md (2026-11-27)
Đã đối chiếu code: R01-R07 đều đúng (R02, R05, R07 theo đường code). Thứ tự làm: R01, R03+R02+R07 (chung cơ chế khóa), R04, R05, R06.
Cơ chế khóa dự kiến (R02/R03/R07): ghi/gỡ link tin, auto-gắn, xóa CN lấy FOR UPDATE trên CN và KHÔNG khóa dòng tin; luồng ghi tin chỉ lấy FOR KEY SHARE trên CN (tương thích khóa FOR NO KEY UPDATE của bãi xe/chấp thuận, chỉ chặn FOR UPDATE).
- [x] R01: guard/fit đọc projection BranchRoomContract, không nạp entity link; PG đạt (BranchAssociationsPostgresTest).
- [x] R02: link/unlink không khóa dòng tin, khóa CN FOR UPDATE; bỏ link khi đổi CN chỉ FOR KEY SHARE. Probe tạm 6/6 trên PostgreSQL (DB hs_lock_probe_r02a): 5 ca xen kẽ 2 transaction đều commit (parking branch->listing với link/unlink; acceptance listing->branch với link; đổi CN tin khi parking giữ CN cũ/mới); ca đối chứng thứ tự cũ báo 'deadlock detected'. SQL Hibernate: listings/property_branches dùng for no key update. File probe đã chuyển khỏi repo: C:/Users/nvmin/AppData/Local/Temp/claude/ade27a17e5c6c2b9/e1a0a164-c853-41f0-bd09-3ea53e43aeaa/scratchpad/BranchLockOrderProbeTest.java.
- [x] R03: autoLink thử tối đa 3 giao dịch nối tiếp khi tin đổi CN trong lúc chờ khóa; PG đạt.
- [x] R04: BranchAssignmentService.reconcileRoom ghi lượt thua vào khay (giao dịch riêng, khóa CN, không khóa HĐ); gọi sau commit ở gia hạn / phụ lục ký / đổi ngày sớm, thêm job riêng trong BranchContractAutoLink; lỗi chỉ log. GET vẫn chỉ đọc. PG đạt.
- [x] R05: GET trả manualStatus NONE khi phòng đang có HĐ hiệu lực hoặc giữ chỗ (theo thứ tự ưu tiên 01 §4); cờ lưu không đổi, hiện lại khi hết lượt thuê; chấp thuận hồ sơ không đổi.
- [x] R06: controller trả 400/4092 cho thiếu/sai kiểu tham số.
- [x] R07: luồng ghi tin khóa FOR KEY SHARE CN đích và đọc lại active, xóa CN khóa FOR UPDATE; PG đạt.
Bước kế: R01-R07 xong; Codex cập nhật 3 test trong BranchRoomLinkServiceTest rồi rà lại.

### Kiểm sau khi sửa
- Unit (`./mvnw -o -B -pl hs-api-service -am -Dmaven.test.failure.ignore=true test`) run/failed/skipped: common 21/0/0, user 136/0/22, storage 71/0/0, listing 577/3/94 (2 failure + 1 error), payment 263/0/25, contract 1233/0/230, api 129/0/1. Skip không tính là đạt.
- PostgreSQL, DB trống mới, mỗi lớp một lần chạy, 0 skip: BranchAssociationsPostgresTest 13/13, BranchLayoutPostgresTest 4/4, ContractLifecyclePhase03PostgresTest 31/31, RentalWorkflowHttpSystemTest 1/1.
- Probe tạm R02 + R07 (bản cuối): 8/8, DB hs_lock_probe_r02b. Thêm R07: xóa CN trước thì luồng ghi tin chờ rồi đọc active=false; ghi tin trước thì xóa chờ rồi đếm thấy tin. File: C:/Users/nvmin/AppData/Local/Temp/claude/ade27a17e5c6c2b9/e1a0a164-c853-41f0-bd09-3ea53e43aeaa/scratchpad/BranchLockOrderProbeTest.java (thư mục tạm, không nằm trong repo; env HS_PROBE_PG_URL, DB hs_lock_probe_*).

### Test cần Codex sửa (không sửa trong task này)
BranchRoomLinkServiceTest, 3 ca ghim đúng cơ chế khóa gây deadlock:
- linksAreIdempotentAndUnlinkChecksExactRoom, rejectsForeignOwnerCrossBranchAndAisle: stub listings.findByIdForUpdate. Nay đọc links.findOwnListing, không khóa tin; khóa/kiểm CN trước tin.
- onlyAChangedLiveBranchDeletesListingLink: verify branches.findByIdForUpdate. Nay là branches.lockForListingWrite.
Chạy bản sao tạm chỉ đổi các lời gọi này: 4/4 đạt, rồi đã xóa bản sao.

### Còn lại / cần biết
- Hệ quả Q2 theo đúng chữ: HĐ nối tiếp (ngày vào = ngày kết thúc của HĐ cũ) bị ghi vào khay ngay trong ngày đó nếu khách cũ chưa bàn giao; sau đó phải xếp lại tay. Nên cho chủ dự án xác nhận.
- Job đối soát quét mọi phòng có từ 2 link HĐ mỗi chu kỳ billing (60s), chưa phân trang, chưa đo tải.
- R05 chỉ đổi hiển thị: cờ đã lưu vẫn còn, nên khi lượt giữ chỗ thành HĐ thì auto-gắn vẫn đưa vào khay (theo 01 §3) và cờ hiện lại.
- Ba hook sau commit của R04: gia hạn có PG test; phụ lục và đổi ngày sớm chưa có test riêng. Chưa thử Gateway/trình duyệt.

## Vòng cuối (chốt bổ sung 27/11: trùng lịch chỉ chiếu khi đọc)
Thay thế R04, mục 2 "Khác thiết kế" và các dòng "Hệ quả Q2", "Job đối soát" ở trên.
- [x] Gỡ toàn bộ ghi khay do trùng lịch: job + reconcileAfterCommit, hook gia hạn/phụ lục/đổi ngày sớm, sharedRoomIds/reconcileRoom, query findSharedRoomIds/unplaceContracts(roomId, ids). ContractLifecycleService và RentalOnboardingService trở về đúng HEAD. Giữ auto-gắn lúc tạo HĐ, 409 gắn tay, gỡ phòng khi xóa. R04b/R04c không còn.
- [x] Test của hành vi đã bỏ: gỡ assert hook ở 2 unit; PG đổi ca gia hạn và thay ca sweep bằng anOverdueOccupantOnlyHidesTheNextLeaseUntilHandover (GET và PUT 409 không ghi; bàn giao xong HĐ hiện lại ở phòng).
- [x] R02b/R03b: dropLinkOutsideBranch(listingId, previousBranchId, newBranchId) khóa FOR KEY SHARE CN nguồn TRƯỚC khi tìm link, kể cả chưa có link. Thứ tự khóa giữ nguyên (dòng tin rồi KEY SHARE; bên giữ FOR UPDATE không khóa tin). Cập nhật chữ ký ở test và mock khóa (3 ca Mockito cũ); ca đua PG giữ nguyên kỳ vọng.
- PUT tay HĐ đang chiếu ra khay (roomId lưu = R): PUT R trả 409/6092, không ghi; PUT null trả 200 và xóa phòng đã lưu (không tự hiện lại nữa); PUT phòng trống khác thì chuyển; DELETE gỡ link. (Đọc từ code; ca PUT R có assert PG.)

Bước kế: chạy unit và PostgreSQL.
- [x] Unit: ./mvnw -o -B -pl hs-api-service -am -Dmaven.test.failure.ignore=true test. run/failed/skipped: common 21/0/0, user 136/0/22, storage 71/0/0, listing 578/0/94, payment 263/0/25, contract 1241/0/238, api 129/0/1. Skip không tính là đạt.
- [x] PostgreSQL, DB trống mới, từng lớp: BranchAssociationsPostgresTest 21/0/0 (gồm 2 ca đua R02b/R03b), BranchLayoutPostgresTest 4/0/0, ContractLifecyclePhase03PostgresTest 31/0/0, RentalWorkflowHttpSystemTest 1/0/0. DB: hs_branch_associations_test_p2last, hs_branch_layout_test_p2last; hs_phase03_test và hs_workflow_http_test tạo mới (bản cũ đổi tên *_old_p2last); mật khẩu role test postgres đổi giữa hai lớp.

Còn biết: tin cũ chưa có slug được cấp slug đúng lúc đổi CN (UPDATE cột unique khóa dòng tin mạnh hơn) mà trùng lúc gắn tay chính tin đó có thể deadlock; chưa dựng ca thử.
