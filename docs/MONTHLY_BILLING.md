# Hóa đơn thuê nhà hằng tháng (giai đoạn 1)

## Luồng đang triển khai

1. Chỉ hợp đồng `ACTIVE` được đồng bộ. Mỗi kỳ tính từ `moveInDate.plusMonths(n)` đến `moveInDate.plusMonths(n + 1)` (ngày cuối không bao gồm). Job chạy mỗi phút và tạo **nháp** cho các kỳ đã kết thúc. Khóa duy nhất `(contract_id, period_index)` tránh tạo trùng sau restart hoặc nhiều instance.
2. Chủ nhà mở chi tiết hợp đồng, chốt chỉ số điện/nước cuối kỳ và khai các khoản phát sinh (nếu có), rồi phát hành. Đơn giá số lấy từ bản phí đã ký; hợp đồng cũ thiếu đơn giá số chỉ được đối chiếu bằng snapshot bất biến nếu nội dung bản ký khớp chính xác. Nếu không đối chiếu được, API chặn phát hành thay vì đoán giá.
   - `STATE_WATER_RATE` chưa có đơn giá số bất biến nên hiện chặn phát hành tự động; cần chuẩn hóa điều khoản/đơn giá trước khi hỗ trợ. Các khoản không có công thức (giờ dùng, thỏa thuận riêng…) chỉ được tính khi chủ nhà khai minh bạch vào dòng phát sinh.
3. Khoản ban đầu chỉ gồm tiền thuê kỳ đầu và tiền cọc (nếu có). Hóa đơn cuối kỳ đầu **không thu lại** tiền thuê, nhưng gồm phí dịch vụ cố định, điện/nước thực dùng và khoản phát sinh. Với hợp đồng cũ đã trả trước phí cố định, kỳ đầu không thu lại khoản đó. Kỳ sau gồm tiền thuê, phí cố định theo snapshot hợp đồng, điện/nước và khoản phát sinh.
4. Hóa đơn có tổng dương tạo đúng một `PaymentRequest` loại `MONTHLY_RENT`, VietQR chuyển trực tiếp người thuê → chủ nhà. Người thuê gửi chứng từ, chủ nhà xác nhận hoặc từ chối. Chỉ xác nhận mới chuyển bill thành `PAID`. Hóa đơn 0 đồng được đóng `PAID` mà không tạo yêu cầu chuyển khoản.
5. Hạn thanh toán là 23:59 ngày 5 kế tiếp sau khi kết thúc kỳ, nhưng tối thiểu 24 giờ sau khi chủ nhà phát hành muộn. Job đánh dấu `OVERDUE` nếu đến hạn mà chưa báo chuyển/được xác nhận. Bill cũ không bị gộp vào bill mới; màn hình hiển thị tổng công nợ riêng.

Chưa làm trong giai đoạn này: tự trích tiền ngân hàng, thanh toán từng phần, thu lãi/phạt, chấm dứt hợp đồng, bàn giao/hoàn cọc và email/push nhắc hạn. Không tự chấm dứt hợp đồng vì quá hạn.

## Dev test thời gian

Trong `.env.dev` của core API:

```properties
HOMESPACE_TIME_SIMULATED_AT=2026-11-01T03:00:00Z
```

Để trống để dùng giờ thực. Chỉ cho phép giá trị khác rỗng ở profile `dev`/`test`. Sau khi đổi giá trị, khởi động lại core API; job đồng bộ trong vòng một phút hoặc tải lại trang chi tiết hợp đồng để đồng bộ ngay. Chỉ đồng hồ của nghiệp vụ hóa đơn/thanh toán tháng được giả lập; OTP, SmartCA, lưu trữ và audit dùng giờ thực.

Ví dụ hợp đồng bắt đầu 01/10/2026, ban đầu đã thanh toán tháng 10:

- `2026-11-01T03:00:00Z`: kỳ 01/10–01/11 xuất hiện nháp. Chủ nhà chốt công tơ và phát hành; bill này không có dòng tiền thuê.
- `2026-11-06T00:00:00Z`: bill chưa trả chuyển `OVERDUE`. Người thuê vẫn có thể gửi chứng từ.
- `2026-12-01T03:00:00Z`: kỳ 01/11–01/12 tạo nháp riêng, bill tháng 10 giữ nguyên công nợ.

## API qua gateway

- `GET /api/v1/monthly-invoices/contracts/{contractId}` — hai bên của hợp đồng xem bill, đồng thời đồng bộ các kỳ đã kết thúc.
- `POST /api/v1/monthly-invoices/{invoiceId}/issue` — chỉ chủ nhà phát hành bill nháp: `{ "electricityEnd": 1250, "waterEnd": 37, "extraCharges": [{ "description": "Sửa khóa", "amount": 100000 }] }`.
- Thanh toán dùng API `/api/v1/payment-requests/{id}` và các endpoint `report-transfer`, `confirm-receipt`, `reject-receipt` hiện có.

Schema được tạo từ JPA entity theo cấu hình DDL hiện tại, không có file SQL migration (theo lựa chọn dev hiện tại). Trước production cần migration có kiểm soát và kiểm thử PostgreSQL thực tế.
