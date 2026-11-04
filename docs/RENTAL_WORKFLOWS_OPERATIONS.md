# Vận hành hồ sơ trước thuê, nhận nhà và kết thúc sớm

Phạm vi ngày 03/11/2026. Không bao gồm hệ thống thông báo. Thao tác thực hiện trong hồ sơ yêu cầu thuê/hợp đồng; API và collection thử nằm tại `hs-infra/postman/HomeSpace - Rental workflows.postman_collection.json`.

## Người thuê và chủ nhà

### Đã nhận cọc nhưng chưa hoàn tất ký

1. Mở **Xử lý cọc và ký trước khi thuê**, đọc trạng thái hiện tại. Một bên lập đề nghị, ghi lý do và gắn ảnh/PDF nếu có. Hồ sơ này hoàn toàn bộ khoản thực nhận, không tự tịch thu cọc vì chưa ký.
2. Bên còn lại đọc đúng bản và chọn đồng ý/từ chối. Nếu không thống nhất, mở khiếu nại từ chính đề nghị; nguồn, lịch sử, chứng cứ đã gắn được chụp vào case.
3. Hạn phản hồi mặc định 48 giờ, cấu hình `rental.resolution-response-hours`. Quá hạn không tự đồng ý. Chủ nhà chỉ dùng **Kết thúc và hoàn toàn bộ tiền** sau hạn, xác nhận đã liên hệ khách và phòng chưa bàn giao/đã nhận lại đầy đủ.
4. Nếu đã có chữ ký đang xử lý hoặc chưa rõ kết quả, đối soát giao dịch cũ trước. Không hủy/gửi ký mới để bỏ qua trạng thái chưa rõ.
5. Sau khi đồng ý hủy, hồ sơ chờ hoàn tiền và phòng vẫn được giữ. Chủ nhà báo chuyển từng khoản hoàn. Khi người thuê xác nhận đã nhận đủ tất cả khoản hoàn, hệ thống mới hoàn tất hủy yêu cầu, hủy bản hợp đồng thuê chưa ký đủ và giải phóng lượt giữ phòng.
6. Sau khi khiếu nại giải quyết mà hai bên vẫn muốn thuê: một bên chọn **Đề nghị tiếp tục thuê / ký**, bên kia chọn **Đồng ý tiếp tục thuê / ký**. Đóng case không tự đồng ý thuê. Không cần lập rồi rút đề nghị hủy để mở khóa. Hạn thanh toán đã quá hạn vẫn phải mở lại/đối soát qua luồng thanh toán hợp lệ; không ghi nhận tiền giả hay dời ngày thuê.

### Biên bản nhận nhà

1. Sau HĐ thuê ký đủ, chủ nhà mở **Biên bản nhận nhà**. Ghi ngày giờ thực tế, chìa khóa, hiện trạng, tài sản/số lượng/tình trạng, công tơ và chứng cứ.
2. Hai bên kiểm tra và xác nhận bản đang xem. Một xác nhận chưa hoàn thành bàn giao. Sửa nội dung tạo bản mới và xóa xác nhận của bản trước, giữ lịch sử.
3. Ngày nhận thực tế không tự thay ngày thuê/hóa đơn; công tơ biên bản là bằng chứng đối chiếu, không tự ghi đè công tơ đã dùng để ký/phát hành tiền.
4. Nếu không bàn giao hoặc sai hiện trạng, chọn **Báo vấn đề / Mở khiếu nại**. Admin đọc được bản nguồn và chứng cứ của cả hai bên đã gắn thật. Upload chưa gắn không tự được chia sẻ.

### Chấm dứt sớm theo thỏa thuận

