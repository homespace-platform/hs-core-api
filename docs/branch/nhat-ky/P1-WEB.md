# P1-WEB-01 · Nhật ký (web: tạo và sửa sơ đồ chi nhánh)

Repo `hs-web-app`, HEAD khi bắt đầu: `6dff15b`, cây làm việc sạch. Người giữ: Claude Sonnet.

## File dự kiến

- `types/branch-layout.type.ts` (mới)
- `lib/branch-layout.ts` (mới, logic bản nháp thuần, không import runtime)
- `services/branch-layout.service.ts` (mới)
- `components/branch/` (mới): editor, bảng sơ đồ, hộp thoại
- `app/dashboard/branches/[id]/page.tsx` (thêm tab Tổng quan / Sơ đồ)
- `app/dashboard/branches/page.tsx` (tạo xong thì chuyển tới `?tab=map&setup=1`)
- `lib/dashboard-role.ts` (thêm prefix chi nhánh cho chủ nhà)

## Các bước

- [x] 1. Types, module thuần, service, dashboard-role
- [x] 2. Component sơ đồ
- [x] 3. Nối 2 trang
- [x] 4. Kiểm: tsc, eslint, script thử module, xem giao diện; đối chiếu code backend

## Tiến độ

- Đã đọc tài liệu và code. Chưa sửa code.
- Xong bước 1: `types/branch-layout.type.ts`, `lib/branch-layout.ts`, `services/branch-layout.service.ts`, `lib/dashboard-role.ts`.
- Xong bước 2: `components/branch/{BranchLayoutEditor,LayoutBoard,LayoutDialogs}.tsx`.
- Xong bước 3: `app/dashboard/branches/page.tsx`, `app/dashboard/branches/[id]/page.tsx`.
- Xong bước 4. **P1-WEB-01 xong phần code**; bước kế: Codex viết test (P1-TEST-01), thử tay với API thật.

## File cuối

Mới: `types/branch-layout.type.ts`, `lib/branch-layout.ts`, `services/branch-layout.service.ts`, `components/branch/BranchLayoutEditor.tsx`, `components/branch/LayoutBoard.tsx`, `components/branch/LayoutDialogs.tsx`.
Sửa: `app/dashboard/branches/page.tsx` (chỉ phần sau khi tạo: dùng id trả về, chuyển tới `?tab=map&setup=1`), `app/dashboard/branches/[id]/page.tsx` (tab Tổng quan / Sơ đồ phòng, bọc Suspense), `lib/dashboard-role.ts`.
Không đụng logic phí, bãi xe. Không thêm thư viện. Không viết test.

## Hành vi chính

- Nhà nguyên căn: editor tự PUT sơ đồ 1 ô (`Tầng 1` / `Dãy 1` / `Nguyên căn`) khi tab sơ đồ mở, `revision` 0 và có quyền ghi. Áp dụng cho cả chi nhánh mới (đi thẳng vào tab sau khi tạo) và chi nhánh cũ. Lưu lỗi thì giữ làm bản nháp.
- Lỗi 409 hoặc mất phản hồi: web GET lại; chỉ coi là đã lưu theo điều kiện ở mục "Sửa sau rà soát" (P1-R01). Còn lại, 409: giữ nháp, khóa nút Lưu, báo tải lại rồi làm lại thay đổi.
- Tab được giữ mounted nên đổi tab không mất nháp. Cảnh báo rời trang: đóng/tải lại tab và bấm link sang trang khác.
- Giới hạn ở web (chỉ để dễ dùng, server không có): 30 tầng khi chọn mẫu / thêm / nhân bản, 50 phòng mỗi lần thêm hoặc mỗi dãy trong mẫu; ô nhập tên và mã tối đa 50 ký tự (server 255).

## Hàm xuất của `lib/branch-layout.ts` (cho người viết test)

File không import gì lúc chạy (chỉ `import type`), nạp được bằng `typescript.transpileModule` + `vm`. Mọi thao tác trả bản nháp mới, hoặc **đúng tham chiếu cũ** khi không đổi gì (riêng `addRow`, `addFloor` luôn trả mảng mới).

