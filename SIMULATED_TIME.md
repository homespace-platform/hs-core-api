# Kịch bản test độc lập — hợp đồng 3 tháng, hiệu lực 12/10/2026

## Cách chạy để không nhầm hóa đơn hoặc lùi dữ liệu

- **Mỗi UC là một hợp đồng MỚI, riêng biệt**, đều bắt đầu từ `2026-10-12T10:00:00`. Không chạy nhiều UC trên cùng ID hợp đồng.
- Chọn một UC, thực hiện tuần tự từ hàng đầu xuống hàng cuối của chính UC đó. **Không cần ghép mốc từ UC khác.** Ca kết thúc khi đã kiểm tra đủ kết quả cuối bảng; không buộc chạy tiếp ca khác.
- Ghi lại mã/ID hợp đồng và mở đúng trang hóa đơn của ID đó ở cả hai tài khoản. Bill 1 của tháng 11 không phải bill 2 của tháng 12.
- Không lùi giờ để chạy lại một nhánh trên HĐ đã tiến tới tháng 12/01. Đổi giờ giả lập **không hoàn tác** hóa đơn, phí phạt, yêu cầu, công nợ hoặc chữ ký.
- Khi chuyển UC, có thể đặt lại 12/10 để **tạo HĐ mới với tin/chỗ thuê khác**, nhưng mọi dữ liệu HĐ cũ vẫn còn. Không tái sử dụng HĐ cũ làm fixture mới.
- Scheduler xử lý **tất cả HĐ ACTIVE**, không chỉ HĐ đang mở. **Không tạo sẵn toàn bộ HĐ các UC rồi chạy một UC tới tháng 01**: các HĐ kia cũng sẽ bị tiến lịch. Tạo HĐ của ca sắp chạy; muốn cách ly hoàn toàn thì dùng dữ liệu/CSDL dev riêng hoặc snapshot dev riêng, không reset CSDL thật.
- Mỗi hàng: sửa đúng `HOMESPACE_TIME_SIMULATED_AT`, restart Core, chờ khoảng 1 phút, làm mới cả hai tài khoản rồi mới thao tác. Các hàng cùng ngày nhưng khác giờ vẫn là các bước riêng.
- Giờ trong file là **giờ Việt Nam UTC+7**, không có `Z`. Đồng hồ đứng yên ở mốc đã đặt; chờ ngoài đời không tự tăng giờ giả lập. Job chạy ở lượt đầu sau mốc, không bảo đảm đúng từng giây.
- Không thực hiện thao tác thanh toán, giữ cọc/chấm dứt/bàn giao giả trên hợp đồng thật. Các ca đó chỉ dành cho dữ liệu demo.
- Cuối buổi để `HOMESPACE_TIME_SIMULATED_AT=` rỗng và restart.

## Cấu hình test

Trong .env Core, profile dev/test:

```dotenv
HOMESPACE_TIME_SIMULATED_AT=2026-10-12T10:00:00
HOMESPACE_BILLING_SYNC_DELAY_MS=60000
HOMESPACE_BILLING_EXTENSION_RESPONSE_HOURS=48
```

Không cần SQL vá riêng. Nếu tạo lại DB dev, Hibernate với `JPA_DDL_AUTO=update` tạo schema; `validate/none` không tạo schema trên DB trống. Không drop DB khi Core đang chạy.

Ngày nhận nhà: **12/10/2026**. Thời hạn **3 tháng**. Ba kỳ thuê:

| Kỳ thuê | Thời gian thuê thực tế | Bill chốt kỳ | Tiền thuê trong bill | Hạn gốc |
| --- | --- | --- | --- | --- |
| 1 | 12/10–11/11/2026 | 12/11/2026 | RENT kỳ 2 + chi phí kỳ 1 | 16/11/2026 23:59:59 |
| 2 | 12/11–11/12/2026 | 12/12/2026 | RENT kỳ 3 + chi phí kỳ 2 | 16/12/2026 23:59:59 |
| 3 | 12/12/2026–11/01/2027 | 12/01/2027 | Chỉ chi phí/nợ, KHÔNG RENT kỳ 4 | 16/01/2027 23:59:59 |

- INITIAL: tiền thuê kỳ 1 + cọc, không phải bill tháng. Nếu giá 12.400.000đ/tháng, cọc 2 tháng thì INITIAL 37.200.000đ, gồm RENT 12.400.000đ và cọc 24.800.000đ. Không thu lại hai khoản này trong bill 1.
- Công tơ đầu dùng thống nhất **1500 kWh / 85 m³**; số cuối gợi ý kỳ 1 **1550/90**, kỳ 2 **1610/96**, kỳ 3 **1660/101**. Ô cuối kỳ nhập số hiển thị trên công tơ, không nhập lượng đã dùng.
- Chọn tin có đơn giá số PER_KWH/PER_M3 đã chốt. Tổng bill tùy giá và phí bản ký; các tiền phạt trong bảng không phải tổng tiền phải chuyển.
- Mặc định phạt **100.000đ/ngày**, graceDays **0**, không trần tiền riêng. UC chính sách khác ghi ngay tại bước 12/10.
- Bill chốt ngày 12: **12–16 là 5 ngày thanh toán**; **17 là ngày phạt đầu**. Không thêm 5 ngày miễn phạt sau ngày 16. `paymentDueOffsetDays=4` là cộng 4 ngày sau ngày 12, tức 5 ngày khi tính cả ngày 12.
- Sau hết 5 ngày quá hạn (17–21), **22** mở ba phương án. Phạt tiếp tối đa 10 ngày (17–26), **27** tự chốt cộng dồn nếu đủ điều kiện. Phí hiển thị **100.000đ/ngày × số ngày đã chốt**, không `1 × tổng phí`.
- Duyệt gia hạn: hạn mới = lúc duyệt + X ngày, X 1–30, phải sau hạn hiện tại. Phí đang chờ tạm dừng; từ chối/hết hạn tính bù, tối đa 10 ngày. Đồng ý giữ phí cố định cho bill đó kể cả trễ hạn mới.
- Tự chốt cộng dồn không tự hủy QR ngay: QR cũ chỉ hủy khi bill sau phát hành và nhận nợ. Đã trả/đang đối soát/gia hạn còn hiệu lực/chấm dứt còn hiệu lực không bị ghi đè. Kỳ cuối hoặc bill sau đã phát hành giữ nợ riêng.
- Phạt 10 ngày là trên từng bill; bill mới có hạn và chính sách riêng. Không tự xóa tiền thuê/dịch vụ hoặc cọc.
- SmartCA, đăng nhập và token upload dùng thời gian thật. Giữ chỗ/INITIAL dùng thời gian thật; hoàn tất thanh toán/ký đúng cửa sổ thực tế trước khi tiến lịch. Báo chuyển khoản của bill MONTHLY dùng BillingTime; nên xác nhận/từ chối ở mốc ghi trong ca, không để chứng từ test treo ngoài ý muốn.
- Các “nhắc việc” hiện là log/trạng thái giao diện, chưa bảo đảm có email/thông báo định kỳ.
- Mốc cuối thuê 11/01; popup có thể ghi 12/01 (mốc kết thúc không bao gồm ngày đó). Không coi đó là được ở thêm cả ngày 12/01.

