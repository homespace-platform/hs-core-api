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

## 2026-11-28 · Opus 5.5 · Chủ dự án duyệt hướng làm, bắt đầu code
- Chủ dự án: "chia sub-agent 3 lần/lượt viết API phục vụ bản thiết kế"; **xuất Excel .xlsx để đợt sau** (giao diện vẫn đặt sẵn nút "Xuất báo cáo" ở vị trí hợp lý); thiết kế giao diện đẹp bằng skill ui-ux-pro-max; **biểu đồ dùng thư viện cài thêm, không tự vẽ** (thay D4: web và admin đều dùng thư viện biểu đồ); backend + web (gồm admin portal) trước, mobile sau.
- Áp dụng đề xuất mặc định cho D1–D3, D5–D10 (xem tin nhắn 28/11). D3 (backfill phiếu thu) và D5 (`listing_view_daily`) làm trong đợt API của P3.
- 5 lỗi ở mục "Lỗi phải sửa trước" (P4) CHƯA làm, chờ chủ dự án đồng ý riêng.
- Kế hoạch đợt:
  - Đợt 1 (core, mỗi agent một worktree): API P1 (`wt/dash-p1`), API P2 (`wt/dash-p2`), API P3 + backfill phiếu thu + ghi lượt xem theo ngày (`wt/dash-p3`). Điều phối gộp vào master.
  - Đợt 2 (core + notification): API P4 (gồm sức khoẻ hệ thống, danh sách KYC), API P5, API P6 (+ endpoint admin của notification).
  - Đợt 3 (giao diện): web P1+P2, web P3, admin P4+P5+P6.

## 2026-11-28 · Codex · Tiếp tục đợt API P1–P3 sau giới hạn tài khoản
- Chủ dự án yêu cầu tiếp tục phần đang dừng. Ba worktree wt/dash-p1, wt/dash-p2, wt/dash-p3 còn code chưa commit, tiếp tục hoàn thiện từ các thay đổi này.
- Phân công ba worker Codex hoàn thiện API theo thiết kế đã duyệt, kiểm tra quyền/phạm vi dữ liệu, unit/API test và truy vấn trên PostgreSQL tạm. Điều phối xử lý các file/index dùng chung rồi gộp và kiểm tra trên master.
- Docker đã xác nhận hoạt động. Kết quả test và commit sẽ ghi khi worker hoàn tất. Báo kết quả gộp đợt 1 trước khi tiếp tục đợt 2 theo kế hoạch đã duyệt.

## 2026-11-28 · Codex · Đợt 1 đã gộp và kiểm tra tích hợp
- P3 `55e7cdf` → master `274e8bf`; P2 `b29a8b6` → `3f7fff7`; P1 `788c22f` → `6195ea4`; đối chiếu tiền hoàn P1/P3 `1582ac8` → `162e982`. Ba API đã có controller, DTO, service và truy vấn theo người dùng xác thực. Hợp đồng API thực tế ghi cuối từng thiết kế P1/P2/P3.
- Gộp index PaymentReceipt giữ cả landlord/tenant-issued và hàng đợi PDF; ViewingAppointment giữ owner-created và owner-status-date; PaymentRequest giữ payer/payee-due và bỏ khai báo trùng cùng bộ cột. P1/P3 cùng loại phần DEPOSIT_REFUND khỏi INITIAL_PAYMENT_REFUND trước khi tính doanh thu ròng.
- Kiểm tra trên master bằng `mvnw.cmd -Pisolated-build -pl hs-api-service -am -Dtest=LandlordDashboard*Test,TenantDashboard*Test,LandlordAnalytics*Test,OccupancyCalcTest,BranchAttention*Test,ViewingAppointmentServiceTest,RentalRequestServiceTest,PaymentReceiptServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`: BUILD SUCCESS, 250 test, 0 failure/error/skipped. Listing 51, payment 14, contract 119, API 66. Đây là bộ test chọn lọc tích hợp các phần vừa gộp, không phải toàn bộ suite.
- PostgreSQL 16.16 trong container riêng `hs-dash-integration-20261128`, cổng loopback Docker cấp 49469, ba database test riêng cho P1/P2/P3. Đã chạy 30 ca PostgreSQL trong tổng 250 test, gồm BranchAttention hồi quy. Container và volume do điều phối tạo đã xóa. Log không commit tại `hs-api-service/target/dashboard-phase1-integration.log`.
- Giới hạn đã xác minh: lượt xem/SLA bắt đầu từ khi ghi mới; không phục dựng lịch sử đã mất. Chế độ Hibernate validate/none cần áp dụng DDL mới trước boot. Chưa thử provider thật/production, chưa chạy full suite, chưa làm frontend/mobile. Chưa push.
- Tiếp tục đợt 2 theo phê duyệt: P4 tổng quan/sức khoẻ đọc từ nguồn hiện có + danh sách KYC; P5 thống kê; P6 blockchain đọc + API admin delivery-health của notification. Năm sửa lỗi operations và Excel vẫn để sau. Những nguồn giám sát phụ thuộc năm sửa lỗi chưa có dữ liệu phải trả unavailable/null, không báo OK giả.

