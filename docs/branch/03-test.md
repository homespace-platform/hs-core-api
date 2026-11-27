# 03 · Kế hoạch test và thử tay

Test Phase 1 và 2 đã có. Xem [kết quả Phase 1](05-tien-do.md#xác-minh-p1-test-01) và [kết quả Phase 2](05-tien-do.md#xác-minh-p2-test-01). Các tên test của phase sau dùng khi task tương ứng đã tạo file. Dùng dữ liệu giả, không đọc `.env` hoặc tệp bí mật.

## Ma trận theo phase

| Ca | Test và kết quả mong đợi | Mức |
|---|---|---|
| P1-01 | Chi nhánh cũ/tạo rồi bỏ qua: GET rev 0, cây rỗng; form phí/bãi xe, danh sách và API chi nhánh cũ vẫn dùng được. | Unit, API, tay |
| P1-02 | Cả bốn cách tạo, nhà nguyên căn 1 ô; nhân tầng, thêm nhiều, đổi mã, ô lối đi, chuyển bằng menu, hoàn tác; reload giữ đúng thứ tự và id. | Unit, web, tay |
| P1-03 | 401 thiếu danh tính, 403 ghi thiếu LISTING_POST, 404 chủ B truy cập CN A có/không có sơ đồ; id phòng/lầu sai, mã trùng sau chuẩn hóa, payload lỗi → 400 và giữ y nguyên sơ đồ. | API |
| P1-04 | Hai PUT dùng cùng revision trên hai transaction: một thành công, một 409, kết quả nguyên vẹn. Layout lỗi không hoàn tác POST chi nhánh đã lưu. GET layout chạy lặp không ghi. | PostgreSQL, tay |
| P2-01 | Gắn/gỡ tin đang có, nhiều tin cùng ô; chủ khác/cross-branch/lối đi bị chặn. Gửi lại cùng lệnh không tạo row trùng. Tạo tin qua ô và prefill; tin nhân bản không lấy roomId nguồn. | Unit, API, web |
| P2-02 | Đổi/bỏ CN thật của tin gỡ link trong cùng giao dịch, không dời HĐ; nháp chờ duyệt/từ chối không gỡ. Đua gắn khi tin chưa có link, đua xóa CN đích theo hai thứ tự commit và đua khóa bãi xe với gắn/gỡ/đổi CN. Hai tin trùng tên và >100 tin vẫn quy đúng theo id. | Unit, PostgreSQL |
| P2-03 | Gắn tay HĐ chưa thuộc CN, đưa vào khay, chuyển cùng CN, gỡ; chặn chủ khác, HĐ cọc, phụ lục và chuyển CN trực tiếp. Cọc/con mở được đúng gốc hoặc trang gốc có sẵn. | Unit, API, tay |
| P2-04 | Tạo HĐ tay và tự động: branch/phòng hợp lệ → đúng ô; branch hợp lệ thiếu phòng hoặc xung đột → khay; branch không hợp lệ → chưa gắn. Retry HĐ có sẵn không tự gắn lại sau gỡ. Hồ sơ trước HĐ theo Q1. | Unit, PostgreSQL, API |
| P2-05 | Hai HĐ từ hai tin cùng ô chồng lịch, hai request dùng transaction độc lập: tối đa một vào ô, đầu mút bằng nhau hợp lệ. Đua auto với tay, với đổi CN của tin, với xóa phòng/CN; lock không deadlock, không link mồ côi, luồng tạo HĐ không hỏng vì xung đột dự kiến. | PostgreSQL |
| P2-06 | Gia hạn, phụ lục ký kéo dài, duyệt đổi ngày vào và HĐ hết hạn chưa bàn giao đối chiếu Q2. Ép giao dịch bàn giao commit giữa lúc job đọc lịch và ghi khay. HĐ kết thúc vẫn gỡ link; xóa phòng có HĐ hiệu lực chặn, xóa phòng chỉ có lịch sử giải link, xóa CN có HĐ trong khay chặn. Giữ nguyên chặn tin active cũ. | Unit, PostgreSQL, tay |
| P2-07 | Bật/tắt thuê ngoài/sửa, xung đột lượt thuê theo Q5; cờ đã bật bị ẩn khi sau đó có giữ chỗ và hiện lại khi hết giữ chỗ. A ghi thất bại không ghi sang B. GET assignments/candidates lặp lại không ghi. So cột/bảng nghiệp vụ cũ theo mục dưới. | API, PostgreSQL |
| P3-01 | Các trạng thái phòng từ nguồn thật: ACTIVE, hết hạn/bàn giao, lượt vào sau, ACCEPTED, tin live đang chờ duyệt bản sửa, phòng trống/thuê ngoài/sửa; nhiều nguồn ưu tiên đúng. | Unit, web |
| P3-02 | Sáu màu, CONFIRMED khi invoice chưa sync, hạn ≤3 ngày/quá hạn, gia hạn, tháng theo dueAt biên múi giờ, nợ cũ, INITIAL, bỏ rolled/cancel, nhiều khoản. Q6/Q7 đã chốt; chữ/icon và Back giữ lớp/tháng. | Unit, web, tay |
| P3-03 | 401/404, dữ liệu A/B, GET overview hai lần khi có dữ liệu đủ để sync có thể ghi; 0 ghi. Bấm vào đúng invoice, ID lạ không đổi sang khoản khác, trước hiệu lực mở đúng cọc/khoản đầu ở trang gốc. | HTTP, PostgreSQL, tay |
| P4-01 | Từng dòng nhóm việc ở 01 có fixture một dương và một âm, kể cả phía xử lý, hết hạn đúng đồng hồ, dữ liệu thiếu và Q7–Q9. Thẻ và màn chi tiết dùng cùng số; thứ tự ổn định khi bằng hạn. | Unit, web |
| P4-02 | Chủ B không thấy việc/tên của A, GET tổng số và chi tiết không ghi. Xử lý một việc qua trang cũ rồi trở về, việc tự mất; mở đúng phụ lục và panel chấm dứt, vào lịch xem/yêu cầu thuê theo id. | HTTP, PostgreSQL, tay |
| P5-01 | Mốc báo trước 3 ngày cả khi chưa có draft, đúng ngày/đã chuẩn bị/đã phát hành, phí không đo chỉ số; 30/60, ngày kết thúc exclusive và gia hạn đổi mốc. Verify quyền đọc, 0 ghi trên GET. | Unit, PostgreSQL, web |
| P5-02 | Không việc → không gửi; hai worker cùng giờ, retry publisher, chạy lại ngày đó → một digest theo owner/ngày; hôm sau có việc gửi tiếp, không lộ dữ liệu chủ khác. Outbox và consumer xử lý đúng event, nhắc cũ vẫn chạy. | Unit, PostgreSQL, tích hợp consumer |
| EXT-01 | Chỉ sau Q11: tái hiện sửa số người/xe nháp không kèm sync và phụ lục gốc branch null. Kiểm sức chứa, không nhả/mất pool nhánh, reservation null lịch sử; tác động lên luồng cũ và cả tin không thuộc chi nhánh. | L3, PostgreSQL |

### Cách kiểm 0 ghi, hồi quy và đồng thời

- Cố định giờ nghiệp vụ và giờ thật, cô lập job nền. Seed hóa đơn thiếu nháp hoặc quá hạn đủ điều kiện sync, payment cần cập nhật và HĐ chưa đồng bộ. Qua endpoint mới, chụp DB trước/sau từ transaction khác. So HĐ, revision/PDF và dữ liệu ký, closure, rental, tin/branch, parking holds, hóa đơn/chỉ số/phí trễ, payment/QR, outbox và các bảng mới có liên quan. Kiểm không có INSERT/UPDATE/DELETE và không lấy pessimistic lock trên đường đọc; annotation read-only hay mock đơn lẻ chưa đủ. Dùng cùng nguyên tắc cho GET danh sách, ứng viên và số việc.
- Khi thao tác gắn/gỡ/sơ đồ, so từng cột nghiệp vụ trên fixture đã có (đặc biệt `contracts.branch_id`, phí, ngày, lease, chữ ký, tin, reservation) trước/sau. Chạy luôn nhánh nghiệp vụ cũ: kết quả giống fixture chuẩn, ngoài phần link mới mong đợi. Chạy test lại với request cũ không có trường phòng.
- Ca đua dùng hai transaction thật trên PostgreSQL với chốt đồng bộ, kiểm row sau cả hai commit/rollback; có timeout để bắt deadlock và kiểm kết quả nghiệp vụ của hai phía. Tách test Mockito khỏi phụ thuộc DB/Docker. Các bài PostgreSQL/HTTP dùng DB test dùng bỏ được cấp riêng, schema test; test skip tính là chưa xác minh. Không chạy với DB đang dùng.

## Lệnh PowerShell

### Core · regression có sẵn

Chạy từ `hs-core-api`, Java 21. Chọn nhóm theo phần chạm; tên class đã kiểm trong repo. Chưa chạy trong Phase 0.

```powershell
.\mvnw.cmd -pl hs-listing-service -am "-Dtest=PropertyBranchServiceTest,ListingServiceTest,ParkingReservationServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl hs-contract-service -am "-Dtest=ContractDraftResidentsTest,ContractListingSyncTest,ContractAmendmentServiceTest,ContractLifecycleServiceTest,MonthlyBillingServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Khi thay đổi xuyên module, nhóm ở API dưới đây cũng biên dịch các module cần thiết. Với ca lifecycle liên quan, cách gọi test opt-in hiện có là lệnh dưới; dùng môi trường test được cấp (`HS_LIFECYCLE_TEST_PG_URL` trỏ loopback đến DB `hs_phase03_test` và `HS_WORKFLOW_HTTP_PG_URL` trỏ loopback đến DB `hs_workflow_http_test`). Không ghi giá trị bí mật vào docs.

```powershell
.\mvnw.cmd -pl hs-api-service -am "-Dtest=ContractLifecyclePhase03PostgresTest,RentalWorkflowHttpSystemTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

### Core · lệnh theo phase sau khi có test mới

```powershell
.\mvnw.cmd -o -pl hs-api-service -am "-Dtest=BranchLayoutServiceTest,BranchLayoutApiTest,BranchLayoutPostgresTest,UserScopePermissionSecurityTest,PropertyBranchServiceTest,BranchChargeMappingHelperTest,ParkingReservationServiceTest,ProfileGateTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -o -pl hs-api-service -am "-Dtest=BranchRoomLinkServiceTest,ListingServiceTest,ListingStatusServiceTest,RentalRequestServiceTest,PropertyBranchServiceTest,BranchLayoutServiceTest,ParkingReservationServiceTest,BranchAssignmentServiceTest,ContractServiceImplTest,ContractLifecycleServiceTest,ContractAmendmentServiceTest,RentalOnboardingServiceTest,MonthlyBillingServiceTest,BranchAssignmentsApiTest,BranchLayoutApiTest,UserScopePermissionSecurityTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -o -pl hs-api-service -am "-Dtest=BranchOverviewTest,BranchOverviewApiTest,BranchOverviewPostgresTest,BranchAssignmentServiceTest,BranchAssignmentsApiTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -o -B -pl hs-api-service -am "-Dtest=BranchAttentionTest,BranchAttentionApiTest,BranchAttentionPostgresTest,BranchOverviewTest,BranchOverviewApiTest,BranchOverviewPostgresTest,MonthlyBillingScheduleTest,MonthlyBillingServiceTest,RentalOnboardingServiceTest,ContractAmendmentServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.failure.ignore=true" test
.\mvnw.cmd -o -B -pl hs-api-service -am "-Dtest=Branch*Test,MonthlyBillingScheduleTest,MonthlyBillingServiceTest,RentalOnboardingServiceTest,ContractAmendmentServiceTest,UserScopePermissionSecurityTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.failure.ignore=true" test
```

Mỗi dòng tương ứng P1–P5. Lệnh PostgreSQL theo phase ở các mục dưới. Đọc số test chạy/skipped trong Surefire cho từng class. Lệnh P4/P5 chạy tiếp qua MockMvc dù contract đỏ, nên lấy kết quả từ Surefire. P2 trở đi và các ca auth/API chia sẻ theo L3. Kiểm tích hợp Gateway với tài khoản thử.

Trong vòng rà P2, có thể thêm `"-Dmaven.test.failure.ignore=true"` để reactor chạy tiếp qua contract và API dù listing còn test đỏ. Đọc kết quả Surefire từng module để quyết định đạt. Hai unit R04 kiểm lời gọi hook sau khi lập thay đổi ở `ContractAmendmentServiceTest.signedLeaseChangeUpdatesFutureBillingLengthNotThePaidRentalOrRootRevision` và `RentalOnboardingServiceTest.earlierApprovalUpdatesDraftHandoverAlongWithCalendarAndKeepsOriginalRevision`. Ca gia hạn PostgreSQL kiểm ghi khay sau commit.

### Core · PostgreSQL opt-in Phase 1

`BranchLayoutPostgresTest` dùng lại ba biến `HS_TEST_PG_URL`, `HS_TEST_PG_USER` và `HS_TEST_PG_PASSWORD`. Chỉ đặt trong phiên PowerShell dành cho test khi đã có **DB bỏ được, trống, dành riêng cho lần chạy này**, chỉ truy cập qua loopback. Ví dụ dạng URL `jdbc:postgresql://127.0.0.1:55432/hs_branch_layout_test_run1`. Tên DB phải khớp `hs_branch_layout_test_[A-Za-z0-9_]+`, host là `localhost` hoặc `127.0.0.1`, URL không kèm tham số. Cấp tên người dùng và mật khẩu riêng cho DB đó qua hai biến còn lại. Hibernate dùng `create-drop` và dừng khi DDL lỗi. Không trỏ đến DB đang dùng.

```powershell
.\mvnw.cmd -o -pl hs-listing-service -am "-Dtest=BranchLayoutPostgresTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Khi chỉ chạy các test thường hoặc muốn kiểm tra chế độ skip, bỏ `BranchLayoutPostgresTest` khỏi `-Dtest` hoặc chạy với `HS_TEST_PG_URL` chưa đặt. Bốn ca skip nghĩa là mapping JPA/tạo bảng, unique/FK/flush, hai transaction đua revision và rollback thật đều chưa xác minh.

### Core · PostgreSQL opt-in Phase 2

`BranchAssociationsPostgresTest` cần một DB trống, dùng bỏ được, riêng cho ca này. URL phải dùng `localhost` hoặc `127.0.0.1`, tên DB khớp `hs_branch_associations_test_[A-Za-z0-9_]+`, không có query string. Hibernate quản lý toàn bộ schema mặc định bằng `create-drop`, dừng khi DDL lỗi. Tài khoản test cần quyền tạo bảng và ràng buộc trong DB đó. Đặt `HS_TEST_PG_USER` và `HS_TEST_PG_PASSWORD` từ thông tin đã cấp trong cùng phiên PowerShell. Chạy riêng với bộ Phase 1 để tránh tái sử dụng sai DB.

```powershell
$env:HS_TEST_PG_URL = 'jdbc:postgresql://127.0.0.1:55432/hs_branch_associations_test_p2final'
.\mvnw.cmd -o -pl hs-contract-service -am "-Dtest=BranchAssociationsPostgresTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

URL vắng hoặc sai mẫu khiến cả class skip. Kiểm Surefire ghi 0 skip. Suite gọi service và repository thật, đọc các JPQL projection, count, giữ chỗ và bulk write. Mỗi vòng GET assignments/candidates có trace SQL kiểm DML/khóa hàng và snapshot rows PostgreSQL trước/sau. Các ca đua giữ khóa ở hai transaction riêng, có timeout và kiểm link sau commit. Luồng tạo nháp dùng service thật, fixture template/KYC và dữ liệu snapshot giả; một constraint chỉ áp vào chi nhánh test ép PostgreSQL từ chối insert link để kiểm HĐ, revision và payment vẫn commit. Các ca đỏ và nguyên nhân được ghi tại [P2-REVIEW.md](nhat-ky/P2-REVIEW.md).

Probe hai transaction được đưa vào suite này, dùng cùng fixture và cùng điều kiện opt-in. Kiểm cả thứ tự của bãi xe và chấp thuận hồ sơ, thứ tự xóa CN/ghi tin và dữ liệu sau commit. Ca đối chứng chủ động tạo deadlock đã được chạy trong probe gốc theo báo cáo agent. Bộ repo giữ các ca kỳ vọng hai luồng hợp lệ commit và các ca kỳ vọng dữ liệu đúng khi chuyển tin chưa có link hoặc bàn giao đồng thời với job. Vòng cuối biên dịch suite rồi skip do phiên sandbox thiếu thông tin kết nối test. Điều phối chạy đủ 21 ca và ghi kết quả trước khi cập nhật cổng.

### Core · PostgreSQL opt-in Phase 3

Từ `hs-core-api`, đặt `HS_TEST_PG_USER` và `HS_TEST_PG_PASSWORD` của DB test trong cùng phiên PowerShell. DB trống, riêng cho lần chạy, trên `localhost` hoặc `127.0.0.1`, tên `hs_branch_overview_test_*`, URL không có tham số. Hibernate dùng `create-drop` và dừng khi DDL lỗi.

```powershell
$env:HS_TEST_PG_URL = 'jdbc:postgresql://127.0.0.1:55432/hs_branch_overview_test_p3'
.\mvnw.cmd -o -pl hs-contract-service -am "-Dtest=BranchOverviewPostgresTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Ngày 27/11, Maven offline đạt 33 unit, 13 MockMvc P3 và 20 ca P2 chạy kèm. PostgreSQL biên dịch và skip cả 5 ca vì phiên thiếu cấu hình DB test. Suite PostgreSQL dùng service và repository thật, kiểm hai lượt đọc, snapshot DB, 0 DML/khóa hàng, chủ khác 404 và số query bằng nhau giữa 3 và 28 phòng có dữ liệu. Chạy riêng để thấy ca Q6/Q7 và dữ liệu lệch chủ. [Ca cụ thể](nhat-ky/P3-REVIEW.md).

Vòng rà gộp thêm ca ngày vào từ hồ sơ lệch chủ. Bộ P3 có 6 ca PostgreSQL, cả 6 skip trong phiên này. Dùng lệnh trên cho cả 6 ca. [Ca mới](nhat-ky/P4-REVIEW.md#cao).

### Core · PostgreSQL opt-in Phase 4

Từ `hs-core-api`, dùng DB trống dành riêng cho test, tên `hs_branch_attention_test_[A-Za-z0-9_]+`, host loopback, URL không kèm tham số. Đặt `HS_TEST_PG_USER` và `HS_TEST_PG_PASSWORD` của tài khoản được cấp quyền tạo bảng trong cùng phiên. Hibernate dùng `create-drop` và dừng khi DDL lỗi.

```powershell
$env:HS_TEST_PG_URL = 'jdbc:postgresql://127.0.0.1:55432/hs_branch_attention_test_p4test01'
.\mvnw.cmd -o -B -pl hs-contract-service -am "-Dtest=BranchAttentionPostgresTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Kiểm Surefire chạy 1 ca và 0 skip. Ca này gọi service/repository thật cho `METER_TODAY`, `LISTING_EXPIRED`, nợ tháng, bù cọc, quyền chủ và số khớp summary. Nó so hai lần đọc, snapshot toàn bộ bảng, transaction read-only ở `REPEATABLE_READ` và SQL chỉ SELECT, không khóa hàng. Hai endpoint giữ nguyên số query khi thêm 25 phòng có dữ liệu trong cùng lô. Sau khi tin có hạn mới và cọc được xác nhận, các việc tương ứng phải biến mất. Vòng này biên dịch, skip vì thiếu cấu hình/tài khoản DB test dù cổng 55432 phản hồi.

### Core · PostgreSQL opt-in Phase 5

Từ `hs-core-api`, đặt `HS_TEST_PG_USER` và `HS_TEST_PG_PASSWORD` của DB test. Tạo trước hai DB trống, dùng bỏ được qua loopback, vì bộ Overview và Attention có điều kiện opt-in theo tên khác nhau. Hibernate dùng `create-drop`. Dùng cùng tài khoản riêng cho cả hai DB, hoặc đặt lại thông tin kết nối giữa hai lệnh.

```powershell
$env:HS_TEST_PG_URL = 'jdbc:postgresql://127.0.0.1:55432/hs_branch_overview_test_p5'
.\mvnw.cmd -o -B -pl hs-contract-service -am "-Dtest=BranchOverviewPostgresTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
$env:HS_TEST_PG_URL = 'jdbc:postgresql://127.0.0.1:55432/hs_branch_attention_test_p5'
.\mvnw.cmd -o -B -pl hs-contract-service -am "-Dtest=BranchAttentionPostgresTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Overview gồm 9 ca, có hai hồ sơ lệch chủ của P4 #2, fixture P5 đọc lặp/no DML/no lock, ca lệch revision dự kiến đỏ và ca hai transaction ghi digest, ngày kế, hai chủ, Kafka ACK giả lập rồi replay. Attention có một ca, dùng tiếp để kiểm query batch, snapshot và tách owner. Cổng TCP 55432 phản hồi, phiên này thiếu tài khoản và DB test nên toàn bộ ca opt-in skip. Unit/MockMvc trong reactor P5 có 372 đạt, 2 đỏ, 35 skip (gồm các suite PostgreSQL P1–P4); MockMvc overview chạy riêng 13/13 với JSON hai lớp. [Chi tiết ca đỏ](nhat-ky/P5-REVIEW.md).

### Notification · P5

Chạy từ `hs-notification-service`.

```powershell
npm.cmd test -- --runInBand
node node_modules/typescript/bin/tsc --noEmit --incremental false
```

Jest 34 suite, 976 đạt, 9 skip; typecheck đạt. Digest test envelope, ranh giới trường đếm, fanout, bản tin cũ, DLT che dữ liệu và replay giữ đúng danh tính. Còn thử Kafka/Mongo/feed thật theo [KAFKA_TESTING.md](../../../hs-notification-service/docs/KAFKA_TESTING.md#branch-daily-digest).

### Web · lệnh tập trung

Chạy từ `hs-web-app`, dùng version Node theo repo; khảo sát dùng v26.13.1 cho test TS. Các lệnh đến hết P5 lần lượt sau khi có test mới.

```powershell
node --test tests/branch-layout.test.cjs tests/listing-branch-fee-independence.test.cjs
node --test --test-isolation=none tests/branch-assignment.test.cjs tests/branch-layout.test.cjs tests/listing-branch-fee-independence.test.cjs tests/listing-edit-retry.test.cjs
node --test --test-isolation=none tests/branch-overview.test.cjs tests/branch-assignment.test.cjs tests/branch-layout.test.cjs
node --test --test-isolation=none tests/branch-attention.test.cjs tests/branch-overview.test.cjs tests/branch-assignment.test.cjs tests/branch-layout.test.cjs
node --test --test-isolation=none tests/branch-monitoring.test.cjs tests/branch-attention.test.cjs tests/branch-overview.test.cjs tests/branch-assignment.test.cjs tests/branch-layout.test.cjs tests/listing-branch-fee-independence.test.cjs tests/listing-edit-retry.test.cjs
npx eslint app/dashboard/branches
npx tsc --noEmit --incremental false
```

P3 ngày 27/11: Node đạt 37/38. Ca thiếu `payments.status` giữ đỏ. Chế độ mặc định gặp `spawn EPERM`, nên chạy chung tiến trình. ESLint test và bốn file P3 đạt. `tsc --noEmit --incremental false` báo TS2307 ở `.next/types/validator.ts:125` do tham chiếu route đã thiếu `app/dashboard/customers/page.js`. Kiểm Back bằng lịch sử mô phỏng và Link do component render; checklist thử tay qua Gateway vẫn còn.

Vòng P4 ngày 27/11: Node đạt 46/47, ca P3 thiếu `payments.status` đã đạt, ca P4 `size: 0` giữ đỏ. ESLint đạt trên test, service, hook, trang chi nhánh và hai component hợp đồng đổi cho deep link. Tsc vẫn chỉ báo TS2307 ở route customers nêu trên. [Chi tiết](nhat-ky/P4-REVIEW.md).

Vòng P5 ngày 27/11: Node 58/58, gồm các lớp mới, nhãn/đích P4-R2 và `size: 0`. ESLint đạt trên các file TypeScript/Node đã đổi. Tsc chuẩn còn TS2307 của validator cũ. Loại riêng `.next/types/validator.ts` bằng cấu hình tạm thì typecheck đạt. Chưa thử Gateway, trình duyệt và feed thật.

Nếu Node báo `spawn EPERM` trong sandbox Windows, thêm `--test-isolation=none` sau `--test`. Lint thêm các file Phase 1 ngoài route, kể cả file test mới. Dùng `node:test`, `node:assert` và loader test hiện có. Không có script `npm test`. Chỉ chạy rộng nếu cần so hồi quy hoặc xác minh routing. Khi kiểm routing cần build, dùng `npm run build` với môi trường test đã được cấp.

| Baseline web theo khảo sát tại mốc `6dff15b`, 2026-11-26 | Kết quả khảo sát |
|---|---|
| `node --test tests/*.test.cjs tests/*.test.mjs scripts/*.test.mjs` | 739 đạt, **85 lỗi / 824**; danh sách file và lỗi ở [khao-sat/web.md](khao-sat/web.md#5-test-lint-typecheck). |
| `node --test lib/*.test.ts` | 32/32 đạt. |
| `npx tsc --noEmit --incremental false` | 1 lỗi trong `.next/types/validator.ts`, thiếu page customers; nguyên nhân output cũ mới là giả thuyết. |
| `npm run lint` | 349 lỗi, 5.731 cảnh báo; theo khảo sát lỗi ở `public/vendor` và `tests/`. |
| `npx eslint app/dashboard/branches` | Không in lỗi theo khảo sát. |

Khi chạy lại ghi mốc HEAD, lệnh, số chạy/đạt/lỗi/skip và dấu vết lỗi mới. Đối chiếu theo từng bài chứ không chỉ tổng 85. Backend khảo sát chưa chạy test nên chưa có baseline đạt/lỗi.

## Thử tay trước mỗi cổng

Dùng chủ A có quyền đăng, chủ B, tài khoản thiếu quyền ghi; hai CN cùng tên, chi nhánh cũ, nhà nguyên căn, hai tin cùng phòng và dữ liệu mẫu tiền/HĐ. Khi đã được cấp môi trường test, mở web qua Gateway theo quy trình repo; ghi màn hình/URL thấy sai và trạng thái trước/sau.

| Phase | Kịch bản |
|---|---|
| P1 | Tạo CN loại phòng → vào bước sơ đồ → bỏ qua → mở lại và thử bốn mẫu. Tạo HOUSE mới, mở tab Sơ đồ của HOUSE cũ và kiểm mỗi chi nhánh tự có đúng một phòng. Thử lại sau khi PUT lỗi, kiểm chi nhánh đã tạo và nháp còn. Kiểm phí/bãi xe và đếm tin cũ. Dùng bàn phím, dark mode và zoom 200%. Mở hai tab để kiểm 409 khi cùng hình dạng nhưng khác ID và kiểm giữ nháp khi Back. |
| P2 · chờ chủ dự án nghiệm thu ở cổng DỪNG | Qua Gateway, gắn tin/HĐ, tạo tin từ ô, gắn tay vào khay rồi chuyển phòng, gỡ. Tạo HĐ ở cả hai đường, thử với phòng bận và với chủ B. Kiểm cờ ngoài/sửa trước và sau khi chấp thuận ở màn cũ, đăng/sửa/duyệt tin thường, xóa phòng/CN và mở đúng hồ sơ thuê. |
| P3 | Chuyển lớp, tháng, bấm chú giải/tìm. Mở hóa đơn từ ô nhiều khoản, mở khoản trước hiệu lực, Back vẫn giữ tầng/lớp/tháng. |
| P4 | So số việc giữa thẻ và chi tiết. Bấm vào yêu cầu thuê, lịch hẹn, hóa đơn và đề nghị HĐ; xử lý ở trang gốc, quay lại thấy việc giảm. |
| P5 | Trên giờ test tiến qua ngày −3, ngày ghi, mốc 60/30. Chạy job qua giờ gửi hai lần, kiểm chỉ một thông báo khi có việc và mở đúng đích đã chốt. |
