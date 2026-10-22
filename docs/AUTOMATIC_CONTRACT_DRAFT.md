# Tự tạo bản nháp sau khi nhận đủ tiền — 22/10/2026

## Quy tắc

- Cọc ngày 22/10, vào ở ngày 01/11: xác nhận cọc chỉ giữ chỗ, **không tạo hợp đồng**. Đến ngày 01/11 mở khoản tháng đầu; sau khi chủ nhà xác nhận đủ khoản này, Core tự tạo bản nháp.
- Vào ở cùng ngày: khoản INITIAL gộp cọc + tháng đầu; xác nhận đủ khoản gộp thì tự tạo bản nháp.
- Báo chuyển/chờ xác nhận/từ chối/tranh chấp không phải đã nhận tiền. Kiểm tra đúng loại, chiều chuyển, rental, người trả/nhận và số tiền theo thỏa thuận. Miễn cọc 0 đồng được ghi nhận riêng, không yêu cầu chuyển tiền giả.
- Chỉ tạo cho yêu cầu ACCEPTED, Listing RESERVED, không đang xin hủy, đã tới ngày bắt đầu thuê. Không đổi trạng thái thành đang thuê, không tự ký. Sau khi lưu bản nháp thành công, hệ thống tự kết xuất Word/PDF nếu đủ thông tin. Thiếu thông tin bắt buộc thì giữ bản nháp để hai bên bổ sung, không tự bịa dữ liệu.
- Tự động là luồng chính. Chủ nhà có nút dự phòng **Tạo hợp đồng** ở danh sách và modal yêu cầu thuê khi đã xác nhận khoản INITIAL nhưng chưa có hợp đồng; chỉ có cọc thì không hiện nút. Bấm nút sẽ refresh onboarding rồi gọi endpoint tạo draft cũ, dùng chung kiểm tra quyền, đủ tiền, ngày bắt đầu, mẫu đã phát hành và khóa chống tạo trùng. Nếu admin chưa phát hành mẫu, thao tác thủ công cũng báo thiếu mẫu, không bỏ qua kiểm tra hoặc tự dùng mẫu loại nhà khác.
- Luồng dự phòng kiểm tra dữ liệu rồi kết xuất file như trước; file lỗi/thiếu dữ liệu vẫn mở bản nháp đã tạo để tiếp tục, không báo tạo draft thất bại. POST có kết quả mạng chưa rõ chỉ đối chiếu GET, không POST lặp. Khi đã có hợp đồng thì thay bằng nút xem/tiếp tục hợp đồng.

## Độ tin cậy

PaymentRequestService phát sự kiện nội bộ; listener chỉ chạy **sau commit**. Tạo draft trong transaction mới, khóa dòng rental trước khi tìm hợp đồng gốc. Gửi lặp hoặc nhiều instance không tạo lại revision/hợp đồng đã tồn tại. Không giả lập UserContext hệ thống hoặc mở quyền API.

Sự kiện sau commit được đưa sang executor riêng có hàng đợi giới hạn (64 tác vụ, tối đa 2 worker), không chặn HTTP xác nhận tiền để render PDF. Nếu không nhận được tác vụ, log và để scheduler thử lại, không biến khoản tiền đã commit thành lỗi xác nhận.

Scheduler onboarding thử lại mỗi chu kỳ cấu hình `rental.expiration-check-delay-ms` (mặc định 30 giây), kể cả không mở trình duyệt. Đây là retry dựa trạng thái DB, không phải hàng đợi sự kiện bền vững: khởi động lại sau mất sự kiện vẫn tìm lại hồ sơ ACCEPTED đủ tiền. Lỗi mẫu/snapshot rollback toàn bộ draft, không rollback tiền đã xác nhận. Lỗi chuyển PDF/storage chỉ rollback transaction kết xuất, giữ nguyên draft đã commit. Kết xuất khóa rental rồi contract và kiểm tra lại đủ tiền, quyền quan hệ, ngày thuê, chưa hủy/chưa ký; PDF đúng fingerprint được dùng lại, không tạo lại mỗi lần quét. Luồng tự động không coi DOCX đơn lẻ là PDF đã xong.

Log `AUTOMATIC_CONTRACT_DRAFT_FAILED` có rental ID để vận hành xử lý. Khi chưa có draft, view kiểm tra mẫu chuẩn cho đúng category: thiếu mẫu/trùng mẫu trả stage `CONTRACT_TEMPLATE_REQUIRED` và hướng dẫn quản trị viên phát hành/sửa mẫu. Không báo giả là đang tạo. Web hiển thị thông báo Core thay vì câu chờ cố định, tra lại hợp đồng chưa có thay vì cache null vĩnh viễn.

Không xóa bản nháp/hợp đồng cũ. Với hồ sơ mới chưa trả tháng đầu, nhánh xử lý quá hạn không đòi hỏi hợp đồng chưa thể tồn tại; vẫn phải có đồng ý điều kiện giữ chỗ, cọc thực nhận, thông báo cuối cùng, ít nhất 24 giờ phản hồi và không có tranh chấp/chuyển tiền/chữ ký chưa rõ kết quả. Quá hạn ký vẫn đòi văn bản đúng revision và chữ ký chủ nhà. Không tự giữ cọc.

## Test thủ công

1. Chấp nhận thuê 01/11, xác nhận cọc 22/10: không draft, không nút tạo; có thể đề nghị bắt đầu sớm qua yêu cầu thuê.
2. Đổi business clock sang 01/11, restart Core: mở tháng đầu, vẫn không draft.
3. Khách báo chuyển tháng đầu: vẫn không draft. Chủ nhà xác nhận: xuất hiện một draft PAID, liên kết cả hai khoản; chưa ACTIVE. Đủ thông tin thì tự có Word/PDF, thiếu thông tin thì mở bản nháp để bổ sung.
4. Xác nhận lại/tải lại/chạy nhiều chu kỳ: cùng ID, chỉ một revision khởi tạo.
5. Thiếu mẫu chuẩn: xác nhận tiền thành công; không có draft dở dang, view giải thích thiếu mẫu cho quản trị viên. Khôi phục mẫu: scheduler tự tạo, không chuyển lại tiền. Tắt chuyển đổi PDF: draft vẫn tồn tại, chưa có file READY giả; khôi phục dịch vụ thì tự kết xuất lại.
6. Hủy/đối soát trước khi đủ tiền: không tự tạo. Miễn cọc hoặc INITIAL gộp cùng ngày: tạo đúng một draft sau đủ tiền.

Không cần drop CSDL. Không tự sửa `.env`, không thử thao tác xác nhận tiền trên DB đang sử dụng.
