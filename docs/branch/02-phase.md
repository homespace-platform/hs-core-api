# 02 · Phase và task

Trạng thái thực tế ở [05-tien-do.md](05-tien-do.md). Phân công theo concept: Sonnet viết code, Codex viết test và docs. Ưu tiên bắt đầu từ web trên fixture theo API thiết kế, rồi nối backend. Mỗi task có đúng một người ghi tại một thời điểm. Các task chỉ chạy song song khi phạm vi ghi tách biệt và phụ thuộc đã thỏa; code và test có thể chuẩn bị song song sau khi giao diện đã ổn định. Trước khi làm, xác nhận tên/đường dẫn file mới trong tiến độ. Trùng file thì tuần tự, kể cả khi khác module về mặt tính năng. Mỗi cổng yêu cầu chủ dự án xác nhận trước khi mở phase kế tiếp.

Ký hiệu đường dẫn: `L`, `C`, `A`, `P` là `hs-listing-service`, `hs-contract-service`, `hs-api-service`, `hs-payment-service` ở core. `W` là repo `hs-web-app`. `src/main` và `src/test` là hai phạm vi riêng. Mỗi dòng nêu phạm vi dự kiến; mở rộng phạm vi để xử lý phụ thuộc chỉ sau khi cập nhật bảng tiến độ và báo người đang giữ file.

## Phase 0 · Tài liệu để triển khai tiếp

| Mã | Người | Phạm vi ghi | Phụ thuộc | Tiêu chí xong |
|---|---|---|---|---|
| P0-DOC-01 | Codex | `docs/branch/01`–`06` | Concept, hai khảo sát, đối chiếu code | Đủ thiết kế, task, test, câu hỏi, tiến độ và bàn giao. |

- [x] Tạo sáu tài liệu và đối chiếu điểm dựa vào code.
- [x] Điều phối kiểm lại (sửa quy tắc nhà nguyên căn cho đúng concept).
- [x] Chủ dự án mở Phase 1 (2026-11-26, "tiếp tục").

Tài liệu Phase 0 đã hoàn tất và chủ dự án đã mở Phase 1. Các câu hỏi Phase 2 có thể chốt trong lúc làm Phase 1.

## Phase 1 · Tạo và sửa sơ đồ

Chủ nhà tạo hoặc bỏ qua sơ đồ khi tạo chi nhánh, sửa được về sau. Chi nhánh cũ mở theo luồng hiện tại.

| Mã | Người | Phạm vi ghi | Phụ thuộc | Tiêu chí xong |
|---|---|---|---|---|
| P1-WEB-01 | Sonnet | `W/app/dashboard/branches/{page.tsx,[id]/page.tsx}`, `W/components/branch/` phần layout, `W/services/branch-layout.service.ts`, `W/lib/{branch-layout,branch-layout-draft,dashboard-role}.ts` | Shape P1; fixture trước, API sau P1-BE-02 | Mẫu, một ô, chức năng sửa, bỏ qua, tải lại, xung đột revision và giữ nháp. |
| P1-BE-01 | Sonnet | `L/src/main` model/repo/service/DTO cho layout, lỗi layout | Shape P1 | GET rỗng, PUT nguyên tử, giữ id, so revision/khóa, đếm phòng thật. |
| P1-BE-02 | Sonnet | `A/src/main` `controller/listing/BranchLayoutController.java` | P1-BE-01 | Hợp đồng API P1, xác thực/owner/quyền ghi; cũ tương thích. |
| P1-TEST-01 | Codex | `L/src/test`, `A/src/test` test BranchLayout; `W/tests/branch-layout.test.cjs`; docs tiến độ | Shape P1 để soạn; đủ ba task trên để chạy cuối | Các ca P1 kiểm được tự động đạt và hết lỗi mở. |

- [x] P1-WEB-01
- [x] P1-BE-01
- [x] P1-BE-02
- [x] P1-TEST-01