1. Dùng **Chấm dứt sớm theo thỏa thuận** cho HĐ đang hiệu lực, kể cả người thuê thanh toán đúng hạn. Đề nghị ghi ngày kết thúc, lý do, trả nhà và nguồn hoàn tiền thuê nếu có. Gia hạn/phụ lục/chênh lệch cọc đang chờ phải xử lý trước.
2. Ngày kết thúc không bao gồm ngày đó: kết thúc 20/12 thì ngày thuê cuối là 19/12. Chọn **Trọn kỳ đã bắt đầu** hoặc **Theo số ngày thực tế trong kỳ**.
3. Khi chọn theo ngày, bấm đối chiếu phép tính rồi chủ động ghi khoản hoàn vào thỏa thuận. Core tính theo ngày neo HĐ, dùng số ngày thực tế 28/29/30/31 và nguồn thuê đã thực nhận. Chưa rõ nguồn hoặc chưa nhận tiền thì đối soát hóa đơn gốc trước; không sửa/xóa hóa đơn đã phát hành. Phí khác không tự chia theo ngày.
4. Bên còn lại đồng ý đúng bản mới chốt ngày kết thúc. Chữ ký thỏa thuận hoặc nút đồng ý chưa xác nhận nhà đã trả/tiền đã nhận.
5. Đến ngày trả nhà, cả hai xác nhận thực tế. Hoàn tất hóa đơn cuối, khoản hoàn thuê đã thống nhất và quyết toán cọc. Cọc không tự bị giữ toàn bộ vì kết thúc sớm.
6. Mục chấm dứt hiển thị theo nguồn tài chính thật: chờ trả nhà, hóa đơn, hoàn thuê, quyết toán, hoàn/thu thêm, disputed hoặc hoàn tất. Đã trả nhà chưa đủ để kết luận đã hoàn tất tiền.

### PDF và chữ ký riêng

Mở **PDF & chữ ký SmartCA riêng** để tạo/mở PDF của đúng bản. Bản cũ vẫn tra cứu được; không được ký/áp dụng thay nội dung mới. HĐ thuê và HĐ cọc gốc giữ nguyên. Văn bản liên kết không sửa qua màn soạn HĐ gốc.

Chủ nhà ký trước, người thuê ký sau. Chỉ khi backend kiểm chứng đủ hai chữ ký và PDF cuối thì văn bản riêng được ghi nhận. Biên bản nhận nhà xác nhận giao nhận thực tế theo nội dung văn bản; thỏa thuận chấm dứt chỉ ghi nhận thỏa thuận, các bước trả nhà và nhận tiền vẫn riêng. Khi thiếu kết nối/chứng thư, vẫn đọc PDF; không có nút ký giả thay SmartCA.

## Admin HomeSpace

1. Mở **Khiếu nại → Khiếu nại hồ sơ thuê**, lọc trạng thái/nhóm/quá hạn và đọc case. Kiểm tra các mục **Căn cứ**: phiên bản nguồn, thời điểm chụp, lịch sử, ảnh/PDF và phép tính kỳ cuối nếu có.
2. **Tiếp nhận khiếu nại** trước khi yêu cầu bổ sung/kết luận. Admin là một bên của vụ việc không tự xử lý vụ việc của mình.
3. Có thể yêu cầu đúng bên bổ sung hoặc ghi kết luận và theo dõi thực hiện. Với quyết toán cọc, dùng đề xuất mới có chứng cứ, chủ nhà chấp thuận và người thuê xác nhận qua luồng tài chính; admin không tự xác nhận tiền đã nhận.
4. Đóng khiếu nại không tự ký, tiếp tục thuê, trả phòng, xóa nợ hoặc hoàn tiền. Không sửa tay DB để bỏ qua phiên bản hay bên xác nhận.
5. Nếu dữ liệu đổi trong lúc thao tác, tải lại, đối chiếu bản mới rồi gửi lại bằng thao tác người dùng. Frontend không tự retry POST với phiên bản mới.

## Kiểm thử lại độc lập

### Trình duyệt + HTTP + PostgreSQL

Từ `hs-core-api/scripts/workflow-e2e`:

```powershell
npm ci
npx playwright install chromium
npm run run
```

Bộ chạy tạo container PostgreSQL thử riêng trên loopback:55445, khởi động test API trên 18197 và frontend harness trên 18198. Dừng/dọn đúng các process/container do nó tạo, không dùng DB vận hành. Cần JDK 21, Maven, Node, Docker và `node_modules` của web/admin. Hai cổng HTTP và cổng DB thử phải trống.

Harness dùng component web/admin, controller, service, validation, transaction và PostgreSQL thật. Danh tính thử cố định nằm hoàn toàn trong `src/test`; ngân hàng, storage, SmartCA được cách ly. Đây là kiểm thử các workflow/component mới, không thay việc nghiệm thu Keycloak/ngân hàng/storage/provider thật hoặc toàn bộ navigation Next.js. Log backend ở `artifacts/backend.log`, lỗi trình duyệt có screenshot/trace trong `test-results`.

Có thể dùng CSDL thử đã chuẩn bị bằng `HS_WORKFLOW_HTTP_PG_URL`, nhưng chỉ chấp nhận tên DB `hs_workflow_http_test` trên loopback. Bộ chạy không dọn container do người chạy cung cấp. JPA create-drop chỉ áp dụng DB thử đó.

