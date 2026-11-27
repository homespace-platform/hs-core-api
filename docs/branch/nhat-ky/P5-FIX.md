# P5-FIX: sửa mục 1-4 của P5-REVIEW và kiểm khởi động

Không commit, không sửa test.

## Trạng thái
- [x] Đọc tài liệu, xác minh 4 mục trong mã (cả 4 đúng).
- [x] JOB 1: sửa
- [x] JOB 1: unit + PostgreSQL
- [x] JOB 2: kiểm khởi động

## JOB 1
- 1+4: ContractNotificationPublisher xếp dòng không phải digest trước (ORDER BY cờ digest, rồi id); digest chỉ được claim khi không còn dòng hợp đồng claim được. Lỗi dòng hợp đồng: kết thúc lượt như cũ. Lỗi gửi digest: dòng đó lùi sau các digest khác (con trỏ trong bộ nhớ), lượt tiếp tục và ưu tiên hợp đồng; digest lỗi thứ 2 kết thúc lượt. Không có digest thì thứ tự và retry như cũ. Không đổi schema.
  Còn lại: thông báo hợp đồng phát sinh đúng lúc đang chờ ACK một digest phải chờ tối đa 1 lần gửi (15 giây); job 08:00 vẫn dùng 1/4 luồng scheduler.
- 2: RevisionCharges/BranchRevisionTerms trả thêm rootId = coalesce(contract.parentContractId, contract.id); overview (chỉ số và hạn khoản xám) và Cần chú ý bỏ revision khác gốc. RevisionCharges giữ constructor 2 tham số cho test cũ.
- 3: RentalOnboardingService.withFinalNotice dùng chung cho view và landlordSigningOverdue (chỉ đọc rental khi HĐ TENANT_SIGNATURE_PENDING sắp bị tính quá hạn).

Unit (`mvnw -o -B -pl hs-api-service -am test`) run/fail/error/skip: common 21/0/0/0, user 136/0/0/22, storage 71/0/0/0, listing 579/0/0/94, payment 263/0/0/25, contract 1327/0/0/248, api 157/0/0/1. BUILD SUCCESS (gồm BranchDigestPublisherTest).
PostgreSQL, DB trống mới: Overview 9/0/0/0, Attention 1/0/0/0, Associations 21/0/0/0, Layout 4/0/0/0, MonthlyInvoiceNotification 12/0/0/0, LifecyclePhase03 31/0/0/0, RentalWorkflowHttpSystem 1/0/0/0. DB cũ trùng tên cố định đổi tên đuôi _old_p5fix.
SQL claim mới chạy thử trên PostgreSQL (giao dịch rollback): digest d1,d2 + c1,c2 (cùng HĐ), c3 -> c1,c3,d1,d2 (c2 chờ c1); con trỏ=d1 -> c1,c3,d2,d1.

## JOB 2: khởi động (XONG, đạt)
Cách ly: không mở .env/.env.prod. Chạy từ thư mục rỗng (cũng là user.home); `spring.config.location` trỏ bản sao application.properties đã bỏ dòng `spring.config.import` (log TRACE: chỉ import đúng file này, 0 lần nhắc .env); env tạo mới chỉ gồm giá trị giả + SystemRoot; `jdk.net.hosts.file` chỉ có localhost (không phân giải được tên ngoài); endpoint trong env trỏ 127.0.0.1:1, TomTom https://127.0.0.1:1. Không tạo container mới. DB: hs_branch_boot_p5fix trong hs-branch-test-pg. Chạy từ target/classes + classpath runtime (bỏ test, devtools) vì maven-jar-plugin không có trong kho offline, nên chưa kiểm fat jar/Docker image. Script: scratchpad phiên e1a0a164.../boot.py.
- Lần 1 (DB trống, ddl-auto=update, NOTIFICATION_OUTBOX_ENABLED=true): Started sau 28 s; 96 bảng, đủ bảng chi nhánh, parking_target_snapshot, unique event_id outbox; 0 "Error executing DDL" (các WARN "constraint ... does not exist, skipping" là bình thường trên DB trống).
- Lần 2 (cùng DB): Started sau 26 s; số cột/kiểu, constraint, index không đổi.
- GET không token: /branches/x/overview, /branches/x/attention, /branches/attention-summary, /branches/x/layout trả 401; /actuator/health/liveness 200. GET /branches (controller cũ, không đổi trong đợt này) trả 200 khi gọi thẳng Core không token; chưa kiểm nội dung, cần xem riêng.
- Lỗi còn trong log đều do phụ thuộc giả: seed admin Keycloak (được bắt, không chặn), Kafka consumer không nối được. Đã dừng tiến trình sau mỗi lần.
- Không phải sửa mã vì khởi động.

Chưa kiểm: Kafka/consumer/feed thật, cron 08:00 thật, tải.

## Sửa theo "Rà cuối bản sửa P5" (mục 1, 2)
Thay cho mô tả publisher ở JOB 1 mục 1+4 (đã bỏ ORDER BY theo cờ và con trỏ):
- Đường hợp đồng như HEAD (claim, ORDER BY o.id, tối đa 20 dòng, lỗi là dừng đến lượt poll sau), chỉ thêm điều kiện loại dòng digest. Không có trạng thái hoãn nào.
- Digest có claim và vòng riêng, chạy sau hợp đồng: tối đa 20 dòng hoặc ngân sách 2 giây (kiểm giữa hai lần gửi), dừng ở lỗi đầu tiên. Dòng lỗi (gửi, ghi sent_at hoặc commit) nghỉ 5 phút, lưu trong bộ nhớ từng instance (restart thì thử lại ngay; instance khác tự tính).
- Lượt hợp đồng sạch mà có claim digest thì chạy hợp đồng thêm một lần trong cùng poll (thông báo commit trong lúc gửi digest không đợi poll sau). Lượt hợp đồng lỗi không chạy lại.
- Còn lại: thông báo phát sinh trong lúc chờ ACK digest vẫn chờ lần gửi đó (tối đa 15 giây chờ ACK). Test unit 2 vẫn đạt vì mock trả digest cho cả claim hợp đồng (1 lần) lẫn claim digest (1 lần); trên SQL thật claim hợp đồng không nhận digest và dòng lỗi bị loại trong 5 phút.
- Bean wiring không đổi nên không kiểm khởi động lại.
- Unit run/fail/error/skip: common 21/0/0/0, user 136/0/0/22, storage 71/0/0/0, listing 579/0/0/94, payment 263/0/0/25, contract 1329/0/0/249, api 157/0/0/1.
- PostgreSQL DB trống mới (_p5fix2): BranchOverview 10/0/0/0 (gồm ca thứ tự commit muộn), MonthlyInvoiceNotification 12/0/0/0, BranchAttention 1/0/0/0. Câu claim digest có NOT IN chạy thử trên PostgreSQL: bỏ d1, nhận d2.