Tick task code/test khi các tiêu chí kiểm được bằng code và test tự động đã đạt và task hết lỗi mở. Bốn task P1 đạt (P1-R04a–d đã sửa; điều phối chạy lại web 25/25). Lần sửa R04a–d chưa được Codex rà lại. [Rà soát P1](nhat-ky/P1-REVIEW.md)

### DỪNG · Phase 1 xong code, CHỜ chủ dự án nghiệm thu

**Chưa xác minh / chờ chủ dự án nghiệm thu**

- [ ] PostgreSQL thật theo [03-test.md](03-test.md#core--postgresql-opt-in-phase-1). Bốn ca opt-in đã skip. Cần kiểm mapping/tạo bảng, GET không ghi, unique/FK/flush, hai giao dịch đua revision và rollback.
- [ ] Thử tay trên web qua Gateway theo [03-test.md](03-test.md#thử-tay-trước-mỗi-cổng). Kiểm quyền bằng tài khoản thử, tạo/bỏ qua, HOUSE mới/cũ một ô, hồi quy phí/bãi xe/đếm tin, Back/khôi phục, 409 hai tab, đổi tài khoản, bàn phím/focus, dark mode và zoom 200%.

Phase 1 còn hai mục nghiệm thu trên. Chờ chủ dự án nghiệm thu, quyết định mở Phase 2 và chốt Q1–Q5 trước các phần P2 bị chặn.

## Phase 2 · Gắn tin, hợp đồng và khay

Chủ nhà gắn/gỡ và thấy thứ chưa xếp, mở đăng tin có chi nhánh/phòng, HĐ tạo lần đầu tự lấy link phù hợp. HĐ trùng lịch tạm hiện trong khay khi đọc và tự hiện lại ở phòng đã xếp khi hết trùng.

| Mã | Người | Phạm vi ghi | Phụ thuộc | Tiêu chí xong |
|---|---|---|---|---|
| P2-WEB-01 | Sonnet | `W/app/dashboard/branches/`, `W/components/branch/`, `W/services/branch-assignment.service.ts` | P1, Q1/Q2/Q4/Q5, shape; nối thật sau P2-BE-03 | Menu, ứng viên, gắn/gỡ, khay, cờ phòng, lỗi xóa từ server, điều hướng tin/HĐ. |
| P2-WEB-02 | Sonnet | `W/app/dashboard/properties/new/`, `W/types/listing.type.ts` | Q3/Q4, shape; nối thật sau P2-BE-01 | Link editor từ phòng, giữ prefill chi nhánh, payload create, không leak roomId sang tin nhân bản/branch khác. |
| P2-BE-01 | Sonnet | `L/src/main` phần link tin, layout/cờ phòng, `ListingService`, `CreateListingRequest`, `PropertyBranchService`, cổng kiểm tra xóa | P1, Q3/Q4/Q5, ký hiệu cổng chốt với P2-BE-02 | Tin liên kết nguyên tử, xử lý đổi CN thật, khóa xóa, chặn tin active cũ. |
| P2-BE-02 | Sonnet | `C/src/main` link/gom phòng, `ContractServiceImpl` và implementation cổng xóa | Q1/Q2/Q5, interface đã chốt; tích hợp sau P2-BE-01 | Auto-gắn cả hai đường, không tạo lại link đã gỡ; tính trùng lịch khi đọc. |
| P2-BE-03 | Sonnet | `A/src/main` `BranchAssignmentsController.java` | P2-BE-01, P2-BE-02 | Các API P2 đúng quyền/shape, phân trang ổn định, wire cổng kiểm tra. |
| P2-TEST-01 | Codex | `L/C/A/src/test` test BranchAssignment/BranchAssociations; `W/tests/branch-assignment.test.cjs`; docs tiến độ | Q1–Q5 để soạn; tất cả task P2 để chạy cuối | Unit, API, PostgreSQL và đối chiếu route/payload Web ↔ Gateway. Thử tay theo mục dưới. |

- [x] P2-WEB-01
- [x] P2-WEB-02
- [x] P2-BE-01
- [x] P2-BE-02
- [x] P2-BE-03
- [x] P2-TEST-01

Đóng Phase 2 ngày 27/11. Tiếp tục Phase 3 theo ủy quyền. [Kết quả kiểm đóng](nhat-ky/P2-REVIEW.md#chốt-phase-2--2026-11-27) và [quyết định 27/11](04-cau-hoi.md).

### Thử tay Phase 2 qua Gateway

- [ ] Thử gắn/gỡ, tạo tin từ ô, mở hồ sơ thuê và xem lỗi trên trình duyệt. Theo dõi sau khi chuyển phase theo ủy quyền. [Kịch bản thử tay](03-test.md#thử-tay-trước-mỗi-cổng).

## Phase 3 · Lớp Phòng và Thu tiền

Sơ đồ đổi được hai lớp, chọn tháng, làm nổi/tìm phòng và mở đúng HĐ/hóa đơn. Chốt shape tổng quan và quy tắc Q6/Q7 trước khi nối.

| Mã | Người | Phạm vi ghi | Phụ thuộc | Tiêu chí xong |
|---|---|---|---|---|
| P3-WEB-01 | Sonnet | `W/app/dashboard/branches/[id]/`, `W/components/branch/`, `W/services/branch-overview.service.ts` | P2, shape, Q6/Q7; fixture trước | Lớp, chọn tháng, chú giải/tìm/làm nổi và link, Back giữ vị trí. |
| P3-BE-01 | Sonnet | `C/P/src/main` phép đọc tổng quan/tiền theo lô, `A/src/main` `BranchOverviewController.java` | P2, Q6/Q7 | Màu theo dữ liệu thật, nợ cũ, 0 ghi trên GET. |
| P3-TEST-01 | Codex | `C/A/src/test` test BranchOverview; `W/tests/branch-overview.test.cjs`; docs tiến độ | Shape để soạn; WEB và BE để chạy cuối | Nhóm P3 ở 03, trả đúng URL, không đổi dữ liệu cũ. |

- [x] P3-WEB-01 · Parser đã từ chối payload thiếu trạng thái lớp tiền; ca hồi quy đạt.
- [x] P3-BE-01 · Code đã có hạn nháp theo điều khoản, khoản quyết toán Q7 và che tên hồ sơ lệch chủ. Còn lọt ngày và kỳ thuê ở projection cũ.
- [x] P3-TEST-01 · Đã thêm unit, MockMvc, PostgreSQL opt-in và Node. Xem [lỗi và kết quả](nhat-ky/P3-REVIEW.md).
- [ ] Cổng Phase 3 · Xử lý dữ liệu lệch chủ, chạy lại PostgreSQL và thử link/Back qua Gateway. [Kiểm lại P3 trong nhật ký P4](nhat-ky/P4-REVIEW.md).

## Phase 4 · Cần chú ý và thẻ chi nhánh

Chủ nhà biết chi nhánh/việc gấp, bấm xử lý ở trang gốc và quay lại thấy việc tự mất khi điều kiện đã hết.

| Mã | Người | Phạm vi ghi | Phụ thuộc | Tiêu chí xong |
|---|---|---|---|---|
| P4-WEB-01 | Sonnet | `W/app/dashboard/branches/`, `W/components/branch/`, service tổng quan; các panel đích trong `W/components/contract/` khi cần deep link | P3, shape; fixture trước | Số việc, thứ tự, màn danh sách và đích đúng tới từng đề nghị. |
| P4-BE-01 | Sonnet | `C/L/P/src/main` phép đọc nguồn việc theo lô, `A/src/main` endpoint tổng quan/tổng số | P3, Q7–Q9 | Chỉ việc thuộc concept và quy được chi nhánh, cùng nguyên tắc đếm/sắp. |
| P4-TEST-01 | Codex | `C/A/src/test` test BranchAttention; `W/tests/branch-attention.test.cjs`; docs tiến độ | Shape để soạn; WEB và BE để chạy cuối | Nhóm P4 ở 03, back/refresh làm hết cảnh báo đã xử lý, 0 ghi. |

- [x] P4-WEB-01 · Danh sách, summary, deep link, ba nhãn P4-R2 và chặn kích thước trang sai đã qua Node.
- [x] P4-BE-01 · #1–5 đã kiểm lại, gồm PostgreSQL theo P5-FIX. P4-R2 đã qua unit cho hồ sơ cũ còn hạn ký.
- [x] P4-TEST-01 · Unit, MockMvc, Node và PostgreSQL opt-in đã có. [Kết quả kiểm lại](nhat-ky/P5-REVIEW.md#kiểm-lại-p4).
- [ ] Cổng Phase 4 · Thử xử lý ở trang gốc rồi Back qua Gateway.

## Phase 5 · Lớp ngày ghi, hết hạn và thông báo ngày

Chủ nhà thấy được mốc ghi chỉ số/kết thúc HĐ trên sơ đồ và nhận một thông báo gộp trong ngày có việc.

| Mã | Người | Phạm vi ghi | Phụ thuộc | Tiêu chí xong |
|---|---|---|---|---|
| P5-WEB-01 | Sonnet | `W/app/dashboard/branches/[id]/`, `W/components/branch/`, service tổng quan | P4, shape; fixture trước | Hai lớp mới, nhãn hạn, đường dẫn gốc kể cả trước khi có hóa đơn. |
| P5-BE-01 | Sonnet | `C/src/main` phép đọc lớp chỉ số/hạn, `A/src/main` phần response tổng quan | P4 | Dùng lịch nghiệp vụ, đếm cửa sổ 30/60, GET chỉ đọc. |
| P5-BE-02 | Sonnet | `C/src/main` phần job/outbox digest, dùng query việc P4 | P4, Q10, hợp đồng consumer được xác nhận | Một bản gộp/owner/ngày có việc, retry an toàn; thông báo cũ chạy như trước. |
| P5-TEST-01 | Codex | `C/A/src/test` test BranchMonitoring/BranchDigest; `W/tests/branch-monitoring.test.cjs`; docs tiến độ | Shape để soạn; WEB, hai BE và consumer để chạy cuối | Nhóm P5 ở 03, kiểm dedupe và nhận đúng kênh. |

- [x] P5-WEB-01 · Hai lớp mới đã qua fixture Node.
- [x] P5-BE-01 · Hai lớp mới đã qua unit, MockMvc và PostgreSQL theo P5-FIX. Rà cuối xác nhận revision phụ lục/gia hạn hợp lệ.
- [x] P5-BE-02 — lỗi thứ tự thông báo / digest bị chặn đã sửa (`nhat-ky/P5-FIX.md`; PG Overview 10/10 gồm ca thứ tự). Lần sửa này do điều phối đọc diff, Codex chưa rà lại.
- [x] P5-TEST-01 · Rà cuối, 382 ca offline đạt. Ca PostgreSQL thứ tự mới đã biên dịch. [Kết quả](nhat-ky/P5-REVIEW.md#rà-cuối-bản-sửa-p5).
- [ ] Cổng Phase 5 · Còn chờ: Kafka → consumer → feed thật, cron 08:00 thật, thử qua Gateway.

Chưa nghiệm thu digest. Khi triển khai, cập nhật toàn bộ consumer trước Core.

## Ngoài các phase · Lỗi chỗ xe có sẵn

| Mã | Người | Phạm vi ghi nếu được duyệt | Tiêu chí |
|---|---|---|---|
| EXT-BE-01 | Sonnet code, Codex test/docs | `L/C/src/main` phần parking và các caller có liên quan, `L/C/src/test` test tương ứng | [CHỜ CHỐT Q11] Tái hiện hai đường backend §2.4, kiểm phần pool đúng và đề xuất xử lý reservation null đã sinh. Xác định nguồn nghiệp vụ trước khi sửa. L3; không dùng link quản lý làm fallback. |

- [x] EXT-BE-01 — đã làm theo quyết định 26/11 (kèm lỗi #5, #6); #1, #3 làm sau, #4 giữ chỗ ở chi nhánh cũ (xem `04-cau-hoi.md`).
