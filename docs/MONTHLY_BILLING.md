# Hóa đơn thuê nhà hằng tháng

## Luồng hiện tại

1. Chỉ hợp đồng `ACTIVE` được đồng bộ. Mỗi kỳ tính từ `moveInDate.plusMonths(n)` đến `moveInDate.plusMonths(n + 1)` (ngày cuối không bao gồm). Job chạy mỗi phút và tạo **nháp từ ngày cuối cùng của kỳ**. Khóa duy nhất `(contract_id, period_index)` tránh tạo trùng sau restart hoặc nhiều instance.
2. Ngày cuối kỳ, backend ghi log nhắc chủ nhà chốt chỉ số. Chủ nhà có thể dùng `issue` để chốt chỉ số điện/nước và phí phát sinh rồi **phát hành ngay trong ngày cuối kỳ**; người thuê thấy hóa đơn và QR để thanh toán ngay. Nếu chỉ dùng `prepare`, dữ liệu còn là nháp, không hiển thị cho người thuê; job tự phát hành từ 10:00 sáng ngày kế tiếp (giờ Việt Nam) khi đã đủ dữ liệu hợp lệ. Nếu thiếu chỉ số hoặc thiếu đơn giá số, job ghi log cần xử lý và giữ nháp, tuyệt đối không ước tính. Chủ nhà vẫn có thể bổ sung rồi phát hành thủ công. Đơn giá lấy từ bản phí đã ký; hợp đồng cũ thiếu đơn giá số chỉ được đối chiếu bằng snapshot bất biến nếu nội dung bản ký khớp chính xác. Nếu không đối chiếu được, API chặn phát hành thay vì đoán giá.
   - `STATE_WATER_RATE` chưa có đơn giá số bất biến nên hiện chặn phát hành tự động; cần chuẩn hóa điều khoản/đơn giá trước khi hỗ trợ. Các khoản không có công thức (giờ dùng, thỏa thuận riêng…) chỉ được tính khi chủ nhà khai minh bạch vào dòng phát sinh.