- Hằng: `MAX_FLOORS`, `MAX_ROOMS_PER_BATCH`.
- Đọc: `normalizeRoomCode(code)` (trim + viết hoa), `usedRoomCodes(draft, exceptSlotKeys?)`, `countRooms(draft)`, `floorNumber(draft, index)`, `nextRoomCode(draft, rowKey)`, `findSlot(draft, slotKey)`.
- Mẫu: `buildTemplate({kind:"single"|"double",rooms} | {kind:"multi",floors,rooms} | {kind:"custom"} | {kind:"house"})`.
- Sửa: `addFloor`, `addRow(d,floorKey)`, `addRooms(d,rowKey,count=1)`, `addEmptyCell(d,rowKey)`, `roomCodeError(d,slotKey,code)` (chuỗi lỗi hoặc null), `setRoomCode`, `toggleEmptyCell`, `moveSlot(d,slotKey,-1|1)`, `moveSlotToRow(d,slotKey,rowKey)`, `removeSlot`, `renameFloor`, `renameRow`, `removeFloor`, `removeRow`, `duplicateFloor(d,floorKey)`, `recodeRooms(d,{floorKey?,prefix?,start?})` (`start` chỉ dùng khi có `floorKey`).
- Server: `fromLayout(layout)`, `toSaveRequest(draft,expectedRevision)`, `sameLayout(a,b)` (so cả id), `isSavedCopy(server,sent,base)`, `validateDraft(draft)` (mảng lỗi; cần ít nhất 1 dãy, 1 phòng, nhãn không rỗng, mã không rỗng/trùng; cho phép tầng/dãy trống như backend).
- Hoàn tác: `createHistory(draft)`, `applyEdit(history,next)` (tối đa 50 bước), `undoEdit(history)`.
- Kiểu xuất: `LayoutTemplate`, `DraftHistory`.

## Lệnh và kết quả (2026-11-26, web @6dff15b + thay đổi này)

| Lệnh | Kết quả |
|---|---|
| `npx tsc --noEmit --incremental false` | Chỉ 1 lỗi baseline `.next/types/validator.ts` (customers). Chạy trước lần chỉnh nhỏ cuối ở `lib/branch-layout.ts` (chỉ đổi chuẩn hóa mã và bỏ 2 dòng kiểm) |
| `npx eslint` các file đổi / mới | Sạch (chạy lại `lib/branch-layout.ts`, `components/branch` sau lần sửa cuối) |
| `node <scratchpad>/sanity.cjs` (module thuần, file tạm) | Đạt |
| Harness tạm trong scratchpad (webpack của Next + Edge headless, API giả), không thêm file vào repo | Xem ảnh 1280px (sáng) và 360px (sáng, tối, hộp thoại ô): bố cục đúng, sơ đồ cuộn trong vùng riêng; đo tràn ngang trang = 0 ở cửa sổ headless hẹp nhất |

Đối chiếu code backend đang viết (`BranchLayoutService`, DTO, mã 4074 = HTTP 409): shape khớp; chuẩn hóa mã và điều kiện tối thiểu đã chỉnh theo.

## Sai khác / quyết định

- Không có chế độ "xem" riêng: có quyền ghi thì sửa ngay trên sơ đồ, chỉ ghi khi bấm "Lưu sơ đồ".
- Hộp thoại dùng Radix Dialog (đã có trong repo, bẫy focus); xác nhận dùng `useActionDialog`.
- Di chuyển bằng menu áp dụng cho ô (lên, xuống, sang dãy khác, kể cả tầng khác); chưa có đổi thứ tự dãy / tầng (thiết kế không yêu cầu rõ).
- Sau khi tạo chi nhánh, danh sách không tải lại vì đã chuyển trang.

## Chưa xác minh

- Chưa chạy với API/Gateway thật, chưa đăng nhập, chưa chạy `next dev` / `next build`.
- Chưa thử tay bằng bàn phím, trình đọc màn hình, zoom 200%; focus sau di chuyển và luồng 409 / lỗi lưu / cảnh báo rời trang mới kiểm bằng đọc code.
- Nút Back và `router.push` trong code không hỏi trước khi rời trang; bản nháp được giữ tại máy (P1-R04).

## Lỗi có sẵn thấy được, không sửa

- `branches/page.tsx` `loadBranches` nuốt lỗi: lỗi mạng hiện như "Chưa có chi nhánh".
- `branches/[id]/page.tsx`: lấy tối đa 100 tin và khớp thêm theo tên chi nhánh (khảo sát web mục 1).

## Sửa sau rà soát P1-REVIEW (2026-11-26)