## Danh mục use case

- **UC01** — Thanh toán đúng hạn, đầy đủ cả 3 tháng
- **UC02** — Chủ nhà phát hành thủ công sớm
- **UC03** — Quên nhập cả điện và nước, tự điền số cũ
- **UC04** — Nhập cuối kỳ bằng đầu kỳ, tiêu thụ 0 nhưng không phải tự điền
- **UC05** — Nhập thiếu hoặc sai chỉ số, sửa lại rồi phát hành
- **UC06** — Thêm khoản phát sinh vào hóa đơn
- **UC07** — Xin trước hạn, được duyệt, trả trong hạn mới
- **UC08** — Xin trước hạn, được duyệt nhưng tiếp tục không trả
- **UC09** — Xin sau 3 ngày trễ, duyệt rồi thanh toán
- **UC10** — Xin sau 3 ngày trễ, duyệt nhưng bỏ quên hạn mới
- **UC11** — Chủ nhà từ chối yêu cầu gia hạn
- **UC12** — Chủ nhà không phản hồi, yêu cầu hết hạn 48 giờ
- **UC13** — Chủ nhà tự chọn cộng dồn tháng sau
- **UC14** — Chọn cộng dồn khi yêu cầu gia hạn còn chờ
- **UC15** — Cả hai bên không thao tác, tự cộng dồn sau 10 ngày phạt
- **UC16** — Backend bỏ lỡ mốc tự cộng dồn
- **UC17** — Yêu cầu gửi muộn còn PENDING tại mốc tự cộng dồn
- **UC18** — Trả bill cũ sau tự chốt nhưng trước bill mới
- **UC19** — Đang đối soát chứng từ khi tới mốc tự cộng dồn
- **UC20** — Từ chối chứng từ sau mốc 10 ngày
- **UC21** — Thanh toán khi yêu cầu gia hạn vẫn PENDING
- **UC22** — Chủ nhà dời hạn trực tiếp, không có yêu cầu của người thuê
- **UC23** — Gia hạn lần hai sau khi hạn mới đã quá hạn đủ 5 ngày
- **UC24** — Xin quá ít ngày làm hạn mới sớm hơn hạn hiện tại
- **UC25** — Kiểm tra giới hạn, trùng yêu cầu và quyền truy cập
- **UC26** — Hai tab chủ nhà chọn hai phương án gần đồng thời
- **UC27** — Hai bên đồng ý chấm dứt, bàn giao rồi hoàn tất
- **UC28** — Người thuê từ chối chấm dứt, chủ nhà chọn cộng dồn
- **UC29** — Chủ nhà rút đề nghị chấm dứt, sau đó bỏ quên
- **UC30** — Chủ nhà buộc chấm dứt sau người thuê từ chối
- **UC31** — Thanh toán khi đề nghị chấm dứt chưa hoàn tất
- **UC32** — Bill cuối quá hạn: phạt 10 ngày nhưng không có kỳ sau để chuyển
- **UC33** — Quên công tơ ở kỳ cuối, không có kỳ sau để thu bù
- **UC34** — Gia hạn 30 ngày vượt qua ngày bill kế tiếp đã phát hành
- **UC35** — Chính sách không phạt nhưng vẫn tự cộng dồn
- **UC36** — Phạt một lần, không nhầm thành phạt theo ngày
- **UC37** — Phạt theo ngày nhưng trần tiền thấp hơn 10 ngày
- **UC38** — Không trả cả ba kỳ: kiểm tra chuỗi cộng dồn

---

