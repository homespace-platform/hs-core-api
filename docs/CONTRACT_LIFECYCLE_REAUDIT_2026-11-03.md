# Đối chiếu lại 10 giai đoạn vòng đời hợp đồng

Ngày: **03/11/2026**. Phạm vi: rà soát checklist, code backend/web/admin/AI, bằng chứng kiểm thử và các phần chưa hoàn tất; **bỏ qua giai đoạn 6 của danh sách người dùng (mã 05 trong tài liệu): thông báo/nhắc hạn**.

**Cập nhật sau lượt rà soát:** chủ dự án đã chốt không đặt cọc/cọc 0 đồng bỏ qua HĐ cọc; đã triển khai và đạt kiểm thử tại [báo cáo miễn cọc](CONTRACT_ZERO_DEPOSIT_IMPLEMENTATION_REPORT.md). Vì vậy mục quyết định cọc 0 bên dưới đã được đóng; số hiện tại là 93/95 ô đã đánh dấu, 2 ô còn mở. Phần dưới giữ lại kết quả và phạm vi của chính lượt rà soát trước khi triển khai miễn cọc.

## Kết luận

Các phần sửa nghiệp vụ đã được giao ở giai đoạn 2–5 và 7–9 có code, giao diện và kiểm thử. Câu “đây là kế hoạch đề xuất, chưa triển khai nghiệp vụ” là trạng thái ban đầu, không còn phản ánh hiện tại. Tuy nhiên, **chưa đủ căn cứ đóng toàn bộ kế hoạch là hoàn thành 100% hoặc đã nghiệm thu production**.

Checklist hiện có **103 ô** sau các lần bổ sung, không còn đúng 97 ô ban đầu. Bỏ 8 ô thông báo còn **95 ô**. Sau khi sửa một ô tài liệu chưa cập nhật về các chính sách đã triển khai, có **92 ô đã đánh dấu và 3 ô còn mở**. Các mục đã đánh dấu chỉ xác nhận đúng phạm vi mô tả trong ô đó; không thay cho nghiệm thu tích hợp thật. Prompt/Word và giới hạn thanh toán T26 được ghi riêng bên dưới, không ngầm tính là đã hoàn thành.

## Đối chiếu theo thứ tự người dùng

