# Vận hành hồ sơ trước thuê và kết thúc sớm

Phạm vi ngày 03/11/2026. Không bao gồm hệ thống thông báo. Thao tác thực hiện trong hồ sơ yêu cầu thuê/hợp đồng; API và collection thử nằm tại `hs-infra/postman/HomeSpace - Rental workflows.postman_collection.json`.

## Người thuê và chủ nhà

### Đã nhận cọc nhưng chưa hoàn tất ký

1. Mở **Xử lý cọc và ký trước khi thuê**, đọc trạng thái hiện tại. Một bên lập đề nghị, ghi lý do và gắn ảnh/PDF nếu có. Hồ sơ này hoàn toàn bộ khoản thực nhận, không tự tịch thu cọc vì chưa ký.
2. Bên còn lại đọc đúng bản và chọn đồng ý/từ chối. Nếu không thống nhất, mở khiếu nại từ chính đề nghị; nguồn, lịch sử, chứng cứ đã gắn được chụp vào case.
3. Hạn phản hồi mặc định 48 giờ, cấu hình `rental.resolution-response-hours`. Quá hạn không tự đồng ý. Chủ nhà chỉ dùng **Kết thúc và hoàn toàn bộ tiền** sau hạn, xác nhận đã liên hệ khách và phòng chưa bàn giao/đã nhận lại đầy đủ.
4. Nếu đã có chữ ký đang xử lý hoặc chưa rõ kết quả, đối soát giao dịch cũ trước. Không hủy/gửi ký mới để bỏ qua trạng thái chưa rõ.
5. Sau khi đồng ý hủy, hồ sơ chờ hoàn tiền và phòng vẫn được giữ. Chủ nhà báo chuyển từng khoản hoàn. Khi người thuê xác nhận đã nhận đủ tất cả khoản hoàn, hệ thống mới hoàn tất hủy yêu cầu, hủy bản hợp đồng thuê chưa ký đủ và giải phóng lượt giữ phòng.
6. Sau khi khiếu nại giải quyết mà hai bên vẫn muốn thuê: một bên chọn **Đề nghị tiếp tục thuê / ký**, bên kia chọn **Đồng ý tiếp tục thuê / ký**. Đóng case không tự đồng ý thuê. Không cần lập rồi rút đề nghị hủy để mở khóa. Hạn thanh toán đã quá hạn vẫn phải mở lại/đối soát qua luồng thanh toán hợp lệ; không ghi nhận tiền giả hay dời ngày thuê.

### Nhận nhà

Không còn biên bản nhận nhà riêng. Ngày thuê, ngày nhận nhà sớm, thanh toán kỳ đầu/cọc và kích hoạt HĐ thuê giữ nguyên; HĐ thuê đã ký đủ là trạng thái hiện hành. Tranh chấp thực tế dùng khiếu nại nhóm Bàn giao. Biên bản cũ (nếu có) chỉ là chứng cứ lưu, không tạo/xác nhận/ký/áp dụng mới.

### Chấm dứt sớm theo thỏa thuận

