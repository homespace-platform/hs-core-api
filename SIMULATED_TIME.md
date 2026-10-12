# Test hóa đơn và gia hạn thanh toán bằng thời gian giả lập

## Chuẩn bị — ngày bắt đầu thuê demo 12/10/2026

**12/10/2026 là ngày nhận nhà/bắt đầu thuê**, không phải ngày phát hành hóa đơn.

- Tạo yêu cầu thuê từ **12/10/2026**, thời hạn **12 tháng**, kết thúc **11/10/2027**.
- Thanh toán ban đầu, lưu công tơ, kết xuất và ký hai bên để hợp đồng ACTIVE.
- Công tơ ban đầu gợi ý: điện **1500 kWh**, nước **85 m³**. Có đơn giá số PER_KWH/PER_M3 đã chốt; không dùng STATE_WATER_RATE cho demo.
- Phạt **100.000đ/ngày**, graceDays **0**, không trần; lưu và ký điều khoản trước khi test.
- Bản ký mới có `financial.paymentDueOffsetDays=4`. Hợp đồng cũ theo hạn ngày 5 sẽ có lịch khác.
- Giá thuê tùy chọn; các con số phí phạt bên dưới là phần cộng thêm, không phải tổng hóa đơn.
- **Mỗi nhánh A/B/C/D/E/F/G dùng một hợp đồng độc lập**. Tạo sẵn trước khi tiến thời gian.

Trong `.env` Core API, profile `dev`/`test`:

```dotenv
HOMESPACE_TIME_SIMULATED_AT=2026-10-12T10:00:00
HOMESPACE_BILLING_SYNC_DELAY_MS=60000
HOMESPACE_BILLING_EXTENSION_RESPONSE_HOURS=48
```

Mỗi bước đổi `HOMESPACE_TIME_SIMULATED_AT`, restart Core, chờ khoảng 1 phút rồi làm mới hai tài khoản.
Giá trị không có `Z` là **giờ Việt Nam UTC+7**. Đồng hồ đứng yên ở mốc đã nhập: chờ ngoài đời không làm giờ giả lập tiến lên.
Scheduler thực hiện ở lượt đầu sau mốc, không bảo đảm đúng từng giây 00:00.
Thời gian đăng nhập, token upload/Storage và SmartCA vẫn là thời gian thật.

**Không lùi giờ trên cùng hợp đồng để thử nhánh khác**: thời gian giả lập không hoàn tác dữ liệu đã lưu.
Không reset/xóa hợp đồng thật. Cuối buổi test để `HOMESPACE_TIME_SIMULATED_AT=` rỗng và restart.

### Schema

Với database local/dev tạo lại từ đầu, đặt `JPA_DDL_AUTO=update` và start Core để Hibernate tạo schema từ entity hiện tại, gồm các cột gia hạn và default boolean. Không cần chạy SQL vá schema riêng.
Nếu dùng `validate`/`none`, schema phải được chuẩn bị sẵn; hai chế độ này không tự tạo bảng trên database trống.
Nạp seed sau khi Core đã tạo schema, tài khoản ADMIN và catalog đã sẵn sàng. Không drop DB trong khi Core đang chạy.
Kịch bản này không bao gồm gia hạn thời gian thuê hoặc hoàn cọc cuối hợp đồng.

## Lịch cơ bản trước khi rẽ nhánh

| HOMESPACE_TIME_SIMULATED_AT | Kiểm tra |
| --- | --- |
| `2026-10-12T10:00:00` | ACTIVE, chưa có bill tháng. Tiền thuê kỳ đầu đã trả ban đầu. |
| `2026-11-10T23:59:59` | Chưa có nháp kỳ 1. |
| `2026-11-11T00:01:00` | Chủ nhà thấy nháp kỳ 1 (12/10–11/11), mở 24 giờ nhập công tơ; người thuê chưa thấy bill/QR. |
| `2026-11-11T23:59:00` | Vẫn được lưu chỉ số. Có thể phát hành thủ công sớm nếu đã chốt đúng. |
| `2026-11-12T00:01:00` | Tự phát hành: chi phí kỳ 1 + tiền thuê kỳ 2 (12/11–11/12). Không thu lại tiền thuê kỳ 1. Hạn 16/11 23:59:59. |
| `2026-11-14T00:01:00` | Nhắc sắp đến hạn nếu chưa gia hạn/đối soát/thanh toán. |
| `2026-11-16T23:59:59` | Vẫn trong hạn, chưa phạt. |
| `2026-11-17T00:01:00` | Quá hạn ngày 1: 100.000đ. |
| `2026-11-18T00:01:00` | Quá hạn ngày 2: 200.000đ. |
| `2026-11-19T00:01:00` | Quá hạn ngày 3: 300.000đ. |
| `2026-11-21T23:59:59` | Phạt 500.000đ; chưa mở ba phương án vì chưa hết ngày thứ 5. |
| `2026-11-22T00:01:00` | Ba phương án mở. Nếu chưa xin/được miễn tăng phạt, đã sang ngày trễ thứ 6: 600.000đ. |
| `2026-12-11T00:01:00` | Nháp kỳ 2 nếu hợp đồng vẫn ACTIVE. |
| `2026-12-12T00:01:00` | Tự phát hành kỳ 2; hạn 16/12 23:59:59. |

