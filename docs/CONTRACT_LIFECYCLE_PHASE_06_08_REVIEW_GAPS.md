# Hậu kiểm phạm vi hoàn thành 06–08

Ngày: 03/11/2026. Thực hiện sau câu hỏi “đã đủ hết chưa, còn thiếu gì không”.

Cập nhật sau sửa ngày 03/11/2026: **R01–R03 đã được khắc phục và kiểm thử**; PDF/ký riêng, tính theo ngày, Postman/RAG/tool, schema/restart và browser harness cũng đã bổ sung. Xem [báo cáo hoàn thiện](CONTRACT_LIFECYCLE_GAPS_COMPLETION_REPORT.md). SmartCA thật chờ sandbox theo người dùng; thông báo bỏ qua. Các mục bên dưới giữ nội dung phát hiện ban đầu để đối chiếu.

Kết luận tại thời điểm hậu kiểm: ba workflow đã được triển khai và lượt kiểm thử đã chạy đạt, nhưng **chưa đủ để chốt hoàn thiện toàn luồng**. Đọc lại code phát hiện các khoảng trống dưới đây; bộ test hiện tại chưa kiểm chứng các kết quả cuối tương ứng. Lượt hậu kiểm này chưa sửa code hoặc chạy lại kiểm thử tái hiện riêng; không dùng số test đạt trước đó để kết luận các điểm này đã được xử lý.

## Các điểm cần sửa trước

| Mã | Ưu tiên | Hiện trạng và ảnh hưởng | Nghiệm thu bổ sung |
|---|---|---|---|
| R01 | P1 | `PreTenancyResolutionService` ESCALATE, `MoveInHandoverService` REPORT_ISSUE và `MutualTerminationService` REPORT_ISSUE đều gọi `ComplaintOpen` với `List.of()` chứng cứ. Hồ sơ khiếu nại chỉ tự chụp bằng chứng nguồn ở nhóm DEPOSIT_SETTLEMENT. Ảnh/PDF và nội dung biên bản/thỏa thuận vẫn còn lưu, nhưng chưa được chuyển thành căn cứ nguồn để admin xem trong vụ việc mới. | Chụp đúng phiên bản nguồn và các chứng cứ đã gắn thật, kể cả chứng cứ bên còn lại cung cấp; admin xem được từ case. Không cấp quyền cho upload chưa gắn, file sai hồ sơ hoặc dữ liệu client tự khai. Có regression cho cả ba đường mở khiếu nại. |
| R02 | P1 | Sau ESCALATE trước thuê, workflow ở DISPUTED và `resolutionWorkflowId` tiếp tục khóa tạo/ký HĐ, mở tiền tháng đầu. Khi complaint RESOLVED, view chỉ mở PROPOSE; không có hành động tiếp tục thuê trực tiếp. Hiện phải lập đề nghị hủy mới rồi rút mới xóa được khóa, không phải thao tác nghiệp vụ phù hợp. | Có hành động tiếp tục đúng quyền sau khi vụ việc đã được giải quyết; kiểm tra nguồn hiện tại, tranh chấp/chữ ký còn mở và phiên bản. Không tự suy ra admin đóng case là đồng ý thuê hoặc đã nhận tiền. Test case resolved → tiếp tục ký/mở kỳ tiền hợp lệ, giữ đủ audit. |
| R03 | P2 | `MutualTerminationService.view` trả SETTLEMENT_PENDING bất cứ khi nào workflow RETURNED, không đối chiếu ContractClosure/receipt đã CLOSED. Vì vậy hợp đồng đã đóng và hoàn xong vẫn có thể hiện chờ quyết toán ở mục chấm dứt. | Đồng bộ kết quả hiển thị từ nguồn tài chính thật, phân biệt chờ hóa đơn, chờ hoàn/thu thêm, disputed và đã hoàn tất. Test đến tận GET early-exit sau khi xác nhận thực nhận và đóng HĐ. Không tạo hoặc xác nhận lại khoản tiền. |

Tham chiếu code:

- [Mở case trước thuê](../hs-contract-service/src/main/java/com/hs/contract/service/PreTenancyResolutionService.java), phần ESCALATE và view.
- [Mở case nhận nhà](../hs-contract-service/src/main/java/com/hs/contract/service/MoveInHandoverService.java), phần REPORT_ISSUE.
- [Chấm dứt sớm](../hs-contract-service/src/main/java/com/hs/contract/service/MutualTerminationService.java), phần REPORT_ISSUE và view.
- [Chụp căn cứ vào case](../hs-contract-service/src/main/java/com/hs/contract/service/ComplaintCaseService.java), phần open/register/view.

## Phạm vi còn hạn chế và nghiệm thu chưa làm

- Biên bản nhận nhà/thỏa thuận chấm dứt hiện là bản điện tử có lịch sử và xác nhận trong ứng dụng. Chưa có xuất PDF hoặc lượt ký SmartCA riêng cho hai loại này. Đây là phần mở rộng sản phẩm, không được mô tả như đã có.
- Kỳ cuối chỉ hỗ trợ FULL_STARTED_PERIOD và khoản hoàn ghi riêng theo nguồn thực nhận; chưa có lựa chọn tính tiền theo ngày. Không tự bổ sung chính sách tiền khác khi chưa thống nhất.
- Chưa chạy E2E trình duyệt với hai tenant, landlord và admin, kiểm thử nhà cung cấp SmartCA/storage thật, restart hoặc nhiều instance cho toàn chuỗi mới. Các test khóa/rollback/PostgreSQL đã chạy không thay cho các kiểm chứng này.
- Chưa áp dụng schema hoặc deploy/restart hệ thống đang vận hành; cần kiểm tra nâng cấp schema và dữ liệu cũ trên môi trường thử nghiệm trước khi triển khai.
- Postman, hướng dẫn vận hành và RAG/tool AI chưa được cập nhật đầy đủ cho ba workflow mới.

Thứ tự đề xuất: R01 → R02 → R03 → nghiệm thu E2E trên môi trường riêng. Xuất PDF/ký riêng và chính sách tính theo ngày là các lựa chọn sản phẩm cần xác định phạm vi. Hệ thống thông báo vẫn bỏ qua theo yêu cầu.
