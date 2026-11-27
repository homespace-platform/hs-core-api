# Nhật ký P1-BE-01 / P1-BE-02 (Sonnet)

Mốc: core @925158e (chỉ docs/branch chưa theo dõi lúc bắt đầu).

## File dự kiến

- L = hs-listing-service/src/main/java/com/hs/listing
- L/model: BranchLayout, BranchFloor, BranchRow, BranchSlot (mới)
- L/repository: 4 repository tương ứng (mới)
- L/dto/request/SaveBranchLayoutRequest, L/dto/response/BranchLayoutResponse (mới)
- L/service/BranchLayoutService (mới)
- L/advice/ListingErrorCode (thêm mã lỗi)
- hs-api-service/src/main/java/com/hs/api/controller/listing/BranchLayoutController (mới)

## Bước

1. [x] P1-BE-01: mã lỗi 4073/4074, 4 entity, 4 repository, 2 DTO, BranchLayoutService (đúng danh sách trên)
2. [x] P1-BE-02: BranchLayoutController
3. [x] Compile + PropertyBranchServiceTest
4. [x] Tổng kết cuối file

Bước kế: P1-BE xong, chuyển Codex làm P1-TEST-01.

## Tổng kết

### File cuối

- L/model/{BranchLayout,BranchFloor,BranchRow,BranchSlot}.java (mới)
- L/repository/{BranchLayout,BranchFloor,BranchRow,BranchSlot}Repository.java (mới)
- L/dto/request/SaveBranchLayoutRequest.java, L/dto/response/BranchLayoutResponse.java (mới, record)
- L/service/BranchLayoutService.java (mới)
- L/advice/ListingErrorCode.java (thêm 2 mã)
- hs-api-service/src/main/java/com/hs/api/controller/listing/BranchLayoutController.java (mới)

### Điểm vào cho người viết test

- `BranchLayoutService.getLayout(branchId, ownerId)`, `saveLayout(branchId, ownerId, SaveBranchLayoutRequest)`.
- `BranchLayoutController`: GET và PUT `/branches/{branchId}/layout`.
- Request: `SaveBranchLayoutRequest(Long expectedRevision, List<Floor>)`, record lồng `Floor/Row/Slot`. Response: `BranchLayoutResponse(long revision, List<Floor>)`.
- GET dùng `findByIdAndOwnerIdAndActiveTrue`; PUT dùng `findByIdForUpdate` rồi lọc owner.

### Mã lỗi

| HTTP | code | Khi nào |
|---|---|---|
| 400 | 4073 `BRANCH_LAYOUT_INVALID` | Payload sai (thông điệp theo từng lỗi; mọi lỗi id dùng chung một thông điệp), JSON hỏng/sai kiểu |
| 409 | 4074 `BRANCH_LAYOUT_STALE` | `expectedRevision` lệch |
| 404 | 404 | Không thấy chi nhánh trong phạm vi chủ nhà (giống endpoint chi nhánh cũ) |
| 401 / 403 | 1002 / 1009, 1010 | Thiếu danh tính / thiếu LISTING_POST, hồ sơ chưa đủ |

Client nhận biết stale bằng **`code == 4074`**, không chỉ dựa vào HTTP 409 (P2 sẽ có thêm 409 khác với mã riêng). Gặp 4074: giữ bản nháp, GET lại rồi áp lại.

### Thứ tự flush Hibernate (đổi chéo mã)

Hibernate flush theo thứ tự insert, update, delete (không theo thứ tự gọi giữa các loại). PostgreSQL kiểm unique `(branch_id, normalized_room_code)` ngay từng câu lệnh (constraint không deferrable). Nên service làm:

1. Xóa slot bị bỏ và đặt `normalized_room_code = null` cho slot đổi mã, rồi flush (NULL không va unique).
2. Ghi tầng/dãy/ô mới (persist cha trước con) và giá trị cuối cho node cũ, rồi flush. Giữ nguyên id.
3. Xóa dãy cũ, flush, rồi xóa tầng cũ (sau khi node chuyển đi đã đổi cha trong DB, tránh va FK).

Id do `GenerationType.UUID` cấp nên `save` node mới là `persist`. Phần này **chưa chạy trên DB thật**; test mock không chứng minh được, cần test PostgreSQL (đổi chéo mã; dùng lại mã của ô vừa xóa; chuyển dãy/ô ra khỏi cha bị xóa).

### Lựa chọn khi thiết kế chưa rõ / khác thiết kế