1. Dùng **Chấm dứt sớm theo thỏa thuận** cho HĐ đang hiệu lực, kể cả người thuê thanh toán đúng hạn. Người thuê mở modal, chọn ngày ở cuối cùng trên lịch và ghi lý do; chủ nhà đồng ý hoặc từ chối. Gia hạn/phụ lục/chênh lệch cọc đang chờ phải xử lý trước.
2. Lịch chỉ cho chọn ngày cuối của kỳ tháng neo từ ngày bắt đầu HĐ, từ hôm nay trở đi và trước kỳ hết hạn hiện tại. Ví dụ bắt đầu 15/03: ngày ở cuối các kỳ là 14/04, 14/05…; mốc kết thúc lưu ở backend là ngày kế tiếp lúc 00:00, không bao gồm ngày đó. Backend cung cấp danh sách ngày được chọn; không tính lại kỳ thuê trong trình duyệt.
3. Đề nghị mới chỉ kết thúc đủ kỳ, không chia theo ngày hay nhập khoản hoàn tùy ý. Không cho cắt qua hóa đơn hoặc khoản tiền thuê đã thu/đang đối soát bao phủ kỳ sau. Dữ liệu tiền đã chốt và văn bản đã ký được giữ nguyên. Hồ sơ cũ đã được chấp thuận vẫn được đối chiếu theo dữ liệu đã lưu.
4. Gửi đề nghị chưa đổi hạn thuê. Chủ nhà từ chối/người thuê rút giữ nguyên ngày kết thúc; chủ nhà đồng ý đúng bản sẽ chuyển APPROVED và tự tạo phụ lục Word/PDF trong cùng giao dịch (tạo lỗi thì phê duyệt cũng hoàn tác), hạn thuê chưa đổi. Hệ thống điền điều khoản đang hiệu lực vào mẫu Word, cập nhật ngày kết thúc/số kỳ trong bản phụ lục và kèm đối chiếu hạn cũ → hạn mới. Khi chưa đủ hai chữ ký, chủ nhà hoặc người thuê có thể hủy đề nghị, kể cả một bên đã ký; kết quả ký đến muộn không được áp dụng. Chủ nhà ký trước, người thuê ký sau trên cùng PDF. Chỉ callback đã kiểm chứng đủ hai chữ ký và PDF cuối đúng phiên bản mới chuyển AGREED, cập nhật mốc kết thúc vận hành. Trước khi áp dụng, backend kiểm tra lại hóa đơn, tiền thuê, nguồn hợp đồng và phiên bản để chặn đề nghị đã lỗi thời.
5. Mốc kết thúc mới hiển thị riêng với thời hạn gốc trong văn bản đã ký. Đến hạn và đủ hóa đơn, chủ nhà lập đề xuất quyết toán cọc và xác nhận đã nhận lại nhà. Người thuê đồng ý đúng phiên bản và xác nhận trả nhà thì hệ thống ghi đủ hai xác nhận vào hồ sơ chấm dứt, tạo nghĩa vụ hoàn/thu thêm và giải phóng nhà theo luồng quyết toán hiện có. Không bắt hai bên xác nhận bàn giao trước ở một màn hình khác; các xác nhận đã có vẫn được giữ nguyên. Đồng ý chấm dứt hoặc chỉ đến hạn không tự bàn giao hay xác nhận đã nhận tiền.
6. Lịch sử, chứng cứ và văn bản riêng vẫn tra cứu được. Mục chấm dứt hiển thị theo nghĩa vụ thực tế: chờ trả nhà, hóa đơn, quyết toán, hoàn/thu thêm, tranh chấp hoặc hoàn tất.

### PDF và chữ ký riêng

Sau khi chủ nhà đồng ý, mở **Phụ lục thời hạn & chữ ký SmartCA** để tạo/mở Word và PDF của đúng bản. Bản cũ vẫn tra cứu được; không được ký/áp dụng thay nội dung mới. HĐ thuê và HĐ cọc gốc giữ nguyên. Văn bản liên kết không sửa qua màn soạn HĐ gốc.

Chủ nhà ký trước, người thuê ký sau. Chỉ khi backend kiểm chứng đủ hai chữ ký và PDF cuối thì văn bản riêng được ghi nhận. Thỏa thuận chấm dứt chỉ ghi nhận thỏa thuận, các bước trả nhà và nhận tiền vẫn riêng. Khi thiếu kết nối/chứng thư, vẫn đọc PDF; không có nút ký giả thay SmartCA.

## Admin HomeSpace

1. Mở **Khiếu nại → Khiếu nại hồ sơ thuê**, lọc trạng thái/nhóm/quá hạn và đọc case. Kiểm tra các mục **Căn cứ**: phiên bản nguồn, thời điểm chụp, lịch sử, ảnh/PDF và phép tính kỳ cuối nếu có.
2. **Tiếp nhận khiếu nại** trước khi yêu cầu bổ sung/kết luận. Admin là một bên của vụ việc không tự xử lý vụ việc của mình.
3. Chi tiết khiếu nại mở trong modal. Hai bên gửi nội dung/chứng cứ; một trong hai bên có thể đóng hồ sơ khi đã giải quyết hoặc chuyển HomeSpace khi cần. Admin đã tiếp nhận được gửi trao đổi, yêu cầu đúng bên bổ sung và ghi kết luận (chờ phản hồi hoặc hết hạn nếu đang yêu cầu bổ sung). Kết luận lần 1 trả hồ sơ về hai bên trao đổi; họ có thể đóng hoặc yêu cầu HomeSpace xem xét lần 2. Kết luận lần 2 đóng hồ sơ vĩnh viễn: chỉ xem lịch sử, không phản hồi/mở lại. Tối đa hai kết luận admin trên mỗi hồ sơ, tính từ lịch sử đã lưu, không đặt lại khi đổi admin hay đóng/mở lại. Hồ sơ đóng trước giới hạn có thể mở lại có lý do. Khiếu nại không sửa hợp đồng hay quyết toán; tiền thực hiện qua chức năng tương ứng.
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
