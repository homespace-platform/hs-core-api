# Nhật ký P2-WEB-01 (Sonnet)

Mốc: hs-web-app @6dff15b + thay đổi P1/P2 chưa commit. Không đụng `app/dashboard/properties/new/**`, `types/listing.type.ts`, `lib/listing-room-link.ts` (agent P2-WEB-02). Chưa viết test (Codex làm).

## Tiến độ
- [x] 1. types, lib, service
- [x] 2. components
- [x] 3. nối vào sơ đồ và trang chi tiết (cảnh báo trước khi lưu bỏ phòng có liên kết, 409 khi lưu)
- [x] 4. xóa chi nhánh hiện lý do của server (2 trang)
- [x] 5. kiểm tra

Bước kế: Codex viết `tests/branch-assignment.test.cjs` (P2-TEST-01). Sau P2-BE-03: nối thật, đối chiếu giả định bên dưới, thử tay.

## File
Mới: `types/branch-assignment.type.ts`, `lib/branch-assignment.ts`, `services/branch-assignment.service.ts`, `components/branch/{BranchMap,RoomPanel,UnplacedTray,CandidatePicker,linkUi}.tsx`.
Sửa: `components/branch/{BranchLayoutEditor,LayoutBoard,LayoutDialogs}.tsx`, `app/dashboard/branches/page.tsx`, `app/dashboard/branches/[id]/page.tsx`.

## Hàm thuần để test
- `lib/branch-assignment.ts`: `listingStatusLabel`, `contractStatusLabel`, `formatDay`, `newRoomListingPath`, `listingViewPath`, `rentalRequestPath`, `itemPath`, `parseAssignments`, `parseCandidates`, `pageCount`, `emptyRoom`, `roomOptions`, `sortContracts`, `roomLines`, `lostAttachedRooms`, `permissionBlock`, `unsavedBlock`, `candidateState`. Hằng: `PAGE_SIZE=20`, `MANUAL_STATUS_LABEL`.
- `components/branch/linkUi.tsx`: `linkError(error, fallback)`.
- `services/branch-assignment.service.ts` (mock `@/lib/axios-client`): `getAssignments`, `getCandidates`, `linkListing`, `unlinkListing`, `putContract`, `removeContract`, `setManualStatus`.

## Giả định request/response (dưới `/api/v1/branches/{branchId}`, envelope `result`)
- `GET /assignments?page=0&size=20` → `{rooms:[{roomId,manualStatus,listings:[{id,title,operationalStatus}],contracts:[{id,status,startDate,endDateExclusive,tenantName}],holds:[{rentalRequestId,moveInDate,tenantName}]}],unplaced:{items:[{kind,id,label}],page,size,totalElements}}`.
  - `rooms` không phân trang theo `page` (page chỉ áp cho khay). Phòng không có trong `rooms` coi là NONE và không có liên kết. `manualStatus`/mảng thiếu đọc là NONE/rỗng. Sai shape → coi như không đọc được.
  - `page` bắt đầu từ 0; `page,size,totalElements` là số. Ngày dạng `yyyy-MM-dd...`.
  - `status` là ContractStatus; `operationalStatus` là ListingStatus.
- `GET /assignment-candidates?kind=LISTING|CONTRACT&page&size=20` → `{items:[{id,label,roomId}],page,size,totalElements}`. `roomId` thiếu = null. HĐ chưa gắn và HĐ ở khay đều có roomId null (web chỉ nhận biết "đã trong khay" với trang khay đang hiện; gửi lại là vô hại, giả định PUT cùng giá trị trả 200).
- `PUT|DELETE /rooms/{roomId}/listings/{listingId}` không body. Web không chuyển tin trực tiếp: phải gỡ rồi gắn.
- `PUT /contracts/{contractId}` `{"roomId": uuid|null}` (luôn gửi trường); `DELETE /contracts/{contractId}`.
- `PUT /rooms/{roomId}/manual-status` `{"status":"NONE"|"EXTERNAL_RENT"|"REPAIR"}`.
- Web không đọc `result` của lệnh ghi; sau mọi lệnh ghi (thành công hay lỗi) đọc lại `/assignments`.
- Lỗi: `message` (hoặc `errors[]`) là câu tiếng Việt hiện nguyên văn; không có thì hiện câu chung kèm mã HTTP.
- `PUT /layout` trả 409: web GET lại. Revision bằng `expectedRevision` → hiện `message` của server, giữ nháp, vẫn lưu lại được. Revision khác → xử lý stale như P1. Vì vậy server không được tăng revision khi từ chối. Gắn/gỡ giả định không tăng revision sơ đồ (nếu tăng, nháp đang mở sẽ thành stale).
- `DELETE /branches/{id}`: hiện `message` của server; web không còn chặn theo số phòng/tin.

## Hành vi / quyết định web
- `BranchMap` truyền prop tùy chọn `links` vào `BranchLayoutEditor`. GET `/assignments` lỗi (kể cả chưa có endpoint) → sơ đồ chạy như P1, khay hiện lý do và nút Thử lại; không có menu gắn.
- Bấm ô phòng: phần liên kết ở trên, phần sửa ô ở dưới. Không có quyền sửa vẫn mở được ô để xem và điều hướng; nút ghi bị khóa.
- Có thay đổi sơ đồ chưa lưu: khóa mọi lệnh ghi trong ô phòng và nút đăng tin; ở khay chỉ khóa việc chọn phòng (thêm vào khay, gỡ khỏi chi nhánh vẫn được). Phòng mới chưa có id: nhắc lưu trước.
- Cảnh báo bỏ phòng có liên kết đặt ở lúc bấm Lưu (bao cả xóa ô/dãy/tầng, đổi thành ô trống, chọn mẫu khác). Chỉ tính hợp đồng, giữ chỗ, tin; không tính cờ phòng.
- Gỡ không hỏi xác nhận (gắn lại được). Khay ẩn ở bước thiết lập `setup=1`.

## Khác với thiết kế / giao việc
- Không đổi API. Thêm xử lý 409 khi lưu sơ đồ ở trên (P1 trước đây coi mọi 409 là stale).
- Tin ở khay chỉ có Mở và Xếp phòng (không có gỡ: tin thuộc chi nhánh theo trang sửa tin).
- Nút xóa chi nhánh đổi sang `useActionDialog` + toast; các `alert` cũ khác ở 2 trang giữ nguyên.

## Kết quả kiểm
- `node --test --test-isolation=none tests/branch-layout.test.cjs`: 21/21 đạt.
- `tsc --noEmit --incremental false`: chỉ còn 1 lỗi baseline `.next/types/validator.ts` TS2307.
- eslint `app/dashboard/branches components/branch lib/branch-assignment.ts services/branch-assignment.service.ts types/branch-assignment.type.ts`: sạch.

## Chưa kiểm
- Chưa chạy dev server/trình duyệt: bố cục 360px, dark mode, focus/bàn phím, hộp xác nhận sau khi đóng hộp thoại ô (focus có thể quay về ô thay vì nút xác nhận), toast.
- Chưa nối backend (chưa có). Hàm thuần mới chưa có test.
- `/dashboard/properties/new?branchId&roomId` do P2-WEB-02 xử lý, chưa kiểm chung.
- Nếu `/assignments` không đọc được thì không có cảnh báo trước khi lưu bỏ phòng; server vẫn là nơi chặn.
