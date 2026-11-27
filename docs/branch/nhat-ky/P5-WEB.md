# Nhật ký P5-WEB-01 (Sonnet)

Hợp đồng: 07 §1, §5, §6, §8. Không viết test (Codex). Không commit.

## Kế hoạch
1. service: kiểu Meter/Expiry, nhãn 4 lớp, kiểm dữ liệu P5 riêng (backend chưa có thì chỉ 2 lớp mới báo thiếu).
2. `BranchOverviewLayers` + `BranchMap` (URL `layer=meters|contracts`).
3. Sửa finding 6 P4-REVIEW (size 1-100).
4. Loại việc P4-R2 ở "Cần chú ý".
5. Kiểm.

## Tiến độ
- [x] 1. service: `OverviewMeter`, `OverviewExpiry`, `LAYER_LABEL`, `LAYER_STATUS_LABEL`, `isOverviewLayer`, `layerReady`
- [x] 2. `BranchOverviewLayers.tsx`, `BranchMap.tsx`: 4 lớp, chú giải/tìm/làm nổi/hộp danh sách dùng lại
- [x] 3. finding 6: `parseAttention` chỉ nhận `size` 1-100
- [x] 4. P4-R2: lúc xong, 07 và enum Core chưa có tên loại mới nên chưa thêm nhãn. Loại lạ đã an toàn sẵn: nhãn "Việc cần xem", nhóm "Khác", `contextLabel` (07 không có trường title riêng), link theo `target.href`. Không đổi code. Khi có tên: thêm vào `WORK_LABEL`.
- [x] 5. kiểm: 4 file test chi nhánh 47/47; tsc chỉ lỗi baseline `.next/types/validator.ts`; eslint 3 file đã sửa sạch.

## Quyết định web
- `parseOverview` không đòi `meters`/`contracts`. `layerReady` kiểm riêng từng lớp: thiếu hoặc sai ở một phòng thì cả lớp đó báo "Máy chủ chưa có dữ liệu", ô không bấm được; Phòng, Thu tiền vẫn chạy.
- Trạng thái ô = phần tử đầu (server sắp); web không tự tính ngày. Một mục: ô là link tới `target.href`; nhiều mục: hộp danh sách.
- `Meter`/`Expiry` không có tên/số hợp đồng: phòng có nhiều hợp đồng thì các mục chỉ phân biệt bằng ngày, kỳ, trạng thái.
- Tháng chỉ hiện ở lớp Thu tiền.

## Chưa kiểm
Chưa chạy trình duyệt (360px, dark, bàn phím, zoom), chưa nối backend P5. Chưa có test P5 (Codex: `tests/branch-monitoring.test.cjs`; hàm thuần `layerReady`, `roomLayerStatus`, `statusLabel`, `isOverviewLayer`).
