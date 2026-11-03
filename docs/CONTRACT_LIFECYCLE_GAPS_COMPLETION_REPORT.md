# Hoàn thiện các điểm còn thiếu sau 06–08

Ngày 03/11/2026. Phạm vi được giao: hoàn thành các gap sau hậu kiểm, giữ luồng đã ổn định và kiểm thử liên quan. **Thông báo bỏ qua theo yêu cầu. SmartCA thật chưa chạy vì người dùng chưa có sandbox; đã chuẩn bị code và bộ kiểm thử để chạy sau.**

## Kết quả đã triển khai

| Hạng mục | Kết quả |
|---|---|
| R01 — căn cứ khiếu nại | Cả ba workflow đọc lại nguồn trong transaction, chụp đúng phiên bản, đề nghị/biên bản, lịch sử, phép tính, dấu xác nhận và chứng cứ đã gắn. Bao gồm chứng cứ bên còn lại, PDF nguồn/đã ký khớp hash. Web/admin xem căn cứ trực tiếp từ case. Không chia sẻ upload chưa gắn hoặc file sai hồ sơ; bản chụp không đổi khi nguồn sửa tiếp. |
| R02 — tiếp tục thuê sau case | Thêm REQUEST_RESUME → RESUME với hai chấp thuận riêng; có từ chối/rút. Chỉ mở sau khi case đã giải quyết, kiểm tra quyền, phiên bản, lượt giữ phòng, tiền/chữ ký chưa rõ. Xóa đúng khóa xử lý trước thuê và mở lại cửa sổ ký HĐ đang chờ; không tạo/rút đề nghị hủy giả, không nhận tiền hoặc dời ngày thuê. |
| R03 — kết quả quyết toán | Mục early-exit đọc closure/payment thật, phân biệt chờ nhà, hóa đơn, hoàn thuê, hoàn/thu thêm, disputed và COMPLETED. Có kiểm thử đến bước người nhận xác nhận thực nhận rồi GET hiển thị hoàn tất. |
| PDF và SmartCA riêng | MOVE_IN và EARLY_EXIT có văn bản liên kết WORKFLOW, snapshot/revision bất biến và PDF qua POI/Gotenberg; dùng engine ký/kiểm chứng SmartCA hiện có. Chủ nhà trước, người thuê sau; đủ chữ ký/PDF hợp lệ mới áp dụng. Không dùng chữ ký HĐ thuê/cọc thay văn bản riêng. Bản nguồn đổi hoặc sai bên/root thì không ký/áp dụng PDF cũ. Chữ ký muộn không tự áp dụng sai nguồn. |
| Tính thuê kỳ cuối theo ngày | Thêm DAILY_PRORATED bên cạnh FULL_STARTED_PERIOD. Core tính theo ngày neo HĐ và số ngày thực tế, làm tròn VND; hoàn từ đúng nguồn đã thực nhận, gồm phần trả trước chưa sử dụng. Có kiểm tra cuối tháng/năm nhuận/nhiều tháng. Phí khác không tự chia ngày; không sửa/xóa invoice hoặc receipt đã phát hành. Nếu chưa rõ nguồn hoặc chưa nhận tiền thì yêu cầu đối soát hóa đơn gốc. |
| AI và kiến thức quy trình | Tool get_contract_workflows chỉ GET trên HĐ đã xác định theo quyền tài khoản; trả tiến trình, căn cứ phép tính và dấu ký riêng. AI phân biệt hỏi quy trình chung với dữ liệu của mình; không tự đồng ý/ký/nhận tiền. Projection dùng ngày kết thúc đã thống nhất. Nguồn RAG 16 mới, cập nhật 06/08/14 và mục lục. |
| Vận hành/kiểm thử | Có Postman, hướng dẫn tenant/landlord/admin, script SmartCA sandbox, browser/API/PG harness, test nâng cấp schema/dữ liệu cũ, đóng/mở và hai context đồng thời. T01–T40 có ma trận chỉ rõ mức đã kiểm chứng và giới hạn. |

## Lỗi bổ sung phát hiện và sửa trong khi kiểm thử

- Ngày văn bản cũ có thể lưu dạng mảng: bộ đọc nhận được dạng cũ; bản mới lưu ISO. Không viết lại payload/hash của văn bản đã tồn tại.
- Tính phạm vi tiền thuê nhiều tháng từ ngày cuối tháng có thể trôi ngày khi neo vào tháng đã bị cắt ngắn: dùng ngày bắt đầu HĐ làm gốc cho mọi boundary, kiểm tra lại phạm vi và tổng tiền nguồn.
- Case bàn giao không có phép tính nhưng giao diện cố đọc số tiền từ map rỗng: web/admin chỉ hiển thị khi có số tiền hợp lệ; thêm hồi quy và chạy lại trình duyệt.
- Phép tính trả về muộn sau khi người dùng đổi ngày/chính sách: bỏ kết quả cũ, không tự thêm khoản hoàn hoặc gửi đề nghị.
- PDF phải dùng revision gốc đã chụp và khớp cả root/tenant/landlord/listing; lịch sử PDF chỉ đọc, không dùng ký thay bản mới. Tạo PDF/gửi thao tác có guard chống nhấp lặp và phản hồi sau khi đổi scope.