| Giai đoạn | Mã trong repo | Kết quả đối chiếu | Căn cứ chính |
|---|---|---|---|
| 1. Nguồn dữ liệu và trạng thái | 00 | Có ma trận nguồn/quyền/trạng thái; các chính sách phụ thuộc đã triển khai. Còn quyết định có cần văn bản giữ chỗ riêng khi cọc 0 đồng. Hiện hệ thống miễn HĐ nhận cọc khi cọc bằng 0. | `ContractDraftIntegrity`, `DepositAgreementService.signedOrExempt`, báo cáo quy tắc 00 và các báo cáo sau |
| 2. Bản nháp lệch hồ sơ | 01 | Đã triển khai chặn sửa danh tính/tài sản/lịch/tiền trái nguồn, kiểm tra trước xuất PDF/ký và chặn khi chữ ký chưa rõ kết quả; frontend giới hạn trường sửa. | `ContractDraftIntegrity`, `ContractDraftIntegrityTest`, `ContractDraftIntegrityPostgresTest`, báo cáo 01 |
| 3. Khoản tiền DISPUTED | 02 | Có hồ sơ, chứng cứ, đề xuất hai bên/chuyển admin, mở lại báo chuyển hoặc kiểm tra chứng từ. Quyết định không tự xác nhận đã nhận tiền. Có UI web/admin và kiểm tra phiên bản. | `PaymentDisputeService`, `PaymentDisputeServiceTest`, `PaymentDisputePostgresTest`, các controller/panel, báo cáo 02 |
| 4. Sổ cọc và gia hạn | 03 | Quyết toán ghi retainedAmount; giữ riêng khoản hoàn/thu thêm và lịch sử thực nhận. Gia hạn sau REJECTED/EXPIRED được gửi mới khi còn cửa sổ, không sống lại đề nghị cũ. | `ContractLifecycleService.acceptClosure/canRequest`, `ContractLifecyclePhase03PostgresTest`, báo cáo 03 |
| 5. Khiếu nại hai bên → admin | 04 | Có SLA mặc định 48 giờ cấu hình được, trao đổi/chứng cứ, chuyển cấp, tiếp nhận, yêu cầu bổ sung, kết luận/theo dõi/rút/mở lại; admin không nhận tiền thay người nhận. | `ComplaintCaseService`, `ComplaintCasePostgresTest`, `ComplaintControllerTest`, web/admin, báo cáo 04 |
| 6. Thông báo và nhắc hạn | 05 | Bỏ qua theo yêu cầu. Không dùng log hoặc hạn trên UI để kết luận đã gửi thông báo. | Backlog giữ nguyên |
| 7. Đã nhận cọc nhưng chưa ký xong | 06 | Có đề nghị hoàn toàn bộ thực nhận, phản hồi/hết hạn/chuyển case; chặn kết thúc khi tiền/chữ ký chưa rõ. Tiếp tục thuê sau case cần hai chấp thuận mới. Giữ lịch sử và đúng lượt giữ phòng/xe. | `PreTenancyResolutionService`, `RentalWorkflowPostgresTest`, browser harness, R01/R02 |
| 8. Biên bản nhận nhà | 07 | Có hiện trạng, thời điểm, chìa khóa, tài sản, công tơ, chứng cứ và xác nhận hai bên đúng bản. Có PDF/lượt ký SmartCA riêng ở code; không dùng chữ ký HĐ thuê làm bằng chứng giao chìa khóa. | `MoveInHandoverService`, `WorkflowDocumentService`, `WorkflowSignatureIsolationTest`, `RentalWorkflowPostgresTest` |
| 9. Chấm dứt sớm theo thỏa thuận | 08 | Có đề nghị khi không nợ, phản hồi/thỏa thuận, trọn kỳ hoặc theo ngày, khoản hoàn từ nguồn thực nhận, trả nhà hai bên và quyết toán riêng. GET kết quả đọc trạng thái tài chính thật. | `MutualTerminationService`, `FinalRentCalculator`, `ContractLifecycleService`, R01/R03 và test PG/browser |
| 10. Nghiệm thu toàn luồng | 09 | Đã kiểm thử cách ly backend/API/PG/browser, PDF Gotenberg, schema/restart/hai context. Chưa nghiệm thu tích hợp SmartCA/chứng thư/ngân hàng/storage/Keycloak thật; chưa deploy/publish/ingest. | Ma trận T01–T40, báo cáo hoàn thiện, log kiểm thử và runbook |

Tên lớp là từ khóa tra cứu trong repo; chi tiết bộ kiểm thử và giới hạn từng ca nằm tại [ma trận nghiệm thu](CONTRACT_LIFECYCLE_ACCEPTANCE_MATRIX.md).

## Những việc còn lại ngoài thông báo

| Việc | Hiện trạng và bước cần thực hiện |
|---|---|
| Quyết định sản phẩm: cọc 0 đồng | Code và test hiện miễn HĐ nhận cọc, không tạo receipt/chữ ký cọc giả. Chưa có quyết định rõ có bổ sung văn bản giữ chỗ 0 đồng ký riêng hay tiếp tục giữ ngoại lệ này. Không tự thay luồng đang ổn định trong lượt rà soát. |
| Đồng bộ prompt và 6 mẫu Word | Lượt rà soát mẫu trước đã phát hiện điều khoản quá hạn thứ 5 trong 3 HĐ thuê khác code/prompt là sau đủ 5 ngày, từ ngày quá hạn thứ 6. Hai prompt còn cần cập nhật luồng nhận nhà, chấm dứt, xử lý cọc và mô tả D2 đã triển khai. Prompt cọc trên đĩa vẫn còn tiêu đề “backend CHƯA hỗ trợ catalog/render cọc”. Chưa chỉnh hai prompt hoặc sáu DOCX. Cần cập nhật prompt → tạo/kiểm tra Word, marker và render → publish version mẫu mới. Không viết lại văn bản đã ký. |
| Nghiệm thu tích hợp thật | Người dùng chưa có SmartCA sandbox/hai tài khoản ký. Có code, Postman, runbook và `scripts/Test-WorkflowSmartCaSandbox.ps1`; chưa có kết quả ký/chứng thư thật. Auth/storage/ngân hàng cũng phải kiểm chứng trên staging, không lấy kết quả provider giả lập thay thế. |
| Triển khai môi trường | Chưa deploy schema/code, publish mẫu hoặc ingest RAG mới. File RAG trên đĩa và tool AI có code không chứng minh runtime đã cập nhật. Việc triển khai và nghiệm thu môi trường cần thực hiện theo quy trình riêng. |
| T26: thiếu/thừa/nhiều lần chuyển tiền | Đã có đối soát và chốt không giả nhận đủ tiền; chưa có ledger phân bổ nhiều lần thanh toán, nhận một phần hoặc tự tách khoản dư. Đây là giới hạn sản phẩm đã ghi trong review/ma trận, chưa được triển khai thành tính năng. Nếu giữ yêu cầu hoàn thiện cả T01–T40 thì T26 vẫn phải có thiết kế và nghiệm thu riêng. |