## UC01 — Thanh toán đúng hạn, đầy đủ cả 3 tháng

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC01: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-14T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 1 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-11-17T00:01:00` | Hai bên | Kiểm tra lại bill 1. | Vẫn PAID, phạt 0; không mở xử lý quá hạn. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hệ thống | Chờ phát hành bill 2, mở đúng HĐ. | Chi phí kỳ 2 + RENT kỳ 3; hạn 16/12 23:59:59. Không có nợ kỳ 1 nếu bill 1 đã PAID. |
| `2026-12-14T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 2 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-12-17T00:01:00` | Hai bên | Kiểm tra bill 2. | Vẫn PAID, phạt 0. |
| `2027-01-11T00:01:00` | Chủ nhà | Mở bill 3 nháp; nhập điện 1660/nước 101, Lưu bản nháp. | Đầu kỳ 1610/96; dùng 50 kWh/5 m³. Đây là ngày thuê cuối. |
| `2027-01-12T00:01:00` | Hệ thống | Chờ phát hành bill 3. | Chỉ chi phí kỳ 3/công nợ nếu có; KHÔNG RENT kỳ 4, KHÔNG thu lại cọc; hạn 16/01 23:59:59. |
| `2027-01-14T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 3, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 3 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2027-01-17T00:01:00` | Hai bên | Kiểm tra cả ba bill. | Đều PAID, không phạt; cọc chưa tự hoàn. |
| `2027-02-11T00:01:00` | Hai bên | Mở danh sách bill. | Không có bill 4; hợp đồng có thể vẫn ACTIVE vì hết hạn tự nhiên chưa hoàn chỉnh. |


---

## UC02 — Chủ nhà phát hành thủ công sớm

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC02: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở nháp bill 1, chưa phát hành. | DRAFT, người thuê chưa thấy. |
| `2026-11-11T23:59:00` | Chủ nhà | Nhập 1550/90; bấm Chốt và phát hành ngay, xác nhận. | Bill 1 UNPAID ngay; người thuê thấy bill/QR. Hạn vẫn 16/11, không tính lại hạn theo giờ bấm. |
| `2026-11-12T00:01:00` | Hai bên | Restart/làm mới nhiều lần. | Không tạo thêm bill hoặc PaymentRequest. |
| `2026-11-14T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 1 PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## UC03 — Quên nhập cả điện và nước, tự điền số cũ

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC03: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Không nhập, không lưu bill 1 nháp. | DRAFT. |
| `2026-11-11T23:59:59` | Chủ nhà | Tiếp tục không nhập. | Còn trong cửa sổ 24 giờ. |
| `2026-11-12T00:01:00` | Hệ thống | Chờ tự phát hành. | Điện 1500→1500, nước 85→85; tiêu thụ 0; có cảnh báo tự điền cả hai. |
| `2026-11-14T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 1 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-12-11T00:01:00` | Chủ nhà | Nhập cuối kỳ 2 điện 1610/nước 96, lưu. | Đầu kỳ vẫn 1500/85, dùng 110 kWh/11 m³; bao gồm phần chưa ghi nhận ở kỳ trước. |
| `2026-12-12T00:01:00` | Hai bên | Mở bill 2. | Tính đúng 110 kWh/11 m³ theo bản ký, không đoán số kỳ 1. |
| `2026-12-14T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 2 PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## UC04 — Nhập cuối kỳ bằng đầu kỳ, tiêu thụ 0 nhưng không phải tự điền

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC04: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Nhập cuối kỳ điện 1500/nước 85 và lưu. | Hiện 0 kWh/0 m³ và cảnh báo hai chỉ số bằng nhau. |
| `2026-11-12T00:01:00` | Hệ thống | Chờ phát hành. | Tiền điện/nước 0; cờ tự điền false vì chính chủ nhà đã nhập. |
| `2026-11-14T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 1 PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## UC05 — Nhập thiếu hoặc sai chỉ số, sửa lại rồi phát hành

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC05: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Nhập điện 1499/nước 84; thử lưu. | Bị chặn vì thấp hơn đầu kỳ 1500/85. |
| `2026-11-11T10:00:00` | Chủ nhà | Nhập điện 1550 nhưng bỏ trống nước; thử lưu. | Không lưu thành công nếu cả hai đều thu theo công tơ; không có luồng UI lưu riêng một bên. |
| `2026-11-11T23:59:00` | Chủ nhà | Nhập đủ 1550/90; lưu. | Lưu thành công, giữ đúng số đo. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-14T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 1 PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## UC06 — Thêm khoản phát sinh vào hóa đơn

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC06: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Nhập 1550/90; thêm khoản Hỏng đèn 100.000đ, lưu. | Nháp lưu chỉ số và đúng một khoản EXTRA. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-12T10:00:00` | Hai bên | Làm mới nhiều lần, xem các dòng. | EXTRA 1 × 100.000đ xuất hiện một lần; không cộng lại vào mỗi lần refresh. |
| `2026-11-14T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 1 PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## UC07 — Xin trước hạn, được duyệt, trả trong hạn mới

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC07: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-14T10:00:00` | Người thuê | Trên BILL 1 xin 7 ngày; lý do ít nhất 10 ký tự. | PENDING; phí chốt 0; phản hồi trước 16/11 10:00. |
| `2026-11-15T10:00:00` | Chủ nhà | Đồng ý thêm 7 ngày. | Hạn mới 22/11 10:00; giữ ID bill/PaymentRequest/mã chuyển khoản; phí 0. |
| `2026-11-20T10:00:00` | Hai bên | Mở bill 1. | Nhắc theo hạn mới bằng log/trạng thái; không còn hạn 16/11 làm hạn hiện tại. |
| `2026-11-21T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 1 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-11-23T00:01:00` | Hai bên | Kiểm tra bill 1. | PAID; không tăng phạt, không tự cộng dồn. |


---

## UC08 — Xin trước hạn, được duyệt nhưng tiếp tục không trả

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC08: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-14T10:00:00` | Người thuê | Xin 7 ngày trên bill 1. | PENDING; phạt 0. |
| `2026-11-15T10:00:00` | Chủ nhà | Duyệt 7 ngày. | Hạn mới 22/11 10:00; miễn tăng phí cho bill này. |
| `2026-11-22T10:00:01` | Hai bên | Không trả. | Quá hạn mới nhưng phí vẫn 0. |
| `2026-11-28T00:01:00` | Chủ nhà | Không chọn phương án. | Ba phương án mở lại; không tự chấm dứt. |
| `2026-12-02T23:59:59` | Hai bên | Không thao tác. | Hết 10 ngày quá hạn mới 23/11–02/12; phí vẫn 0. |
| `2026-12-03T00:01:00` | Hệ thống | Chờ đồng bộ. | Tự chốt cộng dồn theo hạn mới; không dùng mốc 27/11 của hạn cũ. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hai bên | Xem bill 2. | Một dòng nợ bill 1, không có phạt tăng thêm; bill 1 ROLLED_OVER, QR cũ hủy. |


---

## UC09 — Xin sau 3 ngày trễ, duyệt rồi thanh toán

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC09: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-16T23:59:59` | Hai bên | Không trả tiền, không chọn phương án. | Vẫn trong hạn; phạt 0đ. |
| `2026-11-19T10:00:00` | Hai bên | Mở bill 1, chưa thanh toán. | Phạt 100.000đ/ngày × 3 ngày = 300.000đ. |
| `2026-11-19T10:01:00` | Người thuê | Xin 7 ngày. | Chốt 100.000đ/ngày × 3 ngày = 300.000đ; dừng tăng khi PENDING. |
| `2026-11-20T10:00:00` | Chủ nhà | Duyệt. | Hạn mới 27/11 10:00; giữ phí 300.000đ, không tăng thêm cho bill này. |
| `2026-11-25T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | PAID; dòng phạt vẫn 3 ngày × 100.000đ. |
| `2026-11-28T00:01:00` | Hai bên | Xem lại bill. | Không tăng thành 10 ngày; không tự chuyển nợ. |


---

