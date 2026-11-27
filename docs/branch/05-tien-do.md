# 05 · Tiến độ và nhật ký

Mốc gốc để đọc/so sánh: core `925158e`, web `6dff15b`. Phase 2 đóng ngày 2026-11-27. Tiếp tục Phase 3 theo ủy quyền. [Kết quả kiểm đóng](#kiểm-đóng-phase-2).

| Task | Người theo kế hoạch | Trạng thái | Người đang giữ / bằng chứng / bước tiếp |
|---|---|---|---|
| P0-DOC-01 | Codex | Xong | Sáu file 01–06; kiểm tĩnh theo phạm vi. Điều phối kiểm lại trước khi hỏi chủ dự án. |
| P1-WEB-01 | Sonnet | Xong (chờ nghiệm thu phase) | Web 25/25 gồm 4 ca hồi quy phí |
| P1-BE-01 | Sonnet | Xong | Tiêu chí code và unit service đạt. Mapping, flush và khóa trên PostgreSQL ở cổng nghiệm thu. |
| P1-BE-02 | Sonnet | Xong | Hợp đồng API, quyền ghi và các ca payload P1-R03 đạt trong MockMvc. Tích hợp Gateway ở cổng nghiệm thu. |
| P1-TEST-01 | Codex | Xong (chờ nghiệm thu phase) | Web 25/25 gồm 4 ca hồi quy phí |
| P2-WEB-01 | Sonnet | Xong | Thêm vào khay giữ phòng đã lưu. Còn thử tay qua Gateway. |
| P2-WEB-02 | Sonnet | Xong | Unit payload tạo tin từ ô và đổi/bỏ/quay lại chi nhánh đạt. Còn thử tay qua Gateway. |
| P2-BE-01 | Sonnet | Xong | R02b khóa chi nhánh nguồn. Cấp slug cho tin cũ sau khóa chi nhánh. |
| P2-BE-02 | Sonnet | Xong | R03b theo chi nhánh thật. R04/R04b/R04c xử lý theo chốt 27/11, chiếu khay khi đọc. |
| P2-BE-03 | Sonnet | Đạt | MockMvc trả 400 cho query sai hoặc thiếu. Test API chi nhánh và kiểm năm quyền ghi đều đạt. |
| P2-TEST-01 | Codex | Xong | Kết quả điều phối và test tập trung ở [kết quả kiểm đóng](#kiểm-đóng-phase-2). |
| P3-WEB-01 | Sonnet | Đã kiểm lại | Parser thiếu trạng thái đã sửa, test đạt. |
| P3-BE-01 | Sonnet | Đã kiểm lại mã | Projection ngày và kỳ thuê đã join theo đúng chủ. Chờ PostgreSQL xác minh. |
| P3-TEST-01 | Codex | Đã rà lại | 9 ca Overview PostgreSQL đạt theo P5-FIX. Còn thử qua Gateway. |
| P4-WEB-01 | Sonnet | Đạt kiểm Node | Parser từ chối kích thước trang 0, đủ ba nhãn/đích P4-R2. |
| P4-BE-01 | Sonnet | Đã kiểm lại | PostgreSQL theo P5-FIX. Unit P4-R2 đạt tại biên hạn ký của hồ sơ cũ. |
| P4-TEST-01 | Codex | Đã chạy lại | Unit, MockMvc và Node đã chạy. [Kết quả P4](nhat-ky/P5-REVIEW.md#kiểm-lại-p4). |
| P5-WEB-01 | Sonnet | Đạt kiểm Node | Hai lớp mới giữ mốc và đích từ server. Còn thử qua Gateway. |
| P5-BE-01 | Sonnet | Đã kiểm lại | Đã chặn revision lệch gốc. Phụ lục/gia hạn hợp lệ vẫn dùng được. Unit/MockMvc và PostgreSQL theo P5-FIX. |
| P5-BE-02 | Sonnet | Cần sửa publisher | Con trỏ digest lỗi đổi thứ tự giữa hợp đồng. Lỗi ghi nhận sau ACK có thể chặn digest sau. |
| P5-TEST-01 | Codex | Đã rà cuối | 382 ca offline đạt. Ca PostgreSQL mới biên dịch, chờ chạy. [Kết quả](nhat-ky/P5-REVIEW.md#rà-cuối-bản-sửa-p5). |
| EXT-BE-01 | Sonnet và Codex | Chờ chủ dự án / Q11 | Chưa tái hiện runtime; xem backend §2.4 và 01 §5. |

Trạng thái khi giao thật: **Sẵn sàng** → **Đang làm** → **Xong**, hoặc **Dừng dở**/**Bị chặn**, kèm lý do và bước kế. Khi giữ file, ghi rõ đường dẫn đã nhận trong ô cuối. Mỗi phase ghi mốc chủ dự án mở/duyệt bằng một dòng nhật ký riêng.

| Ngày | Ai | Làm gì | Kiểm tra gì | Còn dở gì |
|---|---|---|---|---|
| 2026-11-26 | Claude điều phối | Concept và bản đồ tài liệu | Theo README | Kiểm lại sáu tài liệu, hỏi chủ dự án và ghi cổng P0. |
| 2026-11-26 | Sonnet | Khảo sát core và web | Code core; code và baseline web ghi ở khảo sát | Chưa tái hiện runtime lỗi parking; baseline core chưa chạy. |
| 2026-11-26 | Codex | P0-DOC-01, thiết kế/phase/test/câu hỏi/tiến độ/bàn giao | Đọc hai repo, POM, route và luồng ghi; xác nhận HEAD, rà mã task và phạm vi file; chưa chạy test | 11 quyết định chờ chủ dự án. Q1–Q5 cần trước phần tương ứng P2. |

### Điểm dừng hiện tại

Rà cuối P5 hoàn tất. Cổng Phase 5 chờ sửa publisher, chạy ca PostgreSQL thứ tự mới với DB test riêng và xác minh Kafka → consumer → feed cùng Gateway. [Lệnh và điều kiện PostgreSQL](03-test.md#core--postgresql-opt-in-phase-5). Các mục thử tay Phase 1–2 vẫn ở checklist.

- 2026-11-27 · Codex · P5-TEST-01: đối chiếu lại P4, thêm test Core/Web/Notification và hợp đồng sự kiện Kafka. Giữ hai lỗi tái hiện trong unit và ca lệch revision trong suite PostgreSQL. [P5-REVIEW](nhat-ky/P5-REVIEW.md).

- 2026-11-26 · Claude (điều phối) · Đọc lại khảo sát, 01, 02, 04 và đối chiếu concept; sửa 01 mục nhà nguyên căn thành "tự tạo 1 ô". Chưa đọc kỹ 03, 06. Chưa code, chưa chạy test. Phase 0 xong, **chờ chủ dự án mở Phase 1**.
- 2026-11-26 · Chủ dự án mở **Phase 1** ("tiếp tục"). Q1–Q11 vẫn chờ. Trong lúc agent chạy, nhật ký chi tiết ở `nhat-ky/`.
- 2026-11-26 · Codex · Rà bốn cách sửa, thêm test R01/R04 vào bộ web, ghi bốn ca đỏ và cập nhật checklist. Thêm Q12 để chốt giới hạn kích thước sơ đồ ở server.

### Xác minh P1-TEST-01

2026-11-26 · core `925158e`, web `6dff15b` và các thay đổi trong cây làm việc. Bộ test gồm `BranchLayoutServiceTest`, `BranchLayoutApiTest`, `BranchLayoutPostgresTest`, `tests/branch-layout.test.cjs` và khai báo quyền cho `BranchLayoutController.saveLayout` trong `UserScopePermissionSecurityTest`.

| Vòng cuối sau các bản sửa | Kết quả |
|---|---|
| Maven với `BranchLayout*Test,PropertyBranchServiceTest,UserScopePermissionSecurityTest` | Điều phối chạy, dùng lại kết quả vì đầu vào backend giữ nguyên. 38 ca, 34 đạt, 0 lỗi, 4 skip PostgreSQL. |
| Web trước khi thêm test R01/R04 | Điều phối ghi 9/9 đạt, ESLint các file đổi sạch và tsc còn 1 lỗi baseline TS2307 ở `.next/types/validator.ts`. |
| `node --test --test-isolation=none --test-reporter=tap tests/branch-layout.test.cjs` | Chạy sau khi thêm test. 21 kết quả, 17 đạt, 4 lỗi P1-R04a–d, 0 skip. R01 đạt khi nhận lại PUT đã lưu ở đúng revision kế tiếp và khi từ chối cây khác ID hoặc revision. R04 đạt kiểm khóa user + chi nhánh và chặn Lưu với nháp cũ. [Các ca còn lỗi](nhat-ky/P1-REVIEW.md#lỗi-còn-mở-giữ-test-đỏ). |
| `node node_modules/eslint/bin/eslint.js tests/branch-layout.test.cjs` | Exit 0 cho file test vừa thêm ca. |

#### Lịch sử trước các bản sửa

| Lệnh đã chạy | Kết quả |
|---|---|
| `.\mvnw.cmd -o -pl hs-api-service -am "-Dtest=BranchLayoutServiceTest,BranchLayoutApiTest,BranchLayoutPostgresTest,UserScopePermissionSecurityTest,PropertyBranchServiceTest,BranchChargeMappingHelperTest,ParkingReservationServiceTest,ProfileGateTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` | Exit 1. 75 ca gồm 65 đạt, 6 lỗi, 4 skip. Listing: service mới 17 đạt, hồi quy phí/bãi xe/chi nhánh 35 đạt, PostgreSQL 4 skip. API: 6/12 đạt, 6 lỗi P1-R03; RBAC 2/2 và ProfileGate 5/5 đạt. Dùng Java 21 và wrapper Maven 3.9.16. |
| `node --test tests/branch-layout.test.cjs tests/listing-branch-fee-independence.test.cjs` | Exit 1 do sandbox chặn sinh tiến trình (`spawn EPERM`), chưa chạy ca nào ở lần này. |
| `node --test --test-isolation=none tests/branch-layout.test.cjs tests/listing-branch-fee-independence.test.cjs` | Exit 1. 12/13 đạt. Ca mã phòng hậu tố số lớn timeout, xem P1-R02. Cả 4 ca hồi quy phí đạt. Dùng Node v26.13.1. |
| `node node_modules/typescript/bin/tsc --noEmit --incremental false` | Exit 1. Một lỗi TS2307 tại `.next/types/validator.ts:125`, thiếu `app/dashboard/customers/page.js`, trùng lỗi baseline ghi trong 03. |
| `node node_modules/eslint/bin/eslint.js app/dashboard/branches components/branch lib/branch-layout.ts lib/dashboard-role.ts services/branch-layout.service.ts types/branch-layout.type.ts tests/branch-layout.test.cjs` | Exit 0. |

Các kết quả trước khi sửa giữ để đối chiếu. Sáu lỗi API P1-R03 và lỗi timeout P1-R02 đã qua ở vòng cuối.
- 2026-11-26 · Sonnet sửa P1-R04a–d. Claude (điều phối) chạy lại tại core `925158e`, web `6dff15b` + thay đổi chưa commit: node branch-layout + fee-independence 25/25; eslint file đã đổi sạch; tsc còn 1 lỗi baseline `.next`. Backend (lần chạy trước, code backend không đổi): 38 test, 34 đạt, 4 skip PostgreSQL. Giới hạn: hai tab cùng sửa thì bản nháp lưu trên máy là bản ghi sau cùng. **Dừng ở cổng Phase 1.**
- 2026-11-26 · Chủ dự án chốt Q1–Q5 theo đề xuất, Q11 sửa trước Phase 2, Q12 áp giới hạn, và **mở Phase 2** (thử tay P1 làm sau). Thứ tự: (1) Codex viết test tái hiện EXT-BE-01, song song Sonnet làm P2-WEB-01/02; (2) Sonnet sửa EXT-BE-01 + giới hạn Q12; (3) P2 backend; (4) Codex test P2.
- 2026-11-26 · Sonnet xong EXT-BE-01 + giới hạn Q12 (`nhat-ky/EXT-BE-02-fix.md`). Theo agent: unit 2272 chạy/0 lỗi/358 skip; PostgreSQL (container tạm `hs-branch-test-pg`, 127.0.0.1:55432): BranchLayout 4/4, LifecyclePhase03 31/31, RentalWorkflowHttp 1/1. Câu hỏi mở mới: tin rời chi nhánh rồi đồng bộ nháp mà không đổi xe thì chỗ giữ vẫn ở chi nhánh cũ (chờ chủ dự án). Bắt đầu P2 backend (một agent, tuần tự BE-01→02→03); Codex rà tĩnh EXT.
- 2026-11-26 · Codex rà tĩnh phần sửa bãi xe (`nhat-ky/EXT-BE-03-review-codex.md`): "chưa nên gộp EXT-BE-01". Phân loại của điều phối: #5, #6 thuộc logic chọn pool vừa viết → sửa sau khi agent P2 backend xong (tránh đụng build/file). #1, #3 là lỗi có sẵn ngoài phạm vi đã duyệt → hỏi chủ dự án. #4 trùng câu hỏi "tin rời chi nhánh" → hỏi chủ dự án. #2 (phụ lục duyệt trước bản sửa) chỉ ảnh hưởng dữ liệu thử chưa chính thức → tạo lại dữ liệu, không viết code. CHƯA sửa mục nào.
- 2026-11-27 · Sonnet xong P2 backend (`nhat-ky/P2-BE.md`). Theo agent: compile OK; 1 test đỏ UserScopePermissionSecurityTest (thiếu 5 API ghi mới, chờ Codex); PostgreSQL layout 4/4, lifecycle 31/31, HTTP 1/1; hành vi mới chưa có test. Tiếp theo: (a) Sonnet sửa bãi xe #5, #6 [đang làm]; (b) Codex P2-TEST-01 + rà lỗi; (c) sửa lỗi; (d) dừng cổng P2. Container test `hs-branch-test-pg` còn chạy, xong P2 thì xóa.

### Xác minh P2-TEST-01

2026-11-27 · Codex · core `925158e`, web `6dff15b` và thay đổi trong cây làm việc. Q1–Q5 đã chốt. Chỉ thêm/sửa test và tài liệu. [P2-REVIEW.md](nhat-ky/P2-REVIEW.md) có ca tái hiện, vị trí sản phẩm và các lỗi mở.

| Phạm vi | Kết quả |
|---|---|
| Core Maven offline, JUnit/Mockito/MockMvc và hồi quy | 184 ca, 183 đạt, 1 đỏ, 0 skip; exit 1. Listing 111/111, contract 50/50, API 22/23. `UserScopePermissionSecurityTest` 2/2. R06 tái hiện cả `page=invalid` và thiếu `kind` trả 500 thay vì 400 trong một ca. |
| PostgreSQL `BranchAssociationsPostgresTest` | 13 ca, 10 đạt, 3 đỏ, 0 skip; Maven exit 1. R01 lỗi flush khi bỏ phòng lịch sử, R03 mất link khi đua đổi CN, R04 gia hạn đã commit và GET đã chiếu vào khay nhưng link cũ còn phòng. GET lặp không DML/khóa hàng trên fixture. Đua xóa phòng/CN, constraint và commit tạo HĐ khi SQL ghi link thất bại đã chạy. DB riêng `hs_branch_associations_test_p2test01` trên PostgreSQL đang chạy ở loopback. |
| Web `node --test --test-isolation=none` | 37/37 đạt, gồm 9 ca P2, layout, phí và retry sửa tin. Bổ sung context mới cho fixture retry sửa tin và kiểm payload cũ. Mặc định `node --test` bị sandbox chặn spawn. |
| Web ESLint | Exit 0 trên các file Phase 1/2 liên quan, test mới và fixture retry. |
| Web `tsc --noEmit --incremental false` | Exit 1 với đúng một lỗi baseline TS2307 ở `.next/types/validator.ts:125`, thiếu `app/dashboard/customers/page.js`. |
| Web ↔ Gateway | Đã so route, envelope, tham số và payload ở code, MockMvc và client test. Còn thử xuyên trình duyệt với Gateway đang chạy. |

Chi tiết cách chạy và điều kiện DB ở [03-test.md](03-test.md#core--postgresql-opt-in-phase-2). Hai GET được kiểm bằng controller mock và service/repository thật trên PostgreSQL. Chưa thử ký thật hoặc tải ở giới hạn 1.500 phòng. Phase 2 còn lỗi mở nên giữ nguyên các ô trống trong checklist.
- 2026-11-27 · Codex xong P2-TEST-01 vòng 1 (`nhat-ky/P2-REVIEW.md`: R01–R07; Maven 183/184, PostgreSQL 10/13, web 37/37). Sonnet đang sửa R01–R07. Sau đó: chạy lại test, Codex kiểm vòng cuối, dừng cổng P2.

### Vòng cuối P2-TEST-01

2026-11-27 · Codex · Mốc core/web giữ như trên. Chỉ sửa test và tài liệu. Đối chiếu từng R01–R07 và ghi R02b, R03b, R04b, R04c vào [nhật ký rà](nhat-ky/P2-REVIEW.md#lịch-sử--lỗi-mở-trước-chốt-2711). Ba ca `BranchRoomLinkServiceTest` giữ stub và kỳ vọng cũ theo điều kiện của lượt rà, vì cơ chế khóa mới còn R02b. Các lỗi Mockito của ba ca này phát sinh từ khác biệt đường gọi, còn sai lệch dữ liệu đồng thời nằm ở ca PostgreSQL mới.

| Phạm vi | Kết quả vòng cuối |
|---|---|
| Listing unit | 250 ca phân biệt, 247 đạt, 2 failure và 1 error trong `BranchRoomLinkServiceTest`. `ListingServiceTest` 61/61 sau khi thêm ca R07, `RentalRequestServiceTest` 38/38. |
| Contract unit | 315/315, gồm phụ lục 50/50 và onboarding 46/46 với kiểm tra hook R04. |
| API unit | 23/23, gồm `BranchAssignmentsApiTest` 9/9, `BranchLayoutApiTest` 12/12 và `UserScopePermissionSecurityTest` 2/2. |
| PostgreSQL | 21 ca opt-in biên dịch, 21 skip. Cổng loopback 55432 phản hồi, phiên sandbox thiếu URL/tài khoản cho DB test riêng. Agent sửa đã báo bộ cũ 13/13 và probe tạm 8/8. Các ca mới cần chạy theo [03-test.md](03-test.md#core--postgresql-opt-in-phase-2). |
| Web unit | 37/37, lệnh `node --test --test-isolation=none` cho bốn file P2 trong [03-test.md](03-test.md#web--lệnh-tập-trung). |
| Web ↔ Gateway | Còn [thử tay qua Gateway](02-phase.md#thử-tay-phase-2-qua-gateway). |

JUnit chạy bằng `mvnw.cmd -o -B` với các tham số `-D` được quote. Lượt reactor đầu dùng `maven.test.failure.ignore=true` để chạy tiếp contract và API sau listing đỏ. Lượt bổ sung `ListingServiceTest,RentalRequestServiceTest,BranchAssociationsPostgresTest` exit 0 với ca PostgreSQL skip. Giữ các ca tái hiện và chạy lại với DB test sau khi sửa. Các quyết định bãi xe #1, #3, #4 chờ chủ dự án tại [Q13–Q15](04-cau-hoi.md).
- 2026-11-27 · Codex vòng cuối P2: còn mở R02b, R03b, R04b, R04c (`nhat-ky/P2-REVIEW.md` mục đầu). Điều phối chạy BranchAssociationsPostgresTest trên DB trống: 21 chạy, 18 đạt, 3 đỏ (R02b, R03b, R04b), 0 skip. Đang hỏi chủ dự án: (R04) khay do trùng lịch là tạm thời hay vĩnh viễn; bãi xe Q13–Q15. Chưa sửa 4 lỗi này.
- 2026-11-27 · Chủ dự án chốt: trùng lịch chỉ tạm vào khay, hết trùng tự về phòng (cuối `04-cau-hoi.md`); Q13, Q14 làm sau; Q15 giữ ở chi nhánh cũ. Việc tiếp: Sonnet bỏ job/hook R04 và sửa R02b, R03b; chạy lại test; Codex kiểm; dừng cổng P2.
- 2026-11-27 · Chủ dự án uỷ quyền chạy đến hết, không dừng ở cổng phase (cuối `04-cau-hoi.md`). Điều phối quyết Q6–Q10 theo đề xuất. Container test `hs-branch-test-pg` giữ đến khi xong tất cả rồi xoá.

### Kiểm đóng Phase 2

2026-11-27 · Codex xác nhận R02b/R03b và khay chỉ chiếu khi đọc, rồi sửa hai lỗi phát hiện lúc đóng. Cấp slug cho tin cũ sau khóa chi nhánh. Web thêm HĐ vào khay bằng thao tác giữ phòng đã lưu, kể cả khi ứng viên xuất hiện từ trang khay khác. [Chi tiết kiểm đóng](nhat-ky/P2-REVIEW.md#chốt-phase-2--2026-11-27).

- Điều phối trước hai bản sửa · Listing 578 chạy/0 lỗi/94 skip, contract 1241/0/238, API 129/0/1. PostgreSQL Associations 21/21, Layout 4/4, Phase03 31/31, HTTP 1/1.
- Kiểm phần thay đổi · Maven tập trung listing 66/66, contract 8/8, API 14/14. Web assignment 9/9, ESLint sạch. Còn thử tay qua Gateway và kiểm đua gán slug trên PostgreSQL.
- 2026-11-27 · Sonnet xong P3-WEB-01 và P3-BE-01 (`nhat-ky/P3-WEB.md`, `P3-BE.md`). Theo agent: unit BE không lỗi, PostgreSQL P2/P1 21/21, 4/4; web cũ 30/30. Chưa có test P3. Tiếp: Codex P3-TEST-01 + rà lỗi → sửa → Phase 4.
- 2026-11-27 · Codex P3-TEST-01. Maven offline đạt 46 ca P3 và 20 ca P2; PostgreSQL skip 5 ca do thiếu cấu hình DB dành riêng. Node 37/38, lỗi parser giữ đỏ. ESLint đạt. `tsc` vướng TS2307 trong `.next/types/validator.ts`. Bốn phát hiện và ca hồi quy ở [P3-REVIEW](nhat-ky/P3-REVIEW.md). [Lệnh PostgreSQL](03-test.md#core--postgresql-opt-in-phase-3) để điều phối chạy tiếp.
- 2026-11-27 · P3 sửa xong 4 lỗi (web 38/38; BE unit không lỗi; PG Overview 5/5, Associations 21/21, Phase03 31/31). P4-WEB-01 xong code. Đang làm P4-BE-01. Sau đó Codex test + rà GỘP P3+P4.
- 2026-11-27 · P4-BE-01 xong (`nhat-ky/P4-BE.md`; unit không lỗi; PG Overview 5/5; probe chỉ SELECT, 28 query). Tiếp: Codex test + rà P3+P4.

### Xác minh P4-TEST-01 và kiểm lại P3

27/11/2026 · Codex · Core `925158e`, web `6dff15b` và các thay đổi trong cây làm việc. Chỉ sửa test và tài liệu. Billing cũ giữ nguyên phép tính hạn đã ký. Unit luồng ký qua onboarding kiểm giờ mở, giờ hết hạn và đúng lượt ký. [Lỗi và ca cụ thể](nhat-ky/P4-REVIEW.md).

| Phạm vi | Kết quả |
|---|---|
| Maven offline | 304 ca: 293 đạt, 4 đỏ, 7 skip. Contract 279 ca gồm 3 failure, 1 error và 7 skip. MockMvc 25/25 (P3 13, P4 12). Surefire ghi đỏ dù reactor chạy tiếp bằng `maven.test.failure.ignore=true`. |
| PostgreSQL | P3 6 skip, P4 1 skip. Cổng loopback phản hồi, phiên thiếu cấu hình/tài khoản DB test. [Lệnh P3](03-test.md#core--postgresql-opt-in-phase-3) và [lệnh P4](03-test.md#core--postgresql-opt-in-phase-4). |
| Web | Node 46/47, một ca parser P4 giữ đỏ. ESLint đạt. Tsc còn một TS2307 baseline ở `.next/types/validator.ts:125`. |
| Gateway | Còn thử link, xử lý ở trang gốc và Back qua trình duyệt. |
- 2026-11-27 · Codex rà P3+P4 (`nhat-ky/P4-REVIEW.md`: 6 lỗi; 293 đạt/4 đỏ/7 skip; web 46/47). Đang: Sonnet sửa BE (kèm P4-R2) + chạy PostgreSQL; song song Sonnet làm P5-WEB-01 (kèm sửa lỗi web #6).
- 2026-11-27 · P3/P4 BE sửa xong (unit không lỗi; PG Attention 1, Overview 6, Assoc 21, Layout 4, Phase03 31, HTTP 1 đều đạt). P5-WEB-01 xong. Điều phối thêm nhãn web cho PAYMENT_REVIEW, CANCELLATION_DECISION, SIGNING_REOPEN. Tiếp: P5 backend.
- 2026-11-27 · P5-BE-01/02 xong (`nhat-ky/P5-BE.md`; unit không lỗi; notification 722/722; PG Overview 6/6, Attention 1/1). Deploy: notification-service TRƯỚC, core SAU. Tiếp: Codex P5-TEST-01 + rà → sửa → thử khởi động ứng dụng thật với hạ tầng tạm → kiểm tổng → xoá container.
- 2026-11-27 · Codex rà P5 (`nhat-ky/P5-REVIEW.md`: 4 lỗi; Core 372 đạt/2 đỏ/35 skip; Web 58/58; Notification 976 đạt). Đang: Sonnet sửa 4 lỗi + chạy PostgreSQL + thử khởi động ứng dụng với hạ tầng tạm.
- 2026-11-27 · P5 sửa xong 4 lỗi + ứng dụng khởi động được với hạ tầng giả, DB trống và chạy lại (`nhat-ky/P5-FIX.md`). Unit BE không lỗi; PG: Overview 9, Attention 1, Assoc 21, Layout 4, Phase03 31, HTTP 1, MonthlyInvoiceNotification 12. Tiếp: Codex rà vòng cuối bản sửa P5; điều phối kiểm tổng web/notification; xoá container.
- 2026-11-27 · Codex rà cuối P5. 382 ca offline đạt. Publisher còn lỗi thứ tự và retry digest sau ACK. `GET /branches` Core ẩn danh trả danh sách rỗng theo mã có từ `HEAD`. [Biên bản](nhat-ky/P5-REVIEW.md#rà-cuối-bản-sửa-p5).
- 2026-11-27 · Codex rà cuối: còn lỗi publisher (thứ tự thông báo hợp đồng sau digest lỗi). Đang giao Sonnet sửa. Unit liên quan 382/382. Web: 875 test, 790 đạt, 85 lỗi (trước: 739/85 của 824), branch 51/51, tsc 1 lỗi sẵn, eslint sạch.
- 2026-11-27 · Sửa lỗi publisher cuối (contract 1329/0 lỗi; PG BranchOverview 10, MonthlyInvoiceNotification 12, Attention 1 đạt). Điều phối đọc diff: đường thông báo hợp đồng như HEAD, chỉ thêm lọc bỏ digest. Đã xoá container test `hs-branch-test-pg`. **KẾT THÚC đợt chạy uỷ quyền: P1–P5 xong code + test tự động; CHƯA commit; chờ chủ dự án thử tay.**

## 27/11 · Yêu cầu mới của chủ dự án
- Rà các repo, tạo nhánh và push (chưa merge `master`; chủ dự án sẽ yêu cầu merge sau). Đã push `feature/branch-monitoring`: core-api (`269c077`, `a6272c8`), web-app (`f37a370`), notification-service (`de4ce6e`). CI chỉ chạy khi push/PR vào `master`.
- Làm giao diện chi nhánh cho **mobile** (thay ghi chú "để sau" ở `00`), dùng skill ui-ux-pro-max, ưu tiên đẹp và dễ dùng, chia sub-agent song song. Tài liệu mobile: `hs-mobile-app/docs/chi-nhanh/` (nhánh cùng tên).
- Đang chạy: rà trước merge (Codex, `nhat-ky/PRE-MERGE-REVIEW.md`), build web + notification, kiểm lại toàn bộ test core, nền mobile.

## Việc chủ dự án làm SAU CÙNG (khi mọi việc khác xong)
- [ ] Thử tay trên web, kể cả luồng cũ
- [ ] Xem lại các quyết định điều phối chọn thay (cuối `04-cau-hoi.md`, `07`)
- [ ] Yêu cầu merge `feature/branch-monitoring` vào `master`
- [ ] Deploy: notification-service trước, core sau

## 27/11 · ĐIỂM DỪNG · Rà trước merge lần 3

- **Core còn 1 lỗi chặn merge theo Q15.** Ký bản thay thế phụ lục đồng bộ chỉ đổi tiện ích có thể nhả chỗ xe của kỳ gia hạn dù chi nhánh vẫn hoạt động. Unit offline đạt 184/185, giữ một test đỏ tái hiện. [RECHECK-3](nhat-ky/RECHECK-3.md)
- Bản sửa core còn trong cây làm việc. Lượt 2 ghi nhận web và mobile đã qua các lỗi chặn merge của hai repo. Lượt 3 chỉ rà core. [RECHECK-2](nhat-ky/RECHECK-2.md)

## 27/11 · Trạng thái mới nhất (thay mục ĐIỂM DỪNG ở trên)
- Đã sửa 6 lỗi `RECHECK.md`, 2 lỗi `RECHECK-2.md` và lỗi `RECHECK-3.md` (chi tiết `nhat-ky/RECHECK-FIX.md`). Lần sửa RECHECK-3 được kiểm bằng test đỏ của Codex chuyển xanh, Codex chưa rà lại.
- Đã commit và push cả 4 repo trên `feature/branch-monitoring`. CHƯA merge `master`.
- Còn để sau: Q13 (phụ lục đổi xe/thời hạn/bãi sau gia hạn), Q14, các ca ghi đồng thời. Việc chủ dự án làm cuối: thử tay, xem quyết định, merge, deploy (notification, core, web).
- 27/11 · Lượt 2 (Opus giám sát, Sonnet code, không dùng Codex): sửa R-A..R-D theo cuối `04-cau-hoi.md`. Nhật ký `nhat-ky/R2-*.md`.

## 27/11 · Lượt 2 xong (Opus giám sát, Sonnet code)
- R-A, R-B, R-C (`nhat-ky/R2-PARKING.md`) và R-D (`nhat-ky/R2-LAYOUT.md`) đã sửa, điều phối review diff. Core gộp: unit 7 module 0 lỗi; PG BranchAssociations 22/22 trên bản gộp; BranchCapacityRace 1/1, Phase03 31/31, RentalWorkflowHttp 1/1, BranchLayout 4/4 chạy trước khi gộp.
- Web `19d4e56`, mobile `04712a4` gửi `acknowledgedDetach`. Đã push cả 4 repo trên `feature/branch-monitoring`; CHƯA merge `master`.
- Còn lại: draft sync (`ContractListingSync`) khi tin chuyển A→B vẫn theo master; khoá lúc ký chỉ có unit test, chưa test đua PostgreSQL; chưa thử tay.
