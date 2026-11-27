# WEB-FIX: kiểm các lỗi rà soát mobile trên web

Repo `hs-web-app`, nhánh `feature/branch-monitoring`. Nguồn: `hs-mobile-app/docs/chi-nhanh/REVIEW.md` mục 1–5. Không commit.

## Tiến độ

Đã xong cả 5 mục: 1, 2 có lỗi và đã sửa; 3, 4, 5 không có trên web.

### 1. Tải lại sau 409 xoá nháp trước khi có sơ đồ mới: CÓ, đã sửa
- Bằng chứng (trước sửa): `components/branch/BranchLayoutEditor.tsx` `reload()` gọi `forgetDraft()` rồi mới `fetchLayout()`; nút "Thử lại" chỉ đọc sơ đồ.
- Test đỏ rồi xanh: `tests/branch-layout.test.cjs` "a reload after a 409 keeps the stored draft until the new map has been loaded".
- Sửa: `reload()` chỉ đặt cờ `discardOnLoad`; `fetchLayout` xoá nháp sau khi GET thành công (kể cả qua "Thử lại"). Rời trang khi GET còn lỗi thì nháp còn và được mời khôi phục.
- `branch-layout.test.cjs`: 22/22 đạt.

### 2. Lưu bỏ phòng khi chưa đọc được liên kết: CÓ, đã sửa
- Bằng chứng (trước sửa): `BranchLayoutEditor.tsx` `requestSave` chỉ cảnh báo khi `links.rooms` khác null; đọc lỗi lần đầu thì lưu thẳng, đọc lại lỗi thì `BranchMap.tsx` giữ dữ liệu cũ và cảnh báo theo dữ liệu cũ.
- Sửa: `BranchMap.tsx:143` truyền `fresh` (có dữ liệu, không đang đọc, lần đọc mới nhất không lỗi). `BranchLayoutEditor.tsx:376` khi không `fresh` và lưu làm mất phòng đã lưu (xoá hoặc thành lối đi) thì hỏi bằng `ActionDialog`, nêu mã phòng và việc liên kết có thể bị gỡ. Khi `fresh`, cảnh báo cũ giữ nguyên.
- Test đỏ rồi xanh (`tests/branch-layout.test.cjs`): "without a fresh links read, ... needs a confirmation before the PUT". Thêm ca không hỏi thừa và ca `BranchMap` tính `fresh`.
- Giới hạn: `fresh` nghĩa là lần đọc gần nhất thành công, không đọc lại ngay lúc bấm lưu; thay đổi từ nơi khác sau lần đọc đó chưa được thấy. Server vẫn chặn phòng còn HĐ hiệu lực hoặc giữ chỗ.

### 3. Gắn/gỡ hoàn tất muộn tải lại tháng cũ: KHÔNG
- `components/branch/BranchMap.tsx:23,27,40`: loader đọc tháng từ `monthRef.current` lúc được gọi; effect cập nhật ref khi đổi tháng. `reload` giữ trong callback cũ (`:115-117`) vì thế đọc tháng đang xem. Kết luận từ đọc code, không thêm test.

### 4. Số "cần chú ý" cũ che việc mới: KHÔNG
- Badge chỉ ở trang danh sách, lấy một nguồn summary: `app/dashboard/branches/page.tsx:172-173`. `hooks/useRefetchOnReturn.ts:31-41` đọc khi mở và khi quay lại cửa sổ.
- Tab "Cần chú ý" ở trang chi tiết không có badge (`app/dashboard/branches/[id]/page.tsx:43-47`); danh sách chỉ mount khi đang xem (`:290`) và số của nó không dùng lại cho nơi khác.

### 5. Làm mới không cập nhật số ở khay: KHÔNG
- Nút "Làm mới" của lớp xem (`BranchOverviewLayers.tsx:339`, nối ở `BranchMap.tsx:149`) gọi `reload`, đọc cả assignments lẫn overview (`BranchMap.tsx:115-117`). Nút "Làm mới" của khay cũng gọi `reload`.
- Ghi chú, chưa sửa vì ngoài mục này: tự đọc lại khi quay về cửa sổ (`BranchMap.tsx:43-51`) chỉ đọc overview, không đọc khay.

## Kiểm chứng
- `node --test --test-isolation=none tests/branch-*.test.cjs`: 60/60 đạt.
- `npx tsc --noEmit --incremental false`: đạt.
- eslint 3 file đã sửa: đạt.
- `npm run build`: đạt.
- Chưa thử trên trình duyệt, chưa gọi API thật.