## UC10 — Xin sau 3 ngày trễ, duyệt nhưng bỏ quên hạn mới

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC10: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-19T10:00:00` | Người thuê | Xin 7 ngày. | Phạt chốt 300.000đ, 3 ngày. |
| `2026-11-20T10:00:00` | Chủ nhà | Duyệt. | Hạn mới 27/11 10:00. |
| `2026-11-27T10:00:01` | Hai bên | Không trả. | Quá hạn mới, phí vẫn 300.000đ. |
| `2026-12-03T00:01:00` | Chủ nhà | Không chọn phương án. | Mở ba phương án theo hạn mới. |
| `2026-12-07T23:59:59` | Hai bên | Không thao tác. | Hết 10 ngày quá hạn mới; phí vẫn 3 ngày = 300.000đ. |
| `2026-12-08T00:01:00` | Hệ thống | Chờ đồng bộ. | Tự chốt cộng dồn, không tăng phí lên 1 triệu. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hai bên | Xem bill 2. | Nợ bill 1 gồm phí chốt 300.000đ, chuyển đúng một lần; QR bill 1 hủy. |


---

## UC11 — Chủ nhà từ chối yêu cầu gia hạn

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC11: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-19T10:00:00` | Người thuê | Xin 7 ngày. | PENDING, phí chốt 300.000đ. |
| `2026-11-20T10:00:00` | Chủ nhà | Từ chối. | REJECTED; tính bù thành 400.000đ, không phải 300.000 + 400.000. |
| `2026-11-21T10:00:00` | Người thuê | Thử gửi lại yêu cầu cùng cửa sổ hạn. | Bị chặn; phí 500.000đ. Refresh không tăng trùng trong cùng ngày. |
| `2026-11-22T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | PAID; phí 6 ngày × 100.000đ = 600.000đ, không tăng nữa. |


---

## UC12 — Chủ nhà không phản hồi, yêu cầu hết hạn 48 giờ

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC12: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-19T10:00:00` | Người thuê | Xin 7 ngày. | Phí 300.000đ; respondBy 21/11 10:00. |
| `2026-11-21T09:59:59` | Chủ nhà | Không phản hồi. | PENDING, phí 300.000đ. |
| `2026-11-21T10:00:00` | Hai bên | Mở lại bill; thử duyệt bằng dữ liệu cũ. | EXPIRED; tính bù 500.000đ; không duyệt được sau hạn. |
| `2026-11-22T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | PAID; phí 600.000đ. |


---

## UC13 — Chủ nhà tự chọn cộng dồn tháng sau

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC13: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-22T00:01:00` | Chủ nhà | Mở bill 1; chưa chọn gì. | Đã hết 5 ngày quá hạn; mở ba phương án. Hiện là ngày phạt thứ 6: 600.000đ. |
| `2026-11-22T10:00:00` | Chủ nhà | Chọn Cộng dồn sang kỳ sau. | DEFERRED, chốt phí 600.000đ; QR cũ vẫn còn trước bill mới. |
| `2026-11-27T00:01:00` | Hai bên | Không trả, mở lại bill. | Phí vẫn 600.000đ, không nâng lên 1 triệu; không thêm quyết định SYSTEM. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hai bên | Kiểm tra bill 2. | Một dòng BALANCE_FORWARD bằng tổng bill 1 đã chốt; bill 1 ROLLED_OVER, QR cũ hủy. |
| `2026-12-14T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 2 PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## UC14 — Chọn cộng dồn khi yêu cầu gia hạn còn chờ

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC14: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-21T10:00:00` | Người thuê | Xin 7 ngày. | PENDING; phạt 500.000đ; hạn phản hồi 23/11 10:00. |
| `2026-11-22T00:01:00` | Chủ nhà | Chọn Cộng dồn sang kỳ sau. | Yêu cầu REJECTED, tính bù 600.000đ rồi chốt nợ; chỉ ROLLOVER có hiệu lực. |
| `2026-11-23T10:00:00` | Chủ nhà | Thử duyệt yêu cầu cũ/dời hạn. | Bị chặn, không tạo hai phương án. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hai bên | Mở bill 2. | Chuyển đúng một dòng nợ; QR bill 1 hủy. |


---

## UC15 — Cả hai bên không thao tác, tự cộng dồn sau 10 ngày phạt

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC15: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-16T23:59:59` | Hai bên | Không trả tiền, không chọn phương án. | Vẫn trong hạn; phạt 0đ. |
| `2026-11-22T00:01:00` | Chủ nhà | Mở bill 1; chưa chọn gì. | Đã hết 5 ngày quá hạn; mở ba phương án. Hiện là ngày phạt thứ 6: 600.000đ. |
| `2026-11-26T23:59:59` | Hai bên | Không thao tác. | Phạt 100.000đ/ngày × 10 ngày = 1.000.000đ; chưa tự chọn. |
| `2026-11-27T00:01:00` | Hệ thống | Chờ đồng bộ. | DEFERRED/ROLLOVER; một lịch sử AUTOMATIC_ROLLOVER do SYSTEM tạo. HĐ ACTIVE, cọc/tin chưa đổi. |
| `2026-11-28T10:00:00` | Hai bên | Restart/refresh nhiều lần. | Không tăng phí, không thêm quyết định, không cho chọn chồng; QR cũ chưa hủy. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hai bên | Kiểm tra hai bill. | Bill 1 ROLLED_OVER; bill 2 một dòng nợ; tổng dư nợ không tính hai lần. |
| `2026-12-14T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 2 PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## UC16 — Backend bỏ lỡ mốc tự cộng dồn

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC16: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-22T00:01:00` | Chủ nhà | Mở bill 1; chưa chọn gì. | Đã hết 5 ngày quá hạn; mở ba phương án. Hiện là ngày phạt thứ 6: 600.000đ. |
| `2026-11-22T10:00:00` | Người test | Dừng Core; không chạy các mốc 23–30/11. | Không có lượt scheduler ở mốc 27/11. |
| `2026-12-01T10:00:00` | Người test / hệ thống | Đặt giờ này rồi start Core; chờ đồng bộ. | Phạt chỉ 10 ngày = 1 triệu; tự chốt một lần. Lịch sử ghi lúc chạy thực tế 01/12. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hai bên | Mở bill 2, refresh. | Nợ cũ chuyển một lần; không có phí 15 ngày hay 20 ngày. |


---

## UC17 — Yêu cầu gửi muộn còn PENDING tại mốc tự cộng dồn

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC17: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-25T10:00:00` | Người thuê | Xin 7 ngày. | Chốt 9 ngày = 900.000đ; respondBy 27/11 10:00. |
| `2026-11-27T00:01:00` | Chủ nhà | Chưa phản hồi. | Vẫn PENDING; chưa tự cộng dồn, phí 900.000đ. |
| `2026-11-27T10:00:00` | Hai bên | Không phản hồi, chờ đồng bộ. | EXPIRED; tính bù tối đa 10 ngày = 1 triệu rồi DEFERRED. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hai bên | Mở bill 2. | Nợ bill 1 chuyển một lần, không kèm yêu cầu đang treo. |