3. Khoản ban đầu chỉ gồm tiền thuê kỳ đầu và tiền cọc (nếu có). Mỗi hóa đơn cuối kỳ gồm phí dịch vụ cố định, điện/nước thực dùng và khoản phát sinh **của kỳ vừa kết thúc**, cộng **tiền thuê kỳ tiếp theo** nếu còn kỳ thuê theo hợp đồng. Vì vậy hóa đơn chốt kỳ 10 thu tiền phòng kỳ 11, không thu lại tiền phòng kỳ 10 đã trả ban đầu. Với hợp đồng cũ đã trả trước phí cố định, kỳ đầu không thu lại khoản đó. Hóa đơn kỳ cuối chỉ quyết toán chi phí, không thu tiền phòng ngoài thời hạn thuê.
4. Hóa đơn có tổng dương tạo đúng một `PaymentRequest` loại `MONTHLY_RENT`, VietQR chuyển trực tiếp người thuê → chủ nhà. Người thuê gửi chứng từ, chủ nhà xác nhận hoặc từ chối. Chỉ xác nhận mới chuyển bill thành `PAID`. Hóa đơn 0 đồng được đóng `PAID` mà không tạo yêu cầu chuyển khoản.
5. Các mốc do hệ thống quy định, không phải cấu hình của chủ nhà: **5 ngày lịch thanh toán tính cả ngày chốt kỳ**, tức hạn 23:59:59 của ngày chốt kỳ + 4 ngày. Phát hành thủ công muộn vẫn tối thiểu 24 giờ thanh toán; scheduler phát hành bù giữ hạn gốc. Nhắc trả trước hạn 2 ngày. Hết hạn chưa thanh toán thì `OVERDUE`; sau hết 5 ngày quá hạn mở các phương án xử lý; sau hết 10 ngày quá hạn tự chốt cộng dồn khi đủ điều kiện và có kỳ sau chưa phát hành, không tự chấm dứt hoặc tự hủy QR.
6. Chủ nhà chỉ chọn `NONE`, `FIXED_ONCE`, `FIXED_PER_DAY` và **mức phí**. Không cho sửa số ngày đến hạn, số ngày ân hạn hoặc trần tiền phạt, kể cả qua API phụ lục. Hệ thống không thêm ân hạn sau deadline (graceDays = 0); phạt theo ngày tối đa 10 ngày trên mỗi hóa đơn. Tổng tiền phạt tối đa được **tự tính**: theo ngày = mức phí/ngày × 10, một lần = đúng mức phí/lần, không phạt = 0. Ví dụ 100.000đ/ngày → tối đa 1.000.000đ; không có ô nhập trần riêng. Các giá trị này là **nhãn chỉ đọc**, không phải các mục cần chủ nhà điền; hệ thống đưa đầy đủ điều khoản và mức tối đa vào `{{contract.specialTerms}}` trong Word. Nếu mẫu thiếu marker này, backend chặn cấu hình phí. Khoản phạt thực tế không mặc định bằng mức tối đa: còn phụ thuộc ngày trễ, thanh toán/đối soát, đóng băng hoặc gia hạn. Phí thêm thành dòng `LATE_FEE`, đồng bộ số tiền/QR; `expectedAmount` phải khớp số mới. Phí không tăng khi đang báo chuyển khoản, tranh chấp hoặc đã xác nhận. **Bản đã ký cũ** có ân hạn/trần thấp hơn vẫn đọc đúng snapshot cũ, không sửa hóa đơn/PDF lịch sử. Khi lập điều khoản phí mới, backend cấp lại các giá trị hệ thống, chỉ cách tính/mức phí là đầu vào.
7. Sau 5 ngày quá hạn (và khi không đối soát chuyển khoản), chủ nhà có hai hướng xử lý thật:
   - **Cộng dồn kỳ sau:** đóng băng phí phạt ở số đã tính tại lúc chọn. Trước khi kỳ kế tiếp phát hành, người thuê vẫn có thể thanh toán hóa đơn cũ. Khi phát hành kỳ sau, backend khóa yêu cầu thanh toán cũ, hủy QR và thêm một dòng `BALANCE_FORWARD` bằng đúng tổng hóa đơn cũ (gồm phí phạt) vào hóa đơn mới; hóa đơn cũ thành `ROLLED_OVER` và liên kết tới hóa đơn mới. Không cho cộng dồn nếu đang báo chuyển khoản/đối soát hoặc đã ở kỳ cuối.
   - **Đề nghị hai bên chấm dứt sớm:** đề nghị không đổi hợp đồng/tin/cọc. Người thuê có thể chấp thuận hoặc từ chối. Nếu chấp thuận, chủ nhà xác nhận phòng đã trống, nhận lại chìa khóa/tài sản rồi mới hoàn tất.
   - **Sau khi người thuê từ chối:** hợp đồng vẫn `ACTIVE`, cọc `HELD`, tin vẫn ẩn. Chủ nhà có thể rút đề nghị (giữ nguyên các trạng thái trên), chuyển công nợ kỳ sau nếu còn kỳ, hoặc chọn chấm dứt theo điều khoản quá hạn trong bản ký. Nhánh chấm dứt này chỉ mở cho hợp đồng mới có policy `overdueLandlordTerminationAfterFiveDays=true` và câu điều khoản tương ứng trong revision đã ký; không áp dụng hồi tố cho hợp đồng cũ. Nếu điều khoản bị xóa khi sửa bản nháp, cờ policy cũng bị tắt. Backend yêu cầu hóa đơn đã quá hạn đủ 5 ngày, chưa báo chuyển khoản/đối soát/trả xong, người thuê đã từ chối, chủ nhà xác nhận đã thông báo và thực tế nhận lại phòng/chìa khóa/tài sản. Lúc đó cùng transaction đổi hợp đồng thành `TERMINATED`, cọc thành `RETAINED_BY_LANDLORD`, giải phóng chỗ xe và mở lại tin `PUBLISHED`; công nợ hóa đơn vẫn được theo dõi và có thể chuyển sang `PAID` khi chủ nhà xác nhận nhận tiền sau chấm dứt. Không phát sinh kỳ thuê mới hoặc cộng thêm phí phạt sau chấm dứt. Không có thao tác tự động chấm dứt ở mốc 5 ngày. Backend ghi log, chưa gửi email/push.

