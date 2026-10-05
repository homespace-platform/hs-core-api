# Hóa đơn thuê nhà hằng tháng

## Luồng đang triển khai

1. Chỉ hợp đồng `ACTIVE` được đồng bộ. Mỗi kỳ tính từ `moveInDate.plusMonths(n)` đến `moveInDate.plusMonths(n + 1)` (ngày cuối không bao gồm). Job chạy mỗi phút và tạo **nháp từ ngày cuối cùng của kỳ**. Khóa duy nhất `(contract_id, period_index)` tránh tạo trùng sau restart hoặc nhiều instance.
2. Ngày cuối kỳ, backend ghi log nhắc chủ nhà chốt chỉ số. Chủ nhà có thể dùng `issue` để chốt chỉ số điện/nước và phí phát sinh rồi **phát hành ngay trong ngày cuối kỳ**; người thuê thấy hóa đơn và QR để thanh toán ngay. Nếu chỉ dùng `prepare`, dữ liệu còn là nháp, không hiển thị cho người thuê; job tự phát hành từ 10:00 sáng ngày kế tiếp (giờ Việt Nam) khi đã đủ dữ liệu hợp lệ. Nếu thiếu chỉ số hoặc thiếu đơn giá số, job ghi log cần xử lý và giữ nháp, tuyệt đối không ước tính. Chủ nhà vẫn có thể bổ sung rồi phát hành thủ công. Đơn giá lấy từ bản phí đã ký; hợp đồng cũ thiếu đơn giá số chỉ được đối chiếu bằng snapshot bất biến nếu nội dung bản ký khớp chính xác. Nếu không đối chiếu được, API chặn phát hành thay vì đoán giá.
   - `STATE_WATER_RATE` chưa có đơn giá số bất biến nên hiện chặn phát hành tự động; cần chuẩn hóa điều khoản/đơn giá trước khi hỗ trợ. Các khoản không có công thức (giờ dùng, thỏa thuận riêng…) chỉ được tính khi chủ nhà khai minh bạch vào dòng phát sinh.
3. Khoản ban đầu chỉ gồm tiền thuê kỳ đầu và tiền cọc (nếu có). Hóa đơn cuối kỳ đầu **không thu lại** tiền thuê, nhưng gồm phí dịch vụ cố định, điện/nước thực dùng và khoản phát sinh. Với hợp đồng cũ đã trả trước phí cố định, kỳ đầu không thu lại khoản đó. Kỳ sau gồm tiền thuê, phí cố định theo snapshot hợp đồng, điện/nước và khoản phát sinh.
4. Hóa đơn có tổng dương tạo đúng một `PaymentRequest` loại `MONTHLY_RENT`, VietQR chuyển trực tiếp người thuê → chủ nhà. Người thuê gửi chứng từ, chủ nhà xác nhận hoặc từ chối. Chỉ xác nhận mới chuyển bill thành `PAID`. Hóa đơn 0 đồng được đóng `PAID` mà không tạo yêu cầu chuyển khoản.
5. Hạn thanh toán là 23:59 ngày 5 kế tiếp sau khi kết thúc kỳ, nhưng tối thiểu 24 giờ sau khi phát hành muộn. Từ ngày 3 backend ghi log nhắc trả. Hết hạn mà chưa báo chuyển/được xác nhận, bill chuyển `OVERDUE`; đến ngày 10 ghi log cần chủ nhà xử lý. Bill cũ không bị gộp vào bill mới; màn hình hiển thị tổng công nợ riêng.
6. Bản nháp hợp đồng có tùy chọn `NONE`, `FIXED_ONCE`, `FIXED_PER_DAY`, số ngày miễn phạt và mức trần. Điều khoản được nối vào `contract.specialTerms` trong DOCX kết xuất; nếu mẫu thiếu placeholder này, backend từ chối cấu hình phí. Hợp đồng cũ không có cấu hình thì **không thu phạt**. Quá hạn, phí tăng một lần hoặc theo ngày địa phương, được thêm thành dòng `LATE_FEE` trên chính hóa đơn; số tiền `PaymentRequest` và VietQR đổi đồng bộ. Chỉ tăng khi chưa báo chuyển/đang tranh chấp/xác nhận; người thuê phải gửi `expectedAmount` khớp số tiền mới để tránh dùng QR cũ. Phí tạm ngừng tăng khi đã báo chuyển khoản chờ đối soát.

