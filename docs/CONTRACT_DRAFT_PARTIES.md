# Chỉnh thông tin các bên và số người/xe trên bản nháp — 05/11/2026

Chủ nhà có thể sửa trực tiếp họ tên, CCCD/định danh, địa chỉ thường trú, điện thoại, email, tên ngân hàng, số tài khoản và chủ tài khoản của hai bên trên hợp đồng gốc DRAFT chưa bắt đầu ký. Ngân hàng dùng chung `data/banks.ts` với Settings; có lựa chọn giữ tên ngân hàng cũ khi dữ liệu lịch sử không còn trong danh sách. Số tài khoản và định danh luôn là chuỗi để giữ số 0 đầu.

Đây là dữ liệu văn bản hợp đồng. Không thay `landlordId`/`tenantId`, hồ sơ KYC, tài khoản đăng nhập hoặc tài khoản nhận tiền/hoàn tiền trong Settings. QR và giao dịch đã xác nhận tiếp tục theo hồ sơ thanh toán; muốn đổi tài khoản thực nhận tiền phải dùng Settings. Hợp đồng và PDF đã ký giữ nguyên; thay đổi sau ký đi qua phụ lục.

## Số người và phương tiện

- Sửa qua `draftContent.tenant.occupantCount`, `motorbikeCount`, `carCount`. Số người 1–100, xe mỗi loại 0–100; bắt buộc số nguyên. Backend kiểm tra thêm giới hạn hợp đồng/Listing, chính sách không nhận xe và sức chứa bãi xe trong toàn bộ khoảng thuê.
- Cùng transaction Lưu: thay số người/xe trong hồ sơ thuê và revision; tính lại các dòng `PER_PERSON_MONTH`/`PER_VEHICLE_MONTH` theo đơn giá đã chốt; cập nhật tổng phí tháng, tổng ước tính tháng/toàn kỳ. Số nước không đo bằng đồng hồ có mô tả theo đúng số người mới.
- Bãi xe khóa pool chi nhánh và kiểm tra optimistic version Listing. Khi kiểm tra sức chứa, bỏ chính lượt giữ chỗ cũ của hồ sơ này, tính các hồ sơ khác. Chỉ thay reservation HELD, giữ nguyên khoảng thuê và hạn giữ; không đổi reservation ACTIVE. Không chờ khóa Listing trong lúc đang giữ khóa Rental để tránh đảo thứ tự khóa với API chấp nhận thuê.
- Không lấy đơn giá mới từ tin để áp vào khoản đã thỏa thuận. Giữ nguyên cọc, khoản ban đầu, snapshot Listing khi yêu cầu thuê và lịch sử chuyển tiền. Bảng phí dự kiến của hồ sơ thuê cập nhật theo số mới, tránh modal yêu cầu thuê vẫn hiện phí theo số người/xe cũ; revision khởi tạo vẫn lưu nội dung hợp đồng ban đầu để đối chiếu.
- Chặn khi hồ sơ đang hủy/đối soát, đã phát hành hóa đơn, không còn ACCEPTED/RESERVED hoặc bãi xe đã kích hoạt.
- Nếu số người làm đổi **giá thuê theo đầu người đã thu**, hoặc hồ sơ cũ đã thu trước **phí dịch vụ** và số mới làm lệch khoản ấy: báo cần thỏa thuận tài chính/đối soát riêng. Không tự ghi lại tiền thu tháng đầu hoặc tạo một hợp đồng có thể bị chặn khi lập hóa đơn kỳ đầu. Giá thuê/cọc đã chốt vẫn thuộc quy trình tài chính riêng.

## Kiểm tra

- Định danh mới phải 9 hoặc 12 chữ số; tài khoản mới 1–34 chữ số (giữ số 0 đầu), điện thoại/email theo quy tắc hiện hành. Dữ liệu lịch sử không bị tự chuẩn hóa hoặc thay bằng ngân hàng đầu danh sách.
- Bộ test bao gồm phí nước theo người; cả xe máy/ô tô; tổng tháng/toàn kỳ; số âm/lẻ; sức chứa; bãi xe đầy; không đếm lượt giữ của chính mình hai lần; không sửa chỗ ACTIVE; giữ tiền và snapshot lịch sử; hồ sơ cũ trả phí trước; giá thuê theo đầu người; lưu rồi revalidate Word/PDF và lưu lần tiếp theo.
- Browser fixture Chrome desktop/mobile kiểm tra dropdown thật dùng danh sách Settings, giữ số 0 đầu, loại số thập phân và một nút Lưu tự kết xuất; không cập nhật hồ sơ thuê hoặc gọi ký/thanh toán thật.

Không thêm cột/bảng mới cho phần này. Restart Core và web để dùng code mới.