## Kết quả kiểm thử cuối

| Bộ kiểm tra | Kết quả thực chạy |
|---|---|
| Maven hồi quy listing/payment/contract/API + AI projection | **755 ca báo cáo: 754 đạt, 1 bỏ qua**, 0 lỗi/failure, BUILD SUCCESS |
| PostgreSQL/schema trong tổng Maven | **142 đạt**, gồm **49 ca workflow**, hồi quy dispute/closure/onboarding/draft và **1 ca schema → hai context → restart** |
| Ranh giới SmartCA của văn bản riêng, trong Maven | **3 đạt**: ký thứ nhất, ký thứ hai chỉ áp dụng workflow, callback sai nguồn không ghi/áp dụng |
| Gotenberg thật, trong Maven | **1 đạt**, tạo hai mẫu PDF, kiểm tra text tiếng Việt và render PNG để kiểm tra trực quan |
| HTTP system harness, chạy riêng | **1 đạt**, controller/service/JPA thật, auth/provider thử riêng |
| Playwright, chạy cùng HTTP harness | **5/5 đạt** với tenant, tenant2, landlord, admin; các component web/admin thật |
| Web regression | **204/204 đạt**, gồm handler phiên bản/đồng ý, stale response, PDF current/history và phép tính |
| Admin complaint/payment-dispute | **16/16 đạt** |
| AI contract/tool routing | **68/68 đạt** |
| TypeScript web, ESLint file thay đổi; admin build/lint | Đạt. Admin build có cảnh báo kích thước chunk có sẵn, không có lỗi build |
| PowerShell sandbox kit | Parse cú pháp đạt; không chạy ký thật |

Không cộng lại các lượt chạy nháp hoặc các ca đã nằm trong tổng Maven. Ca bỏ qua là diagnostic SmartCA cần dữ liệu/chứng thư/dịch vụ thật, không phải regression bị lỗi. Bộ backend dùng profile isolated-build; các DB đều là container/test database riêng. Kiểm thử browser dùng identity thử cố định trong src/test và mock ngân hàng/storage/SmartCA; không phải chứng minh Keycloak, VNPT, S3 hoặc ngân hàng production đã được nghiệm thu.

Các log của lượt cuối ở `%TEMP%`: `hs-gaps-core-final.log`, `hs-gaps-browser-system.log`, `hs-gaps-web-full.log`, `hs-gaps-admin-tests.log`, `hs-gaps-ai.log`, `hs-gaps-web-types.log`, `hs-gaps-web-lint.log`, `hs-gaps-admin-build.log`, `hs-gaps-admin-lint.log`. Lượt kiểm tra cách ly cuối (schema/HTTP/browser, đã tắt autoconfig Mongo không dùng) nằm ở `hs-gaps-isolated-system.log` và `hs-gaps-browser-isolated-final.log`, đều đạt. Harness giữ backend log/screenshot/trace tại `scripts/workflow-e2e/artifacts` và `test-results` (đã ignore).

## Cách chạy lại và chuẩn bị triển khai

- [Hướng dẫn vận hành và bộ chạy lại](RENTAL_WORKFLOWS_OPERATIONS.md).
- [Ma trận T01–T40 và giới hạn kiểm chứng](CONTRACT_LIFECYCLE_ACCEPTANCE_MATRIX.md).
- [Checklist đã cập nhật](CONTRACT_LIFECYCLE_COMPLETION_CHECKLIST.md).
- Postman: `hs-infra/postman/HomeSpace - Rental workflows.postman_collection.json`; đọc GET lấy phiên bản trước mỗi POST, điền token thử theo vai trò. Không chạy mọi nhánh ghi tuần tự như một collection thanh toán thật.
- SmartCA: `scripts/Test-WorkflowSmartCaSandbox.ps1` mặc định chỉ preflight. Có sandbox và hai tài khoản ký thì người ký đọc PDF trước, chạy lần lượt cho chủ nhà/người thuê; dùng request cũ để đối soát timeout. Các ca nghiệm thu thật nằm trong hướng dẫn.
- JPA bổ sung workflow_signing_documents và calculation_json cùng các trường nullable của 06–08; đã kiểm tra nâng cấp schema thử và giữ dữ liệu cũ. Áp dụng lên môi trường vận hành qua quy trình deploy của dự án, không dùng bộ create-drop thử cho DB thật.
- File RAG đã cập nhật trên đĩa; runtime chỉ đổi sau upload/apply/ingest của môi trường triển khai. Lượt này không publish chính sách hoặc ingest vào DB vận hành.

## Giới hạn được giữ rõ

Không triển khai thông báo/outbox/worker. Không nghiệm thu ký VNPT thật khi chưa có sandbox. Không deploy/restart dịch vụ vận hành hoặc sửa/seed/drop DB vận hành; các process/container thử được dọn đúng phạm vi sau kiểm tra.

Các gap R01–R03 và phần bổ sung nêu trên đã hoàn thiện ở code/bộ kiểm thử. Không tuyên bố mọi tình huống production đã hết lỗi. Những mục rộng hơn trong review ban đầu, như thanh toán nhiều phần/tự xử lý tiền dư (T26), vẫn cần phạm vi nghiệp vụ riêng; không giả mạo xác nhận đủ tiền để lấp khoảng trống đó.