Chưa làm: tự trích tiền ngân hàng, thanh toán từng phần, bàn giao/hoàn cọc cho trường hợp khác và email/push nhắc hạn. Trước khi dùng điều khoản phí phạt, giữ cọc hoặc chấm dứt ở production, cần duyệt pháp lý và xác định quy trình giao nhận/chứng cứ ngoài hệ thống; nút xác nhận trên web chưa thay thế thủ tục bàn giao thực tế.

## Dev test thời gian

Trong `hs-api-service/src/main/resources/application.properties`, khi Core chạy profile `dev`/`test`:

```properties
homespace.time.simulated-at=2026-11-01T10:00:00
```

Giá trị không có `Z`/offset được hiểu là **giờ Việt Nam** (`Asia/Ho_Chi_Minh`), không cần tự đổi sang UTC. Giá trị ISO-8601 có `Z` hoặc offset vẫn được hỗ trợ để tương thích với cấu hình cũ. Để trống để dùng giờ thực. Chỉ cho phép giá trị khác rỗng ở profile `dev`/`test`. Sau khi đổi giá trị, khởi động lại core API; log khởi động sẽ hiện giờ Việt Nam và UTC đã áp dụng. Job đồng bộ trong vòng một phút hoặc tải lại trang chi tiết hợp đồng để đồng bộ ngay. Chỉ đồng hồ của nghiệp vụ hóa đơn/thanh toán tháng được giả lập; OTP, SmartCA, lưu trữ và audit dùng giờ thực.

Ví dụ hợp đồng bắt đầu 01/10/2026, ban đầu đã thanh toán tháng 10:

- `2026-10-31T00:00:00` (31/10 00:00): nháp xuất hiện cho chủ nhà; có thể chốt và phát hành ngay, người thuê chỉ thấy sau khi phát hành.
- `2026-11-01T10:00:00` (01/11 10:00): tự phát hành nếu đã lưu chỉ số. Bill gồm chi phí thực tế kỳ 10 và tiền thuê kỳ 11; không thu lại tiền thuê kỳ 10.
- `2026-11-03T00:00:00` (03/11 00:00): log nhắc người thuê thanh toán.
- `2026-11-06T00:00:00` (06/11 00:00): bill chưa trả chuyển `OVERDUE`, bắt đầu tính phí nếu hợp đồng đã ký có điều khoản. Người thuê vẫn có thể gửi chứng từ.
- `2026-11-11T00:00:00` (11/11 00:00): đã hết 5 ngày quá hạn (06–10/11), mở phương án xử lý cho chủ nhà; không tự chấm dứt.
- `2026-11-30T00:00:00`: kỳ 01/11–01/12 tạo nháp riêng; nếu chủ nhà đã chốt chỉ số, có thể tự phát hành từ `2026-12-01T10:00:00`. Nếu đã chọn cộng dồn, bill mới gồm một dòng công nợ bill trước; QR bill cũ bị hủy.

Với hợp đồng bắt đầu **05/10/2026** của luồng thử nghiệm: đặt một giá trị ở mỗi lần chạy backend, dùng giờ Việt Nam không có `Z`.

| `homespace.time.simulated-at` | Kiểm tra |
| --- | --- |
| `2026-11-04T00:01:00` | Chủ nhà thấy nháp kỳ 05/10–05/11, nhập điện/nước và phát hành; người thuê thấy hóa đơn/QR ngay. |
| `2026-11-05T10:01:00` | Nếu đã lưu đủ chỉ số nhưng chưa phát hành, job tự phát hành. Hóa đơn gồm phí kỳ 10 và tiền thuê kỳ 11. |
| `2026-11-10T00:01:00` | Quá hạn (hạn 09/11 23:59), phí phạt hợp đồng bắt đầu tính. |
| `2026-11-15T00:01:00` | Đã hết 5 ngày quá hạn (10–14/11): mở các phương án xử lý; không tự chấm dứt. |
| `2026-12-04T00:01:00` | Nếu chọn chuyển nợ, chủ nhà chốt chỉ số kỳ tiếp theo; hóa đơn cũ vẫn thanh toán được cho tới lúc hóa đơn mới phát hành. |
| `2026-12-05T10:01:00` | Hóa đơn mới có dòng `BALANCE_FORWARD`, QR cũ hủy; hai bên chỉ trả tổng mới, không trả trùng. |

