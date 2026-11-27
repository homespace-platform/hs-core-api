# P2-WEB-02 · Đăng tin từ phòng (Sonnet)

2026-11-26. File giữ: `W/app/dashboard/properties/new/**`, `W/types/listing.type.ts`, `W/lib/listing-room-link.ts`. Không đụng `branches/**`, `components/branch/**`, `services/branch-assignment*`. Không viết test (Codex).

## Tiến độ
- [x] Đọc thiết kế, code editor.
- [x] `lib/listing-room-link.ts` + `roomId?` trong `CreateListingRequest`.
- [x] `new/components/RoomLinkBanner.tsx` + nối vào `ListingEditor.tsx`. `buildListingPayload.ts` không đổi.
- [x] Verify: `node --test` 3 file (13/13 đạt); `tsc --noEmit --incremental false` chỉ còn 1 lỗi nền `.next/types/validator.ts`; eslint 4 file đã sửa không in lỗi.

## Hành vi (cho Codex viết test)
- Chỉ bật khi tạo mới, có đủ `branchId` + `roomId`, không có `duplicateFrom`. Không có `roomId`: payload giữ nguyên, không gọi GET layout.
- `withRoomId` không thêm khi payload có `id` hoặc `duplicateSourceListingId`.
- Đổi/bỏ chi nhánh: bỏ liên kết hẳn (chọn lại chi nhánh cũ cũng không khôi phục), có toast.
- Trước khi upload/tạo (cả lưu nháp): đọc lại GET layout. Mất phòng, ô thành lối đi hoặc đọc lỗi thì không tạo. Banner cho chọn lại phòng, thử lại, hoặc chủ động "Đăng tin không gắn phòng".
- Chi nhánh của phòng chưa áp dụng được (không tải được danh sách): chặn lưu.
- Server từ chối: hiện message server như cũ, giữ `roomId`, đọc lại layout để banner phản ánh. Không tự retry, không tự bỏ `roomId`. Chưa dựa vào mã lỗi riêng vì core chưa có.
- Q3: không đưa mã phòng vào tiêu đề hay nội dung tin.

## Chưa kiểm / chờ
- Chưa xem trên trình duyệt, chưa nối API thật. Core chưa nhận `roomId` (chờ P2-BE-01); nếu backend bỏ qua trường lạ thì tin được tạo mà không gắn phòng. Chỉ phát hành web sau hoặc cùng P2-BE-01.
