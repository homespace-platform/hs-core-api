# Báo cáo triển khai quá hạn 5 ngày và đơn phương chấm dứt 24 giờ

Hoàn thành ngày **04/11/2026**, theo quy tắc hệ thống chủ dự án đã yêu cầu. Phạm vi gồm backend/API, giao diện hóa đơn, nội dung sinh hợp đồng và prompt. Quy tắc và 16 ca nghiệm thu thủ công nằm trong [hướng dẫn hiện hành](OVERDUE_5_DAY_UNILATERAL_24H_2026-11-04.md).

## Kết quả

- Trần phí theo ngày giảm xuống **5 ngày/hóa đơn**, dùng chung trước/sau các lần gia hạn. Phạt một lần/NONE và trần tiền thấp hơn giữ đúng cách tính; không viết lại khoản đã trả, phí lịch sử đã chốt hoặc quyết định miễn phí cũ.
- Tách `MAXIMUM_PENALTY_DAYS=5` khỏi `AUTO_ROLLOVER_DAYS=10`. Mở phương án vẫn sau 5 ngày quá hạn; tự chốt nợ khi không chọn phương án vẫn sau hết 10 ngày, có điều kiện.
- Chủ nhà thông báo đơn phương bằng lý do, hệ thống lưu thông báo cho cả hai bên trên hồ sơ. Không cần người thuê xác nhận và không tạo `terminationAcceptedAt` giả.
- Khách có **24 giờ** khiếu nại xin cộng nợ hoặc dời hạn 1–30 ngày. Khiếu nại đúng hạn chuyển REVIEW_REQUESTED, chờ chủ nhà. Đồng ý thì REPLACED và áp dụng đúng một phương án; từ chối thì ENDED ngay. Không khiếu nại thì job/API tự kết thúc khi qua hạn.
- Đang đối soát tiền thì tạm dừng kết thúc; xác nhận tiền rút quyết định. Công nợ còn nguyên sau kết thúc. Không có thao tác thu/chuyển tiền tự động.
- Bàn giao tách khỏi thời điểm kết thúc. Chủ nhà xác nhận thực tế nhận lại phòng/chìa khóa/tài sản mới xử lý cọc thực nhận, giải phóng chỗ xe và mở lại tin. Cọc 0đ không có ghi nhận giữ/hoàn giả; không ghi đè thời điểm kết thúc bằng ngày bàn giao.
- Nháp chưa phát hành bị hủy sau kết thúc, hiển thị CANCELLED thay vì chờ thanh toán. Hóa đơn đã phát hành tiếp tục được đối soát/thanh toán.
- Thao tác có kiểm tra vai trò, phiên bản, đồng hồ, tình trạng tiền và điều kiện kỳ sau, khóa contract → invoice → payment. Tab cũ hoặc hai quyết định đồng thời không thể cùng commit. `approve` bắt buộc, thiếu không được hiểu ngầm là từ chối.
- Job dùng cursor theo ID; việc các hợp đồng trước đó rời tập ACTIVE không làm bỏ sót các hàng sau như phân trang offset.
- Endpoint tạo đề nghị quá hạn cũ bị ngừng tạo mới để không đổi ngầm ý nghĩa thao tác thành đơn phương. Hồ sơ đề nghị cũ đang xử lý giữ tương thích; các endpoint đồng ý/từ chối cũ không được dùng trên notice mới. Luồng chấm dứt theo thỏa thuận vì lý do khác vẫn giữ nguyên.

## Kiểm thử cuối

| Bộ kiểm tra | Kết quả |
|---|---|
| UnilateralTerminationServiceTest | **20/20** |
| PostgreSQL lifecycle, billing và concurrency | **31/31**, gồm 6 ca mới cho notice/review/quyết định đồng thời |
| MonthlyBillingServiceTest | **87/87** |
| ContractAmendmentServiceTest / ContractDraftIntegrityTest | **43/43**, **39/39** |
| ContractLifecycleServiceTest / ContractServiceImplTest | **37/37**, **30/30** |
| ContractDataBuilderTest / ContractRenderServiceTest | **11/11**, **4/4**, gồm kiểm tra số tiền/điều khoản trong nội dung DOCX test |
| LatePaymentPolicyTest / MonthlyBillingScheduleTest | **9/9**, **3/3** |
| PaymentRequestServiceTest / BillingTimeTest | **20/20**, **6/6** |
| API: MonthlyInvoice, Complaint, DepositAgreement, RentalWorkflow | **15/15** |
| Tổng backend/API | **355/355**, 17 suite, không failure/error/skipped, BUILD SUCCESS |
| Frontend: billing-rules, unilateral-termination, automatic-rollover, payment-extension, contract-lifecycle, payment-dispute, invoice-line-calculation, payment-module | **64/64** |
| TypeScript `tsc --noEmit` | Đạt |
| ESLint các TS/TSX thay đổi | Đạt |
| `git diff --check` core/web/infra | Đạt |