## 2026-11-28 · Codex · Chủ dự án yêu cầu điều phối đến hoàn tất, chưa push
- Yêu cầu mới: hoàn thành toàn bộ phạm vi dashboard đã duyệt, giữ mọi thay đổi local; chưa push ở tất cả repo.
- Đợt 2: P5/P6 đã xác nhận đang viết code qua output worker; P4 còn bản nhiệm vụ trong composer, đã gửi yêu cầu tiếp tục qua orchestration. Không coi trạng thái start_unknown là đã chạy hoặc đã xong.
- Đã xếp ba task giao diện phụ thuộc API P4/P5/P6: task_ee9e5aa51e5a (web P1/P2), task_c45d128c22d1 (web P3), task_86336892486d (admin P4/P5/P6 + KYC). Khi API hoàn tất sẽ gộp/kiểm tra rồi khởi động các task giao diện.
- Điều kiện kết thúc: API/backend và giao diện khớp hợp đồng thực, route/quyền/drilldown hoạt động, kiểm tra trạng thái tải/trống/lỗi/unavailable, lint/typecheck/build phù hợp, kiểm tra browser và tích hợp sau gộp; ghi rõ giới hạn dữ liệu/provider. Không đánh dấu hoàn tất chỉ vì đã giao task. Mobile theo sau web như phê duyệt ban đầu; Excel và năm sửa lỗi operations vẫn để sau.

## 2026-11-28 · Codex · Khôi phục P4 theo xác nhận chủ dự án
- Chủ dự án đã cho phép khởi động lại P4. Dispatch cũ ctx_2d36c4bc6016 dừng thành công (ptyKilled=true), worktree giữ nguyên và sạch.
- Khởi động lại cùng task task_3115d9b5a5cd trên cùng worktree, dispatch mới ctx_396a572d84b5, terminal term_94809536-2d16-4eff-9b7e-f11105282902. Orca trả turn_start_unobserved, chưa xác nhận bắt đầu; không coi là hoàn tất và không gửi trùng nhiệm vụ.
- P5/P6 xác nhận đang làm qua transcript, P6 đang bổ sung kiểm tra PostgreSQL/HTTP sau compile và test notification. Chưa push.

## 2026-11-28 · Codex · P4 chạy được, gộp API P5/P6 local
- Chủ dự án yêu cầu tiếp tục P4. Lần khởi động mới vẫn mắc draft; đã dừng thành công dispatch ctx_396a572d84b5 và tái sử dụng phiên P5 đã hoàn tất, xác nhận idle/live trên cùng task P4. Dispatch mới ctx_b98c962205dd, task task_3115d9b5a5cd, terminal term_178f20d4-c640-4961-b3f6-82ca20b2733c, workspace dash-api-p5; worker-start xác nhận ready.
- P5 commit worker 5dd6b18 gộp master 6e9fd94, P6 worker 4a61a6a gộp master 9fe60d8 không xung đột. Notification delivery-health ở commit local 0099286 trong repo notification. P4 sẽ bổ sung catalog quyền dùng chung.
- Bằng chứng worker: P5 18 test qua (4 query, 4 HTTP, 10 PostgreSQL); P6 26 test trong lượt cuối (6 PostgreSQL) và 17 integrity test hồi quy trước đó; notification 11 endpoint + 12 guard test, tsc và build qua. Chưa chạy lại tích hợp P4/P5/P6 trên master vì P4 đang thực thi, chưa coi các kết quả riêng là kiểm tra bản gộp.
- Yêu cầu chưa push giữ nguyên ở mọi repo; chờ kết quả P4 để gộp, kiểm tra đợt 2 rồi chạy các task giao diện đã xếp.
