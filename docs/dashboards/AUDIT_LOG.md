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

## 2026-11-28 · Opus 5.5 · Kiểm kê toàn bộ API (inventory/)
- 5 agent (chỉ đọc) lập `inventory/users-kyc-admin.md` (86 endpoint, 26 tín hiệu THIẾU), `listings-branches-rental.md` (120, 25 thiếu + 4 một phần), `contracts-billing.md` (117, 24 thiếu + 7 một phần), `payments-membership.md` (55, 24 thiếu + 5 chưa có dữ liệu), `services-ops.md` (85 + 14 route gateway, 18 thiếu, 7 chưa có dữ liệu).
- Lỗi/rủi ro thật (điều phối đã kiểm 2 mục đầu):
  1. Outbox thông báo hợp đồng chặn đầu hàng: `ContractNotificationPublisher.CLAIM_SQL` lấy `ORDER BY o.id LIMIT 1`; dòng cũ nhất gửi lỗi thì mọi dòng sau (kể cả hợp đồng khác) đứng yên. Cần kiểm các outbox khác cùng mẫu.
  2. Gateway `SecurityConfig.java:32,40` mở công khai `/actuator/**` và `/api/v1/actuator/prometheus` (route sang core). Core chưa có registry Prometheus nên nhiều khả năng 404; vẫn nên đóng.
  3. Rate limit gateway theo IP socket; sau proxy có thể gom mọi user vào một xô (chưa kiểm trên server).
  4. 34 `@Scheduled` ở core + 4 vòng lặp Nest chỉ ghi log, không lưu lần chạy/lỗi.
  5. Notification-service không có API admin (email/push/DLT); chỉ gateway được canh, các service khác chết không ai báo.
- Tiếp: cập nhật P1–P6 với tín hiệu THIẾU (khối "Sức khoẻ hệ thống" ở P4), rồi gửi chủ dự án duyệt cùng D1–D10 và danh sách lỗi cần sửa trước.

## 2026-11-28 · Opus 5.5 · Bổ sung giám sát vào P1–P6 sau kiểm kê (chờ duyệt)
- P1 (222 dòng): 7 nhóm (Thiết lập, Việc cần làm, Tiền, Hợp đồng, Phòng & tin, Khách & lịch hẹn, Khiếu nại), +~28 tín hiệu, endpoint tổng hợp có `include`.
- P2 (175): +~14 tín hiệu (việc mới, cọc/hoàn cọc, "đang chờ chủ nhà", khiếu nại, tin đã lưu). P3 (145): phí chậm, nợ chuyển kỳ, chi phí hội viên, lượt lưu, cờ "chưa có khách".
- P4 (209): khối "Sức khoẻ hệ thống" 10 nhóm, mục "Lỗi phải sửa trước" (5 rủi ro), bảng quyền admin duy nhất (§6). P5 (153): +5 khối (sự cố/MTTR, duyệt tin, SLA yêu cầu thuê & lịch hẹn, hội viên hiệu lực, hoá đơn tháng). P6 (109): integrity, lỗi theo mã, ETH chưa settle, lần chạy worker.
- Ràng buộc: đóng `/actuator/**` ở gateway nhưng giữ `/api/v1/actuator/health` (admin + mobile đang gọi); SLA phản hồi cần cột `responded_at`; cần trang danh sách KYC cùng P4.
- Giả định chưa kiểm (ghi trong file): số tin chat chưa đọc, cột `deposit_records`/`dispute_cases`, gateway có chuyển `X-User-Authorities` tới notification/chat/ai không.
- Chờ chủ dự án: duyệt thiết kế, D1–D10, thứ tự sửa 5 lỗi trước.
