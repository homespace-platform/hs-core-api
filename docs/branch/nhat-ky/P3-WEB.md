# Nhật ký P3-WEB-01 (Sonnet)

Hợp đồng: 07 §1, §3, §6, §8. Không viết test (Codex). Không commit.

## Thiết kế web
- `services/branch-overview.service.ts`: kiểu, đọc response, GET, hàm thuần (nhãn, tìm, đếm ngược).
- `components/branch/BranchOverviewLayers.tsx`: lớp Phòng / Thu tiền, tháng, chú giải, tìm, ô, danh sách khoản.
- `BranchMap.tsx` nạp overview, giữ `floor/layer/month` trên URL (replaceState).
- `BranchLayoutEditor.tsx` (sửa nhỏ): xem tình trạng thay cho bảng sửa, dùng chung menu ô P2; "Sửa sơ đồ" để về bảng P1/P2. Overview lỗi -> bảng P1/P2 như cũ.
- `app/dashboard/branches/[id]/page.tsx`: đổi tab giữ `floor/layer/month`.

## Tiến độ
- [x] 1. service
- [x] 2. component lớp
- [x] 3. nối BranchMap, editor, page
- [x] 4. kiểm


Bước kế: Codex viết `tests/branch-overview.test.cjs` (P3-TEST-01). Sau P3-BE-01: nối thật, thử đích `target.href` và Back.

## Hàm thuần để test (`services/branch-overview.service.ts`)
`parseOverview`, `targetHref`, `isMonth`, `shiftMonth`, `monthLabel`, `formatInstant`, `confirmationCountdown`, `foldText`, `roomMatches`, `statusLabel`, `roomLayerStatus`, `paymentKindLabel`; `branchOverviewService.getOverview` (mock `@/lib/axios-client`).

## Hành vi / quyết định web
- Overview đọc được thì tab Sơ đồ mở chế độ xem tình trạng, một tầng và một lớp. Nút "Sửa sơ đồ, liên kết" về bảng P1/P2. Bản nháp, thiết lập, chọn mẫu luôn dùng bảng sửa; đã sửa thì ở lại bảng sửa đến khi bấm "Xem tình trạng phòng".
- Overview lỗi lần đầu hoặc khi đổi tháng: bảng P1/P2, báo lỗi, Thử lại / Về tháng hiện tại. Làm mới cùng tháng bị lỗi: giữ dữ liệu, ghi "có thể đã cũ". Không hiện dữ liệu tháng khác.
- Nạp lại khi đổi tháng, focus/pageshow, nút Làm mới, sau ghi liên kết và lưu sơ đồ. Bỏ phản hồi cũ.
- URL `floor/layer/month` ghi bằng `history.replaceState`; đổi tab Tổng quan/Sơ đồ giữ lại. Tìm và làm nổi không lưu trên URL.
- Ô: một đích thì là Link; nhiều mục (nhiều khoản, hoặc đang thuê + sắp vào) mở hộp danh sách. Lớp Phòng không có đích trang (hoặc đích ROOM): mở menu ô P2. Lớp Thu tiền không có khoản: ô không bấm được.
- Chỉ theo `href` bắt đầu bằng `/dashboard/`.
- Chú giải đếm ô của cả chi nhánh; nút tầng hiện số ô khớp khi đang tìm/làm nổi.
- `unplacedPayments` hiện ngay trên khay "Chưa xếp phòng" (không sửa `UnplacedTray`). `Payment` không có tên/số tiền nên mục chỉ ghi trạng thái, loại, hạn.
- Sửa nhỏ ngoài danh sách 07 §8: `BranchLayoutEditor.tsx` (prop `links.overview`).

## Kiểm
- `node --test` branch-layout + branch-assignment: 30/30.
- `tsc --noEmit --incremental false`: chỉ lỗi baseline `.next/types/validator.ts`. Đã xóa `.next/dev/types` (file sinh ra, còn trỏ trang thử).
- eslint file đã sửa: sạch.
- Chrome headless + trang thử tạm với API giả (đã xóa): 1280 sáng lớp Phòng; khung 360 lớp Thu tiền (sáng); lớp Thu tiền tối ở 520px; overview lỗi về bảng cũ. Dev overlay báo "1 Issue" (log thấy Keycloak init timeout trong môi trường thử; chưa mở overlay).

## Chưa kiểm
Chưa nối backend; chưa bấm thử hộp danh sách, menu ô từ chế độ xem, tìm/làm nổi, Back, bàn phím, zoom 200%.

## Sửa sau review Codex (P3-REVIEW)
- [x] Finding 4: `parseOverview` kiểm đủ trường bắt buộc của 07 §1 (Room, PaymentLayer, Payment, Stay, Target); trường nullable phải có khóa, cho phép null; status/clock phải thuộc tập đã chốt; `count` và `payments.status` phải khớp `items`. Sai -> về sơ đồ P1/P2.
- [x] `SETTLEMENT_BALANCE` = "Quyết toán còn thiếu", đích theo server. `Payment.kind` lạ vẫn nhận (không đổi màu), nhãn "Khoản thu".
- [x] "1 Issue" ở Next dev overlay: `Keycloak initialization timed out` (`features/auth/AuthInitializer.tsx`), do chạy thử không có Keycloak, không phải code nhánh. Căn cứ: log dev chỉ có lỗi browser này; badge chỉ hiện sau mốc chờ 10 giây. Không sửa. (Không mở lại overlay.)