Chưa làm: tự trích tiền ngân hàng, thanh toán từng phần, chấm dứt hợp đồng, bàn giao/hoàn cọc và email/push nhắc hạn. Các mốc hiện chỉ ghi log backend và hiển thị trên trang hợp đồng; **không tự chấm dứt hợp đồng vì quá hạn**. Trước khi dùng phí phạt ở production, cần duyệt nội dung điều khoản và chính sách pháp lý.

## Dev test thời gian

Trong `.env` của core API khi chạy bằng cấu hình VS Code của dự án:

```properties
HOMESPACE_TIME_SIMULATED_AT=2026-11-01T10:00:00
```

Giá trị không có `Z`/offset được hiểu là **giờ Việt Nam** (`Asia/Ho_Chi_Minh`), không cần tự đổi sang UTC. Giá trị ISO-8601 có `Z` hoặc offset vẫn được hỗ trợ để tương thích với cấu hình cũ. Để trống để dùng giờ thực. Chỉ cho phép giá trị khác rỗng ở profile `dev`/`test`. Sau khi đổi giá trị, khởi động lại core API; log khởi động sẽ hiện giờ Việt Nam và UTC đã áp dụng. Job đồng bộ trong vòng một phút hoặc tải lại trang chi tiết hợp đồng để đồng bộ ngay. Chỉ đồng hồ của nghiệp vụ hóa đơn/thanh toán tháng được giả lập; OTP, SmartCA, lưu trữ và audit dùng giờ thực.

Ví dụ hợp đồng bắt đầu 01/10/2026, ban đầu đã thanh toán tháng 10:

- `2026-10-31T00:00:00` (31/10 00:00): nháp xuất hiện cho chủ nhà; có thể chốt và phát hành ngay, người thuê chỉ thấy sau khi phát hành.
- `2026-11-01T10:00:00` (01/11 10:00): tự phát hành nếu đã lưu chỉ số. Bill tháng 10 không có dòng tiền thuê.
- `2026-11-03T00:00:00` (03/11 00:00): log nhắc người thuê thanh toán.
- `2026-11-06T00:00:00` (06/11 00:00): bill chưa trả chuyển `OVERDUE`, bắt đầu tính phí nếu hợp đồng đã ký có điều khoản. Người thuê vẫn có thể gửi chứng từ.
- `2026-11-10T00:00:00` (10/11 00:00): log chủ nhà cần xử lý quá hạn; không tự chấm dứt.
- `2026-11-30T00:00:00`: kỳ 01/11–01/12 tạo nháp riêng; nếu chủ nhà đã chốt chỉ số, có thể tự phát hành từ `2026-12-01T10:00:00`. Bill tháng 10 giữ nguyên công nợ.

## API qua gateway

- `GET /api/v1/monthly-invoices/contracts/{contractId}` — hai bên của hợp đồng xem bill, đồng thời đồng bộ các kỳ đã kết thúc.
- `POST /api/v1/monthly-invoices/{invoiceId}/prepare` — chủ nhà lưu chỉ số và khoản phát sinh trước khi tự phát hành; cùng payload với `issue`.
- `POST /api/v1/monthly-invoices/{invoiceId}/issue` — chỉ chủ nhà phát hành bill nháp: `{ "electricityEnd": 1250, "waterEnd": 37, "extraCharges": [{ "description": "Sửa khóa", "amount": 100000 }] }`.
- Thanh toán dùng API `/api/v1/payment-requests/{id}` và các endpoint `report-transfer`, `confirm-receipt`, `reject-receipt` hiện có. Với bill tháng, `report-transfer` cần `expectedAmount` bằng tổng hiện tại.

Schema được tạo từ JPA entity theo cấu hình DDL hiện tại, không có file SQL migration (theo lựa chọn dev hiện tại). Trước production cần migration có kiểm soát và kiểm thử PostgreSQL thực tế.
