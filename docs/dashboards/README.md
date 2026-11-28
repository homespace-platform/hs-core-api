# Dashboard & Báo cáo — điểm vào cho mọi model

Đọc file này trước. Sau đó đọc `AUDIT_LOG.md` (mục cuối = trạng thái hiện tại) rồi file thiết kế của trang đang làm.

## Mục tiêu (chủ dự án, 28/11/2026)
Hoàn thiện các trang còn rỗng, **web trước, mobile sau**. Không làm "Dự báo AI".

| # | Trang | App | Route | File thiết kế |
|---|---|---|---|---|
| P1 | Dashboard chủ nhà | hs-web-app | `/dashboard/landlord` | `P1-landlord-dashboard.md` |
| P2 | Dashboard người thuê | hs-web-app | `/dashboard/tenant` | `P2-tenant-dashboard.md` |
| P3 | Phân tích & Báo cáo (chủ nhà) | hs-web-app | `/dashboard/analytics` | `P3-landlord-analytics.md` |
| P4 | Tổng quan (admin) | hs-admin-portal | `/dashboard` | `P4-admin-overview.md` |
| P5 | Thống kê (admin) | hs-admin-portal | `/analytics/statistics` | `P5-admin-statistics.md` |
| P6 | Blockchain Explorer (admin) | hs-admin-portal | `/blockchain` | `P6-admin-blockchain.md` |
| C1 | Dọn trang thừa | admin + web | `AdminsPage.tsx`, `MembersPage.tsx` (không có route); `/dashboard/deposit`, `/dashboard/withdraw` (dữ liệu giả `data/mock-*-data`) | ghi trong `AUDIT_LOG.md` |

Mobile (sau khi web ổn định): Dashboard tương ứng trong `hs-mobile-app` + "Trung tâm hỗ trợ" (Hồ sơ, đang báo "chưa hỗ trợ").

## Quy trình (bắt buộc)
1. **Thiết kế trước, code sau.** Mỗi trang có file thiết kế; chủ dự án duyệt → ghi "DUYỆT" vào `AUDIT_LOG.md` → mới code.
2. Mỗi bước xong ghi một mục vào `AUDIT_LOG.md`: ngày, model, việc, commit, test đã chạy (số liệu thật), việc còn lại, quyết định mới.
3. Làm trực tiếp trên `master` (không tạo nhánh). Push chỉ khi chủ dự án bảo.
4. Không đọc `.env`/`.env.prod`. Không đụng database/dịch vụ đang chạy của chủ dự án; test DB dùng container PostgreSQL tạm rồi xoá.
5. Quy ước env: `${VAR:?}` (compose) / `${VAR}` (properties), không default, đủ mọi file env, thứ tự nhóm như `application.properties`.

## Nguyên tắc thiết kế chung
- **Số liệu phải đúng nghiệp vụ, không ước lượng:** mỗi chỉ số ghi rõ công thức, nguồn bảng/API, khoảng thời gian, múi giờ `Asia/Ho_Chi_Minh`, đồng hồ nghiệp vụ (`homespace.time.simulated-at` / BillingTime) khi liên quan hạn.
- **Phạm vi dữ liệu theo người xem:** chủ nhà chỉ thấy dữ liệu của mình; người thuê chỉ thấy của mình; admin cần quyền (permission) riêng, kiểm ở backend.
- **Dùng lại API/màn hình có sẵn** (Chi nhánh "Cần chú ý", lịch sử thanh toán, hợp đồng…). Chỉ thêm endpoint tổng hợp khi gọi nhiều API sẽ chậm hoặc lộ dữ liệu.
- Endpoint tổng hợp: một truy vấn gộp (không N+1), có giới hạn, chỉ đọc, cache ngắn nếu tốn.
- Mỗi khối có trạng thái **đang tải / trống / lỗi**; mỗi số liệu bấm vào được thì dẫn tới trang chi tiết có sẵn.
- Giao diện theo thiết kế hiện có của web (Tailwind tokens, card bo 2xl); biểu đồ dùng thư viện đã cài trong repo (kiểm `package.json`), không thêm thư viện mới nếu đã có.