---

## UC18 — Trả bill cũ sau tự chốt nhưng trước bill mới

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC18: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-27T00:01:00` | Hệ thống | Không thao tác đến mốc này. | Bill 1 DEFERRED; phí 1 triệu, QR còn dùng trong hệ thống. |
| `2026-11-28T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 1 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hai bên | Xem bill 2. | KHÔNG có BALANCE_FORWARD bill 1 đã trả; không thu nợ hai lần. |


---

## UC19 — Đang đối soát chứng từ khi tới mốc tự cộng dồn

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC19: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-26T23:50:00` | Người thuê | Chuyển đúng tổng bill 1 hiện tại gồm phí 1 triệu, gửi chứng từ; chủ nhà chưa xác nhận. | TRANSFER_REPORTED/UNDER_REVIEW; dừng tăng phí. |
| `2026-11-27T00:01:00` | Hai bên | Mở bill 1. | Không tự cộng dồn/hủy QR/đổi số tiền trong lúc đối soát. |
| `2026-11-27T10:00:00` | Chủ nhà | Xác nhận đã nhận tiền. | PAID. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hai bên | Xem bill 2. | Không có nợ bill 1. |


---

## UC20 — Từ chối chứng từ sau mốc 10 ngày

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC20: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-26T23:50:00` | Người thuê | Gửi chứng từ bill 1 tổng gồm phí 1 triệu. | UNDER_REVIEW. |
| `2026-11-27T00:01:00` | Hai bên | Chưa xử lý. | Chưa tự cộng dồn do đang đối soát. |
| `2026-11-27T10:00:00` | Chủ nhà | Từ chối chứng từ, ghi lý do. | REJECTED; đồng bộ tiếp theo chốt DEFERRED nếu không còn phương án khác; phí vẫn tối đa 1 triệu. |
| `2026-11-28T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | Gửi lại chứng từ hợp lệ, chủ nhà xác nhận PAID. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hai bên | Xem bill 2. | Không chuyển nợ đã thanh toán. |


---

## UC21 — Thanh toán khi yêu cầu gia hạn vẫn PENDING

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC21: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-19T10:00:00` | Người thuê | Xin 7 ngày. | Phí chốt 300.000đ, PENDING. |
| `2026-11-20T10:00:00` | Người thuê | Chuyển tổng gồm phí 300.000đ và gửi chứng từ. | UNDER_REVIEW, không cho duyệt gia hạn/đổi phương án khi đang đối soát. |
| `2026-11-20T10:05:00` | Chủ nhà | Xác nhận nhận tiền. | Bill 1 PAID; yêu cầu CLOSED khi đồng bộ, không còn duyệt được. |
| `2026-11-27T00:01:00` | Hai bên | Xem lại bill. | PAID, giữ phí 3 ngày, không tự cộng dồn. |


---

## UC22 — Chủ nhà dời hạn trực tiếp, không có yêu cầu của người thuê

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC22: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-21T23:59:59` | Chủ nhà | Thử mở ba phương án. | Chưa được chọn vì chưa hết ngày trễ thứ 5. |
| `2026-11-22T00:00:00` | Chủ nhà | Dời hạn 3 ngày, lý do ít nhất 10 ký tự. | Hạn mới 25/11 00:00; phí chốt 6 ngày = 600.000đ; không tạo bill mới. |
| `2026-11-24T10:00:00` | Chủ nhà | Thử cộng dồn/chấm dứt/dời tiếp. | Bị chặn vì gia hạn còn hiệu lực. |
| `2026-11-25T00:00:01` | Hai bên | Không trả. | Trễ hạn mới, phí vẫn 600.000đ. |
| `2026-12-01T00:01:00` | Chủ nhà | Không chọn gì. | Ba phương án mở lại. |
| `2026-12-06T00:01:00` | Hệ thống | Chờ đồng bộ. | Tự chốt cộng dồn sau 10 ngày trễ hạn mới 26/11–05/12; phí vẫn 600.000đ. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hai bên | Mở bill 2. | Nợ bill 1 gồm phí 600.000đ, không tăng lên 1 triệu. |


---

## UC23 — Gia hạn lần hai sau khi hạn mới đã quá hạn đủ 5 ngày

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC23: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-22T00:00:00` | Chủ nhà | Dời hạn 3 ngày. | Hạn 25/11 00:00, phí 600.000đ. |
| `2026-12-01T00:01:00` | Chủ nhà | Dời tiếp 3 ngày, lý do hợp lệ. | Hạn mới 04/12 00:01; lịch sử thêm lần gia hạn; phí vẫn 600.000đ. |
| `2026-12-02T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 1 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hệ thống | Chờ phát hành bill 2, mở đúng HĐ. | Chi phí kỳ 2 + RENT kỳ 3; hạn 16/12 23:59:59. Không có nợ kỳ 1 nếu bill 1 đã PAID. |


---

## UC24 — Xin quá ít ngày làm hạn mới sớm hơn hạn hiện tại

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC24: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-13T10:00:00` | Người thuê | Xin 1 ngày. | PENDING; hạn hiện tại 16/11 23:59:59. |
| `2026-11-14T10:00:00` | Chủ nhà | Bấm Đồng ý thêm 1 ngày. | Bị từ chối: 15/11 10:00 sớm hơn hạn 16/11; không đổi hạn/QR, yêu cầu chưa APPROVED. |
| `2026-11-14T10:05:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | PAID; yêu cầu CLOSED sau đồng bộ, phí 0. |


---

## UC25 — Kiểm tra giới hạn, trùng yêu cầu và quyền truy cập

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC25: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-14T10:00:00` | Người thuê / test API | Thử ngày 0/31/1000, lý do dưới 10 ký tự hoặc trên 1000. | UI/API từ chối; không có yêu cầu hoặc tiền mới. |
| `2026-11-14T10:01:00` | Người thuê | Gửi 7 ngày, lý do hợp lệ; gửi lần hai. | Chỉ một PENDING; lần hai bị chặn. |
| `2026-11-14T10:02:00` | Test API có xác thực | Chủ nhà thử gửi yêu cầu như người thuê; người thuê thử approve; tài khoản thứ ba thử đọc HĐ. | Bị chặn theo vai trò/quyền; không đổi dữ liệu. Dùng tài khoản test, không dùng token người thật. |
| `2026-11-15T10:00:00` | Chủ nhà | Duyệt yêu cầu hợp lệ duy nhất. | Hạn mới 22/11 10:00. |
| `2026-11-21T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 1 PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## UC26 — Hai tab chủ nhà chọn hai phương án gần đồng thời

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC26: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-22T00:01:00` | Chủ nhà | Mở bill 1; chưa chọn gì. | Đã hết 5 ngày quá hạn; mở ba phương án. Hiện là ngày phạt thứ 6: 600.000đ. |
| `2026-11-22T10:00:00` | Chủ nhà | Mở hai tab cùng bill; tab A cộng dồn, tab B dời 7 ngày gần đồng thời. | Chỉ một quyết định hiệu lực, không tạo hai PaymentRequest. Tab thua tải lại trạng thái mới. |
| `2026-11-23T10:00:00` | Hai bên | Xem lịch sử/hạn/phí hai tab. | Nếu cộng dồn thắng: DEFERRED, không đổi hạn. Nếu dời hạn thắng: PAYMENT_EXTENSION, không có deferredAt. Phí đều chốt 600.000đ. |

