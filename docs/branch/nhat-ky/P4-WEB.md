# Nhật ký P4-WEB-01 (Sonnet)

Hợp đồng: 07 §1, §4, §6, §8. Không viết test (Codex). Không commit.

## Rà deep link (code hiện có)
- Đã có: `?tab=` trang HĐ, `section/invoice`, `payments?paymentId`, `rental-requests?requestId` (useRentalRequestDeepLink), `viewing-schedules?appointmentId`.
- Thiếu: `amendmentId`, `focus=early-exit`, `#unplaced`.

## Kế hoạch
1. service: attention + summary.
2. `BranchAttentionList` + tab "Cần chú ý" ở trang chi nhánh.
3. Số việc, thứ tự trên thẻ danh sách.
4. Deep link: UnplacedTray, EndTab, ContractAmendmentPanel.
5. Kiểm.

## Tiến độ
- [x] 1. service: getAttention, getAttentionSummary; hàm thuần parseAttention, parseAttentionSummary, orderByAttention, workLabel, workContext, workDeadline
- [x] 2. components/branch/BranchAttentionList.tsx + hooks/useRefetchOnReturn.ts (nạp lại khi focus/pageshow, bỏ phản hồi cũ) + tab `?tab=attention`
- [x] 3. thẻ danh sách: số việc (bấm mở tab Cần chú ý), thứ tự theo server; summary lỗi thì giữ thứ tự cũ, báo lỗi, Thử lại
- [x] 4. deep link: UnplacedTray `#unplaced`; EndTab `?focus=early-exit` (dùng focusRequest sẵn có), `?amendmentId=` -> ContractAmendmentPanel (prop focusId)
- [x] 5. kiểm

## Quyết định web
- Danh sách việc chỉ gắn khi đang ở tab, nên đổi tab / Back là nạp lại; trang 20 việc, không lưu trên URL. Trang cuối hết việc thì lùi trang.
- Loại việc lạ vẫn hiện, nhãn "Việc cần xem". Chỉ theo href `/dashboard/`.
- `focus=early-exit` còn trên URL nên đổi tab HĐ rồi quay lại tab Kết thúc sẽ mở lại cửa sổ.
- Đang soạn phụ lục inline (panel nằm ở tab nội dung) thì không focus đề nghị.

## Kiểm
- 3 file test chi nhánh: 38/38. Toàn bộ tests/*.test.cjs: 650/676, 26 lỗi trùng danh sách lỗi trước khi sửa.
- tsc: chỉ lỗi baseline `.next/types/validator.ts`. eslint file đã sửa: sạch.

## Chưa kiểm
Chưa chạy trình duyệt (360px, dark, bàn phím, cuộn tới đích của 3 deep link), chưa nối backend P4. Bước kế: Codex viết tests/branch-attention.test.cjs.