- **P1-R01 (đã sửa).** Bỏ so sánh bỏ qua id. `isSavedCopy(server, sent, base)`: id đã gửi phải nằm đúng node, node gửi không id phải nhận id mà `base` chưa có. Editor chỉ nhận là đã lưu khi thêm điều kiện `revision === expectedRevision + 1`. Server thay id ta định giữ thì báo 409, giữ nháp.
- **P1-R02 (đã sửa).** `incrementCode` dùng `BigInt`.
- **P1-R04 (đã sửa bằng lưu nháp tại máy).** Tài liệu Next 16 App Router chỉ có `onNavigate` của `<Link>`, không có cách chặn Back. File mới `lib/branch-layout-draft.ts`: `readStoredDraft(userId, branchId)`, `writeStoredDraft(userId, branchId, value|null)`; khóa `hs.branch-layout-draft.{keycloak.subject}.{branchId}`; mọi truy cập bọc try/catch. Ghi khi nháp khác bản đã lưu; xóa khi lưu, hủy, hoàn tác về bản đã lưu, tải lại, hoặc xác nhận bỏ khi bấm link. Mở lại thì hỏi "Khôi phục bản nháp" / "Bỏ bản nháp" (trong lúc hỏi chưa sửa được, nhà nguyên căn chưa tự tạo). Nháp khác revision server: có cảnh báo, khôi phục chỉ để xem và làm lại, khóa Lưu như 409. Khóa tạm `key` của node mới thêm phần ngẫu nhiên để không trùng với nháp khôi phục.
- **Ô lối đi:** `toSaveRequest` luôn gửi `roomCode` (`null` cho ô trống). Không phải đổi.
- **P1-R03** thuộc backend, web không sửa.
- Không sửa test; không thấy test web nào sai.

| Lệnh | Kết quả |
|---|---|
| `node --test --test-isolation=none tests/branch-layout.test.cjs` | 9/9 đạt, 0 skip (trước khi sửa: 8 đạt, 1 lỗi timeout P1-R02) |
| `npx eslint lib/branch-layout.ts lib/branch-layout-draft.ts components/branch` | Sạch (file khác không đổi từ lần lint trước) |
| `npx tsc --noEmit --incremental false` | Chỉ 1 lỗi baseline `.next/types/validator.ts` (customers) |
| Script tạm trong scratchpad cho `isSavedCopy`, ô lối đi, `branch-layout-draft.ts` | Đạt |

Chưa xác minh: Back rồi khôi phục, và hai tab lưu xung đột, trên trình duyệt thật; chưa chụp lại giao diện (khung đề nghị khôi phục là phần mới). `branch-layout-draft.ts` và `isSavedCopy` chưa có test trong repo.

## Sửa P1-R04a..d (2026-11-26)

Thiết kế nháp được làm gọn lại (thay cho mô tả xóa nháp ở mục P1-R04 phía trên):

- **R04a.** Khi mở lại không còn suy đoán "server đã có". Chỉ tự bỏ nháp khi cùng revision và `sameLayout` (tính cả id) với cây vừa tải; còn lại đều hỏi chủ nhà. Không cần lưu thêm cây gốc.
- **R04b.** Tab chỉ xóa đúng phiên bản nháp nó đã ghi hoặc tiếp quản (`removeStoredDraft(userId, branchId, owned)`, so nguyên chuỗi). Áp dụng cho lưu xong, hủy, hoàn tác, tải lại, rời trang, "Bỏ bản nháp". Hai tab cùng đang sửa vẫn ghi đè nhau trên một khóa (tab ghi sau thắng).
- **R04c.** `readStoredDraft` kiểm cả cây: `key` là chuỗi không rỗng, không trùng; `id` nếu có là chuỗi; nhãn là chuỗi; `roomCode` là chuỗi hoặc null.
- **R04d.** `revision` luôn là bản đã tải; revision của nháp khôi phục nằm riêng (`restoredFrom`). Khóa Lưu khi hai số khác nhau hoặc sau 409; hủy / hoàn tác về cây đã tải / thoát bước thiết lập thì bỏ `restoredFrom`.
- Hệ quả chấp nhận: nếu PUT đã commit nhưng mất phản hồi đúng lúc đã rời trang, lần mở sau vẫn hỏi khôi phục (có cảnh báo khác revision); chủ nhà bấm "Bỏ bản nháp".

`lib/branch-layout-draft.ts` giờ chỉ `import type`; xuất `readStoredDraft`, `writeStoredDraft`, `removeStoredDraft`, kiểu `StoredDraft`.

| Lệnh | Kết quả |
|---|---|
| `node --test --test-isolation=none tests/branch-layout.test.cjs tests/listing-branch-fee-independence.test.cjs` | 25/25 đạt, 0 skip (trước khi sửa: 21 đạt, 4 lỗi R04a..d) |
| `npx eslint lib/branch-layout-draft.ts components/branch` | Sạch |
| `npx tsc --noEmit --incremental false` | Chỉ 1 lỗi baseline `.next/types/validator.ts` (customers), exit 2 |

Không sửa test; không thấy test nào sai. Chưa xác minh trên trình duyệt thật.