**Lưu ý riêng:** Đây là ca kiểm tra cạnh tranh: kết quả phụ thuộc thao tác thắng khóa, không chạy cả hai nhánh tiếp trên cùng HĐ. Dừng sau khi xác minh chỉ một phương án có hiệu lực.


---

## UC27 — Hai bên đồng ý chấm dứt, bàn giao rồi hoàn tất

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC27: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-22T00:01:00` | Chủ nhà | Mở bill 1; chưa chọn gì. | Đã hết 5 ngày quá hạn; mở ba phương án. Hiện là ngày phạt thứ 6: 600.000đ. |
| `2026-11-22T10:00:00` | Chủ nhà | Gửi đề nghị chấm dứt. | TERMINATION; HĐ vẫn ACTIVE, chưa giữ cọc/mở lại tin. Nợ vẫn tồn tại. |
| `2026-11-23T10:00:00` | Người thuê | Đọc và xác nhận đầy đủ đồng ý chấm dứt, giữ cọc, hiểu công nợ. | Chờ bàn giao; HĐ chưa TERMINATED. |
| `2026-11-24T10:00:00` | Chủ nhà | Trên dữ liệu demo: xác nhận đã nhận phòng trống, chìa khóa/tài sản; Hoàn tất chấm dứt. | TERMINATED, cọc RETAINED_BY_LANDLORD, tin/chỗ xe được giải phóng theo code. Không xóa công nợ. |
| `2026-12-11T00:01:00` | Hai bên | Mở danh sách bill. | Không tạo bill tháng mới cho HĐ đã TERMINATED. |

**Lưu ý riêng:** THAO TÁC KHÔNG HOÀN TÁC TRÊN UI. Chỉ dùng HĐ demo có cọc HELD; không xác nhận bàn giao trên hợp đồng thật khi chưa nhận lại tài sản.


---

## UC28 — Người thuê từ chối chấm dứt, chủ nhà chọn cộng dồn

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC28: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-22T10:00:00` | Chủ nhà | Gửi đề nghị chấm dứt. | HĐ ACTIVE, chưa xử lý cọc. |
| `2026-11-23T10:00:00` | Người thuê | Không đồng ý. | Đề nghị bị từ chối; HĐ tiếp tục, cọc/tin chưa đổi. |
| `2026-11-23T10:05:00` | Chủ nhà | Chọn Cộng dồn sang kỳ sau. | Chốt phí 7 ngày = 700.000đ; chỉ ROLLOVER hiệu lực. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hai bên | Mở bill 2. | Một dòng nợ cũ; QR bill 1 hủy. |


---

## UC29 — Chủ nhà rút đề nghị chấm dứt, sau đó bỏ quên

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC29: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-22T10:00:00` | Chủ nhà | Gửi đề nghị chấm dứt. | TERMINATION, không tự cộng dồn khi đề nghị còn hiệu lực. |
| `2026-11-23T10:00:00` | Chủ nhà | Rút đề nghị trước khi người thuê đồng ý. | HĐ ACTIVE, phương án chấm dứt hết hiệu lực; cọc/tin chưa đổi. |
| `2026-11-27T00:01:00` | Hai bên | Không chọn phương án khác. | Tự DEFERRED, phạt tối đa 10 ngày = 1 triệu. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hai bên | Kiểm tra bill 2. | Chuyển nợ một lần. |


---

## UC30 — Chủ nhà buộc chấm dứt sau người thuê từ chối

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC30: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-22T10:00:00` | Chủ nhà | Gửi đề nghị chấm dứt. | HĐ chưa kết thúc. |
| `2026-11-23T10:00:00` | Người thuê | Từ chối. | ACTIVE; chưa xử lý cọc/tin. |
| `2026-11-24T10:00:00` | Chủ nhà | Nếu bản ký có điều khoản phù hợp: xác nhận điều khoản đã ký, đã thông báo, đã thực tế nhận phòng/chìa khóa/tài sản; Buộc chấm dứt. | TERMINATED, giữ cọc/mở lại tin theo luồng hiện hành. Nếu thiếu điều khoản/điều kiện thì bị chặn. |
| `2026-12-11T00:01:00` | Hai bên | Kiểm tra bill tháng. | Không tạo bill mới sau TERMINATED. |

**Lưu ý riêng:** Chọn HĐ demo có điều khoản chấm dứt quá hạn được ghi trong bản ký ngay 12/10. Không tự suy ra quyền buộc chấm dứt từ một hóa đơn trễ; không sửa PDF đã ký. Thao tác này có tác động không hoàn tác trên UI.


---

## UC31 — Thanh toán khi đề nghị chấm dứt chưa hoàn tất

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC31: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-22T10:00:00` | Chủ nhà | Gửi đề nghị chấm dứt; người thuê chưa đồng ý. | HĐ ACTIVE, cọc/tin chưa đổi. |
| `2026-11-23T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | PAID; phí 7 ngày = 700.000đ. Đề nghị chấm dứt chưa hoàn tất bị hủy khi đồng bộ. |
| `2026-11-24T10:00:00` | Hai bên | Thử tiếp tục đồng ý/hoàn tất đề nghị cũ. | Bị chặn; không giữ cọc/mở lại tin. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hệ thống | Chờ phát hành bill 2, mở đúng HĐ. | Chi phí kỳ 2 + RENT kỳ 3; hạn 16/12 23:59:59. Không có nợ kỳ 1 nếu bill 1 đã PAID. |