Giới hạn khác cần giữ rõ: đề nghị chấm dứt hiện chặn cắt qua hóa đơn đã phát hành; không sửa/xóa invoice cũ để áp dụng ngày mới. Phí ngoài tiền thuê không tự chia theo ngày. Các hành vi này có guard/test, không được diễn đạt như hệ thống đã hỗ trợ mọi điều chỉnh tài chính tùy ý.

## Kiểm thử trong chính lượt rà soát này

Chạy lại **137/137 test**, 0 failure, 0 error, 0 skipped, **BUILD SUCCESS**:

| Suite | Số ca |
|---|---:|
| PaymentDisputeServiceTest | 34 |
| DepositAgreementServiceTest | 8 |
| ContractDraftIntegrityTest | 39 |
| ContractLifecycleServiceTest | 33 |
| FinalRentCalculatorTest | 5 |
| WorkflowSignatureIsolationTest | 3 |
| MonthlyBillingScheduleTest | 3 |
| ComplaintControllerTest | 4 |
| RentalWorkflowControllerTest | 4 |
| PaymentDisputeControllerTest | 4 |

Lệnh chạy từ thư mục `hs-core-api`:

```powershell
mvn -Pisolated-build -pl hs-api-service -am '-Dtest=ContractDraftIntegrityTest,ContractLifecycleServiceTest,FinalRentCalculatorTest,MonthlyBillingScheduleTest,DepositAgreementServiceTest,WorkflowSignatureIsolationTest,PaymentDisputeServiceTest,RentalWorkflowControllerTest,ComplaintControllerTest,PaymentDisputeControllerTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

Log: `%TEMP%/hs-contract-checklist-reaudit-20261103.log`, kết thúc 19:31 ngày 03/11/2026 giờ Việt Nam. Lượt này chạy unit/controller/provider giả lập, không chạy lại PG/browser/provider thật hoặc toàn bộ regression cũ. Không cộng 137 ca này vào số ca của lượt trước thành số test mới.

Đã đọc lại log lượt hoàn thiện trước để đối chiếu báo cáo: Maven BUILD SUCCESS (báo cáo 754 đạt/1 diagnostic SmartCA bỏ qua), web 204/204, admin 16/16, AI 68/68, browser 5/5; PG/schema và HTTP cách ly có log BUILD SUCCESS riêng. Xem [báo cáo hoàn thiện](CONTRACT_LIFECYCLE_GAPS_COMPLETION_REPORT.md) và [runbook](RENTAL_WORKFLOWS_OPERATIONS.md) để biết môi trường, provider được giả lập và bộ chạy lại. Những số này là bằng chứng lượt trước, không phải mọi bộ đã chạy lại trong lần đối chiếu này.

## Thay đổi trong lượt đối chiếu

Chỉ bổ sung báo cáo này và cập nhật checklist: đóng ô chính sách đã triển khai qua các giai đoạn sau, giữ nguyên ba ô còn mở và backlog thông báo. Không đổi nghiệp vụ/code, không sửa Word/prompt, không deploy/ingest hoặc thay đổi CSDL vận hành. Không kết luận không còn bug chỉ dựa vào số ca đạt.
