# Nhật ký Dashboard & Báo cáo

Mỗi mục: ngày · model · việc · commit · test (số liệu thật) · còn lại · quyết định. Mục mới thêm ở cuối.

## 2026-11-28 · Opus 5.5 (điều phối) · Khởi tạo
- Dò toàn dự án tìm trang rỗng (grep "đang được xây dựng", "Trang trống", mock data, page ngắn bất thường):
  - Web: `app/dashboard/landlord/page.tsx`, `app/dashboard/tenant/page.tsx`, `app/dashboard/analytics/page.tsx` chỉ có dòng "đang được xây dựng".
  - Admin: `pages/dashboard/DashboardPage.tsx`, `pages/analytics/StatisticsPage.tsx`, `pages/analytics/AiForecastPage.tsx`, `pages/blockchain/BlockchainExplorerPage.tsx` là trang trắng; `pages/users/AdminsPage.tsx`, `MembersPage.tsx` trắng và không có route.
  - Web `app/dashboard/deposit`, `app/dashboard/withdraw` chạy dữ liệu giả, không có trong menu.
  - Mobile: Hồ sơ → "Trung tâm hỗ trợ" báo chưa hỗ trợ.
- Chủ dự án chốt: làm tất cả trừ Dự báo AI; web trước, mobile sau; **bước 1 = thiết kế chi tiết từng trang**.
- Đang làm: 3 agent (chỉ đọc code) viết thiết kế P1+P3, P2, P4+P5+P6 vào thư mục này.
- Còn lại: chủ dự án duyệt thiết kế → code từng trang → test → push.

## 2026-11-28 · Opus 5.5 · Thiết kế xong P1–P6 (chờ duyệt)
- Files: P1-landlord-dashboard.md, P2-tenant-dashboard.md, P3-landlord-analytics.md, P4-admin-overview.md, P5-admin-statistics.md, P6-admin-blockchain.md (agent Sonnet, chỉ đọc code).
- Phát hiện chung: web và admin chưa có thư viện biểu đồ; `payment_receipts`/`membership_receipts` không có cột tiền (doanh thu phải join `payment_requests` / `membership_orders`); phiếu thu không có backfill cho thanh toán cũ; lượt xem tin chỉ có tổng cộng dồn (cần bảng `listing_view_daily`, số liệu từ ngày bật); admin chưa có API xem bằng chứng blockchain; "Cần chú ý" của chi nhánh gom sẵn 22 loại việc của chủ nhà (P1 dùng lại).
- Câu hỏi gom: xem tin nhắn gửi chủ dự án cùng ngày (10 quyết định D1–D10). Chưa code.