---

## UC32 — Bill cuối quá hạn: phạt 10 ngày nhưng không có kỳ sau để chuyển

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC32: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-14T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 1 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hệ thống | Chờ phát hành bill 2, mở đúng HĐ. | Chi phí kỳ 2 + RENT kỳ 3; hạn 16/12 23:59:59. Không có nợ kỳ 1 nếu bill 1 đã PAID. |
| `2026-12-14T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 2 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2027-01-11T00:01:00` | Chủ nhà | Mở bill 3 nháp; nhập điện 1660/nước 101, Lưu bản nháp. | Đầu kỳ 1610/96; dùng 50 kWh/5 m³. Đây là ngày thuê cuối. |
| `2027-01-12T00:01:00` | Hệ thống | Chờ phát hành bill 3. | Chỉ chi phí kỳ 3/công nợ nếu có; KHÔNG RENT kỳ 4, KHÔNG thu lại cọc; hạn 16/01 23:59:59. |
| `2027-01-16T23:59:59` | Hai bên | Không trả bill 3. | Trong hạn, phí 0. |
| `2027-01-17T00:01:00` | Hai bên | Không trả. | Phạt 1 ngày = 100.000đ. |
| `2027-01-22T00:01:00` | Chủ nhà | Mở phương án. | Không có cộng dồn vì không có bill 4; không tự kéo dài quyền ở. |
| `2027-01-26T23:59:59` | Hai bên | Không thao tác. | Phạt 10 ngày = 1 triệu. |
| `2027-01-27T00:01:00` | Hệ thống | Chờ đồng bộ. | Giữ bill 3 OVERDUE, không DEFERRED/ROLLED_OVER; cọc/tin không tự đổi. |
| `2027-01-28T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 3, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 3 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2027-02-11T00:01:00` | Hai bên | Mở danh sách bill. | Không tạo bill 4. |


---

## UC33 — Quên công tơ ở kỳ cuối, không có kỳ sau để thu bù

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC33: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-14T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 1 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hệ thống | Chờ phát hành bill 2, mở đúng HĐ. | Chi phí kỳ 2 + RENT kỳ 3; hạn 16/12 23:59:59. Không có nợ kỳ 1 nếu bill 1 đã PAID. |
| `2026-12-14T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 2 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2027-01-11T00:01:00` | Chủ nhà | Mở bill 3 nhưng không nhập chỉ số. | DRAFT. Phải chú ý đây là ngày thuê cuối. |
| `2027-01-12T00:01:00` | Hệ thống | Chờ tự phát hành. | Tự lấy 1610/96 làm cuối kỳ; tiêu thụ 0, có cảnh báo. Không có RENT kỳ 4. |
| `2027-01-14T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 3, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 3 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2027-02-11T00:01:00` | Hai bên | Kiểm tra. | Không có bill 4 để thu bù; chênh lệch thực tế cần đối soát riêng, chưa có luồng sửa bill đã phát hành. |


---

## UC34 — Gia hạn 30 ngày vượt qua ngày bill kế tiếp đã phát hành

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC34: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-22T00:00:00` | Chủ nhà | Dời hạn bill 1 thêm 30 ngày. | Hạn mới 22/12 00:00, phí giữ 600.000đ. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hệ thống | Chờ phát hành bill 2, mở đúng HĐ. | Chi phí kỳ 2 + RENT kỳ 3; hạn 16/12 23:59:59. Không có nợ kỳ 1 nếu bill 1 đã PAID. |
| `2026-12-14T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 2 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-12-22T00:00:01` | Hai bên | Không trả bill 1. | Bill 1 trễ hạn mới, phí vẫn 600.000đ. |
| `2027-01-02T00:01:00` | Hệ thống | Chờ đồng bộ sau 10 ngày quá hạn mới. | Không nhét nợ vào bill 2 đã phát hành; bill 1 giữ riêng, phí không tăng. Không tự tạo bill ngoài hạn thuê. |
| `2027-01-03T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 1, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 1 PAID; không tăng phạt, không chuyển nợ sau đó. |

**Lưu ý riêng:** Đây là ngoại lệ cần đối soát riêng, không phải lỗi cho phép sửa bill đã phát hành. Gia hạn thanh toán không gia hạn thời gian thuê.


---

## UC35 — Chính sách không phạt nhưng vẫn tự cộng dồn

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC35: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt NONE (không phạt); kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-17T00:01:00` | Hai bên | Không trả. | OVERDUE, phí 0. |
| `2026-11-22T00:01:00` | Chủ nhà | Mở bill 1; chưa chọn gì. | Ba phương án mở; phí vẫn 0. |
| `2026-11-27T00:01:00` | Hệ thống | Chờ đồng bộ, không chọn gì. | DEFERRED, phí 0; vẫn phải trả tiền thuê/dịch vụ. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hai bên | Mở bill 2. | Chuyển nợ gốc một lần, không tự tạo phí 1 triệu. |


---

## UC36 — Phạt một lần, không nhầm thành phạt theo ngày

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC36: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt FIXED_ONCE 100.000đ/lần, graceDays=0; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-17T00:01:00` | Hai bên | Không trả. | Phạt 100.000đ × 1 lần. |
| `2026-11-26T23:59:59` | Hai bên | Không chọn gì. | Vẫn 100.000đ × 1 lần, không 1 triệu. |
| `2026-11-27T00:01:00` | Hệ thống | Chờ đồng bộ. | Tự chốt nợ với phí một lần 100.000đ. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hai bên | Mở bill 2. | Nợ cũ chuyển một lần; tổng đúng. |


---