Kiểm thử schema/restart/nhiều context dùng `RentalWorkflowSchemaUpgradeTest`, opt-in qua `HS_WORKFLOW_UPGRADE_PG_URL` với DB riêng `hs_workflow_upgrade_test`. Nó mô phỏng schema cũ, chạy nâng cấp, kiểm tra giữ dữ liệu và khóa qua hai application context, rồi đóng/mở lại để kiểm tra trạng thái đã lưu. Không chạy script này trên DB vận hành.

### SmartCA thật — chuẩn bị để chạy khi có sandbox

Người dùng xác nhận **chưa có sandbox và hai tài khoản ký thử**, nên chưa nghiệm thu ký thật. Các kiểm thử provider giả lập/PDF/đối soát không được dùng thay kết quả này.

Chuẩn bị môi trường staging riêng, tài khoản chủ nhà/người thuê có CCCD và KYC hợp lệ, chứng thư SmartCA tương ứng, SP sandbox, cấu hình `SMARTCA_BASE_URL`, `SMARTCA_SP_ID`, `SMARTCA_SP_PASSWORD`, Gotenberg và storage riêng. Bật chế độ SMARTCA; không dùng endpoint production cho ca thử. Không đưa mật khẩu/token vào repo, Postman export hoặc chat.

Tạo đúng workflow/PDF trên staging. Trong terminal riêng, đặt `HS_SMARTCA_TEST_OWNER_TOKEN`, `HS_SMARTCA_TEST_OWNER_SERIAL` và hai biến tương ứng `TENANT`. Chạy `scripts/Test-WorkflowSmartCaSandbox.ps1` với `BaseUrl`, `SandboxHost`, `RentalId`, `Kind`, `SigningContractId`, `Role`. Mặc định script chỉ preflight, xác minh PDF khớp nguồn và SmartCA đã bật. Sau khi đọc PDF, thêm `-Initiate` cho chủ nhà rồi người thuê; người ký xác nhận trên app. Dùng `-RequestId` để đối soát giao dịch cũ, không gửi lại mù quáng.

Các ca cần chạy và lưu biên bản khi có sandbox:

| Ca | Kết quả cần đạt |
|---|---|
| Chủ nhà ký, người thuê ký đúng bản | PDF cuối có hai chữ ký hợp lệ; HĐ gốc/hash/receipt không đổi |
| Người thuê ký trước | Bị chặn; không tạo giao dịch VNPT |
| Người ký từ chối/hết hạn | Nguồn không tự đồng ý hoặc mất tiền; chỉ retry sau khi trạng thái rõ |
| VNPT timeout nhưng đã ký | Đối soát request cũ; không tạo giao dịch mới khi kết quả chưa rõ |
| Poll/webhook lặp và restart sau provider trả kết quả | Chỉ ghi nhận một lần; không hoàn tiền/áp dụng thỏa thuận hai lần |
| Đổi bản nguồn khi đang ký, callback đến muộn | Không áp dụng chữ ký/PDF cũ cho bản mới; lưu giao dịch để đối soát |
| Chứng thư sai CCCD/hết hạn/thu hồi | Bị chặn hoặc kiểm chứng thất bại; không đổi nghiệp vụ nguồn |
| Storage/converter lỗi rồi retry | Receipt/HĐ gốc giữ nguyên, không tạo nhiều văn bản cùng hash |

## Dữ liệu cũ và tài liệu AI

Schema thêm `workflow_signing_documents` và cột nullable `rental_workflow_records.calculation_json`; các trường workflow/rental/contract của 06–08 phải được áp dụng cùng phiên bản API. Không backfill bằng cách sửa văn bản đã ký. Bộ đọc tương thích ngày cũ dạng mảng; bản mới lưu ISO, hash cũ không bị viết lại.

Nguồn RAG mới tại `hs-infra/documents/rag/16-rental-workflows-and-handover.md`, cùng cập nhật 06/08/14. AI đọc trạng thái qua tool `get_contract_workflows` theo quyền tài khoản, chỉ GET, không tự đồng ý/ký/nhận tiền. Cập nhật file nguồn chưa có nghĩa kho RAG đang chạy đã được ingest: dùng quy trình upload/publish/ingest của môi trường khi triển khai. Không ingest hoặc deploy hệ thống vận hành trong lượt sửa này.

Thông báo/nhắc hạn sẽ tích hợp hệ thống riêng sau; không giả định email/SMS đã được gửi.