Ca mới kiểm tra trước/đúng/sau 24 giờ; không có người mở trang nhưng job vẫn chạy; có khiếu nại thì chờ; hai phương án thay thế; từ chối kết thúc ngay; quyền/vai trò/phiên bản sai; lùi đồng hồ; chứng từ báo chuyển/tranh chấp/đã xác nhận; kỳ sau đã phát hành; xử lý cọc 0đ/có cọc; ngăn dùng endpoint cũ để vượt quy trình; không ghi nợ từ nháp đã hủy; không bỏ sót khi job làm thay đổi tập ACTIVE. Kiểm thử UI chạy handler và markup thật với API cô lập, kiểm tra mutex, dữ liệu gửi, thông báo hậu quả, điều kiện từ server, polling và unmount.

PostgreSQL dùng MonthlyBillingService, PaymentRequestService và transaction thật trong DB dùng một lần; kiểm tra JSON lưu bền, số tiền/hạn/QR khớp, giữ cọc trước bàn giao, chỉ xử lý cọc một lần, và hai quyết định trái ngược chỉ một bên commit. Ngân hàng/QR ngoài hệ thống, lưu trữ chứng từ và SmartCA được cô lập bằng mock; không có chuyển tiền hoặc ký thật. Không tuyên bố đã kiểm thử toàn bộ hệ thống hay mọi tích hợp bên ngoài.

Các lần chạy trung gian phát hiện assertion còn mong đợi trần cũ 10 ngày/1 triệu trong test tính tiền và test nội dung Word; đã đối chiếu phép tính rồi cập nhật sang 5 ngày/500 nghìn, đồng thời giữ assertion khoản thuê gốc và phí lịch sử. Có thêm sửa lỗi nháp CANCELLED hiển thị UNPAID trước lần nghiệm thu cuối. Không bỏ qua test lỗi để lấy kết quả đạt.

## Log và tài liệu

Log trong `%TEMP%`:

- `hs-unilateral-core-acceptance-20261104.log`
- `hs-unilateral-web-acceptance-20261104.log`
- `hs-unilateral-types-acceptance-20261104.log`
- `hs-unilateral-lint-acceptance-20261104.log`

Lệnh backend cuối dùng profile isolated-build và DB thử opt-in:

```powershell
$env:HS_LIFECYCLE_TEST_PG_URL='jdbc:postgresql://127.0.0.1:55450/hs_phase03_test'
mvn -Pisolated-build -pl hs-api-service -am '-Dtest=UnilateralTerminationServiceTest,MonthlyBillingServiceTest,LatePaymentPolicyTest,MonthlyBillingScheduleTest,ContractLifecyclePhase03PostgresTest,ContractLifecycleServiceTest,PaymentRequestServiceTest,ContractDataBuilderTest,ContractDraftIntegrityTest,ContractRenderServiceTest,ContractServiceImplTest,ContractAmendmentServiceTest,MonthlyInvoiceControllerTest,BillingTimeTest,RentalWorkflowControllerTest,DepositAgreementControllerTest,ComplaintControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

PG container `hs-unilateral-test-20261104`, loopback 55450, DB `hs_phase03_test`; container/volume dùng một lần đã xóa sau khi xác minh đúng ID/nhãn/volume. Không truy cập hoặc sửa CSDL vận hành, không restart dịch vụ người dùng. Giữ nguyên chỉnh sửa `.env.example` và `application.properties` có sẵn trước công việc.

Đã cập nhật `hs-infra/prompts/HOMESPACE_CONTRACT_PROMPT.md` cho cả ba mẫu nhà nguyên căn/căn hộ/phòng trọ; prompt cọc ghi rõ không áp dụng nhánh mới vào trước thuê. RAG thanh toán 0.3.3 được sửa ở nguồn draft, chưa ingest. Hướng dẫn MONTHLY_BILLING cập nhật quy tắc/API, SIMULATED_TIME và hai báo cáo gia hạn trước đó được đánh dấu phần đã thay thế.

## Để dùng trên ứng dụng đang chạy

1. Build/restart Core và nạp frontend mới theo quy trình đang dùng. Build kiểm thử isolated không thay lớp ứng dụng IDE đang chạy.
2. Thêm cột nullable `contracts.unilateral_termination_snapshot TEXT` nếu môi trường không dùng JPA update; SQL nằm trong hướng dẫn hiện hành. Không backfill quyết định mới lên hồ sơ cũ.
3. Tạo và upload phiên bản mẫu Word mới từ prompt đã sửa. Sửa prompt không tự đổi mẫu đã upload hoặc DOCX/PDF đã ký; các văn bản lịch sử giữ nguyên.
4. Chạy C01–C16 trên hồ sơ thử mới theo đồng hồ nghiệp vụ. Thông báo ở thay đổi này được lưu/hiển thị trên hồ sơ hóa đơn; hệ thống email/push riêng chưa được tích hợp.

Chủ nhà chưa quyết định khiếu nại đúng hạn thì hồ sơ tiếp tục chờ; không tự suy diễn đồng ý/từ chối. Đây là cách xử lý đã triển khai cho nhánh còn chờ, không đặt thêm một hạn tự kết thúc mới ngoài quy tắc người dùng yêu cầu.