## UC37 — Phạt theo ngày nhưng trần tiền thấp hơn 10 ngày

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC37: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt FIXED_PER_DAY 100.000đ/ngày, graceDays=0, trần tiền 700.000đ trong bản ký; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-26T23:59:59` | Hai bên | Từ 12/11 tiến thẳng đến mốc này, chưa trả/chọn gì. | Tính 100.000đ/ngày × 10 ngày = 1 triệu, áp dụng trần còn 700.000đ; UI giải thích phần trần. |
| `2026-11-27T00:01:00` | Hệ thống | Chờ đồng bộ. | Tự chốt nợ; phạt 700.000đ, không nâng lên 1 triệu. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hai bên | Kiểm tra bill 2. | Nợ cũ gồm 700.000đ phí, chuyển một lần. |

**Lưu ý riêng:** UI hiện tại có thể không cho nhập trần riêng; ca này cần fixture/test API bản nháp có trần hợp lệ trước khi ký. Không chỉnh snapshot của hợp đồng đã ký. Nếu đã chạm trần từ ngày 7 thì dòng đã chốt có thể ghi 7 ngày, không tự đổi thành 10 ngày khi tiền không tăng.


---

## UC38 — Không trả cả ba kỳ: kiểm tra chuỗi cộng dồn

**Hợp đồng riêng:** bắt đầu/ký tại 12/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| HOMESPACE_TIME_SIMULATED_AT | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-12T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC38: thuê 3 tháng từ 12/10/2026; thanh toán tiền thuê kỳ 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; chưa có bill tháng. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 11/01/2027. |
| `2026-11-11T00:01:00` | Chủ nhà | Mở bill 1 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-12T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở bill 1 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí kỳ 1 + tiền thuê kỳ 2; hạn 16/11 23:59:59. Không thu lại RENT kỳ 1 hoặc cọc. |
| `2026-11-27T00:01:00` | Hệ thống | Không thao tác bill 1. | Chốt nợ bill 1 với phí tối đa 1 triệu. |
| `2026-12-11T00:01:00` | Chủ nhà | Mở bill 2 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-12T00:01:00` | Hệ thống | Phát hành bill 2; tiếp tục không trả. | Bill 2 nhận một dòng nợ bill 1; bill 1 ROLLED_OVER. |
| `2026-12-27T00:01:00` | Hệ thống | Không chọn phương án bill 2. | Chốt bill 2 sau 10 ngày quá hạn; phí của bill 2 tối đa 1 triệu, không tăng lại dòng phí đã chốt trên bill 1. |
| `2027-01-11T00:01:00` | Chủ nhà | Mở bill 3 nháp; nhập điện 1660/nước 101, Lưu bản nháp. | Đầu kỳ 1610/96; dùng 50 kWh/5 m³. Đây là ngày thuê cuối. |
| `2027-01-12T00:01:00` | Hệ thống | Phát hành bill 3; chưa trả. | Bill 3 không RENT kỳ 4; nhận nợ bill 2 một lần (đã chứa nợ bill 1). Bill 2 ROLLED_OVER. |
| `2027-01-27T00:01:00` | Hai bên | Không thao tác. | Bill 3 phạt tối đa 1 triệu; không có bill 4 để chuyển. Tổng dư nợ chỉ tính bill 3, không cộng trùng các bill ROLLED_OVER. |
| `2027-01-28T10:00:00` | Người thuê → chủ nhà | Mở đúng bill 3, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | bill 3 PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## Phần chưa có luồng UI tự động hoàn chỉnh — không coi là ca chạy đạt

Các tính năng sau **chưa triển khai đủ**; không tìm nút/QR và không đánh dấu “test thành công” chỉ vì đã tới mốc:

| Mốc trên HĐ bắt đầu 12/10/2026, 3 tháng | Tính năng còn thiếu |
| --- | --- |
| 13/12/2026 (30 ngày trước mốc 12/01/2027) | Xin gia hạn THỜI GIAN THUÊ, chủ nhà duyệt/từ chối, ký phụ lục/hợp đồng nối tiếp. Gia hạn THANH TOÁN trong các UC không thay thế việc này. |
| 11/01/2027 | Biên bản trả nhà/công tơ cuối/ảnh/nội thất/chìa khóa và xác nhận bàn giao đầy đủ. |
| 12/01/2027 | Scheduler hết hạn tự nhiên, xử lý quyền ở và trạng thái hết hạn. Hiện HĐ có thể còn ACTIVE; không có bill 4 vẫn không đồng nghĩa đã đóng HĐ. |
| Sau bàn giao và chốt công nợ | Quyết toán cọc/khấu trừ có căn cứ/QR hoàn cọc chủ nhà → người thuê/chứng từ/người thuê xác nhận. |
| Sau hoàn thành nghĩa vụ | Đóng hồ sơ và giải phóng tin/chỗ xe theo bàn giao thực tế. |

Không sử dụng nhánh **chấm dứt quá hạn giữ cọc** để thay thế việc hết hạn tự nhiên/hoàn cọc đúng hạn.

## Các ca cần fixture hoặc API, không giả vờ thao tác được bằng UI

- Chỉ một công tơ cuối kỳ đã lưu, công tơ còn lại null: scheduler có giữ số đã lưu và chỉ tự điền phần thiếu, nhưng UI/prepare hiện yêu cầu đủ hai số khi cả hai metered. Cần fixture/test tự động; không hướng dẫn lưu một bên như ca UI thành công.
- Thiếu công tơ đầu hoặc đơn giá hợp lệ: không được tự đoán số/giá. Đầu kỳ bắt buộc còn có thể chặn kết xuất/ký, nên không tạo HĐ ACTIVE sai bằng UI chỉ để thử; dùng fixture tự động.
- Biểu phí STATE_WATER_RATE chưa có giá số: không phát hành tự động để tránh tính sai; không dùng cho các UC demo PER_M3.
- DISPUTED: backend không tự cộng dồn/tăng phí trong tranh chấp. Nếu UI hiện không có thao tác tạo tranh chấp thì test API/fixture, không ghi là ca bấm nút trên trang bill.
- Hóa đơn cũ `1 × tổng phí`: API diễn giải được số ngày khi biết đơn giá ngày và không chạm trần, không sửa snapshot/số tiền/QR. Nếu chạm trần và thiếu metadata thì không tự đoán ngày.
- Phí cũ đã chốt trên 10 ngày trước cập nhật: không tự giảm tiền/PDF cũ; cần đối soát riêng. Dùng HĐ mới từ 12/10 cho các UC trong file.

## Ghi nhận kết quả từng UC

Mỗi UC ghi riêng: **mã/ID HĐ**, mốc đã chạy, bill/PaymentRequest ID, ảnh của hai bên, tổng bill, dòng phí ngày × đơn giá, trạng thái yêu cầu, quyết định đang hiệu lực, QR nào còn/hủy, PASS/FAIL và log nếu lỗi.

Không ghi “gia hạn lỗi” nếu đang mở bill 2 hạn 16/12 nhưng lùi đồng hồ về 14/11: duyệt 7 ngày sẽ ra 21/11, sớm hơn hạn hiện tại và phải bị chặn. Khi gặp trường hợp này, chuyển sang **HĐ mới đúng UC**, không sửa dữ liệu HĐ đã tiến lịch.