- Thứ tự kiểm: chi nhánh/owner (404), `expectedRevision` thiếu (400), revision lệch (409), rồi cây (400). Bản nháp cũ chứa id đã bị xóa vẫn nhận 409.
- Server chỉ buộc tổng thể ít nhất 1 tầng, 1 dãy, 1 phòng; **chấp nhận** tầng/dãy trống nằm cạnh (thiết kế chỉ nói bản nháp web được trống). `rows`, `slots` bắt buộc là mảng; `floors: []` bị 400 (chưa có cách xóa sơ đồ).
- `roomCode` thiếu hoặc null = ô trống; chuỗi rỗng/chỉ khoảng trắng = 400. Nhãn và mã được `strip()`, tối đa 255 ký tự. Chuẩn hóa: `toUpperCase(Locale.ROOT)`; không chuẩn hóa Unicode NFC/NFD.
- `revision` tăng ở mọi PUT thành công, kể cả cây không đổi.
- PUT có `@RequiresCompleteProfile` như các endpoint ghi chi nhánh cũ (`ProfileGateTest` bắt buộc), nên có thể trả 403/1010; bảng API trong 01 không nhắc.
- Controller tự bắt `HttpMessageNotReadableException` để trả 400/4073 (handler chung trả 500). Lỗi này xảy ra trước kiểm quyền/chủ sở hữu. Kiểu vô hướng sai bị từ chối (xem mục sửa P1-R03).
- GET chạy `readOnly` + `REPEATABLE_READ` để 4 câu đọc cùng một snapshot.
- Entity mới không kế thừa `BaseEntity` (thiết kế không có cột active/audit; node bị bỏ được xóa thật).
- "Đếm phòng thật" (P1-BE-01): shape API không có trường đếm nên không thêm; ô có `roomCode` là phòng. `totalUnits`, `activeListingsCount` giữ nguyên.
- Không sửa xóa chi nhánh: xóa mềm hiện tại giữ lại các row sơ đồ, GET/PUT sau đó trả 404. P1 không cần đổi (P2 mới cần khóa khi xóa).

### Lệnh đã chạy (gốc repo, bash)

- `./mvnw -o -q -pl hs-api-service -am compile`: đạt (exit 0).
- `./mvnw -o -pl hs-listing-service -am -Dtest=PropertyBranchServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`: 3 chạy, 0 lỗi, 0 skip, BUILD SUCCESS.

### Việc cho P1-TEST-01 và rủi ro chưa kiểm

- `UserScopePermissionSecurityTest`: mục `BranchLayoutController.saveLayout` đã có khi chạy lại sau P1-TEST-01, test đạt.
- Chưa khởi động ứng dụng: mapping JPA, derived query, tạo bảng/constraint theo `JPA_DDL_AUTO`, isolation, khóa đồng thời, flush và FK đều chưa kiểm trên PostgreSQL. Nếu môi trường không tự tạo schema thì 4 bảng mới chưa tồn tại.
- Chưa kiểm HTTP/Gateway.
- Chưa giới hạn số node trong một payload (thiết kế không nêu).

## Sửa P1-R03 (sau rà soát của Codex, P1-REVIEW.md)

- Xác nhận: binder mặc định ép số/boolean thành chuỗi, `"0"`/`0.9` thành revision nguyên, và thiếu `roomCode` thành null (phòng bị đổi thành lối đi).
- Sửa chỉ trong `L/dto/request/SaveBranchLayoutRequest.java`: deserializer chặt `StrictString` (id, label, roomCode) và `StrictLong` (expectedRevision); `@JsonProperty(required = true)` cho `roomCode`. Explicit `null` vẫn hợp lệ. Không đổi cấu hình Jackson chung. Lỗi đi qua handler sẵn có của controller: 400/4073, service không được gọi.
- 01 §2: PUT ghi `{id?,roomCode}` (roomCode bắt buộc, ô trống gửi `roomCode:null`). Câu "`roomCode:null` hoặc thiếu trong JSON đọc là ô trống" chỉ áp cho GET (response bỏ trường null do `NON_NULL` thì client vẫn đọc là ô trống), không áp cho PUT.
- Web (chỉ đọc, không sửa): `hs-web-app/lib/branch-layout.ts` `toSaveRequest` luôn gửi `roomCode` (null cho lối đi), `expectedRevision` kiểu số, label và mã là chuỗi, `id` chỉ gửi khi có. Phía đọc dùng `s.roomCode ?? null`.
- Không sửa test. Không đụng P1-R01, R02, R04 (web).
- Chạy: `./mvnw -o -pl hs-api-service -am "-Dtest=BranchLayoutServiceTest,BranchLayoutApiTest,BranchLayoutPostgresTest,UserScopePermissionSecurityTest,PropertyBranchServiceTest,BranchChargeMappingHelperTest,ParkingReservationServiceTest,ProfileGateTest" -Dsurefire.failIfNoSpecifiedTests=false test`: 75 chạy, 71 đạt, 0 lỗi, 4 skip (`BranchLayoutPostgresTest`, không đặt `HS_TEST_PG_*`). BUILD SUCCESS.
- Còn chưa kiểm: PostgreSQL thật (mapping, flush, khóa), HTTP qua Gateway.