### Test công tơ vào 11/11, trước khi rẽ nhánh

- Nhập điện **1550**, nước **90**: lượng dùng 50 kWh, 5 m³; không nhãn tự điền.
- Không nhập: tự điền 1500→1500, 85→85, lượng dùng tạm tính 0; cả hai bên thấy cảnh báo.
- Nhập điện 1550, bỏ nước: giữ điện; chỉ nước tự điền 85.
- Kỳ tiếp theo lấy cuối kỳ trước làm đầu kỳ; phần chưa ghi nhận có thể dồn sang kỳ sau.
- Restart/refresh nhiều lần không tạo trùng bill/PaymentRequest.
- Thiếu chỉ số đầu hoặc đơn giá hợp lệ: chưa phát hành, không tự đoán dữ liệu.

## A — Xin trước hạn, duyệt, không tăng phạt nữa

| Mốc | Thao tác / kết quả |
| --- | --- |
| `2026-11-14T10:00:00` | Người thuê xin **7 ngày**, lý do ít nhất 10 ký tự. Phí chốt 0; phản hồi trước 16/11 10:00. Không gửi chồng. |
| `2026-11-15T10:00:00` | Chủ nhà đồng ý: hạn mới **22/11 10:00**, tính từ lúc duyệt +7 ngày. Giữ hạn gốc và ID bill/PaymentRequest/mã chuyển khoản. |
| `2026-11-20T10:00:00` | Nhắc theo hạn mới, không lịch cũ. |
| `2026-11-22T10:00:01` | Quá hạn mới nhưng phí vẫn **0đ**. |
| `2026-11-28T00:01:00` | Hết 5 ngày quá hạn mới, ba phương án mở lại; không tự chấm dứt. |

Nếu số ngày chọn khi duyệt sớm tạo hạn mới không sau hạn hiện tại, backend từ chối rút ngắn hạn; chọn số ngày lớn hơn hoặc duyệt gần hạn.

## B — Xin sau 3 ngày trễ, giữ 300.000đ, duyệt

| Mốc | Thao tác / kết quả |
| --- | --- |
| `2026-11-19T10:00:00` | Xin **7 ngày**, chốt phạt **300.000đ**, dừng tăng ngay khi ghi nhận yêu cầu. Phản hồi trước 21/11 10:00. |
| `2026-11-20T10:00:00` | Trước khi duyệt vẫn 300.000đ. Chủ nhà đồng ý: hạn mới **27/11 10:00**, phạt vẫn 300.000đ. |
| `2026-11-27T10:00:01` | Trễ hạn mới, phạt vẫn 300.000đ; tổng bill/QR khớp. |
| `2026-12-03T00:01:00` | Ba phương án mở lại. Có thể dời tiếp 1–30 ngày, không chọn hai phương án cùng lúc. |

## C — Từ chối, tính bù không cộng trùng

| Mốc | Thao tác / kết quả |
| --- | --- |
| `2026-11-19T10:00:00` | Xin 7 ngày; phí chốt 300.000đ. |
| `2026-11-20T10:00:00` | Chủ nhà từ chối: phạt **400.000đ**, không phải 700.000đ. Bill/chi tiết thanh toán/QR cập nhật cùng nhau. |
| `2026-11-21T10:00:00` | Phạt 500.000đ; refresh không tăng trong cùng ngày. Không gửi lại yêu cầu trong cửa sổ hạn cũ để dừng phạt tiếp. |
| `2026-11-22T00:01:00` | Ba phương án mở; chủ nhà vẫn có thể chủ động dời hạn dù đã từ chối yêu cầu trước. |

## D — Không phản hồi, hết hạn 48 giờ

| Mốc | Thao tác / kết quả |
| --- | --- |
| `2026-11-19T10:00:00` | Xin 7 ngày, phí 300.000đ; hạn phản hồi 21/11 10:00. |
| `2026-11-21T09:59:59` | Còn chờ, phí vẫn 300.000đ. |
| `2026-11-21T10:00:00` | EXPIRED, phạt tính bù **500.000đ**. Không duyệt được dù scheduler chưa chạy; API cũng kiểm tra hạn. |
| `2026-11-22T00:01:00` | Ba phương án mở, không treo yêu cầu vô hạn. |