Nếu chọn chấm dứt, kiểm tra chủ nhà gửi đề nghị → người thuê chấp thuận/không chấp thuận → chủ nhà xác nhận bàn giao; chỉ sau bước cuối tin mới hiển thị công khai và cọc chuyển trạng thái. Nếu người thuê đã báo chuyển khoản hoặc đang đối soát, xử lý thanh toán trước khi thực hiện quyết định ở mốc quá hạn.

## API qua gateway

- `GET /api/v1/monthly-invoices/contracts/{contractId}` — hai bên của hợp đồng xem bill, đồng thời đồng bộ các kỳ đã kết thúc.
- `POST /api/v1/monthly-invoices/{invoiceId}/prepare` — chủ nhà lưu chỉ số và khoản phát sinh trước khi tự phát hành; cùng payload với `issue`.
- `POST /api/v1/monthly-invoices/{invoiceId}/issue` — chỉ chủ nhà phát hành bill nháp: `{ "electricityEnd": 1250, "waterEnd": 37, "extraCharges": [{ "description": "Sửa khóa", "amount": 100000 }] }`.
- `POST /api/v1/monthly-invoices/{invoiceId}/overdue-actions` — chủ nhà ghi phương án sau 5 ngày quá hạn: `{ "type": "PAYMENT_REQUEST", "note": "Đề nghị kiểm tra và thanh toán khoản nợ." }`. Với `EXTENSION_PROPOSAL`, truyền thêm `proposedDate` (ngày địa phương `YYYY-MM-DD`). Không tự gia hạn hóa đơn.
- `POST /api/v1/monthly-invoices/{invoiceId}/overdue-actions/{actionId}/acknowledge` — người thuê xác nhận đã xem và có thể phản hồi `{ "note": "Tôi sẽ liên hệ lại." }`; không phải chấp thuận sửa hợp đồng.
- `POST /api/v1/monthly-invoices/{invoiceId}/defer-to-next-period` — chủ nhà chốt công nợ để cộng dồn khi hóa đơn mới phát hành.
- `POST /api/v1/monthly-invoices/{invoiceId}/termination/propose` — chủ nhà đề nghị chấm dứt.
- `POST /api/v1/monthly-invoices/{invoiceId}/termination/accept` — người thuê đồng ý với body `{ "acceptEarlyTermination": true, "acceptDepositRetention": true, "acknowledgeOutstandingDebt": true }`.
- `POST /api/v1/monthly-invoices/{invoiceId}/termination/decline` — người thuê không đồng ý; hợp đồng không thay đổi.
- `POST /api/v1/monthly-invoices/{invoiceId}/termination/withdraw` — chủ nhà rút đề nghị trước khi người thuê đồng ý, kể cả sau khi người thuê từ chối; hợp đồng không thay đổi. Đề nghị cũng tự hết hiệu lực khi hóa đơn được xác nhận đã trả.
- `POST /api/v1/monthly-invoices/{invoiceId}/termination/complete` — chủ nhà xác nhận đã bàn giao với body `{ "vacantPossessionConfirmed": true, "keysAndAssetsReturned": true }`. Chỉ lúc này hợp đồng, cọc và tin đăng đổi trạng thái trong cùng transaction.
- `POST /api/v1/monthly-invoices/{invoiceId}/termination/force-after-decline` — sau khi người thuê từ chối, chủ nhà xác nhận điều khoản ký và bàn giao thực tế với body `{ "signedClauseAcknowledged": true, "tenantNotified": true, "vacantPossessionConfirmed": true, "keysAndAssetsReturned": true }`; chỉ mở cho hợp đồng có điều khoản có cấu trúc trong revision đã ký.
- Thanh toán dùng API `/api/v1/payment-requests/{id}` và các endpoint `report-transfer`, `confirm-receipt`, `reject-receipt` hiện có. Với bill tháng, `report-transfer` cần `expectedAmount` bằng tổng hiện tại.

Schema được tạo từ JPA entity theo cấu hình DDL hiện tại, không có file SQL migration (theo lựa chọn dev hiện tại). Trước production cần migration có kiểm soát và kiểm thử PostgreSQL thực tế.