## E — Cộng dồn kỳ sau khi có yêu cầu đang chờ

| Mốc | Thao tác / kết quả |
| --- | --- |
| `2026-11-21T10:00:00` | Xin 7 ngày, phí chốt 500.000đ; chờ tới 23/11 10:00. |
| `2026-11-22T00:01:00` | Chọn **Cộng dồn sang kỳ sau**: từ chối yêu cầu, tính bù phí **600.000đ**, chốt tổng nợ trong cùng giao dịch. Không đồng thời duyệt/chấm dứt. |
| `2026-12-11T10:00:00` | Nhập công tơ kỳ 2 nếu muốn. |
| `2026-12-12T00:01:00` | Kỳ 2 có đúng một dòng nợ kỳ 1; kỳ 1 ROLLED_OVER, QR không còn hiển thị/được hệ thống chấp nhận; tổng nợ không tính hai lần. |

Kỳ cuối không có kỳ sau thì cộng dồn bị khóa. Hủy QR trong hệ thống không thể ngăn chuyển tiền bằng ảnh QR đã lưu; khoản chuyển sai cần đối soát.

## F — Chủ nhà dời hạn trực tiếp, ba phương án loại trừ

| Mốc | Thao tác / kết quả |
| --- | --- |
| `2026-11-21T23:59:59` | Chưa được chủ động chọn ba phương án. |
| `2026-11-22T00:00:00` | Dời **3 ngày**, lý do ít nhất 10 ký tự: hạn **25/11 00:00**, phí chốt **600.000đ**. Không tạo bill mới. |
| `2026-11-24T10:00:00` | Không thể cộng dồn/chấm dứt/dời thêm chồng khi gia hạn còn hiệu lực. |
| `2026-11-25T00:00:01` | Trễ hạn mới, phí vẫn 600.000đ. |
| `2026-12-01T00:01:00` | Ba phương án mở lại. |

Thử ngày 0, 31, 1000: phải từ chối. Hai tab chọn dời hạn/cộng dồn gần đồng thời: chỉ một quyết định thành công; tab thua refresh trạng thái mới.

## G — Thanh toán, đối soát và chấm dứt

### G1: thanh toán trong khi yêu cầu đang chờ

- `2026-11-19T10:00:00`: xin gia hạn, phí 300.000đ.
- `2026-11-20T10:00:00`: gửi chứng từ đúng tổng tiền. UNDER_REVIEW không được đổi phương án.
- Chủ nhà xác nhận: PAID, yêu cầu CLOSED; không duyệt tiếp hoặc tăng số tiền đã trả.
- Từ chối chứng từ: áp dụng trạng thái yêu cầu hiện tại, vẫn kiểm tra hạn phản hồi 48 giờ.

### G2: chấm dứt — dùng hợp đồng độc lập khác, chưa trả tiền

- `2026-11-22T00:01:00`: chọn đề nghị chấm dứt; nếu có yêu cầu chờ thì từ chối/tính bù trước khi chọn.
- Đề nghị còn hiệu lực: không duyệt gia hạn/cộng dồn trên hợp đồng đó.
- Người thuê đồng ý theo luồng hiện tại; chỉ hoàn tất khi thực tế nhận lại phòng/chìa khóa/tài sản.
- **Cảnh báo:** nhánh chấm dứt quá hạn hiện ghi giữ cọc cho chủ nhà, mở lại tin đăng. Chỉ thao tác trên hợp đồng demo.
- Người thuê từ chối/chủ nhà rút đề nghị: ACTIVE, có thể chọn phương án khác. Nhánh chấm dứt sau từ chối vẫn cần điều khoản bản ký và bàn giao như trước.

## Tiêu chí cuối

- Chỉ chủ nhà thấy nháp; cả hai thấy cảnh báo công tơ tự điền sau phát hành.
- Đồng ý gia hạn giữ ID bill/PaymentRequest/mã chuyển khoản, cập nhật hạn cùng nhau.
- Dừng phạt từ lúc gửi; từ chối/hết hạn tính bù; đồng ý miễn tăng phạt cho đúng bill đó kể cả trễ hạn mới.
- Một yêu cầu mỗi cửa sổ hạn, một phương án hiệu lực; restart không mất lịch sử.
- Backend dùng BillingTime; frontend không dùng giờ máy khách để cấp quyền thao tác.
- Không sửa PDF đã ký. Gia hạn thanh toán không phải gia hạn thời gian thuê.
