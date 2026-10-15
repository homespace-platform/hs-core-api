# Kịch bản test độc lập — hợp đồng 3 tháng, hiệu lực 16/10/2026

## Cách chạy để không nhầm hóa đơn hoặc lùi dữ liệu

**Vị trí thao tác sau khi gom module thanh toán:** vào **Hóa đơn → chọn hợp đồng**. Hồ sơ có ba mục **Thanh toán ban đầu**, **Hóa đơn theo kỳ** và **Hoàn cọc**. Chọn đúng kỳ ở danh sách bên trái để nhập công tơ/thanh toán/xác nhận; mỗi lần chỉ mở chi tiết một hóa đơn. Đề xuất khấu trừ, khiếu nại, QR chuyển tiền hoàn và xác nhận nhận cọc đều nằm ở mục **Hoàn cọc**. Trong các bảng UC bên dưới, thao tác “mở hồ sơ/quyết toán cọc” dùng mục này, thay vì block quyết toán trong Chi tiết hợp đồng. **Gia hạn thời gian thuê và ký SmartCA vẫn ở Hợp đồng**. Các mốc thời gian và điều kiện xử lý tiền giữ nguyên.

- **Mỗi UC là một hợp đồng MỚI, riêng biệt**, đều bắt đầu từ `2026-10-16T10:00:00`. Không chạy nhiều UC trên cùng ID hợp đồng.
- Chọn một UC, thực hiện tuần tự từ hàng đầu xuống hàng cuối của chính UC đó. **Không cần ghép mốc từ UC khác.** Ca kết thúc khi đã kiểm tra đủ kết quả cuối bảng; không buộc chạy tiếp ca khác.
- Ghi lại mã/ID hợp đồng và mở đúng trang hóa đơn của ID đó ở cả hai tài khoản. Kỳ thanh toán 2 thu vào tháng 11; kỳ thanh toán 3 thu vào tháng 12; quyết toán cuối hợp đồng vào tháng 01.
- Không lùi giờ để chạy lại một nhánh trên HĐ đã tiến tới tháng 12 hoặc tháng 01. Đổi giờ giả lập **không hoàn tác** hóa đơn, phí phạt, yêu cầu, công nợ hoặc chữ ký.
- Khi chuyển UC, có thể đặt lại 16/10 để **tạo HĐ mới với tin/chỗ thuê khác**, nhưng mọi dữ liệu HĐ cũ vẫn còn. Không tái sử dụng HĐ cũ làm fixture mới.
- Scheduler xử lý **tất cả HĐ gốc ACTIVE/EXPIRED**, không chỉ HĐ đang mở. **Không tạo sẵn toàn bộ HĐ các UC rồi chạy một UC tới tháng 01**: các HĐ kia cũng sẽ bị tiến lịch. Tạo HĐ của ca sắp chạy; muốn cách ly hoàn toàn thì dùng dữ liệu/CSDL dev riêng hoặc snapshot dev riêng, không reset CSDL thật.
- Mỗi hàng: sửa `HOMESPACE_TIME_SIMULATED_AT` trong `hs-core-api/.env` theo mốc `homespace.time.simulated-at` của bảng, dừng/chạy lại Core để nạp lại biến môi trường, chờ khoảng 1 phút, làm mới cả hai tài khoản rồi mới thao tác. Các hàng cùng ngày nhưng khác giờ vẫn là các bước riêng.
- Giờ trong file là **giờ Việt Nam UTC+7**, không có `Z`. Đồng hồ đứng yên ở mốc đã đặt; chờ ngoài đời không tự tăng giờ giả lập. Job chạy ở lượt đầu sau mốc, không bảo đảm đúng từng giây.
- Không thực hiện thao tác thanh toán, giữ cọc/chấm dứt/bàn giao giả trên hợp đồng thật. Các ca đó chỉ dành cho dữ liệu demo.
- Cuối buổi để `HOMESPACE_TIME_SIMULATED_AT=` rỗng trong `.env` và dừng/chạy lại Core. Không chỉ dựa vào DevTools reload vì môi trường của tiến trình Java cũ không tự cập nhật.

## Cấu hình test

Riêng đồng hồ giả lập đặt trong `hs-core-api/.env`, khi Core chạy profile dev/test:

```dotenv
HOMESPACE_TIME_SIMULATED_AT=2026-10-16T10:00:00
```

`application.properties` chỉ ánh xạ biến (không ghi cứng ngày). Các cấu hình nghiệp vụ khác vẫn ở file này:

```properties
homespace.time.simulated-at=${HOMESPACE_TIME_SIMULATED_AT:}
homespace.billing.sync-delay-ms=60000
homespace.billing.extension-response-hours=48
homespace.closure.response-hours=48
```

Các giá trị nghiệp vụ khác được ghi trực tiếp trong properties. Chỉ giá trị đồng hồ giả lập chuyển sang `.env`/`.env.example`. Cấu hình Run/Debug Core trong `.vscode/launch.json` đã nạp `hs-core-api/.env`; chạy bằng terminal/JAR cần truyền biến môi trường tương ứng qua launcher (Java không tự đọc `.env`). Khi kết thúc test, để `HOMESPACE_TIME_SIMULATED_AT=` rỗng rồi dừng/chạy lại Core; đổi giá trị này không cần build lại. Việc trở về giờ thực không xóa hóa đơn/công nợ đã tạo trước đó.

Không cần SQL vá riêng. Nếu tạo lại DB dev, Hibernate với `JPA_DDL_AUTO=update` tạo schema; `validate/none` không tạo schema trên DB trống. Không drop DB khi Core đang chạy.

Ngày nhận nhà/ký: **16/10/2026**, thuê **3 tháng**, ngày thuê cuối **15/01/2027**, hết hạn lúc **00:00 ngày 16/01/2027**.

## Quy ước kỳ thanh toán theo luồng thực tế

**Kỳ thanh toán 1 được tính từ lần đóng cọc + tiền phòng tháng 1.** Tiền phòng trả trước cho tháng sắp ở; điện/nước, dịch vụ và phát sinh chốt cho tháng đã ở. Đợt thu sau tháng thuê cuối mang tên **Quyết toán cuối hợp đồng**.

| Mốc thanh toán | Tên hiển thị mục tiêu | Tiền phòng được trả cho khoảng nào? | Chi phí được chốt cho khoảng nào? | Hạn gốc |
| --- | --- | --- | --- | --- |
| 16/10/2026 | **Kỳ thanh toán 1 — Cọc + tiền phòng tháng 1** | 16/10–15/11/2026 | Chưa phát sinh chi phí sử dụng tháng 1 | Theo yêu cầu thanh toán ban đầu; hoàn tất trước ký |
| 16/11/2026 | **Kỳ thanh toán 2 — Tiền phòng tháng 2 + chi phí tháng 1** | 16/11–15/12/2026 | 16/10–15/11/2026 | 20/11/2026 23:59:59 |
| 16/12/2026 | **Kỳ thanh toán 3 — Tiền phòng tháng 3 + chi phí tháng 2** | 16/12/2026–15/01/2027 | 16/11–15/12/2026 | 20/12/2026 23:59:59 |
| 16/01/2027 | **Quyết toán cuối hợp đồng — Chi phí tháng 3** | Không thu tiền phòng tháng 4 khi không gia hạn | 16/12/2026–15/01/2027; cộng nợ/phạt còn lại nếu có | 20/01/2027 23:59:59 |
| Sau khi thanh toán, xác nhận đủ chi phí và bàn giao | **Hoàn cọc — Chủ nhà → Người thuê** | Không phát sinh tiền phòng | Các khoản khấu trừ cọc khác cần được hai bên thống nhất | Theo hạn hoàn cọc trong hợp đồng |

Chốt công tơ trước ngày phát hành: 15/11, 15/12 và 15/01. Có thể phát hành thủ công vào ngày chốt theo luồng hiện tại; ngày phát hành tự động là ngày 16. Hóa đơn quyết toán cuối vẫn hiển thị và thanh toán trong mục **Hóa đơn**, trước bước hoàn cọc. Khoản bổ sung chi phí sau quyết toán có tên riêng, không đánh số thành một tháng thuê mới.

Hợp đồng 3 tháng không gia hạn có **3 kỳ thanh toán tiền phòng + 1 đợt quyết toán chi phí cuối** (chưa tính bổ sung nếu có). Tổng tiền phòng cả vòng đời bằng đúng 3 tháng; cọc thu một lần và hoàn theo kết quả bàn giao/quyết toán.

**Ngoại lệ gia hạn đã ký đủ:** nếu gia hạn được áp dụng trước hạn, ngày 16/01 trở thành **Kỳ thanh toán 4 — Tiền phòng tháng 4 + chi phí tháng 3**. Quyết toán cuối lùi đến khi hết thời gian thuê mới. Với UC39 gia hạn thêm 3 tháng, mốc quyết toán mới là 16/04/2027, chỉ chi phí tháng 6, không thu tiền phòng tháng 7.

- INITIAL là nguồn dữ liệu của **Kỳ thanh toán 1 — Cọc + tiền phòng tháng 1**, hiện lưu ở yêu cầu thanh toán ban đầu. Nếu giá 12.400.000đ/tháng, cọc 2 tháng thì INITIAL 37.200.000đ, gồm RENT 12.400.000đ và cọc 24.800.000đ. Không thu lại hai khoản này trong hóa đơn kỳ thanh toán 2.
- Công tơ đầu dùng thống nhất **1500 kWh / 85 m³**; số cuối gợi ý tháng 1 **1550/90**, tháng 2 **1610/96**, tháng 3 **1660/101**. Ô cuối kỳ nhập số hiển thị trên công tơ, không nhập lượng đã dùng.
- Chọn tin có đơn giá số PER_KWH/PER_M3 đã chốt. Tổng bill tùy giá và phí bản ký; các tiền phạt trong bảng không phải tổng tiền phải chuyển.
- Mặc định phạt **100.000đ/ngày**. Chủ nhà chỉ nhập cách tính và mức phí; hệ thống tự cấp graceDays **0**, tối đa **10 ngày**, tổng phí tối đa **1.000.000đ/hóa đơn**. Deadline 5 ngày tính cả ngày chốt, ân hạn và giới hạn tiền là **nhãn chỉ đọc**, không phải trường nhập của chủ nhà. UC chính sách khác chỉ đổi cách tính/mức phí; ngoại lệ legacy như UC37 dùng fixture bản ký cũ, không tạo bằng API mới.
- Bill chốt ngày 16: **16–20 là 5 ngày thanh toán**; **21 là ngày phạt đầu**. Không thêm 5 ngày miễn phạt sau ngày 20. `paymentDueOffsetDays=4` là cộng 4 ngày sau ngày 16, tức 5 ngày khi tính cả ngày 16.
- Với bill chốt 16/11: sau hết 5 ngày quá hạn (21–25/11), **26/11** mở ba phương án. Phạt tiếp tối đa 10 ngày (21–30/11), **01/12** tự chốt cộng dồn nếu đủ điều kiện. Mốc tự chốt được cộng theo ngày lịch, kể cả khi sang tháng sau. Phí hiển thị **100.000đ/ngày × số ngày đã chốt**, không `1 × tổng phí`.
- Duyệt gia hạn: hạn mới = lúc duyệt + X ngày, X 1–30, phải sau hạn hiện tại. Phí đang chờ tạm dừng; từ chối/hết hạn tính bù, tối đa 10 ngày. Đồng ý giữ phí cố định cho bill đó kể cả trễ hạn mới.
- Tự chốt cộng dồn không tự hủy QR ngay: QR cũ chỉ hủy khi bill sau phát hành và nhận nợ. Đã trả/đang đối soát/gia hạn còn hiệu lực/chấm dứt còn hiệu lực không bị ghi đè. Kỳ cuối hoặc bill sau đã phát hành giữ nợ riêng.
- Phạt 10 ngày là trên từng bill; bill mới có hạn và chính sách riêng. Không tự xóa tiền thuê/dịch vụ hoặc cọc.
- **Nhảy thời gian/bỏ lỡ nhiều tháng mà không ai thao tác:** scheduler phát hành bù từ kỳ cũ đến kỳ mới, giữ hạn gốc của từng kỳ (không đẩy hạn bill cũ thành giờ hiện tại + 1 ngày). Bill cũ đủ 10 ngày quá hạn sẽ chốt phí theo bản hợp đồng rồi cộng vào bill sau; chỉ QR bill nhận nợ còn dùng được. `issuedAt`/lịch sử vẫn ghi thời điểm job thực sự chạy, không giả mạo thời điểm phát hành. Công tơ bị bỏ quên vẫn tự lấy số cuối kỳ trước, tiêu thụ tạm tính 0.
- Bản sửa phát hành bù áp dụng cho bill DRAFT/chưa tạo. **Không tự sửa hạn/tổng của bill đã phát hành bằng phiên bản cũ** (ví dụ bill tháng 11 có hạn 18/12); không lùi giờ để sửa dữ liệu đã chốt. Chạy UC52–UC54 bằng HĐ mới. Phát hành thủ công muộn vẫn có tối thiểu 1 ngày thanh toán; khác với scheduler khôi phục mốc đã bỏ lỡ.
- SmartCA, đăng nhập và token upload dùng thời gian thật. Giữ chỗ/INITIAL dùng thời gian thật; hoàn tất thanh toán/ký đúng cửa sổ thực tế trước khi tiến lịch. Bill MONTHLY và khoản quyết toán/hoàn cọc dùng BillingTime; nên xác nhận/từ chối ở mốc ghi trong ca, không để chứng từ test treo ngoài ý muốn. Chờ PDF SIGNED_FINAL READY và yêu cầu gia hạn APPLIED trước khi nhảy đồng hồ; chỉ thấy SmartCA đã ký chưa đủ nếu worker chưa đóng gói PDF.
- Các “nhắc việc” hiện là log/trạng thái giao diện, chưa bảo đảm có email/thông báo định kỳ.
- Mốc cuối thuê 15/01; popup có thể ghi 16/01 (mốc kết thúc không bao gồm ngày đó). Không coi đó là được ở thêm cả ngày 16/01.


## Cách đánh số đã triển khai — 13/10/2026

Áp dụng cho **mọi thời hạn thuê**, không chỉ demo 3 tháng. Restart backend và tải lại frontend để nhận trường `billingPeriod` mới; không cần drop CSDL vì thay đổi này không thêm bảng/cột hay ghi lại tiền đã chốt.

- **Kỳ thanh toán 1:** đọc PaymentRequest INITIAL gốc theo rentalRequestId, hiển thị cọc + tiền phòng tháng 1 cùng trạng thái xác nhận thực tế. Có lỗi tải thì hiện lỗi/thử lại, không giả lập số tiền hay trạng thái.
- **Kỳ thanh toán 2…N:** tiền phòng tháng tương ứng + chi phí tháng vừa kết thúc. Ví dụ kỳ 2 có tiền phòng từ 16/11 đến trước 16/12 và chi phí từ 16/10 đến trước 16/11.
- **Quyết toán cuối hợp đồng:** chi phí tháng N + công nợ nếu có; KHÔNG thu tiền phòng tháng N+1 khi chưa có gia hạn APPLIED.
- **Bổ sung chi phí kỳ cuối:** chỉ khoản chưa thu sau khi bill cuối đã phát hành; không tăng số tháng thuê.
- Danh sách, chi tiết và block quyết toán dùng chung nhãn; số hóa đơn định kỳ, quyết toán cuối và bổ sung được đếm riêng. Kỳ 1 nằm trong lịch sử riêng, không cộng lại vào dư nợ.
- Bộ lọc ngày/tháng ghi rõ **lọc theo khoảng chi phí**, không theo tiền phòng trả trước. Xóa lọc để xem khoản INITIAL; khoản này luôn có trên trang chi tiết.
- Backend giữ `periodIndex` làm chỉ số tháng chi phí nội bộ. Nháp xét thời hạn hiệu lực; hóa đơn đã phát hành xét dòng RENT trong snapshot đã chốt, không đổi loại hóa đơn vì ngày hiện tại hoặc thời hạn gia hạn thay đổi.
- Dòng tiền phòng mới ghi “Tiền phòng tháng X”; chuyển nợ mới ghi “Công nợ kỳ thanh toán X”. Không sửa snapshot/số tiền hóa đơn đã phát hành trước cập nhật.

### Gia hạn và hoàn cọc

- Nút gửi yêu cầu nằm ở **Đi thuê → Hợp đồng → Chi tiết → Gia hạn & quyết toán**; người thuê được gửi từ đúng 00:00 ngày kết thúc trừ 30 ngày. Trước đó nút bị khóa, có ghi ngày mở; không suy ra từ số hóa đơn đã trả.
- Với HĐ 3 tháng từ 16/10/2026: kết thúc 16/01/2027 (không gồm ngày này), mở 17/12/2026 00:00. Hạn ký đủ: **trước 15/01/2027 00:00**, để chốt công tơ cuối và quyết định có thu tháng tiếp theo.
- Với HĐ 12 tháng từ 16/10/2026: kết thúc 16/10/2027 (không gồm ngày này), mở **16/09/2027 00:00**, ký đủ trước **15/10/2027 00:00**.
- Chủ nhà duyệt → tạo văn bản mới liên kết hợp đồng gốc → hai bên ký SmartCA → PDF cuối READY và gia hạn APPLIED mới kéo dài thời hạn. Duyệt đơn/chỉ một bên ký chưa kéo dài; không thu cọc/tháng đầu thêm lần nữa.
- Trả tiền phòng tháng cuối chưa phải kết thúc thuê. Đến cuối tháng cuối mới chốt điện/nước/dịch vụ/phát sinh; thanh toán qua mục Hóa đơn, chủ nhà xác nhận đủ tiền, sau đó bàn giao/quyết toán cọc.
- Backend chặn chốt cọc nếu thiếu hóa đơn, còn nháp/chưa trả/chờ xác nhận hoặc có bổ sung chưa tất toán; không tự lấy công nợ hóa đơn trừ cọc. Người thuê xác nhận nhận tiền hoàn mới hoàn tất CLOSED.

### Cập nhật màn hình hoàn cọc và khiếu nại

- Restart backend để Hibernate `update` bổ sung cột lịch sử, chi tiết khấu trừ và hạn phản hồi trong `contract_closures`; tải lại frontend. Không cần drop CSDL để nhận cập nhật này. Cấu hình `validate/none` cần triển khai schema tương ứng trước khi chạy.
- Sau khi hóa đơn cuối PAID và chủ nhà xác nhận, **Bước 1 tự thu gọn**. Tiếp tục ngay **Bước 2 · Lập đề xuất quyết toán cọc**. Không phải nhập công tơ hoặc tạo thêm hóa đơn.
- **Ngoại lệ · Bổ sung chi phí chưa thu (không bắt buộc)** nằm trong Bước 1 đã thu gọn. Chỉ dùng khi thực sự còn điện/nước/chi phí chưa thu; không dùng cho hư hại đã đưa vào khấu trừ cọc. Phát hành bổ sung làm đề xuất đang chờ mất hiệu lực; phải thanh toán/xác nhận hóa đơn mới rồi gửi đề xuất mới. Đã đồng ý quyết toán thì không được bổ sung theo luồng này.
- Không khấu trừ: để danh sách khoản trống (tương đương “khấu trừ 0” trong các UC cũ), ghi nội dung bàn giao, tích xác nhận và gửi. Có khấu trừ: **Thêm khoản khấu trừ**, nhập từng mô tả và số tiền; có thể đính kèm ảnh/PDF. Các UC ghi “nhập khấu trừ X” được thao tác bằng một hoặc nhiều khoản có tổng X.
- Tối đa 20 khoản, mỗi mô tả 3–300 ký tự, số tiền dương, tổng tối đa 1 tỷ đồng; không trùng mô tả. Mỗi khoản/lần khiếu nại tối đa 5 ảnh JPG/PNG/WebP hoặc PDF, mỗi tệp tối đa 15 MB. Backend tính lại tổng; không tin tổng tiền do frontend gửi.
- Người thuê đọc từng khoản và minh chứng: **Đồng ý quyết toán** hoặc mở **Chưa đồng ý · Gửi yêu cầu đối soát**, nhập lý do tối thiểu 10 ký tự, đính kèm minh chứng nếu có. Khi DISPUTED, chưa có QR; chủ nhà lấy đề xuất hiện tại để chỉnh sửa và gửi phiên bản mới. Người thuê phải đồng ý lại đúng phiên bản.
- **Lịch sử quyết toán & khiếu nại** giữ nội dung, khoản khấu trừ và minh chứng từng phiên bản. Minh chứng PRIVATE chỉ chia sẻ cho bên còn lại sau khi gắn vào hồ sơ; quyền quản trị kho tệp hiện có vẫn áp dụng. Không cho người tải xóa minh chứng đã gắn vào lịch sử qua API xóa tệp. Không dựng lại được lịch sử các phiên bản đã bị ghi đè trước cập nhật; chỉ bảo lưu đề xuất cũ còn lưu khi có thao tác mới.
- Hạn phản hồi mặc định 48 giờ tính từ lúc gửi đề xuất; khi có khiếu nại, chủ nhà có mốc phản hồi mới 48 giờ. Đến hạn đánh dấu **cần xử lý** và ghi lịch sử một lần, không tự đồng ý, không tự hoàn cọc, không tự đóng hợp đồng; vẫn cho phản hồi sau hạn. Đây là cảnh báo trong hồ sơ, **chưa có email/push hoặc cơ chế trọng tài tự động**.
- Hạn chuyển tiền hoàn cọc vẫn theo điều khoản hợp đồng, tính từ lúc người thuê đồng ý (demo 15 ngày). Nộp chứng từ chưa phải nhận tiền; chỉ người nhận xác nhận đủ tiền mới CLOSED. Khấu trừ bằng cọc: không tạo QR 0đ; lớn hơn cọc: người thuê thanh toán phần thiếu.

### Kết quả kiểm thử sau cập nhật hoàn cọc

Đã chạy ngày 13/10/2026: **160 test backend** thuộc 10 suite billing/payment/lifecycle/storage đều đạt ở lần kiểm tra backend trước; sau khi gom module thanh toán, **99 test frontend** đều đạt; TypeScript và ESLint các file frontend thay đổi không báo lỗi. Đã kiểm tra mở thanh toán ban đầu, chọn đúng kỳ hóa đơn và hồ sơ hoàn cọc trên Chrome bằng hợp đồng demo hiện có. Chưa thực hiện giao dịch SmartCA/chuyển khoản mới hoặc xác nhận tiền tự động khi kiểm tra giao diện. Test tổng tiền phòng chạy xuyên suốt riêng hợp đồng 3 tháng và 12 tháng.

Test tự động bao phủ cách đánh số 1/3/12/36 tháng, tổng tiền phòng đúng thời hạn 3/12 tháng (kể cả INITIAL), không thu tiền phòng tháng kế tiếp ở quyết toán cuối, gia hạn đã ký, biên mở nút trước 30 ngày cho 12 tháng và khóa cọc khi thiếu bill cuối/chứng từ chưa xác nhận. Các UC bên dưới là kịch bản test thủ công; chưa thay cho xác minh SmartCA/ngân hàng thực tế.

Bổ sung test tổng khấu trừ/từng khoản, quyền và trạng thái minh chứng, giữ lịch sử v1 sau khiếu nại/v2, từ chối phiên bản cũ, hạn phản hồi 48 giờ không tự tạo QR, chặn xóa minh chứng đã đính kèm và giao diện thu gọn hóa đơn đã trả/ngoại lệ mặc định đóng.

## Danh mục 76 use case

UC01–UC38 kiểm tra hóa đơn/thanh toán/quá hạn; UC39–UC51 kiểm tra gia hạn thời gian thuê, hết hạn tự nhiên, bàn giao và hoàn cọc; UC52–UC54 kiểm tra nhảy thời gian và phát hành bù nhiều kỳ; UC55 kiểm tra riêng hợp đồng 12 tháng; UC56–UC57 kiểm tra quá hạn phản hồi đề xuất/khiếu nại cọc; UC58–UC76 kiểm tra phụ lục điều chỉnh, yêu cầu người thuê, bản nháp riêng và chỉnh trực tiếp theo loại tài sản. Mỗi UC đều có kịch bản riêng bên dưới.

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
- **UC37** — Tương thích bản ký cũ có trần tiền thấp hơn 10 ngày
- **UC38** — Không trả kỳ thanh toán 2, kỳ thanh toán 3 và quyết toán cuối: kiểm tra chuỗi cộng dồn
- **UC39** — Gia hạn thuê thêm 3 tháng, ký đủ và tiếp tục billing
- **UC40** — Chủ nhà từ chối gia hạn thuê
- **UC41** — Yêu cầu gia hạn thuê hết 48 giờ không phản hồi
- **UC42** — Đã duyệt nhưng không ai ký văn bản gia hạn
- **UC43** — Hết hạn tự nhiên, bàn giao và hoàn toàn bộ cọc
- **UC44** — Người thuê không đồng ý khấu trừ, sửa đề xuất rồi quyết toán
- **UC45** — Chưa thanh toán bill cuối thì chưa chốt hoàn cọc
- **UC46** — Chủ nhà hoàn cọc trễ, chứng từ bị từ chối rồi gửi lại
- **UC47** — Khấu trừ lớn hơn cọc: người thuê trả phần thiếu
- **UC48** — Khấu trừ đúng bằng cọc: quyết toán 0 đồng
- **UC49** — Công tơ kỳ cuối tự điền 0: bổ sung lượng thực dùng khi trả nhà
- **UC50** — Chỉ chủ nhà ký, người thuê không ký gia hạn
- **UC51** — Hết hạn nhưng chưa bàn giao: không tự mở lại tin hoặc hoàn cọc
- **UC52** — Nhảy thẳng hai tháng: cộng dồn và phạt theo ngày tối đa 10 ngày
- **UC53** — Nhảy thẳng hai tháng: cộng dồn và phạt cố định một lần
- **UC54** — Nhảy qua hết hợp đồng: chuỗi công nợ vào bill cuối, không thu tiền phòng tháng 4
- **UC55** — Hợp đồng 12 tháng: đủ 12 kỳ tiền phòng, quyết toán cuối rồi hoàn cọc
- **UC56** — Người thuê không phản hồi đề xuất cọc trong 48 giờ
- **UC57** — Chủ nhà không phản hồi khiếu nại cọc trong 48 giờ
- **UC58** — Chủ nhà đề nghị đổi giá thuê, hai bên ký phụ lục
- **UC59** — Người thuê đề nghị đổi phí, áp dụng đúng kỳ chi phí
- **UC60** — Đổi số người cư trú và phí theo người
- **UC61** — Từ chối phụ lục, điều khoản cũ tiếp tục áp dụng
- **UC62** — Không phản hồi phụ lục trong 48 giờ
- **UC63** — Yêu cầu chỉnh sửa, ký lại đúng phiên bản mới
- **UC64** — Chỉ ký một bên rồi quá hạn ký phụ lục
- **UC65** — Phát hiện sai sau chữ ký đầu, dừng phiên và sửa phụ lục
- **UC66** — Chặn ngày áp dụng đã chốt hóa đơn và trường dữ liệu được bảo vệ
- **UC67** — Phụ lục nội quy/tài sản cũng chờ đầu kỳ tương lai
- **UC68** — Chỗ xe qua phụ lục và luồng gia hạn sau đó
- **UC69** — Phụ lục trong hợp đồng 12 tháng, quyết toán cuối không thu tháng 13
- **UC70** — Người thuê gửi yêu cầu bằng chữ, chủ nhà không phản hồi
- **UC71** — Chủ nhà từ chối yêu cầu bằng chữ của người thuê
- **UC72** — Chủ nhà lưu và tiếp tục bản nháp riêng
- **UC73** — Yêu cầu người thuê được chuyển thành đề xuất, sửa lại phải duyệt lại
- **UC74** — Chỉnh trực tiếp nội thất và tiện ích nhà nguyên căn
- **UC75** — Chỉnh trực tiếp tiện ích chung cư, không đổi quyền chung thành riêng
- **UC76** — Chỉnh trực tiếp phòng trọ, kiểm tra người ở và loại công tơ

---

## UC01 — Thanh toán đúng hạn, đầy đủ cả 3 tháng

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC01: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-18T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 2 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-11-21T00:01:00` | Hai bên | Kiểm tra lại hóa đơn kỳ thanh toán 2. | Vẫn PAID, phạt 0; không mở xử lý quá hạn. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hệ thống | Chờ phát hành hóa đơn kỳ thanh toán 3, mở đúng HĐ. | chi phí tháng 2 + tiền phòng tháng 3; hạn 20/12 23:59:59. Không có nợ kỳ 1 nếu hóa đơn kỳ thanh toán 2 đã PAID. |
| `2026-12-18T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 3, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 3 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-12-21T00:01:00` | Hai bên | Kiểm tra hóa đơn kỳ thanh toán 3. | Vẫn PAID, phạt 0. |
| `2027-01-15T00:01:00` | Chủ nhà | Mở hóa đơn quyết toán cuối hợp đồng nháp; nhập điện 1660/nước 101, Lưu bản nháp. | Đầu kỳ 1610/96; dùng 50 kWh/5 m³. Đây là ngày thuê cuối. |
| `2027-01-16T00:01:00` | Hệ thống | Chờ phát hành hóa đơn quyết toán cuối hợp đồng. | Chỉ chi phí tháng 3/công nợ nếu có; KHÔNG thu tiền phòng tháng 4, KHÔNG thu lại cọc; hạn 20/01 23:59:59. |
| `2027-01-18T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn quyết toán cuối hợp đồng, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn quyết toán cuối hợp đồng PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2027-01-21T00:01:00` | Hai bên | Kiểm tra hai hóa đơn kỳ thanh toán 2, 3 và hóa đơn quyết toán cuối. | Đều PAID, không phạt; cọc chưa tự hoàn. |
| `2027-02-15T00:01:00` | Hai bên | Mở danh sách bill và chi tiết HĐ. | Không có hóa đơn tháng thuê tiếp theo. HĐ đã EXPIRED từ 16/01, chờ bàn giao/quyết toán nếu chưa thực hiện; không tự CLOSED hoặc hoàn cọc. |


---

## UC02 — Chủ nhà phát hành thủ công sớm

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC02: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở nháp hóa đơn kỳ thanh toán 2, chưa phát hành. | DRAFT, người thuê chưa thấy. |
| `2026-11-15T23:59:00` | Chủ nhà | Nhập 1550/90; bấm Chốt và phát hành ngay, xác nhận. | Hóa đơn kỳ thanh toán 2 UNPAID ngay; người thuê thấy bill/QR. Hạn vẫn 20/11, không tính lại hạn theo giờ bấm. |
| `2026-11-16T00:01:00` | Hai bên | Restart/làm mới nhiều lần. | Không tạo thêm bill hoặc PaymentRequest. |
| `2026-11-18T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 2 PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## UC03 — Quên nhập cả điện và nước, tự điền số cũ

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC03: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Không nhập, không lưu hóa đơn kỳ thanh toán 2 nháp. | DRAFT. |
| `2026-11-15T23:59:59` | Chủ nhà | Tiếp tục không nhập. | Còn trong cửa sổ 24 giờ. |
| `2026-11-16T00:01:00` | Hệ thống | Chờ tự phát hành. | Điện 1500→1500, nước 85→85; tiêu thụ 0; có cảnh báo tự điền cả hai. |
| `2026-11-18T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 2 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-12-15T00:01:00` | Chủ nhà | Nhập cuối kỳ 2 điện 1610/nước 96, lưu. | Đầu kỳ vẫn 1500/85, dùng 110 kWh/11 m³; bao gồm phần chưa ghi nhận ở kỳ trước. |
| `2026-12-16T00:01:00` | Hai bên | Mở hóa đơn kỳ thanh toán 3. | Tính đúng 110 kWh/11 m³ theo bản ký, không đoán số kỳ 1. |
| `2026-12-18T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 3, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 3 PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## UC04 — Nhập cuối kỳ bằng đầu kỳ, tiêu thụ 0 nhưng không phải tự điền

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC04: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Nhập cuối kỳ điện 1500/nước 85 và lưu. | Hiện 0 kWh/0 m³ và cảnh báo hai chỉ số bằng nhau. |
| `2026-11-16T00:01:00` | Hệ thống | Chờ phát hành. | Tiền điện/nước 0; cờ tự điền false vì chính chủ nhà đã nhập. |
| `2026-11-18T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 2 PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## UC05 — Nhập thiếu hoặc sai chỉ số, sửa lại rồi phát hành

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC05: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Nhập điện 1499/nước 84; thử lưu. | Bị chặn vì thấp hơn đầu kỳ 1500/85. |
| `2026-11-15T10:00:00` | Chủ nhà | Nhập điện 1550 nhưng bỏ trống nước; thử lưu. | Không lưu thành công nếu cả hai đều thu theo công tơ; không có luồng UI lưu riêng một bên. |
| `2026-11-15T23:59:00` | Chủ nhà | Nhập đủ 1550/90; lưu. | Lưu thành công, giữ đúng số đo. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-18T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 2 PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## UC06 — Thêm khoản phát sinh vào hóa đơn

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC06: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Nhập 1550/90; thêm khoản Hỏng đèn 100.000đ, lưu. | Nháp lưu chỉ số và đúng một khoản EXTRA. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-16T10:00:00` | Hai bên | Làm mới nhiều lần, xem các dòng. | EXTRA 1 × 100.000đ xuất hiện một lần; không cộng lại vào mỗi lần refresh. |
| `2026-11-18T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 2 PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## UC07 — Xin trước hạn, được duyệt, trả trong hạn mới

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC07: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-18T10:00:00` | Người thuê | Trên Hóa đơn kỳ thanh toán 2 xin 7 ngày; lý do ít nhất 10 ký tự. | PENDING; phí chốt 0; phản hồi trước 20/11 10:00. |
| `2026-11-19T10:00:00` | Chủ nhà | Đồng ý thêm 7 ngày. | Hạn mới 26/11 10:00; giữ ID bill/PaymentRequest/mã chuyển khoản; phí 0. |
| `2026-11-24T10:00:00` | Hai bên | Mở hóa đơn kỳ thanh toán 2. | Nhắc theo hạn mới bằng log/trạng thái; không còn hạn 20/11 làm hạn hiện tại. |
| `2026-11-25T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 2 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-11-27T00:01:00` | Hai bên | Kiểm tra hóa đơn kỳ thanh toán 2. | PAID; không tăng phạt, không tự cộng dồn. |


---

## UC08 — Xin trước hạn, được duyệt nhưng tiếp tục không trả

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC08: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-18T10:00:00` | Người thuê | Xin 7 ngày trên hóa đơn kỳ thanh toán 2. | PENDING; phạt 0. |
| `2026-11-19T10:00:00` | Chủ nhà | Duyệt 7 ngày. | Hạn mới 26/11 10:00; miễn tăng phí cho bill này. |
| `2026-11-26T10:00:01` | Hai bên | Không trả. | Quá hạn mới nhưng phí vẫn 0. |
| `2026-12-02T00:01:00` | Chủ nhà | Không chọn phương án. | Ba phương án mở lại; không tự chấm dứt. |
| `2026-12-06T23:59:59` | Hai bên | Không thao tác. | Hết 10 ngày quá hạn mới 27/11–06/12; phí vẫn 0. |
| `2026-12-07T00:01:00` | Hệ thống | Chờ đồng bộ. | Tự chốt cộng dồn theo hạn mới; không dùng mốc 01/12 của hạn cũ. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hai bên | Xem hóa đơn kỳ thanh toán 3. | Một dòng nợ hóa đơn kỳ thanh toán 2, không có phạt tăng thêm; hóa đơn kỳ thanh toán 2 ROLLED_OVER, QR cũ hủy. |


---

## UC09 — Xin sau 3 ngày trễ, duyệt rồi thanh toán

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC09: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-20T23:59:59` | Hai bên | Không trả tiền, không chọn phương án. | Vẫn trong hạn; phạt 0đ. |
| `2026-11-23T10:00:00` | Hai bên | Mở hóa đơn kỳ thanh toán 2, chưa thanh toán. | Phạt 100.000đ/ngày × 3 ngày = 300.000đ. |
| `2026-11-23T10:01:00` | Người thuê | Xin 7 ngày. | Chốt 100.000đ/ngày × 3 ngày = 300.000đ; dừng tăng khi PENDING. |
| `2026-11-24T10:00:00` | Chủ nhà | Duyệt. | Hạn mới 01/12 10:00; giữ phí 300.000đ, không tăng thêm cho bill này. |
| `2026-11-29T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | PAID; dòng phạt vẫn 3 ngày × 100.000đ. |
| `2026-12-02T00:01:00` | Hai bên | Xem lại bill. | Không tăng thành 10 ngày; không tự chuyển nợ. |


---

## UC10 — Xin sau 3 ngày trễ, duyệt nhưng bỏ quên hạn mới

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC10: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-23T10:00:00` | Người thuê | Xin 7 ngày. | Phạt chốt 300.000đ, 3 ngày. |
| `2026-11-24T10:00:00` | Chủ nhà | Duyệt. | Hạn mới 01/12 10:00. |
| `2026-12-01T10:00:01` | Hai bên | Không trả. | Quá hạn mới, phí vẫn 300.000đ. |
| `2026-12-07T00:01:00` | Chủ nhà | Không chọn phương án. | Mở ba phương án theo hạn mới. |
| `2026-12-11T23:59:59` | Hai bên | Không thao tác. | Hết 10 ngày quá hạn mới; phí vẫn 3 ngày = 300.000đ. |
| `2026-12-12T00:01:00` | Hệ thống | Chờ đồng bộ. | Tự chốt cộng dồn, không tăng phí lên 1 triệu. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hai bên | Xem hóa đơn kỳ thanh toán 3. | Nợ hóa đơn kỳ thanh toán 2 gồm phí chốt 300.000đ, chuyển đúng một lần; QR hóa đơn kỳ thanh toán 2 hủy. |


---

## UC11 — Chủ nhà từ chối yêu cầu gia hạn

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC11: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-23T10:00:00` | Người thuê | Xin 7 ngày. | PENDING, phí chốt 300.000đ. |
| `2026-11-24T10:00:00` | Chủ nhà | Từ chối. | REJECTED; tính bù thành 400.000đ, không phải 300.000 + 400.000. |
| `2026-11-25T10:00:00` | Người thuê | Thử gửi lại yêu cầu cùng cửa sổ hạn. | Bị chặn; phí 500.000đ. Refresh không tăng trùng trong cùng ngày. |
| `2026-11-26T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | PAID; phí 6 ngày × 100.000đ = 600.000đ, không tăng nữa. |


---

## UC12 — Chủ nhà không phản hồi, yêu cầu hết hạn 48 giờ

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC12: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-23T10:00:00` | Người thuê | Xin 7 ngày. | Phí 300.000đ; respondBy 25/11 10:00. |
| `2026-11-25T09:59:59` | Chủ nhà | Không phản hồi. | PENDING, phí 300.000đ. |
| `2026-11-25T10:00:00` | Hai bên | Mở lại bill; thử duyệt bằng dữ liệu cũ. | EXPIRED; tính bù 500.000đ; không duyệt được sau hạn. |
| `2026-11-26T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | PAID; phí 600.000đ. |


---

## UC13 — Chủ nhà tự chọn cộng dồn tháng sau

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC13: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-26T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2; chưa chọn gì. | Đã hết 5 ngày quá hạn; mở ba phương án. Hiện là ngày phạt thứ 6: 600.000đ. |
| `2026-11-26T10:00:00` | Chủ nhà | Chọn Cộng dồn sang kỳ sau. | DEFERRED, chốt phí 600.000đ; QR cũ vẫn còn trước bill mới. |
| `2026-12-01T00:01:00` | Hai bên | Không trả, mở lại bill. | Phí vẫn 600.000đ, không nâng lên 1 triệu; không thêm quyết định SYSTEM. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hai bên | Kiểm tra hóa đơn kỳ thanh toán 3. | Một dòng BALANCE_FORWARD bằng tổng hóa đơn kỳ thanh toán 2 đã chốt; hóa đơn kỳ thanh toán 2 ROLLED_OVER, QR cũ hủy. |
| `2026-12-18T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 3, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 3 PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## UC14 — Chọn cộng dồn khi yêu cầu gia hạn còn chờ

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC14: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-25T10:00:00` | Người thuê | Xin 7 ngày. | PENDING; phạt 500.000đ; hạn phản hồi 27/11 10:00. |
| `2026-11-26T00:01:00` | Chủ nhà | Chọn Cộng dồn sang kỳ sau. | Yêu cầu REJECTED, tính bù 600.000đ rồi chốt nợ; chỉ ROLLOVER có hiệu lực. |
| `2026-11-27T10:00:00` | Chủ nhà | Thử duyệt yêu cầu cũ/dời hạn. | Bị chặn, không tạo hai phương án. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hai bên | Mở hóa đơn kỳ thanh toán 3. | Chuyển đúng một dòng nợ; QR hóa đơn kỳ thanh toán 2 hủy. |


---

## UC15 — Cả hai bên không thao tác, tự cộng dồn sau 10 ngày phạt

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC15: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-20T23:59:59` | Hai bên | Không trả tiền, không chọn phương án. | Vẫn trong hạn; phạt 0đ. |
| `2026-11-26T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2; chưa chọn gì. | Đã hết 5 ngày quá hạn; mở ba phương án. Hiện là ngày phạt thứ 6: 600.000đ. |
| `2026-11-30T23:59:59` | Hai bên | Không thao tác. | Phạt 100.000đ/ngày × 10 ngày = 1.000.000đ; chưa tự chọn. |
| `2026-12-01T00:01:00` | Hệ thống | Chờ đồng bộ. | DEFERRED/ROLLOVER; một lịch sử AUTOMATIC_ROLLOVER do SYSTEM tạo. HĐ ACTIVE, cọc/tin chưa đổi. |
| `2026-12-02T10:00:00` | Hai bên | Restart/refresh nhiều lần. | Không tăng phí, không thêm quyết định, không cho chọn chồng; QR cũ chưa hủy. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hai bên | Kiểm tra hai bill. | Hóa đơn kỳ thanh toán 2 ROLLED_OVER; hóa đơn kỳ thanh toán 3 một dòng nợ; tổng dư nợ không tính hai lần. |
| `2026-12-18T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 3, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 3 PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## UC16 — Backend bỏ lỡ mốc tự cộng dồn

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC16: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-26T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2; chưa chọn gì. | Đã hết 5 ngày quá hạn; mở ba phương án. Hiện là ngày phạt thứ 6: 600.000đ. |
| `2026-11-26T10:00:00` | Người test | Dừng Core; không chạy các mốc 27/11–04/12. | Không có lượt scheduler ở mốc 01/12. |
| `2026-12-05T10:00:00` | Người test / hệ thống | Đặt giờ này rồi start Core; chờ đồng bộ. | Phạt chỉ 10 ngày = 1 triệu; tự chốt một lần. Lịch sử ghi lúc chạy thực tế 05/12. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hai bên | Mở hóa đơn kỳ thanh toán 3, refresh. | Nợ cũ chuyển một lần; không có phí 15 ngày hay 20 ngày. |


---

## UC17 — Yêu cầu gửi muộn còn PENDING tại mốc tự cộng dồn

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC17: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-29T10:00:00` | Người thuê | Xin 7 ngày. | Chốt 9 ngày = 900.000đ; respondBy 01/12 10:00. |
| `2026-12-01T00:01:00` | Chủ nhà | Chưa phản hồi. | Vẫn PENDING; chưa tự cộng dồn, phí 900.000đ. |
| `2026-12-01T10:00:00` | Hai bên | Không phản hồi, chờ đồng bộ. | EXPIRED; tính bù tối đa 10 ngày = 1 triệu rồi DEFERRED. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hai bên | Mở hóa đơn kỳ thanh toán 3. | Nợ hóa đơn kỳ thanh toán 2 chuyển một lần, không kèm yêu cầu đang treo. |


---

## UC18 — Trả bill cũ sau tự chốt nhưng trước bill mới

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC18: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-12-01T00:01:00` | Hệ thống | Không thao tác đến mốc này. | Hóa đơn kỳ thanh toán 2 DEFERRED; phí 1 triệu, QR còn dùng trong hệ thống. |
| `2026-12-02T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 2 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hai bên | Xem hóa đơn kỳ thanh toán 3. | KHÔNG có BALANCE_FORWARD hóa đơn kỳ thanh toán 2 đã trả; không thu nợ hai lần. |


---

## UC19 — Đang đối soát chứng từ khi tới mốc tự cộng dồn

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC19: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-30T23:50:00` | Người thuê | Chuyển đúng tổng hóa đơn kỳ thanh toán 2 hiện tại gồm phí 1 triệu, gửi chứng từ; chủ nhà chưa xác nhận. | TRANSFER_REPORTED/UNDER_REVIEW; dừng tăng phí. |
| `2026-12-01T00:01:00` | Hai bên | Mở hóa đơn kỳ thanh toán 2. | Không tự cộng dồn/hủy QR/đổi số tiền trong lúc đối soát. |
| `2026-12-01T10:00:00` | Chủ nhà | Xác nhận đã nhận tiền. | PAID. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hai bên | Xem hóa đơn kỳ thanh toán 3. | Không có nợ hóa đơn kỳ thanh toán 2. |


---

## UC20 — Từ chối chứng từ sau mốc 10 ngày

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC20: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-30T23:50:00` | Người thuê | Gửi chứng từ hóa đơn kỳ thanh toán 2 tổng gồm phí 1 triệu. | UNDER_REVIEW. |
| `2026-12-01T00:01:00` | Hai bên | Chưa xử lý. | Chưa tự cộng dồn do đang đối soát. |
| `2026-12-01T10:00:00` | Chủ nhà | Từ chối chứng từ, ghi lý do. | REJECTED; đồng bộ tiếp theo chốt DEFERRED nếu không còn phương án khác; phí vẫn tối đa 1 triệu. |
| `2026-12-02T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | Gửi lại chứng từ hợp lệ, chủ nhà xác nhận PAID. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hai bên | Xem hóa đơn kỳ thanh toán 3. | Không chuyển nợ đã thanh toán. |


---

## UC21 — Thanh toán khi yêu cầu gia hạn vẫn PENDING

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC21: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-23T10:00:00` | Người thuê | Xin 7 ngày. | Phí chốt 300.000đ, PENDING. |
| `2026-11-24T10:00:00` | Người thuê | Chuyển tổng gồm phí 300.000đ và gửi chứng từ. | UNDER_REVIEW, không cho duyệt gia hạn/đổi phương án khi đang đối soát. |
| `2026-11-24T10:05:00` | Chủ nhà | Xác nhận nhận tiền. | Hóa đơn kỳ thanh toán 2 PAID; yêu cầu CLOSED khi đồng bộ, không còn duyệt được. |
| `2026-12-01T00:01:00` | Hai bên | Xem lại bill. | PAID, giữ phí 3 ngày, không tự cộng dồn. |


---

## UC22 — Chủ nhà dời hạn trực tiếp, không có yêu cầu của người thuê

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC22: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-25T23:59:59` | Chủ nhà | Thử mở ba phương án. | Chưa được chọn vì chưa hết ngày trễ thứ 5. |
| `2026-11-26T00:00:00` | Chủ nhà | Dời hạn 3 ngày, lý do ít nhất 10 ký tự. | Hạn mới 29/11 00:00; phí chốt 6 ngày = 600.000đ; không tạo bill mới. |
| `2026-11-28T10:00:00` | Chủ nhà | Thử cộng dồn/chấm dứt/dời tiếp. | Bị chặn vì gia hạn còn hiệu lực. |
| `2026-11-29T00:00:01` | Hai bên | Không trả. | Trễ hạn mới, phí vẫn 600.000đ. |
| `2026-12-05T00:01:00` | Chủ nhà | Không chọn gì. | Ba phương án mở lại. |
| `2026-12-10T00:01:00` | Hệ thống | Chờ đồng bộ. | Tự chốt cộng dồn sau 10 ngày trễ hạn mới 30/11–09/12; phí vẫn 600.000đ. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hai bên | Mở hóa đơn kỳ thanh toán 3. | Nợ hóa đơn kỳ thanh toán 2 gồm phí 600.000đ, không tăng lên 1 triệu. |


---

## UC23 — Gia hạn lần hai sau khi hạn mới đã quá hạn đủ 5 ngày

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC23: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-26T00:00:00` | Chủ nhà | Dời hạn 3 ngày. | Hạn 29/11 00:00, phí 600.000đ. |
| `2026-12-05T00:01:00` | Chủ nhà | Dời tiếp 3 ngày, lý do hợp lệ. | Hạn mới 08/12 00:01; lịch sử thêm lần gia hạn; phí vẫn 600.000đ. |
| `2026-12-06T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 2 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hệ thống | Chờ phát hành hóa đơn kỳ thanh toán 3, mở đúng HĐ. | chi phí tháng 2 + tiền phòng tháng 3; hạn 20/12 23:59:59. Không có nợ kỳ 1 nếu hóa đơn kỳ thanh toán 2 đã PAID. |


---

## UC24 — Xin quá ít ngày làm hạn mới sớm hơn hạn hiện tại

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC24: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-17T10:00:00` | Người thuê | Xin 1 ngày. | PENDING; hạn hiện tại 20/11 23:59:59. |
| `2026-11-18T10:00:00` | Chủ nhà | Bấm Đồng ý thêm 1 ngày. | Bị từ chối: 19/11 10:00 sớm hơn hạn 20/11; không đổi hạn/QR, yêu cầu chưa APPROVED. |
| `2026-11-18T10:05:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | PAID; yêu cầu CLOSED sau đồng bộ, phí 0. |


---

## UC25 — Kiểm tra giới hạn, trùng yêu cầu và quyền truy cập

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC25: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-18T10:00:00` | Người thuê / test API | Thử ngày 0/31/1000, lý do dưới 10 ký tự hoặc trên 1000. | UI/API từ chối; không có yêu cầu hoặc tiền mới. |
| `2026-11-18T10:01:00` | Người thuê | Gửi 7 ngày, lý do hợp lệ; gửi lần hai. | Chỉ một PENDING; lần hai bị chặn. |
| `2026-11-18T10:02:00` | Test API có xác thực | Chủ nhà thử gửi yêu cầu như người thuê; người thuê thử approve; tài khoản thứ ba thử đọc HĐ. | Bị chặn theo vai trò/quyền; không đổi dữ liệu. Dùng tài khoản test, không dùng token người thật. |
| `2026-11-19T10:00:00` | Chủ nhà | Duyệt yêu cầu hợp lệ duy nhất. | Hạn mới 26/11 10:00. |
| `2026-11-25T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 2 PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## UC26 — Hai tab chủ nhà chọn hai phương án gần đồng thời

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC26: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-26T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2; chưa chọn gì. | Đã hết 5 ngày quá hạn; mở ba phương án. Hiện là ngày phạt thứ 6: 600.000đ. |
| `2026-11-26T10:00:00` | Chủ nhà | Mở hai tab cùng bill; tab A cộng dồn, tab B dời 7 ngày gần đồng thời. | Chỉ một quyết định hiệu lực, không tạo hai PaymentRequest. Tab thua tải lại trạng thái mới. |
| `2026-11-27T10:00:00` | Hai bên | Xem lịch sử/hạn/phí hai tab. | Nếu cộng dồn thắng: DEFERRED, không đổi hạn. Nếu dời hạn thắng: PAYMENT_EXTENSION, không có deferredAt. Phí đều chốt 600.000đ. |

**Lưu ý riêng:** Đây là ca kiểm tra cạnh tranh: kết quả phụ thuộc thao tác thắng khóa, không chạy cả hai nhánh tiếp trên cùng HĐ. Dừng sau khi xác minh chỉ một phương án có hiệu lực.


---

## UC27 — Hai bên đồng ý chấm dứt, bàn giao rồi hoàn tất

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC27: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-26T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2; chưa chọn gì. | Đã hết 5 ngày quá hạn; mở ba phương án. Hiện là ngày phạt thứ 6: 600.000đ. |
| `2026-11-26T10:00:00` | Chủ nhà | Gửi đề nghị chấm dứt. | TERMINATION; HĐ vẫn ACTIVE, chưa giữ cọc/mở lại tin. Nợ vẫn tồn tại. |
| `2026-11-27T10:00:00` | Người thuê | Đọc và xác nhận đầy đủ đồng ý chấm dứt, giữ cọc, hiểu công nợ. | Chờ bàn giao; HĐ chưa TERMINATED. |
| `2026-11-28T10:00:00` | Chủ nhà | Trên dữ liệu demo: xác nhận đã nhận phòng trống, chìa khóa/tài sản; Hoàn tất chấm dứt. | TERMINATED, cọc RETAINED_BY_LANDLORD, tin/chỗ xe được giải phóng theo code. Không xóa công nợ. |
| `2026-12-15T00:01:00` | Hai bên | Mở danh sách bill. | Không tạo bill tháng mới cho HĐ đã TERMINATED. |

**Lưu ý riêng:** THAO TÁC KHÔNG HOÀN TÁC TRÊN UI. Chỉ dùng HĐ demo có cọc HELD; không xác nhận bàn giao trên hợp đồng thật khi chưa nhận lại tài sản.


---

## UC28 — Người thuê từ chối chấm dứt, chủ nhà chọn cộng dồn

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC28: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-26T10:00:00` | Chủ nhà | Gửi đề nghị chấm dứt. | HĐ ACTIVE, chưa xử lý cọc. |
| `2026-11-27T10:00:00` | Người thuê | Không đồng ý. | Đề nghị bị từ chối; HĐ tiếp tục, cọc/tin chưa đổi. |
| `2026-11-27T10:05:00` | Chủ nhà | Chọn Cộng dồn sang kỳ sau. | Chốt phí 7 ngày = 700.000đ; chỉ ROLLOVER hiệu lực. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hai bên | Mở hóa đơn kỳ thanh toán 3. | Một dòng nợ cũ; QR hóa đơn kỳ thanh toán 2 hủy. |


---

## UC29 — Chủ nhà rút đề nghị chấm dứt, sau đó bỏ quên

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC29: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-26T10:00:00` | Chủ nhà | Gửi đề nghị chấm dứt. | TERMINATION, không tự cộng dồn khi đề nghị còn hiệu lực. |
| `2026-11-27T10:00:00` | Chủ nhà | Rút đề nghị trước khi người thuê đồng ý. | HĐ ACTIVE, phương án chấm dứt hết hiệu lực; cọc/tin chưa đổi. |
| `2026-12-01T00:01:00` | Hai bên | Không chọn phương án khác. | Tự DEFERRED, phạt tối đa 10 ngày = 1 triệu. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hai bên | Kiểm tra hóa đơn kỳ thanh toán 3. | Chuyển nợ một lần. |


---

## UC30 — Chủ nhà buộc chấm dứt sau người thuê từ chối

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC30: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-26T10:00:00` | Chủ nhà | Gửi đề nghị chấm dứt. | HĐ chưa kết thúc. |
| `2026-11-27T10:00:00` | Người thuê | Từ chối. | ACTIVE; chưa xử lý cọc/tin. |
| `2026-11-28T10:00:00` | Chủ nhà | Nếu bản ký có điều khoản phù hợp: xác nhận điều khoản đã ký, đã thông báo, đã thực tế nhận phòng/chìa khóa/tài sản; Buộc chấm dứt. | TERMINATED, giữ cọc/mở lại tin theo luồng hiện hành. Nếu thiếu điều khoản/điều kiện thì bị chặn. |
| `2026-12-15T00:01:00` | Hai bên | Kiểm tra bill tháng. | Không tạo bill mới sau TERMINATED. |

**Lưu ý riêng:** Chọn HĐ demo có điều khoản chấm dứt quá hạn được ghi trong bản ký ngay 16/10. Không tự suy ra quyền buộc chấm dứt từ một hóa đơn trễ; không sửa PDF đã ký. Thao tác này có tác động không hoàn tác trên UI.


---

## UC31 — Thanh toán khi đề nghị chấm dứt chưa hoàn tất

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC31: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-26T10:00:00` | Chủ nhà | Gửi đề nghị chấm dứt; người thuê chưa đồng ý. | HĐ ACTIVE, cọc/tin chưa đổi. |
| `2026-11-27T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | PAID; phí 7 ngày = 700.000đ. Đề nghị chấm dứt chưa hoàn tất bị hủy khi đồng bộ. |
| `2026-11-28T10:00:00` | Hai bên | Thử tiếp tục đồng ý/hoàn tất đề nghị cũ. | Bị chặn; không giữ cọc/mở lại tin. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hệ thống | Chờ phát hành hóa đơn kỳ thanh toán 3, mở đúng HĐ. | chi phí tháng 2 + tiền phòng tháng 3; hạn 20/12 23:59:59. Không có nợ kỳ 1 nếu hóa đơn kỳ thanh toán 2 đã PAID. |


---

## UC32 — Bill cuối quá hạn: phạt 10 ngày nhưng không có kỳ sau để chuyển

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC32: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-18T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 2 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hệ thống | Chờ phát hành hóa đơn kỳ thanh toán 3, mở đúng HĐ. | chi phí tháng 2 + tiền phòng tháng 3; hạn 20/12 23:59:59. Không có nợ kỳ 1 nếu hóa đơn kỳ thanh toán 2 đã PAID. |
| `2026-12-18T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 3, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 3 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2027-01-15T00:01:00` | Chủ nhà | Mở hóa đơn quyết toán cuối hợp đồng nháp; nhập điện 1660/nước 101, Lưu bản nháp. | Đầu kỳ 1610/96; dùng 50 kWh/5 m³. Đây là ngày thuê cuối. |
| `2027-01-16T00:01:00` | Hệ thống | Chờ phát hành hóa đơn quyết toán cuối hợp đồng. | Chỉ chi phí tháng 3/công nợ nếu có; KHÔNG thu tiền phòng tháng 4, KHÔNG thu lại cọc; hạn 20/01 23:59:59. |
| `2027-01-20T23:59:59` | Hai bên | Không trả hóa đơn quyết toán cuối hợp đồng. | Trong hạn, phí 0. |
| `2027-01-21T00:01:00` | Hai bên | Không trả. | Phạt 1 ngày = 100.000đ. |
| `2027-01-26T00:01:00` | Chủ nhà | Mở phương án. | Không có cộng dồn vì không có hóa đơn tháng thuê tiếp theo; không tự kéo dài quyền ở. |
| `2027-01-30T23:59:59` | Hai bên | Không thao tác. | Phạt 10 ngày = 1 triệu. |
| `2027-01-31T00:01:00` | Hệ thống | Chờ đồng bộ. | Giữ hóa đơn quyết toán cuối hợp đồng OVERDUE, không DEFERRED/ROLLED_OVER; cọc/tin không tự đổi. |
| `2027-02-01T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn quyết toán cuối hợp đồng, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn quyết toán cuối hợp đồng PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2027-02-15T00:01:00` | Hai bên | Mở danh sách bill. | Không tạo hóa đơn tháng thuê tiếp theo. |


---

## UC33 — Quên công tơ ở kỳ cuối, không có kỳ sau để thu bù

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC33: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-18T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 2 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hệ thống | Chờ phát hành hóa đơn kỳ thanh toán 3, mở đúng HĐ. | chi phí tháng 2 + tiền phòng tháng 3; hạn 20/12 23:59:59. Không có nợ kỳ 1 nếu hóa đơn kỳ thanh toán 2 đã PAID. |
| `2026-12-18T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 3, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 3 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2027-01-15T00:01:00` | Chủ nhà | Mở hóa đơn quyết toán cuối hợp đồng nhưng không nhập chỉ số. | DRAFT. Phải chú ý đây là ngày thuê cuối. |
| `2027-01-16T00:01:00` | Hệ thống | Chờ tự phát hành. | Tự lấy 1610/96 làm cuối kỳ; tiêu thụ 0, có cảnh báo. Không có tiền phòng tháng 4. |
| `2027-01-18T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn quyết toán cuối hợp đồng, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn quyết toán cuối hợp đồng PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2027-02-15T00:01:00` | Hai bên | Kiểm tra. | Không có hóa đơn tháng thuê tiếp theo để thu bù; chênh lệch thực tế cần đối soát riêng, chưa có luồng sửa bill đã phát hành. |


---

## UC34 — Gia hạn 30 ngày vượt qua ngày bill kế tiếp đã phát hành

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC34: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-26T00:00:00` | Chủ nhà | Dời hạn hóa đơn kỳ thanh toán 2 thêm 30 ngày. | Hạn mới 26/12 00:00, phí giữ 600.000đ. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hệ thống | Chờ phát hành hóa đơn kỳ thanh toán 3, mở đúng HĐ. | chi phí tháng 2 + tiền phòng tháng 3; hạn 20/12 23:59:59. Không có nợ kỳ 1 nếu hóa đơn kỳ thanh toán 2 đã PAID. |
| `2026-12-18T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 3, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 3 PAID; không tăng phạt, không chuyển nợ sau đó. |
| `2026-12-26T00:00:01` | Hai bên | Không trả hóa đơn kỳ thanh toán 2. | Hóa đơn kỳ thanh toán 2 trễ hạn mới, phí vẫn 600.000đ. |
| `2027-01-06T00:01:00` | Hệ thống | Chờ đồng bộ sau 10 ngày quá hạn mới. | Không nhét nợ vào hóa đơn kỳ thanh toán 3 đã phát hành; hóa đơn kỳ thanh toán 2 giữ riêng, phí không tăng. Không tự tạo bill ngoài hạn thuê. |
| `2027-01-07T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn kỳ thanh toán 2, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn kỳ thanh toán 2 PAID; không tăng phạt, không chuyển nợ sau đó. |

**Lưu ý riêng:** Đây là ngoại lệ cần đối soát riêng, không phải lỗi cho phép sửa bill đã phát hành. Gia hạn thanh toán không gia hạn thời gian thuê.


---

## UC35 — Chính sách không phạt nhưng vẫn tự cộng dồn

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC35: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt NONE (không phạt); kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-21T00:01:00` | Hai bên | Không trả. | OVERDUE, phí 0. |
| `2026-11-26T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2; chưa chọn gì. | Ba phương án mở; phí vẫn 0. |
| `2026-12-01T00:01:00` | Hệ thống | Chờ đồng bộ, không chọn gì. | DEFERRED, phí 0; vẫn phải trả tiền thuê/dịch vụ. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hai bên | Mở hóa đơn kỳ thanh toán 3. | Chuyển nợ gốc một lần, không tự tạo phí 1 triệu. |


---

## UC36 — Phạt một lần, không nhầm thành phạt theo ngày

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC36: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt FIXED_ONCE 100.000đ/lần, graceDays=0; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-21T00:01:00` | Hai bên | Không trả. | Phạt 100.000đ × 1 lần. |
| `2026-11-30T23:59:59` | Hai bên | Không chọn gì. | Vẫn 100.000đ × 1 lần, không 1 triệu. |
| `2026-12-01T00:01:00` | Hệ thống | Chờ đồng bộ. | Tự chốt nợ với phí một lần 100.000đ. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hai bên | Mở hóa đơn kỳ thanh toán 3. | Nợ cũ chuyển một lần; tổng đúng. |


---

## UC37 — Tương thích bản ký cũ có trần tiền thấp hơn 10 ngày

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Người kiểm thử | Dùng fixture riêng mô phỏng hợp đồng đã ký theo chính sách cũ: thuê 3 tháng từ 16/10/2026, FIXED_PER_DAY 100.000đ/ngày, graceDays=0, trần 700.000đ; đã trả tiền phòng tháng 1 + cọc, công tơ đầu điện 1500/nước 85. Không tạo cấu hình trần này bằng UI/API mới, không sửa hợp đồng thật đã ký. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID fixture. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-11-30T23:59:59` | Hai bên | Từ 16/11 tiến thẳng đến mốc này, chưa trả/chọn gì. | Tính 100.000đ/ngày × 10 ngày = 1 triệu, áp dụng trần còn 700.000đ; UI giải thích phần trần. |
| `2026-12-01T00:01:00` | Hệ thống | Chờ đồng bộ. | Tự chốt nợ; phạt 700.000đ, không nâng lên 1 triệu. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hai bên | Kiểm tra hóa đơn kỳ thanh toán 3. | Nợ cũ gồm 700.000đ phí, chuyển một lần. |

**Lưu ý riêng:** Đây chỉ là kiểm thử tương thích dữ liệu đã ký trước đây. UI và API mới không cho nhập trần/ân hạn riêng; chính sách mới 100.000đ/ngày được hệ thống tính tối đa 1.000.000đ. Không chỉnh snapshot hoặc PDF của hợp đồng thật đã ký. Nếu bản cũ đã chạm trần từ ngày 7 thì dòng đã chốt có thể ghi 7 ngày, không tự đổi thành 10 ngày khi tiền không tăng.


---

## UC38 — Không trả kỳ thanh toán 2, kỳ thanh toán 3 và quyết toán cuối: kiểm tra chuỗi cộng dồn

**Hợp đồng riêng:** bắt đầu/ký tại 16/10/2026, thuê 3 tháng. Không dùng ID đã chạy UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới riêng cho UC38: thuê 3 tháng từ 16/10/2026; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; nhập công tơ đầu điện 1500/nước 85, lưu chính sách phạt 100.000đ/ngày, graceDays=0, không trần tiền riêng; kết xuất PDF và ký đủ hai bên. | ACTIVE; kỳ thanh toán 1 đã hoàn tất; chưa có hóa đơn từ kỳ thanh toán 2. Ghi lại mã/ID HĐ của ca này. Ngày thuê cuối 15/01/2027. |
| `2026-11-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 2 nháp, nhập CHỈ SỐ CUỐI điện 1550/nước 90; bấm Lưu bản nháp. | DRAFT; dùng 50 kWh/5 m³; người thuê chưa thấy bill. Không nhập lại số đầu 1500/85. |
| `2026-11-16T00:01:00` | Hệ thống / hai bên | Chờ scheduler rồi mở hóa đơn kỳ thanh toán 2 của đúng HĐ này; chưa trả tiền. | UNPAID; chi phí tháng 1 + tiền phòng tháng 2; hạn 20/11 23:59:59. Không thu lại tiền phòng tháng 1 hoặc cọc. |
| `2026-12-01T00:01:00` | Hệ thống | Không thao tác hóa đơn kỳ thanh toán 2. | Chốt nợ hóa đơn kỳ thanh toán 2 với phí tối đa 1 triệu. |
| `2026-12-15T00:01:00` | Chủ nhà | Mở hóa đơn kỳ thanh toán 3 nháp; nhập điện 1610/nước 96, Lưu bản nháp. | Đầu kỳ lấy 1550/90; dùng 60 kWh/6 m³. |
| `2026-12-16T00:01:00` | Hệ thống | Phát hành hóa đơn kỳ thanh toán 3; tiếp tục không trả. | Hóa đơn kỳ thanh toán 3 nhận một dòng nợ hóa đơn kỳ thanh toán 2; hóa đơn kỳ thanh toán 2 ROLLED_OVER. |
| `2026-12-31T00:01:00` | Hệ thống | Không chọn phương án hóa đơn kỳ thanh toán 3. | Chốt hóa đơn kỳ thanh toán 3 sau 10 ngày quá hạn; phí của hóa đơn kỳ thanh toán 3 tối đa 1 triệu, không tăng lại dòng phí đã chốt trên hóa đơn kỳ thanh toán 2. |
| `2027-01-15T00:01:00` | Chủ nhà | Mở hóa đơn quyết toán cuối hợp đồng nháp; nhập điện 1660/nước 101, Lưu bản nháp. | Đầu kỳ 1610/96; dùng 50 kWh/5 m³. Đây là ngày thuê cuối. |
| `2027-01-16T00:01:00` | Hệ thống | Phát hành hóa đơn quyết toán cuối hợp đồng; chưa trả. | Hóa đơn quyết toán cuối hợp đồng không thu tiền phòng tháng 4; nhận nợ hóa đơn kỳ thanh toán 3 một lần (đã chứa nợ hóa đơn kỳ thanh toán 2). Hóa đơn kỳ thanh toán 3 ROLLED_OVER. |
| `2027-01-31T00:01:00` | Hai bên | Không thao tác. | Hóa đơn quyết toán cuối hợp đồng phạt tối đa 1 triệu; không có hóa đơn tháng thuê tiếp theo để chuyển. Tổng dư nợ chỉ tính hóa đơn quyết toán cuối hợp đồng, không cộng trùng các bill ROLLED_OVER. |
| `2027-02-01T10:00:00` | Người thuê → chủ nhà | Mở đúng hóa đơn quyết toán cuối hợp đồng, kiểm tra tổng mới nhất/QR, chuyển tiền và gửi chứng từ; chủ nhà Xác nhận đã nhận tiền ngay tại mốc này. | hóa đơn quyết toán cuối hợp đồng PAID; không tăng phạt, không chuyển nợ sau đó. |


---

## Gia hạn thời gian thuê và hết hạn tự nhiên — đã có luồng riêng

- Chi tiết HĐ gốc có block **Gia hạn & quyết toán hợp đồng**, riêng cho hai bên; văn bản gia hạn mở tab mới, không có bill riêng.
- Gia hạn mở **17/12/2026 00:00** (30 ngày trước 16/01). Chủ nhà phản hồi trong 48 giờ, nhưng không vượt hạn ký. Chỉ một yêu cầu cho mỗi mốc kết thúc; bị từ chối/hết hạn không gửi chồng lại mốc đó.
- **Hạn ký đủ: trước 15/01/2027 00:00**, tức trước ngày chốt công tơ kỳ cuối. Đây là khoảng đệm để hóa đơn quyết toán cuối hợp đồng xác định có thu tiền phòng tháng 4 hay không. Duyệt yêu cầu chưa thay đổi ngày hết hạn: phải ký đủ hai bên và đóng gói PDF thành công.
- Văn bản gia hạn giữ nguyên giá/chính sách/cọc, liên kết HĐ gốc, không sửa/xóa PDF đã ký. Thêm 1–36 tháng/lần, tổng tối đa 120 tháng. Tổng thời hạn dùng cho billing tách riêng snapshot 3 tháng đã ký; các kỳ tiếp tục neo theo ngày vào ở gốc, không thu INITIAL/cọc lần nữa.
- Không gia hạn: **16/01 00:00 → EXPIRED**, vẫn xử lý/thanh toán bill cuối. Không tự mở lại tin, xác nhận bàn giao hay hoàn cọc chỉ vì hết ngày thuê.
- **Bước 1: tất cả chi phí kỳ cuối phải qua mục Hóa đơn.** Hóa đơn quyết toán cuối hợp đồng tính điện/nước, dịch vụ và phát sinh kỳ 3, không có tiền phòng tháng 4 khi không gia hạn. Người thuê báo chuyển khoản chưa đủ: phải có chủ nhà xác nhận nhận tiền. Chưa đủ thì cả UI và API khóa lập/chốt quyết toán cọc.
- Nếu bill cuối đã phát hành rồi mới phát hiện thiếu điện/nước/phát sinh, chủ nhà dùng **Phát hành hóa đơn bổ sung kỳ cuối** trong block Bước 1. Hóa đơn này hiện trong mục Hóa đơn, chỉ thu phần công tơ tăng so với số đã thu gần nhất và khoản phát sinh chưa thu, không RENT/dịch vụ định kỳ lần nữa, không phải kỳ thuê thứ 4. Hạn thanh toán 5 ngày tính từ ngày phát hành bổ sung. Phải trả và xác nhận bill bổ sung trước khi hoàn cọc; gửi lại cùng yêu cầu không tạo bill/QR trùng.
- **Bước 2: sau tất cả hóa đơn PAID**, chủ nhà lập bàn giao/quyết toán cọc với khấu trừ khác có căn cứ (ví dụ hư hại tài sản); người thuê xác nhận đúng phiên bản. Không nhập lại điện/nước vào đề xuất cọc. Chưa thống nhất có nút đối soát, không tạo QR hoàn tiền.
- Mọi bill phải PAID hoặc đã chuyển nợ ROLLED_OVER và bill nhận nợ đã PAID, đủ số kỳ, trước khi chốt. Không vừa khấu trừ nợ khỏi cọc vừa để QR nợ còn thu tiếp.
- Cọc còn lại = cọc đang giữ − khấu trừ khác đã đồng ý; **không trừ điện/nước/chi phí kỳ cuối lần nữa**. Khoản thừa tạo QR **chủ nhà → người thuê**; thiếu do khấu trừ khác tạo QR **người thuê → chủ nhà**; đúng 0 không tạo QR 0đ. Hạn quyết toán tính từ lúc người thuê đồng ý, theo `depositRefundDays` của bản ký (mặc định 15 ngày).
- Hai bên đồng ý bàn giao mới giải phóng tin/chỗ xe. HĐ vẫn EXPIRED/chờ nghĩa vụ tiền; chỉ CLOSED sau bên nhận xác nhận đủ tiền (hoặc quyết toán không phát sinh tiền).
- Quá hạn hoàn cọc không tự xóa nghĩa vụ hoặc tự xác nhận. Chứng từ không đồng nghĩa tiền đã vào tài khoản. Ngân hàng nhận lấy từ tài khoản mặc định đã đăng ký của bên nhận, cố định khi tạo QR.
- Chưa có checklist ảnh bàn giao hoặc PDF biên bản quyết toán riêng. Nội dung, chỉ số, khấu trừ, số tiền và phiên bản được lưu trong hồ sơ; PDF HĐ gốc/gia hạn vẫn dùng pipeline kết xuất/ký hiện tại. Nhắc việc hiện qua log/trạng thái UI, không khẳng định đã gửi email/push.

Không sử dụng nhánh **chấm dứt quá hạn giữ cọc** để thay thế việc hết hạn tự nhiên/hoàn cọc đúng hạn. Các UC39 trở đi ở cuối file độc lập như các UC trước, mỗi ca bắt đầu từ 16/10/2026.

## Các ca cần fixture hoặc API, không giả vờ thao tác được bằng UI

- Chỉ một công tơ cuối kỳ đã lưu, công tơ còn lại null: scheduler có giữ số đã lưu và chỉ tự điền phần thiếu, nhưng UI/prepare hiện yêu cầu đủ hai số khi cả hai metered. Cần fixture/test tự động; không hướng dẫn lưu một bên như ca UI thành công.
- Thiếu công tơ đầu hoặc đơn giá hợp lệ: không được tự đoán số/giá. Đầu kỳ bắt buộc còn có thể chặn kết xuất/ký, nên không tạo HĐ ACTIVE sai bằng UI chỉ để thử; dùng fixture tự động.
- Biểu phí STATE_WATER_RATE chưa có giá số: không phát hành tự động để tránh tính sai; không dùng cho các UC demo PER_M3.
- DISPUTED: backend không tự cộng dồn/tăng phí trong tranh chấp. Nếu UI hiện không có thao tác tạo tranh chấp thì test API/fixture, không ghi là ca bấm nút trên trang bill.
- Hóa đơn cũ `1 × tổng phí`: API diễn giải được số ngày khi biết đơn giá ngày và không chạm trần, không sửa snapshot/số tiền/QR. Nếu chạm trần và thiếu metadata thì không tự đoán ngày.
- Phí cũ đã chốt trên 10 ngày trước cập nhật: không tự giảm tiền/PDF cũ; cần đối soát riêng. Dùng HĐ mới từ 16/10 cho các UC trong file.

## Ghi nhận kết quả từng UC

Mỗi UC ghi riêng: **mã/ID HĐ**, mốc đã chạy, bill/PaymentRequest ID, ảnh của hai bên, tổng bill, dòng phí ngày × đơn giá, trạng thái yêu cầu, quyết định đang hiệu lực, QR nào còn/hủy, PASS/FAIL và log nếu lỗi.

Không ghi “gia hạn lỗi” nếu đang mở hóa đơn kỳ thanh toán 3 hạn 20/12 nhưng lùi đồng hồ về 18/11: duyệt 7 ngày sẽ ra 25/11, sớm hơn hạn hiện tại và phải bị chặn. Khi gặp trường hợp này, chuyển sang **HĐ mới đúng UC**, không sửa dữ liệu HĐ đã tiến lịch.


---

## UC39 — Gia hạn thuê thêm 3 tháng, ký đủ và tiếp tục billing

**Hợp đồng độc lập:** bắt đầu/ký ngày 16/10/2026, thời hạn ban đầu 3 tháng. Chạy đủ các hàng dưới, không ghép UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ MỚI riêng UC39, thuê 3 tháng từ 16/10/2026; trả RENT kỳ đầu + cọc, chủ nhà xác nhận; nhập công tơ đầu 1500/85; chốt phạt 100.000đ/ngày, graceDays=0 và hoàn cọc 15 ngày; kết xuất, ký đủ SmartCA. | ACTIVE, ngày cuối 15/01/2027; ghi lại ID gốc. Gọi cọc giữ là D. Nếu thuê 12.400.000đ, cọc 2 tháng thì D=24.800.000đ. Không dùng ID ca khác. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 2: điện 1550, nước 90. | DRAFT, dùng 50 kWh/5 m³. |
| `2026-11-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 2. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 2 PAID; gồm chi phí tháng 1 + tiền phòng tháng 2, không cọc. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 3: điện 1610, nước 96. | DRAFT, dùng 60 kWh/6 m³. |
| `2026-12-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 3. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 3 PAID; chi phí tháng 2 + tiền phòng tháng 3. Chưa được xin gia hạn thuê trước 17/12. |
| `2026-12-17T10:00:00` | Người thuê | Trên chi tiết HĐ gốc, gửi yêu cầu thêm 3 tháng và lý do ít nhất 10 ký tự. | REQUESTED, phản hồi trước 19/12 10:00. HĐ vẫn kết thúc trước 16/01, chưa tự gia hạn. |
| `2026-12-18T10:00:00` | Chủ nhà | Duyệt & tạo văn bản; mở văn bản liên kết trong tab mới, kết xuất PDF rồi ký SmartCA. | APPROVED, văn bản mới có ngày thuê 16/01–15/04; không sửa PDF gốc, không thu INITIAL/cọc mới. Người thuê chờ chữ ký chủ nhà. |
| `2026-12-18T11:00:00` | Người thuê → hệ thống | Mở văn bản gia hạn, kiểm tra PDF đã ký chủ nhà, ký SmartCA; chờ worker hoàn tất PDF, làm mới hồ sơ gốc. | APPLIED, đủ hai SIGNED và SIGNED_FINAL READY. Thời hạn hiệu lực tổng 6 tháng, mốc hết hạn 16/04. Cọc và giá không đổi, chỗ xe nối tiếp được giữ. |
| `2027-01-15T10:00:00` | Chủ nhà | Lưu công tơ hóa đơn kỳ thanh toán 4: 1660/101. | DRAFT, vẫn là một bill trên ID gốc, không bill trên văn bản gia hạn. |
| `2027-01-16T10:00:00` | Hai bên | Hóa đơn kỳ thanh toán 4 phát hành; trả và xác nhận đủ tổng bill. | HĐ gốc ACTIVE; hóa đơn kỳ thanh toán 4 có tiền phòng tháng 4 + chi phí tháng 3. Không tạo WAITING_HANDOVER ở mốc cũ. |
| `2027-02-15T10:00:00` | Chủ nhà | Lưu công tơ hóa đơn kỳ thanh toán 5: 1710/106. | Đầu 1660/101, dùng 50/5. |
| `2027-02-16T10:00:00` | Hai bên | Trả và xác nhận hóa đơn kỳ thanh toán 5. | chi phí tháng 4 + tiền phòng tháng 5; PAID. |
| `2027-03-15T10:00:00` | Chủ nhà | Lưu công tơ hóa đơn kỳ thanh toán 6: 1760/111. | Đầu 1710/106, dùng 50/5. |
| `2027-03-16T10:00:00` | Hai bên | Trả và xác nhận hóa đơn kỳ thanh toán 6; không yêu cầu gia hạn thuê lần nữa. | chi phí tháng 5 + tiền phòng tháng 6; PAID. |
| `2027-04-15T10:00:00` | Chủ nhà | Lưu công tơ hóa đơn quyết toán cuối hợp đồng: 1810/116. | DRAFT, kỳ cuối sau gia hạn. |
| `2027-04-16T10:00:00` | Hai bên | Trả và xác nhận hóa đơn quyết toán cuối hợp đồng; xem chi tiết HĐ. | EXPIRED, chỉ chi phí tháng 6, không thu tiền phòng tháng 7; cọc còn giữ D, chờ quyết toán theo mốc mới. |


---

## UC40 — Chủ nhà từ chối gia hạn thuê

**Hợp đồng độc lập:** bắt đầu/ký ngày 16/10/2026, thời hạn ban đầu 3 tháng. Chạy đủ các hàng dưới, không ghép UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ MỚI riêng UC40, thuê 3 tháng từ 16/10/2026; trả RENT kỳ đầu + cọc, chủ nhà xác nhận; nhập công tơ đầu 1500/85; chốt phạt 100.000đ/ngày, graceDays=0 và hoàn cọc 15 ngày; kết xuất, ký đủ SmartCA. | ACTIVE, ngày cuối 15/01/2027; ghi lại ID gốc. Gọi cọc giữ là D. Nếu thuê 12.400.000đ, cọc 2 tháng thì D=24.800.000đ. Không dùng ID ca khác. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 2: điện 1550, nước 90. | DRAFT, dùng 50 kWh/5 m³. |
| `2026-11-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 2. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 2 PAID; gồm chi phí tháng 1 + tiền phòng tháng 2, không cọc. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 3: điện 1610, nước 96. | DRAFT, dùng 60 kWh/6 m³. |
| `2026-12-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 3. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 3 PAID; chi phí tháng 2 + tiền phòng tháng 3. Chưa được xin gia hạn thuê trước 17/12. |
| `2026-12-17T10:00:00` | Người thuê | Xin thêm 3 tháng, lý do hợp lệ. | REQUESTED. |
| `2026-12-18T10:00:00` | Chủ nhà | Bấm Từ chối. | REJECTED; không văn bản mới, không tăng thời hạn; không gửi yêu cầu trùng mốc cũ. |
| `2027-01-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn quyết toán cuối hợp đồng: điện 1660, nước 101. | DRAFT; dùng 50 kWh/5 m³. Không có văn bản gia hạn APPLIED. |
| `2027-01-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn quyết toán cuối hợp đồng; người thuê trả tổng bill và nộp chứng từ, chủ nhà xác nhận. | Hóa đơn quyết toán cuối hợp đồng PAID, chỉ chi phí tháng 3, không thu tiền phòng tháng 4. HĐ EXPIRED, có block bàn giao, tin/chỗ xe chưa giải phóng. |
| `2027-01-18T10:00:00` | Chủ nhà | Sau khi tất cả bill đã PAID và bàn giao thực tế xong, nhập khấu trừ 0, nội dung 'Không khấu trừ khác'; tích đã nhận phòng trống/chìa khóa, gửi đề xuất. | PROPOSED v1, chưa QR hoàn cọc, chưa giải phóng tin. Điện/nước đã thanh toán trên hóa đơn, không trừ vào cọc. |
| `2027-01-18T11:00:00` | Người thuê | Đọc đúng đề xuất, tích đồng ý bàn giao/khấu trừ và bấm Đồng ý quyết toán. | REFUND_PENDING, QR tài khoản hoàn cọc người thuê, hướng chủ nhà → người thuê. Hạn 02/02 11:00; tin/chỗ xe giải phóng, HĐ chưa CLOSED. |
| `2027-01-19T10:00:00` | Chủ nhà | Chuyển đúng số tiền theo QR hoàn cọc, tải chứng từ và bấm Tôi đã chuyển khoản. | TRANSFER_REPORTED; chưa REFUNDED/CLOSED. Người thuê xem chứng từ; chủ nhà không được tự xác nhận đã nhận tiền. |
| `2027-01-19T11:00:00` | Người thuê | Kiểm tra tài khoản ngân hàng thực tế; bấm Xác nhận đã nhận tiền, làm mới HĐ. | Payment CONFIRMED, cọc REFUNDED, HĐ CLOSED. PDF đã ký không bị thay thế; scheduler chạy lại không tạo QR mới. |


---

## UC41 — Yêu cầu gia hạn thuê hết 48 giờ không phản hồi

**Hợp đồng độc lập:** bắt đầu/ký ngày 16/10/2026, thời hạn ban đầu 3 tháng. Chạy đủ các hàng dưới, không ghép UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ MỚI riêng UC41, thuê 3 tháng từ 16/10/2026; trả RENT kỳ đầu + cọc, chủ nhà xác nhận; nhập công tơ đầu 1500/85; chốt phạt 100.000đ/ngày, graceDays=0 và hoàn cọc 15 ngày; kết xuất, ký đủ SmartCA. | ACTIVE, ngày cuối 15/01/2027; ghi lại ID gốc. Gọi cọc giữ là D. Nếu thuê 12.400.000đ, cọc 2 tháng thì D=24.800.000đ. Không dùng ID ca khác. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 2: điện 1550, nước 90. | DRAFT, dùng 50 kWh/5 m³. |
| `2026-11-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 2. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 2 PAID; gồm chi phí tháng 1 + tiền phòng tháng 2, không cọc. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 3: điện 1610, nước 96. | DRAFT, dùng 60 kWh/6 m³. |
| `2026-12-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 3. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 3 PAID; chi phí tháng 2 + tiền phòng tháng 3. Chưa được xin gia hạn thuê trước 17/12. |
| `2026-12-17T10:00:00` | Người thuê | Gửi xin thêm 3 tháng; chủ nhà không thao tác. | REQUESTED, respondBy 19/12 10:00. |
| `2026-12-19T10:00:01` | Hệ thống / hai bên | Chờ scheduler, làm mới chi tiết gốc. | EXPIRED trên yêu cầu; HĐ gốc vẫn ACTIVE tới 16/01. Không tạo văn bản/QR; không gửi chồng cùng mốc. |
| `2027-01-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn quyết toán cuối hợp đồng: điện 1660, nước 101. | DRAFT; dùng 50 kWh/5 m³. Không có văn bản gia hạn APPLIED. |
| `2027-01-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn quyết toán cuối hợp đồng; người thuê trả tổng bill và nộp chứng từ, chủ nhà xác nhận. | Hóa đơn quyết toán cuối hợp đồng PAID, chỉ chi phí tháng 3, không thu tiền phòng tháng 4. HĐ EXPIRED, có block bàn giao, tin/chỗ xe chưa giải phóng. |
| `2027-01-18T10:00:00` | Chủ nhà | Sau khi tất cả bill đã PAID và bàn giao thực tế xong, nhập khấu trừ 0, nội dung 'Không khấu trừ khác'; tích đã nhận phòng trống/chìa khóa, gửi đề xuất. | PROPOSED v1, chưa QR hoàn cọc, chưa giải phóng tin. Điện/nước đã thanh toán trên hóa đơn, không trừ vào cọc. |
| `2027-01-18T11:00:00` | Người thuê | Đọc đúng đề xuất, tích đồng ý bàn giao/khấu trừ và bấm Đồng ý quyết toán. | REFUND_PENDING, QR tài khoản hoàn cọc người thuê, hướng chủ nhà → người thuê. Hạn 02/02 11:00; tin/chỗ xe giải phóng, HĐ chưa CLOSED. |
| `2027-01-19T10:00:00` | Chủ nhà | Chuyển đúng số tiền theo QR hoàn cọc, tải chứng từ và bấm Tôi đã chuyển khoản. | TRANSFER_REPORTED; chưa REFUNDED/CLOSED. Người thuê xem chứng từ; chủ nhà không được tự xác nhận đã nhận tiền. |
| `2027-01-19T11:00:00` | Người thuê | Kiểm tra tài khoản ngân hàng thực tế; bấm Xác nhận đã nhận tiền, làm mới HĐ. | Payment CONFIRMED, cọc REFUNDED, HĐ CLOSED. PDF đã ký không bị thay thế; scheduler chạy lại không tạo QR mới. |


---

## UC42 — Đã duyệt nhưng không ai ký văn bản gia hạn

**Hợp đồng độc lập:** bắt đầu/ký ngày 16/10/2026, thời hạn ban đầu 3 tháng. Chạy đủ các hàng dưới, không ghép UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ MỚI riêng UC42, thuê 3 tháng từ 16/10/2026; trả RENT kỳ đầu + cọc, chủ nhà xác nhận; nhập công tơ đầu 1500/85; chốt phạt 100.000đ/ngày, graceDays=0 và hoàn cọc 15 ngày; kết xuất, ký đủ SmartCA. | ACTIVE, ngày cuối 15/01/2027; ghi lại ID gốc. Gọi cọc giữ là D. Nếu thuê 12.400.000đ, cọc 2 tháng thì D=24.800.000đ. Không dùng ID ca khác. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 2: điện 1550, nước 90. | DRAFT, dùng 50 kWh/5 m³. |
| `2026-11-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 2. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 2 PAID; gồm chi phí tháng 1 + tiền phòng tháng 2, không cọc. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 3: điện 1610, nước 96. | DRAFT, dùng 60 kWh/6 m³. |
| `2026-12-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 3. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 3 PAID; chi phí tháng 2 + tiền phòng tháng 3. Chưa được xin gia hạn thuê trước 17/12. |
| `2026-12-17T10:00:00` | Người thuê | Xin thêm 3 tháng. | REQUESTED. |
| `2026-12-18T10:00:00` | Chủ nhà | Duyệt, kết xuất văn bản nhưng không ký. | APPROVED; HĐ gốc vẫn 3 tháng. |
| `2027-01-14T23:59:59` | Hai bên | Chỉ xem trạng thái, không ký. | Chưa APPLIED; hạn ký chưa qua, vẫn có thể bắt đầu ký nhưng worker phải hoàn tất trước cutoff. |
| `2027-01-15T00:01:00` | Hệ thống | Chờ đồng bộ. | Yêu cầu EXPIRED, văn bản CANCELLED, phần giữ chỗ xe gia hạn được giải phóng. Không tự kéo dài HĐ. |
| `2027-01-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn quyết toán cuối hợp đồng: điện 1660, nước 101. | DRAFT; dùng 50 kWh/5 m³. Không có văn bản gia hạn APPLIED. |
| `2027-01-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn quyết toán cuối hợp đồng; người thuê trả tổng bill và nộp chứng từ, chủ nhà xác nhận. | Hóa đơn quyết toán cuối hợp đồng PAID, chỉ chi phí tháng 3, không thu tiền phòng tháng 4. HĐ EXPIRED, có block bàn giao, tin/chỗ xe chưa giải phóng. |
| `2027-01-18T10:00:00` | Chủ nhà | Sau khi tất cả bill đã PAID và bàn giao thực tế xong, nhập khấu trừ 0, nội dung 'Không khấu trừ khác'; tích đã nhận phòng trống/chìa khóa, gửi đề xuất. | PROPOSED v1, chưa QR hoàn cọc, chưa giải phóng tin. Điện/nước đã thanh toán trên hóa đơn, không trừ vào cọc. |
| `2027-01-18T11:00:00` | Người thuê | Đọc đúng đề xuất, tích đồng ý bàn giao/khấu trừ và bấm Đồng ý quyết toán. | REFUND_PENDING, QR tài khoản hoàn cọc người thuê, hướng chủ nhà → người thuê. Hạn 02/02 11:00; tin/chỗ xe giải phóng, HĐ chưa CLOSED. |
| `2027-01-19T10:00:00` | Chủ nhà | Chuyển đúng số tiền theo QR hoàn cọc, tải chứng từ và bấm Tôi đã chuyển khoản. | TRANSFER_REPORTED; chưa REFUNDED/CLOSED. Người thuê xem chứng từ; chủ nhà không được tự xác nhận đã nhận tiền. |
| `2027-01-19T11:00:00` | Người thuê | Kiểm tra tài khoản ngân hàng thực tế; bấm Xác nhận đã nhận tiền, làm mới HĐ. | Payment CONFIRMED, cọc REFUNDED, HĐ CLOSED. PDF đã ký không bị thay thế; scheduler chạy lại không tạo QR mới. |


---

## UC43 — Hết hạn tự nhiên, bàn giao và hoàn toàn bộ cọc

**Hợp đồng độc lập:** bắt đầu/ký ngày 16/10/2026, thời hạn ban đầu 3 tháng. Chạy đủ các hàng dưới, không ghép UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ MỚI riêng UC43, thuê 3 tháng từ 16/10/2026; trả RENT kỳ đầu + cọc, chủ nhà xác nhận; nhập công tơ đầu 1500/85; chốt phạt 100.000đ/ngày, graceDays=0 và hoàn cọc 15 ngày; kết xuất, ký đủ SmartCA. | ACTIVE, ngày cuối 15/01/2027; ghi lại ID gốc. Gọi cọc giữ là D. Nếu thuê 12.400.000đ, cọc 2 tháng thì D=24.800.000đ. Không dùng ID ca khác. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 2: điện 1550, nước 90. | DRAFT, dùng 50 kWh/5 m³. |
| `2026-11-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 2. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 2 PAID; gồm chi phí tháng 1 + tiền phòng tháng 2, không cọc. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 3: điện 1610, nước 96. | DRAFT, dùng 60 kWh/6 m³. |
| `2026-12-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 3. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 3 PAID; chi phí tháng 2 + tiền phòng tháng 3. Chưa được xin gia hạn thuê trước 17/12. |
| `2027-01-15T10:00:00` | Chủ nhà | Vào mục Hóa đơn, lưu hóa đơn quyết toán cuối hợp đồng nháp: điện cuối 1660/nước cuối 101; thêm khoản phát sinh đã thỏa thuận 'Chi phí phát sinh kỳ cuối' 100000đ. | Đầu kỳ 1610/96, dùng 50 kWh/5 m³. DRAFT; chưa QR. Không gia hạn thuê. |
| `2027-01-16T10:00:00` | Hệ thống / hai bên | Chờ phát hành hóa đơn quyết toán cuối hợp đồng rồi mở Hóa đơn ở cả hai tài khoản. Chưa trả tiền. | UNPAID; điện/nước + dịch vụ theo HĐ + phát sinh 100000đ. **Không có dòng tiền phòng tháng 4**. Hạn 20/01 23:59:59. HĐ EXPIRED; Bước 1 hiển thị bill, Bước 2 quyết toán cọc bị khóa. |
| `2027-01-16T11:00:00` | Người thuê | Chuyển tổng hóa đơn quyết toán cuối hợp đồng theo QR trong Hóa đơn, nộp chứng từ. | Đã thanh toán · Chờ xác nhận. Cọc vẫn nguyên D; chưa được gửi/chốt đề xuất cọc hoặc tạo QR hoàn cọc. |
| `2027-01-16T12:00:00` | Chủ nhà | Xem chứng từ, kiểm tra tài khoản thực tế, Xác nhận đã nhận tiền hóa đơn quyết toán cuối hợp đồng; làm mới HĐ gốc. | Hóa đơn quyết toán cuối hợp đồng PAID, đủ cả 3 bill đã xác nhận. Bước 2 được mở. Cọc vẫn nguyên D, không trừ điện/nước/phát sinh đã thu. |
| `2027-01-18T10:00:00` | Chủ nhà | Sau khi tất cả bill đã PAID và bàn giao thực tế xong, nhập khấu trừ 0, nội dung 'Không khấu trừ khác'; tích đã nhận phòng trống/chìa khóa, gửi đề xuất. | PROPOSED v1, chưa QR hoàn cọc, chưa giải phóng tin. Điện/nước đã thanh toán trên hóa đơn, không trừ vào cọc. |
| `2027-01-18T11:00:00` | Người thuê | Đọc đúng đề xuất, tích đồng ý bàn giao/khấu trừ và bấm Đồng ý quyết toán. | REFUND_PENDING, QR tài khoản hoàn cọc người thuê, hướng chủ nhà → người thuê. Hạn 02/02 11:00; tin/chỗ xe giải phóng, HĐ chưa CLOSED. |
| `2027-01-19T10:00:00` | Chủ nhà | Chuyển đúng số tiền theo QR hoàn cọc, tải chứng từ và bấm Tôi đã chuyển khoản. | TRANSFER_REPORTED; chưa REFUNDED/CLOSED. Người thuê xem chứng từ; chủ nhà không được tự xác nhận đã nhận tiền. |
| `2027-01-19T11:00:00` | Người thuê | Kiểm tra tài khoản ngân hàng thực tế; bấm Xác nhận đã nhận tiền, làm mới HĐ. | Payment CONFIRMED, cọc REFUNDED, HĐ CLOSED. PDF đã ký không bị thay thế; scheduler chạy lại không tạo QR mới. |
| `2027-02-15T10:00:00` | Hai bên | Mở hồ sơ đã CLOSED và danh sách hóa đơn. | Không hóa đơn tháng thuê tiếp theo/QR hoàn trùng. Không xóa chứng từ/PDF/hồ sơ cọc. |


---

## UC44 — Người thuê không đồng ý khấu trừ, sửa đề xuất rồi quyết toán

**Hợp đồng độc lập:** bắt đầu/ký ngày 16/10/2026, thời hạn ban đầu 3 tháng. Chạy đủ các hàng dưới, không ghép UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ MỚI riêng UC44, thuê 3 tháng từ 16/10/2026; trả RENT kỳ đầu + cọc, chủ nhà xác nhận; nhập công tơ đầu 1500/85; chốt phạt 100.000đ/ngày, graceDays=0 và hoàn cọc 15 ngày; kết xuất, ký đủ SmartCA. | ACTIVE, ngày cuối 15/01/2027; ghi lại ID gốc. Gọi cọc giữ là D. Nếu thuê 12.400.000đ, cọc 2 tháng thì D=24.800.000đ. Không dùng ID ca khác. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 2: điện 1550, nước 90. | DRAFT, dùng 50 kWh/5 m³. |
| `2026-11-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 2. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 2 PAID; gồm chi phí tháng 1 + tiền phòng tháng 2, không cọc. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 3: điện 1610, nước 96. | DRAFT, dùng 60 kWh/6 m³. |
| `2026-12-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 3. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 3 PAID; chi phí tháng 2 + tiền phòng tháng 3. Chưa được xin gia hạn thuê trước 17/12. |
| `2027-01-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn quyết toán cuối hợp đồng: điện 1660, nước 101. | DRAFT; dùng 50 kWh/5 m³. Không có văn bản gia hạn APPLIED. |
| `2027-01-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn quyết toán cuối hợp đồng; người thuê trả tổng bill và nộp chứng từ, chủ nhà xác nhận. | Hóa đơn quyết toán cuối hợp đồng PAID, chỉ chi phí tháng 3, không thu tiền phòng tháng 4. HĐ EXPIRED, có block bàn giao, tin/chỗ xe chưa giải phóng. |
| `2027-01-18T10:00:00` | Chủ nhà | Bỏ qua Ngoại lệ vì hóa đơn đã trả đủ. Thêm khoản 'Hỏng kính cửa' 300000đ và 'Mất chìa khóa' 200000đ; đính kèm ảnh/PDF cho khoản kính. Ghi nội dung, xác nhận bàn giao và không thu trùng, gửi đề xuất. | PROPOSED v1, tổng khấu trừ 500000, hoàn D−500000; chưa QR. Hạn phản hồi 20/01 10:00; từng khoản/minh chứng có trong đề xuất và lịch sử. |
| `2027-01-18T11:00:00` | Người thuê | Xem minh chứng; mở Chưa đồng ý · Gửi yêu cầu đối soát. Nhập 'Kính đã vỡ trước khi nhận nhà, tôi chỉ đồng ý khoản chìa khóa'; đính kèm ảnh nhận nhà, gửi. | DISPUTED, lý do/minh chứng hiển thị cho chủ nhà; hạn phản hồi chủ nhà 20/01 11:00. Chưa QR, cọc chưa hoàn, chưa mở lại tin. Không gửi lại khiếu nại trùng trên cùng đề xuất. |
| `2027-01-18T12:00:00` | Chủ nhà | Bấm Lấy nội dung đề xuất hiện tại để chỉnh sửa, xóa khoản kính, giữ chìa khóa 200000, ghi giải thích, xác nhận bàn giao và gửi lại. | PROPOSED v2; hoàn D−200000. Lịch sử giữ v1 và lý do/minh chứng khiếu nại. Tab đang mở v1 không thể xác nhận v2 bằng đồng ý cũ. |
| `2027-01-18T13:00:00` | Người thuê | Làm mới, đọc và tích đồng ý đúng v2, bấm Đồng ý quyết toán. | REFUND_PENDING, QR D−200000, hạn 02/02 13:00, giải phóng tin/chỗ xe. |
| `2027-01-19T10:00:00` | Chủ nhà | Chuyển đúng số tiền theo QR hoàn cọc, tải chứng từ và bấm Tôi đã chuyển khoản. | TRANSFER_REPORTED; chưa REFUNDED/CLOSED. Người thuê xem chứng từ; chủ nhà không được tự xác nhận đã nhận tiền. |
| `2027-01-19T11:00:00` | Người thuê | Kiểm tra tài khoản ngân hàng thực tế; bấm Xác nhận đã nhận tiền, làm mới HĐ. | Payment CONFIRMED, cọc REFUNDED, HĐ CLOSED. PDF đã ký không bị thay thế; scheduler chạy lại không tạo QR mới. |


---

## UC45 — Chưa thanh toán bill cuối thì chưa chốt hoàn cọc

**Hợp đồng độc lập:** bắt đầu/ký ngày 16/10/2026, thời hạn ban đầu 3 tháng. Chạy đủ các hàng dưới, không ghép UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ MỚI riêng UC45, thuê 3 tháng từ 16/10/2026; trả RENT kỳ đầu + cọc, chủ nhà xác nhận; nhập công tơ đầu 1500/85; chốt phạt 100.000đ/ngày, graceDays=0 và hoàn cọc 15 ngày; kết xuất, ký đủ SmartCA. | ACTIVE, ngày cuối 15/01/2027; ghi lại ID gốc. Gọi cọc giữ là D. Nếu thuê 12.400.000đ, cọc 2 tháng thì D=24.800.000đ. Không dùng ID ca khác. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 2: điện 1550, nước 90. | DRAFT, dùng 50 kWh/5 m³. |
| `2026-11-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 2. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 2 PAID; gồm chi phí tháng 1 + tiền phòng tháng 2, không cọc. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 3: điện 1610, nước 96. | DRAFT, dùng 60 kWh/6 m³. |
| `2026-12-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 3. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 3 PAID; chi phí tháng 2 + tiền phòng tháng 3. Chưa được xin gia hạn thuê trước 17/12. |
| `2027-01-15T10:00:00` | Chủ nhà | Lưu công tơ hóa đơn quyết toán cuối hợp đồng: 1660/101. | DRAFT. |
| `2027-01-16T10:00:00` | Hệ thống | Phát hành hóa đơn quyết toán cuối hợp đồng; người thuê chưa trả. | UNPAID, HĐ EXPIRED; QR bill cuối vẫn đúng, không tự trừ nợ khỏi cọc. |
| `2027-01-18T10:00:00` | Chủ nhà | Mở block Gia hạn & quyết toán HĐ khi hóa đơn quyết toán cuối hợp đồng chưa trả. | Bước 1 báo cần thanh toán; **không hiện form gửi đề xuất cọc**. Gọi API cố vượt cũng bị chặn. Không QR hoàn cọc, không mở lại tin. |
| `2027-01-18T11:00:00` | Người thuê | Mở Hóa đơn, thanh toán hóa đơn quyết toán cuối hợp đồng và gửi chứng từ; chủ nhà chưa xác nhận. | Chờ xác nhận, bước cọc vẫn khóa. Upload không đồng nghĩa PAID. |
| `2027-01-18T12:00:00` | Chủ nhà | Kiểm tra và xác nhận đã nhận đủ hóa đơn quyết toán cuối hợp đồng, làm mới HĐ. Nhận bàn giao, khấu trừ 0 và gửi đề xuất cọc. | Hóa đơn quyết toán cuối hợp đồng PAID; form cọc mở và đề xuất PROPOSED v1 được tạo; cọc D không giảm vì chi phí bill. |
| `2027-01-18T13:00:00` | Người thuê | Đọc đề xuất v1, đồng ý bàn giao/quyết toán. | REFUND_PENDING, hạn 02/02 13:00, QR D. |
| `2027-01-19T10:00:00` | Chủ nhà | Chuyển đúng số tiền theo QR hoàn cọc, tải chứng từ và bấm Tôi đã chuyển khoản. | TRANSFER_REPORTED; chưa REFUNDED/CLOSED. Người thuê xem chứng từ; chủ nhà không được tự xác nhận đã nhận tiền. |
| `2027-01-19T11:00:00` | Người thuê | Kiểm tra tài khoản ngân hàng thực tế; bấm Xác nhận đã nhận tiền, làm mới HĐ. | Payment CONFIRMED, cọc REFUNDED, HĐ CLOSED. PDF đã ký không bị thay thế; scheduler chạy lại không tạo QR mới. |


---

## UC46 — Chủ nhà hoàn cọc trễ, chứng từ bị từ chối rồi gửi lại

**Hợp đồng độc lập:** bắt đầu/ký ngày 16/10/2026, thời hạn ban đầu 3 tháng. Chạy đủ các hàng dưới, không ghép UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ MỚI riêng UC46, thuê 3 tháng từ 16/10/2026; trả RENT kỳ đầu + cọc, chủ nhà xác nhận; nhập công tơ đầu 1500/85; chốt phạt 100.000đ/ngày, graceDays=0 và hoàn cọc 15 ngày; kết xuất, ký đủ SmartCA. | ACTIVE, ngày cuối 15/01/2027; ghi lại ID gốc. Gọi cọc giữ là D. Nếu thuê 12.400.000đ, cọc 2 tháng thì D=24.800.000đ. Không dùng ID ca khác. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 2: điện 1550, nước 90. | DRAFT, dùng 50 kWh/5 m³. |
| `2026-11-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 2. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 2 PAID; gồm chi phí tháng 1 + tiền phòng tháng 2, không cọc. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 3: điện 1610, nước 96. | DRAFT, dùng 60 kWh/6 m³. |
| `2026-12-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 3. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 3 PAID; chi phí tháng 2 + tiền phòng tháng 3. Chưa được xin gia hạn thuê trước 17/12. |
| `2027-01-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn quyết toán cuối hợp đồng: điện 1660, nước 101. | DRAFT; dùng 50 kWh/5 m³. Không có văn bản gia hạn APPLIED. |
| `2027-01-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn quyết toán cuối hợp đồng; người thuê trả tổng bill và nộp chứng từ, chủ nhà xác nhận. | Hóa đơn quyết toán cuối hợp đồng PAID, chỉ chi phí tháng 3, không thu tiền phòng tháng 4. HĐ EXPIRED, có block bàn giao, tin/chỗ xe chưa giải phóng. |
| `2027-01-18T10:00:00` | Chủ nhà | Sau khi tất cả bill đã PAID và bàn giao thực tế xong, nhập khấu trừ 0, nội dung 'Không khấu trừ khác'; tích đã nhận phòng trống/chìa khóa, gửi đề xuất. | PROPOSED v1, chưa QR hoàn cọc, chưa giải phóng tin. Điện/nước đã thanh toán trên hóa đơn, không trừ vào cọc. |
| `2027-01-18T11:00:00` | Người thuê | Đọc đúng đề xuất, tích đồng ý bàn giao/khấu trừ và bấm Đồng ý quyết toán. | REFUND_PENDING, QR tài khoản hoàn cọc người thuê, hướng chủ nhà → người thuê. Hạn 02/02 11:00; tin/chỗ xe giải phóng, HĐ chưa CLOSED. |
| `2027-02-02T11:00:01` | Hệ thống / hai bên | Chủ nhà chưa chuyển tiền, làm mới. | Payment OVERDUE, HĐ vẫn EXPIRED/REFUND_PENDING. QR còn hiệu lực, không tự CLOSED/giữ cọc hoặc tính phí phạt thuê cho khoản hoàn. |
| `2027-02-03T10:00:00` | Chủ nhà | Gửi chứng từ hoàn tiền để thử luồng. | TRANSFER_REPORTED, chưa đóng hồ sơ. |
| `2027-02-03T11:00:00` | Người thuê | Chưa nhận tiền thực tế, nhập lý do rõ ràng rồi Từ chối chứng từ. | REJECTED hoặc OVERDUE sau scheduler; cọc REFUND_DUE. Chủ nhà được gửi lại, không tự xác nhận. |
| `2027-02-04T10:00:00` | Chủ nhà | Kiểm tra/chuyển đúng khoản còn phải trả; gửi chứng từ đúng. | TRANSFER_REPORTED; vẫn cùng PaymentRequest/reference, không tạo nghĩa vụ thứ hai. Không chuyển trùng khoản đã thực nhận. |
| `2027-02-04T11:00:00` | Người thuê | Nhận đủ tiền thực tế, Xác nhận đã nhận tiền. | CONFIRMED/REFUNDED, HĐ CLOSED sau làm mới/scheduler. |


---

## UC47 — Khấu trừ lớn hơn cọc: người thuê trả phần thiếu

**Hợp đồng độc lập:** bắt đầu/ký ngày 16/10/2026, thời hạn ban đầu 3 tháng. Chạy đủ các hàng dưới, không ghép UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ MỚI riêng UC47, thuê 3 tháng từ 16/10/2026; trả RENT kỳ đầu + cọc, chủ nhà xác nhận; nhập công tơ đầu 1500/85; chốt phạt 100.000đ/ngày, graceDays=0 và hoàn cọc 15 ngày; kết xuất, ký đủ SmartCA. | ACTIVE, ngày cuối 15/01/2027; ghi lại ID gốc. Gọi cọc giữ là D. Nếu thuê 12.400.000đ, cọc 2 tháng thì D=24.800.000đ. Không dùng ID ca khác. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 2: điện 1550, nước 90. | DRAFT, dùng 50 kWh/5 m³. |
| `2026-11-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 2. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 2 PAID; gồm chi phí tháng 1 + tiền phòng tháng 2, không cọc. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 3: điện 1610, nước 96. | DRAFT, dùng 60 kWh/6 m³. |
| `2026-12-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 3. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 3 PAID; chi phí tháng 2 + tiền phòng tháng 3. Chưa được xin gia hạn thuê trước 17/12. |
| `2027-01-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn quyết toán cuối hợp đồng: điện 1660, nước 101. | DRAFT; dùng 50 kWh/5 m³. Không có văn bản gia hạn APPLIED. |
| `2027-01-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn quyết toán cuối hợp đồng; người thuê trả tổng bill và nộp chứng từ, chủ nhà xác nhận. | Hóa đơn quyết toán cuối hợp đồng PAID, chỉ chi phí tháng 3, không thu tiền phòng tháng 4. HĐ EXPIRED, có block bàn giao, tin/chỗ xe chưa giải phóng. |
| `2027-01-18T10:00:00` | Chủ nhà | Sau khi tất cả bill đã PAID và bàn giao thực tế xong, nhập khấu trừ D + 100000 (nhập số tiền cụ thể), nội dung 'Hai bên thống nhất bồi hoàn tài sản D cộng 100.000đ; ghi căn cứ cụ thể'; tích đã nhận phòng trống/chìa khóa, gửi đề xuất. | PROPOSED v1, chưa QR hoàn cọc, chưa giải phóng tin. Điện/nước đã thanh toán trên hóa đơn, không trừ vào cọc. |
| `2027-01-18T11:00:00` | Người thuê | Kiểm tra khoản bồi hoàn, xác nhận bàn giao và đồng ý quyết toán. | BALANCE_PENDING; hoàn 0, trả thêm 100000; cọc RETAINED_BY_LANDLORD theo thỏa thuận. QR tài khoản chủ nhà, hướng người thuê → chủ nhà; hạn 02/02 11:00. |
| `2027-01-19T10:00:00` | Người thuê | Chuyển 100000 theo QR quyết toán, gửi chứng từ. | TRANSFER_REPORTED; người thuê không tự xác nhận nhận tiền. |
| `2027-01-19T11:00:00` | Chủ nhà | Kiểm tra đã nhận đúng phần thiếu rồi xác nhận. | CONFIRMED, HĐ CLOSED. Không đồng thời QR hoàn cọc. |


---

## UC48 — Khấu trừ đúng bằng cọc: quyết toán 0 đồng

**Hợp đồng độc lập:** bắt đầu/ký ngày 16/10/2026, thời hạn ban đầu 3 tháng. Chạy đủ các hàng dưới, không ghép UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ MỚI riêng UC48, thuê 3 tháng từ 16/10/2026; trả RENT kỳ đầu + cọc, chủ nhà xác nhận; nhập công tơ đầu 1500/85; chốt phạt 100.000đ/ngày, graceDays=0 và hoàn cọc 15 ngày; kết xuất, ký đủ SmartCA. | ACTIVE, ngày cuối 15/01/2027; ghi lại ID gốc. Gọi cọc giữ là D. Nếu thuê 12.400.000đ, cọc 2 tháng thì D=24.800.000đ. Không dùng ID ca khác. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 2: điện 1550, nước 90. | DRAFT, dùng 50 kWh/5 m³. |
| `2026-11-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 2. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 2 PAID; gồm chi phí tháng 1 + tiền phòng tháng 2, không cọc. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 3: điện 1610, nước 96. | DRAFT, dùng 60 kWh/6 m³. |
| `2026-12-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 3. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 3 PAID; chi phí tháng 2 + tiền phòng tháng 3. Chưa được xin gia hạn thuê trước 17/12. |
| `2027-01-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn quyết toán cuối hợp đồng: điện 1660, nước 101. | DRAFT; dùng 50 kWh/5 m³. Không có văn bản gia hạn APPLIED. |
| `2027-01-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn quyết toán cuối hợp đồng; người thuê trả tổng bill và nộp chứng từ, chủ nhà xác nhận. | Hóa đơn quyết toán cuối hợp đồng PAID, chỉ chi phí tháng 3, không thu tiền phòng tháng 4. HĐ EXPIRED, có block bàn giao, tin/chỗ xe chưa giải phóng. |
| `2027-01-18T10:00:00` | Chủ nhà | Sau khi tất cả bill đã PAID và bàn giao thực tế xong, nhập khấu trừ D (nhập số tiền cọc cụ thể), nội dung 'Hai bên thống nhất khấu trừ đúng D; ghi đầy đủ căn cứ'; tích đã nhận phòng trống/chìa khóa, gửi đề xuất. | PROPOSED v1, chưa QR hoàn cọc, chưa giải phóng tin. Điện/nước đã thanh toán trên hóa đơn, không trừ vào cọc. |
| `2027-01-18T11:00:00` | Người thuê | Đọc và đồng ý bàn giao/khấu trừ đúng cọc. | Hoàn 0, trả thêm 0, không QR 0đ. CLOSED, giải phóng tin/chỗ xe. Không dùng phương án này nếu chưa thống nhất khấu trừ. |
| `2027-01-19T10:00:00` | Hai bên | Làm mới lại hồ sơ. | Vẫn CLOSED, không tạo payment/lần khấu trừ trùng. |


---

## UC49 — Công tơ kỳ cuối tự điền 0: bổ sung lượng thực dùng khi trả nhà

**Hợp đồng độc lập:** bắt đầu/ký ngày 16/10/2026, thời hạn ban đầu 3 tháng. Chạy đủ các hàng dưới, không ghép UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ MỚI riêng UC49, thuê 3 tháng từ 16/10/2026; trả RENT kỳ đầu + cọc, chủ nhà xác nhận; nhập công tơ đầu 1500/85; chốt phạt 100.000đ/ngày, graceDays=0 và hoàn cọc 15 ngày; kết xuất, ký đủ SmartCA. | ACTIVE, ngày cuối 15/01/2027; ghi lại ID gốc. Gọi cọc giữ là D. Nếu thuê 12.400.000đ, cọc 2 tháng thì D=24.800.000đ. Không dùng ID ca khác. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 2: điện 1550, nước 90. | DRAFT, dùng 50 kWh/5 m³. |
| `2026-11-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 2. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 2 PAID; gồm chi phí tháng 1 + tiền phòng tháng 2, không cọc. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 3: điện 1610, nước 96. | DRAFT, dùng 60 kWh/6 m³. |
| `2026-12-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 3. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 3 PAID; chi phí tháng 2 + tiền phòng tháng 3. Chưa được xin gia hạn thuê trước 17/12. |
| `2027-01-15T10:00:00` | Chủ nhà | Không lưu công tơ hóa đơn quyết toán cuối hợp đồng nháp. | Còn cửa sổ 24 giờ; đầu kỳ 1610/96. |
| `2027-01-16T10:00:00` | Hệ thống → hai bên | Hóa đơn quyết toán cuối hợp đồng tự điền cuối 1610/96, phát hành; người thuê trả tổng bill thực tế và chủ nhà xác nhận (bill tổng 0 thì PAID không QR). | Điện/nước 0, có cảnh báo tự điền. HĐ EXPIRED, cọc D; các bill đủ PAID. |
| `2027-01-18T10:00:00` | Chủ nhà | Ở Bước 1 của block quyết toán, nhập công tơ thực tế 1660/101 rồi bấm Phát hành hóa đơn bổ sung kỳ cuối. Không nhập điện/nước vào form cọc. | Có bill **Bổ sung chi phí kỳ cuối** trong mục Hóa đơn: thêm 50 kWh/5 m³. Với giá 3500/16000 là 255000đ, nếu giá khác lấy đúng bản ký. Không RENT/dịch vụ lặp, không sửa bill đã PAID. Cọc vẫn D, bước cọc lại khóa; hạn bill bổ sung 22/01 23:59:59. |
| `2027-01-18T10:15:00` | Người thuê | Mở mục Hóa đơn, chuyển tiền bill bổ sung và gửi chứng từ. | Chờ xác nhận, không được chốt cọc. |
| `2027-01-18T10:30:00` | Chủ nhà | Xác nhận thực nhận tiền bill bổ sung; làm mới HĐ. | Bill bổ sung PAID; không coi đây là tháng thuê thứ 4. Bước cọc mở. |
| `2027-01-18T10:45:00` | Chủ nhà | Bàn giao xong, đề xuất khấu trừ cọc khác 0, nhập nội dung hợp lệ rồi gửi. | PROPOSED, hoàn nguyên D. Không trừ 255000đ lần nữa vì bill bổ sung đã thanh toán. |
| `2027-01-18T11:00:00` | Người thuê | Đọc đúng đề xuất, tích đồng ý bàn giao/khấu trừ và bấm Đồng ý quyết toán. | REFUND_PENDING, QR tài khoản hoàn cọc người thuê, hướng chủ nhà → người thuê. Hạn 02/02 11:00; tin/chỗ xe giải phóng, HĐ chưa CLOSED. |
| `2027-01-19T10:00:00` | Chủ nhà | Chuyển đúng số tiền theo QR hoàn cọc, tải chứng từ và bấm Tôi đã chuyển khoản. | TRANSFER_REPORTED; chưa REFUNDED/CLOSED. Người thuê xem chứng từ; chủ nhà không được tự xác nhận đã nhận tiền. |
| `2027-01-19T11:00:00` | Người thuê | Kiểm tra tài khoản ngân hàng thực tế; bấm Xác nhận đã nhận tiền, làm mới HĐ. | Payment CONFIRMED, cọc REFUNDED, HĐ CLOSED. PDF đã ký không bị thay thế; scheduler chạy lại không tạo QR mới. |


---

## UC50 — Chỉ chủ nhà ký, người thuê không ký gia hạn

**Hợp đồng độc lập:** bắt đầu/ký ngày 16/10/2026, thời hạn ban đầu 3 tháng. Chạy đủ các hàng dưới, không ghép UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ MỚI riêng UC50, thuê 3 tháng từ 16/10/2026; trả RENT kỳ đầu + cọc, chủ nhà xác nhận; nhập công tơ đầu 1500/85; chốt phạt 100.000đ/ngày, graceDays=0 và hoàn cọc 15 ngày; kết xuất, ký đủ SmartCA. | ACTIVE, ngày cuối 15/01/2027; ghi lại ID gốc. Gọi cọc giữ là D. Nếu thuê 12.400.000đ, cọc 2 tháng thì D=24.800.000đ. Không dùng ID ca khác. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 2: điện 1550, nước 90. | DRAFT, dùng 50 kWh/5 m³. |
| `2026-11-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 2. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 2 PAID; gồm chi phí tháng 1 + tiền phòng tháng 2, không cọc. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 3: điện 1610, nước 96. | DRAFT, dùng 60 kWh/6 m³. |
| `2026-12-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 3. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 3 PAID; chi phí tháng 2 + tiền phòng tháng 3. Chưa được xin gia hạn thuê trước 17/12. |
| `2026-12-17T10:00:00` | Người thuê | Xin thêm 3 tháng. | REQUESTED. |
| `2026-12-18T10:00:00` | Chủ nhà | Duyệt, mở văn bản mới, kết xuất và ký SmartCA, chờ PDF chữ ký chủ nhà READY. | APPROVED, văn bản TENANT_SIGNATURE_PENDING. Chưa APPLIED, chưa thu tiền kỳ 4. |
| `2027-01-15T00:01:00` | Hệ thống | Người thuê không ký; chờ scheduler. | Yêu cầu EXPIRED, văn bản CANCELLED, không kéo dài ngày thuê; giữ bản PDF một chữ ký để đối soát, không xóa/sửa chữ ký. |
| `2027-01-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn quyết toán cuối hợp đồng: điện 1660, nước 101. | DRAFT; dùng 50 kWh/5 m³. Không có văn bản gia hạn APPLIED. |
| `2027-01-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn quyết toán cuối hợp đồng; người thuê trả tổng bill và nộp chứng từ, chủ nhà xác nhận. | Hóa đơn quyết toán cuối hợp đồng PAID, chỉ chi phí tháng 3, không thu tiền phòng tháng 4. HĐ EXPIRED, có block bàn giao, tin/chỗ xe chưa giải phóng. |
| `2027-01-18T10:00:00` | Chủ nhà | Sau khi tất cả bill đã PAID và bàn giao thực tế xong, nhập khấu trừ 0, nội dung 'Không khấu trừ khác'; tích đã nhận phòng trống/chìa khóa, gửi đề xuất. | PROPOSED v1, chưa QR hoàn cọc, chưa giải phóng tin. Điện/nước đã thanh toán trên hóa đơn, không trừ vào cọc. |
| `2027-01-18T11:00:00` | Người thuê | Đọc đúng đề xuất, tích đồng ý bàn giao/khấu trừ và bấm Đồng ý quyết toán. | REFUND_PENDING, QR tài khoản hoàn cọc người thuê, hướng chủ nhà → người thuê. Hạn 02/02 11:00; tin/chỗ xe giải phóng, HĐ chưa CLOSED. |
| `2027-01-19T10:00:00` | Chủ nhà | Chuyển đúng số tiền theo QR hoàn cọc, tải chứng từ và bấm Tôi đã chuyển khoản. | TRANSFER_REPORTED; chưa REFUNDED/CLOSED. Người thuê xem chứng từ; chủ nhà không được tự xác nhận đã nhận tiền. |
| `2027-01-19T11:00:00` | Người thuê | Kiểm tra tài khoản ngân hàng thực tế; bấm Xác nhận đã nhận tiền, làm mới HĐ. | Payment CONFIRMED, cọc REFUNDED, HĐ CLOSED. PDF đã ký không bị thay thế; scheduler chạy lại không tạo QR mới. |


---

## UC51 — Hết hạn nhưng chưa bàn giao: không tự mở lại tin hoặc hoàn cọc

**Hợp đồng độc lập:** bắt đầu/ký ngày 16/10/2026, thời hạn ban đầu 3 tháng. Chạy đủ các hàng dưới, không ghép UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ MỚI riêng UC51, thuê 3 tháng từ 16/10/2026; trả RENT kỳ đầu + cọc, chủ nhà xác nhận; nhập công tơ đầu 1500/85; chốt phạt 100.000đ/ngày, graceDays=0 và hoàn cọc 15 ngày; kết xuất, ký đủ SmartCA. | ACTIVE, ngày cuối 15/01/2027; ghi lại ID gốc. Gọi cọc giữ là D. Nếu thuê 12.400.000đ, cọc 2 tháng thì D=24.800.000đ. Không dùng ID ca khác. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 2: điện 1550, nước 90. | DRAFT, dùng 50 kWh/5 m³. |
| `2026-11-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 2. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 2 PAID; gồm chi phí tháng 1 + tiền phòng tháng 2, không cọc. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn kỳ thanh toán 3: điện 1610, nước 96. | DRAFT, dùng 60 kWh/6 m³. |
| `2026-12-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn kỳ thanh toán 3. Người thuê chuyển tổng bill, nộp chứng từ; chủ nhà xác nhận đủ tiền. | Hóa đơn kỳ thanh toán 3 PAID; chi phí tháng 2 + tiền phòng tháng 3. Chưa được xin gia hạn thuê trước 17/12. |
| `2027-01-15T10:00:00` | Chủ nhà | Lưu công tơ cuối hóa đơn quyết toán cuối hợp đồng: điện 1660, nước 101. | DRAFT; dùng 50 kWh/5 m³. Không có văn bản gia hạn APPLIED. |
| `2027-01-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Phát hành hóa đơn quyết toán cuối hợp đồng; người thuê trả tổng bill và nộp chứng từ, chủ nhà xác nhận. | Hóa đơn quyết toán cuối hợp đồng PAID, chỉ chi phí tháng 3, không thu tiền phòng tháng 4. HĐ EXPIRED, có block bàn giao, tin/chỗ xe chưa giải phóng. |
| `2027-01-18T10:00:00` | Hai bên | Không đề xuất/xác nhận bàn giao, chỉ xem trang HĐ. | EXPIRED/WAITING_HANDOVER, cọc HELD D; không QR hoàn, không CLOSED; tin/chỗ xe vẫn chưa được giải phóng theo xác nhận. |
| `2027-02-15T10:00:00` | Hai bên | Không thao tác tiếp, làm mới trang. | Không hóa đơn tháng thuê tiếp theo, không tự chiếm nghĩa vụ thuê mới, không tự xác nhận trả nhà; cần hai bên giải quyết bàn giao/quyết toán thực tế. |

---

## UC52 — Nhảy thẳng hai tháng: cộng dồn và phạt theo ngày tối đa 10 ngày

**Hợp đồng độc lập:** bắt đầu/ký ngày 16/10/2026, thuê đúng 3 tháng. Không dùng HĐ đã tạo bill ở phiên bản cũ, không chạy các mốc tháng 11.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ MỚI UC52; thuê 3 tháng từ 16/10; thanh toán tiền phòng tháng 1 + cọc, chủ nhà xác nhận; lưu công tơ đầu 1500/85, phạt 100.000đ/ngày, graceDays=0, không trần riêng; kết xuất và ký đủ SmartCA. | ACTIVE; chưa bill tháng. Ngày cuối thuê 15/01/2027. Ghi ID riêng. |
| `2026-12-16T00:01:00` | Người test / hệ thống | Nhảy TRỰC TIẾP từ 16/10 tới mốc này; restart Core, chờ scheduler, mở đúng HĐ ở hai tài khoản. Không nhập công tơ/thanh toán/gửi yêu cầu ở tháng 11. | Hai bill tự phát hành theo thứ tự. Hóa đơn kỳ thanh toán 2 giữ hạn 20/11 23:59:59; điện 1500→1500, nước 85→85, có cảnh báo tự điền. Phạt 100.000 × 10 ngày = 1.000.000đ; một quyết định AUTOMATIC_ROLLOVER; trạng thái ROLLED_OVER và QR cũ bị hủy. Hóa đơn kỳ thanh toán 3 giữ hạn 20/12 23:59:59, chỉ một dòng BALANCE_FORWARD gồm tổng hóa đơn kỳ thanh toán 2 + phí đã chốt. |
| `2026-12-16T10:00:00` | Hai bên | Refresh/restart nhiều lần, kiểm tra tổng chưa xác nhận và QR. | Chỉ hóa đơn kỳ thanh toán 3 yêu cầu thanh toán. Tổng = chi phí tháng 1 + tiền phòng tháng 2 + 1 triệu phạt + chi phí tháng 2 + tiền phòng tháng 3. Không cộng lại hóa đơn kỳ thanh toán 2 vào tổng lần nữa; không nhân đôi phí/phát hành/QR. Nếu mỗi bill gốc 24.470.000đ thì tổng hóa đơn kỳ thanh toán 3 là 49.940.000đ. Hóa đơn kỳ thanh toán 3 chưa bị phạt tại mốc này. |
| `2026-12-18T10:00:00` | Người thuê | Chuyển đúng tổng hóa đơn kỳ thanh toán 3 theo QR mới và gửi chứng từ. | Hóa đơn kỳ thanh toán 3 chờ xác nhận; hóa đơn kỳ thanh toán 2 vẫn đã chuyển nợ, không thanh toán riêng. |
| `2026-12-18T11:00:00` | Chủ nhà | Kiểm tra tiền thực nhận và xác nhận hóa đơn kỳ thanh toán 3. | Hóa đơn kỳ thanh toán 3 PAID; tổng chưa xác nhận bằng 0; không phát sinh lại nợ hóa đơn kỳ thanh toán 2. Không hoàn cọc chỉ vì đã trả hóa đơn kỳ thanh toán 3, vẫn còn kỳ thuê 3. |

---

## UC53 — Nhảy thẳng hai tháng: cộng dồn và phạt cố định một lần

**Hợp đồng độc lập:** bắt đầu/ký ngày 16/10/2026, thuê đúng 3 tháng. Không ghép dữ liệu UC52.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ MỚI UC53; thuê 3 tháng từ 16/10; trả tiền phòng tháng 1 + cọc, chủ nhà xác nhận; lưu công tơ 1500/85; chọn phạt CỐ ĐỊNH MỘT LẦN 300.000đ, graceDays=0; kết xuất và ký đủ SmartCA. | ACTIVE; chính sách bản ký FIXED_ONCE, không FIXED_PER_DAY; chưa bill tháng. |
| `2026-12-16T00:01:00` | Người test / hệ thống | Nhảy trực tiếp từ 16/10 tới mốc này, restart và chờ scheduler. Cả hai bên không thao tác ở tháng 11. | Hóa đơn kỳ thanh toán 2 tự điền công tơ 1500/85, giữ hạn 20/11, phạt 300.000đ × 1 lần (KHÔNG 300.000 × 10), ROLLED_OVER. Hóa đơn kỳ thanh toán 3 nhận đúng một khoản nợ, hạn 20/12; chỉ QR hóa đơn kỳ thanh toán 3 còn hiệu lực. |
| `2026-12-16T10:00:00` | Hai bên | Restart/refresh, đối chiếu tiền. | Tổng hóa đơn kỳ thanh toán 3 = tổng hai bill gốc + 300.000đ. Nếu mỗi bill gốc 24.470.000đ thì tổng 49.240.000đ. Không thu lại cọc hoặc tiền phòng tháng 1. |
| `2026-12-18T10:00:00` | Người thuê | Thanh toán hóa đơn kỳ thanh toán 3 và gửi chứng từ theo QR mới. | Chờ chủ nhà xác nhận; hóa đơn kỳ thanh toán 2 không mở lại QR. |
| `2026-12-18T11:00:00` | Chủ nhà | Xác nhận đủ tiền thực nhận. | Hóa đơn kỳ thanh toán 3 PAID, công nợ đã chuyển được tất toán; không phạt thêm lần thứ hai cho hóa đơn kỳ thanh toán 2. |

---

## UC54 — Nhảy qua hết hợp đồng: chuỗi công nợ vào bill cuối, không thu tiền phòng tháng 4

**Hợp đồng độc lập:** bắt đầu/ký ngày 16/10/2026, thuê đúng 3 tháng. Không chạy mốc trung gian hoặc dùng HĐ UC khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ MỚI UC54; thuê 3 tháng từ 16/10; trả tiền phòng tháng 1 + cọc, chủ nhà xác nhận; lưu công tơ 1500/85, phạt 100.000đ/ngày, graceDays=0, không trần riêng; ký đủ SmartCA. | ACTIVE; chưa bill tháng; ngày cuối thuê 15/01/2027. |
| `2027-01-31T00:01:00` | Người test / hệ thống | Nhảy trực tiếp từ 16/10 đến mốc này; restart, chờ scheduler. Không nhập số, gửi chứng từ/yêu cầu hay quyết định ở bất kỳ tháng nào. | HĐ EXPIRED. Tạo đủ đúng 3 hóa đơn sau khoản thanh toán ban đầu: hóa đơn kỳ thanh toán 2 giữ hạn 20/11, phạt 10 ngày rồi chuyển nợ vào hóa đơn kỳ thanh toán 3; hóa đơn kỳ thanh toán 3 giữ hạn 20/12, phạt tối đa 10 ngày rồi chuyển toàn bộ vào hóa đơn quyết toán cuối hợp đồng. Hóa đơn quyết toán cuối hợp đồng giữ hạn 20/01, OVERDUE, phạt tối đa 10 ngày; không có kỳ sau để chuyển. Công tơ cả ba kỳ tự điền 1500/85; hóa đơn quyết toán cuối hợp đồng KHÔNG thu tiền phòng tháng 4. |
| `2027-01-31T10:00:00` | Hai bên | Restart/refresh nhiều lần, kiểm tra hóa đơn và quyết toán. | Chỉ hóa đơn quyết toán cuối hợp đồng có QR/nợ cần trả. Tổng = hai hóa đơn kỳ thanh toán 2, 3 và hóa đơn quyết toán cuối (chỉ chi phí) + phí hóa đơn kỳ thanh toán 2 + phí hóa đơn kỳ thanh toán 3 + phí hóa đơn quyết toán cuối hợp đồng, mỗi phí 1 triệu nếu áp dụng như trên. Mỗi bước chỉ một BALANCE_FORWARD; không cộng trùng tổng bill cũ. Không tạo hóa đơn tháng thuê tiếp theo, không tự khấu trừ nợ vào cọc, không tự hoàn cọc/CLOSED. |
| `2027-02-01T10:00:00` | Người thuê | Chuyển đủ tổng hóa đơn quyết toán cuối hợp đồng, gửi chứng từ. | Chờ xác nhận; vẫn chưa được chốt hoàn cọc. |
| `2027-02-01T11:00:00` | Chủ nhà | Xác nhận đã nhận đủ tiền hóa đơn quyết toán cuối hợp đồng. | Hóa đơn quyết toán cuối hợp đồng PAID, các nguồn đã chuyển nợ được tất toán theo chuỗi. Có thể tiếp tục bàn giao/quyết toán; KHÔNG cần trả riêng hóa đơn kỳ thanh toán 2, hóa đơn kỳ thanh toán 3. |


---

## UC55 — Hợp đồng 12 tháng: đủ 12 kỳ tiền phòng, quyết toán cuối rồi hoàn cọc

**Hợp đồng độc lập:** tạo/ký từ **16/10/2026**, thuê đúng **12 tháng**, không dùng hợp đồng của UC01–UC54. Chọn điện theo kWh, nước theo m³, đơn giá đã chốt, WiFi/dịch vụ theo tháng; không phí trả trước khác. Không gửi yêu cầu gia hạn trong ca này, chỉ kiểm tra nút mở. Mỗi mốc phải sửa homespace.time.simulated-at, restart backend và chờ đồng bộ. Chọn công tơ ban đầu 1500/85.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo yêu cầu thuê 12 tháng, chủ nhà duyệt; trả cọc + tiền phòng tháng 1, chủ nhà xác nhận; lưu công tơ 1500/85, xuất PDF, ký đủ hai bên. | ACTIVE. Kỳ thanh toán 1 hiển thị đúng khoản INITIAL. Hết thuê trước 16/10/2027; chưa có bill tháng và chưa mở gia hạn. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ cuối tháng 1: điện 1550, nước 90. | Nháp kỳ thanh toán 2; dùng 50 kWh và 5 m³. |
| `2026-11-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Chờ phát hành; chuyển đủ tổng tiền, gửi chứng từ và chủ nhà xác nhận. | Kỳ thanh toán 2 PAID: tiền phòng tháng 2 + chi phí tháng 1; hạn gốc 20/11/2026 23:59:59. Không thu lại cọc. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ cuối tháng 2: điện 1600, nước 95. | Nháp kỳ thanh toán 3; dùng 50 kWh và 5 m³. |
| `2026-12-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Chờ phát hành; chuyển đủ tổng tiền, gửi chứng từ và chủ nhà xác nhận. | Kỳ thanh toán 3 PAID: tiền phòng tháng 3 + chi phí tháng 2; hạn gốc 20/12/2026 23:59:59. Không thu lại cọc. |
| `2027-01-15T10:00:00` | Chủ nhà | Lưu công tơ cuối tháng 3: điện 1650, nước 100. | Nháp kỳ thanh toán 4; dùng 50 kWh và 5 m³. |
| `2027-01-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Chờ phát hành; chuyển đủ tổng tiền, gửi chứng từ và chủ nhà xác nhận. | Kỳ thanh toán 4 PAID: tiền phòng tháng 4 + chi phí tháng 3; hạn gốc 20/01/2027 23:59:59. Không thu lại cọc. |
| `2027-02-15T10:00:00` | Chủ nhà | Lưu công tơ cuối tháng 4: điện 1700, nước 105. | Nháp kỳ thanh toán 5; dùng 50 kWh và 5 m³. |
| `2027-02-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Chờ phát hành; chuyển đủ tổng tiền, gửi chứng từ và chủ nhà xác nhận. | Kỳ thanh toán 5 PAID: tiền phòng tháng 5 + chi phí tháng 4; hạn gốc 20/02/2027 23:59:59. Không thu lại cọc. |
| `2027-03-15T10:00:00` | Chủ nhà | Lưu công tơ cuối tháng 5: điện 1750, nước 110. | Nháp kỳ thanh toán 6; dùng 50 kWh và 5 m³. |
| `2027-03-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Chờ phát hành; chuyển đủ tổng tiền, gửi chứng từ và chủ nhà xác nhận. | Kỳ thanh toán 6 PAID: tiền phòng tháng 6 + chi phí tháng 5; hạn gốc 20/03/2027 23:59:59. Không thu lại cọc. |
| `2027-04-15T10:00:00` | Chủ nhà | Lưu công tơ cuối tháng 6: điện 1800, nước 115. | Nháp kỳ thanh toán 7; dùng 50 kWh và 5 m³. |
| `2027-04-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Chờ phát hành; chuyển đủ tổng tiền, gửi chứng từ và chủ nhà xác nhận. | Kỳ thanh toán 7 PAID: tiền phòng tháng 7 + chi phí tháng 6; hạn gốc 20/04/2027 23:59:59. Không thu lại cọc. |
| `2027-05-15T10:00:00` | Chủ nhà | Lưu công tơ cuối tháng 7: điện 1850, nước 120. | Nháp kỳ thanh toán 8; dùng 50 kWh và 5 m³. |
| `2027-05-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Chờ phát hành; chuyển đủ tổng tiền, gửi chứng từ và chủ nhà xác nhận. | Kỳ thanh toán 8 PAID: tiền phòng tháng 8 + chi phí tháng 7; hạn gốc 20/05/2027 23:59:59. Không thu lại cọc. |
| `2027-06-15T10:00:00` | Chủ nhà | Lưu công tơ cuối tháng 8: điện 1900, nước 125. | Nháp kỳ thanh toán 9; dùng 50 kWh và 5 m³. |
| `2027-06-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Chờ phát hành; chuyển đủ tổng tiền, gửi chứng từ và chủ nhà xác nhận. | Kỳ thanh toán 9 PAID: tiền phòng tháng 9 + chi phí tháng 8; hạn gốc 20/06/2027 23:59:59. Không thu lại cọc. |
| `2027-07-15T10:00:00` | Chủ nhà | Lưu công tơ cuối tháng 9: điện 1950, nước 130. | Nháp kỳ thanh toán 10; dùng 50 kWh và 5 m³. |
| `2027-07-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Chờ phát hành; chuyển đủ tổng tiền, gửi chứng từ và chủ nhà xác nhận. | Kỳ thanh toán 10 PAID: tiền phòng tháng 10 + chi phí tháng 9; hạn gốc 20/07/2027 23:59:59. Không thu lại cọc. |
| `2027-08-15T10:00:00` | Chủ nhà | Lưu công tơ cuối tháng 10: điện 2000, nước 135. | Nháp kỳ thanh toán 11; dùng 50 kWh và 5 m³. |
| `2027-08-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Chờ phát hành; chuyển đủ tổng tiền, gửi chứng từ và chủ nhà xác nhận. | Kỳ thanh toán 11 PAID: tiền phòng tháng 11 + chi phí tháng 10; hạn gốc 20/08/2027 23:59:59. Không thu lại cọc. |
| `2027-09-15T10:00:00` | Chủ nhà / người thuê | Chủ nhà lưu công tơ cuối tháng 11: điện 2050, nước 140. Người thuê mở chi tiết hợp đồng. | Nháp kỳ thanh toán 12; dùng 50 kWh và 5 m³. Nút gia hạn vẫn khóa, ghi sẽ mở 16/09 00:00. |
| `2027-09-16T10:00:00` | Hệ thống → người thuê → chủ nhà | Chờ phát hành; chuyển đủ tổng tiền, gửi chứng từ và chủ nhà xác nhận. | Kỳ thanh toán 12 PAID: tiền phòng tháng 12 + chi phí tháng 11; hạn gốc 20/09/2027 23:59:59. Không thu lại cọc. |
| `2027-09-16T10:05:00` | Người thuê | Mở chi tiết HĐ → Gia hạn & quyết toán, không gửi yêu cầu. | Nút gia hạn đã mở từ 16/09 00:00, dù tiền phòng tháng 12 đã trả. Không hoàn cọc vì chưa đến cuối tháng 12, chưa thanh toán chi phí tháng 12. |
| `2027-10-14T23:59:59` | Người thuê | Mở chi tiết HĐ; vẫn không gửi yêu cầu. | Cửa sổ yêu cầu/ký chưa đóng; không tự gia hạn. |
| `2027-10-15T00:01:00` | Hai bên | Chờ đồng bộ, mở chi tiết HĐ/hóa đơn. | Hết hạn ký gia hạn; nháp **Quyết toán cuối hợp đồng** xuất hiện cho chi phí tháng 12. Không có tiền phòng tháng 13. |
| `2027-10-15T10:00:00` | Chủ nhà | Lưu điện cuối 2100, nước 145; nhập phát sinh cuối nếu có. | So với lần trước 2050/140: dùng 50 kWh/5 m³. Chỉ chi phí tháng 12 (16/09–trước 16/10), không RENT tháng 13. |
| `2027-10-16T00:01:00` | Hệ thống | Chờ phát hành, không thanh toán ngay. | HĐ EXPIRED; bill quyết toán cuối có điện/nước/dịch vụ/phát sinh, hạn 20/10 23:59:59. Bước chốt cọc bị khóa. |
| `2027-10-16T10:00:00` | Người thuê | Thanh toán đủ bill cuối, gửi chứng từ. | Chờ chủ nhà xác nhận; vẫn khóa chốt cọc. |
| `2027-10-16T11:00:00` | Chủ nhà | Kiểm tra tiền thực nhận, xác nhận bill cuối. | PAID; đã đủ 12 tháng chi phí và đúng 12 tháng tiền phòng kể cả INITIAL. Không thêm cọc, không thêm tiền phòng tháng 13. |
| `2027-10-17T10:00:00` | Chủ nhà | Sau bàn giao phòng/chìa khóa/tài sản, khấu trừ khác 0; ghi nội dung rõ, tích xác nhận bàn giao, gửi đề xuất. | PROPOSED, chưa QR hoàn cọc. Điện/nước đã trả ở hóa đơn nên không trừ lại cọc. Nếu phát hiện chi phí chưa thu, phải phát hành/thanh toán hóa đơn bổ sung trước bước này. |
| `2027-10-17T11:00:00` | Người thuê | Đồng ý đúng phiên bản đề xuất quyết toán. | REFUND_PENDING, QR tài khoản người thuê; tiền hoàn = cọc gốc nếu không khấu trừ. Với điều khoản 15 ngày, hạn hoàn 01/11 11:00. |
| `2027-10-18T10:00:00` | Chủ nhà | Chuyển đúng tiền hoàn cọc, tải chứng từ và báo chuyển khoản. | Chờ người thuê xác nhận; không tự CLOSED. |
| `2027-10-18T11:00:00` | Người thuê | Xác nhận đã nhận đủ tiền hoàn. | HĐ và quyết toán CLOSED. Refresh/restart không tạo thêm hóa đơn/QR/nghĩa vụ trả tiền. |

**Đối chiếu tổng:** tiền phòng = đơn giá tháng × 12; cọc chỉ thu một lần rồi hoàn theo quyết toán; có 11 hóa đơn định kỳ sau INITIAL và 1 hóa đơn quyết toán cuối, không gọi là 13 tháng thuê. Nếu không phát sinh thêm thì không tạo bill bổ sung.

---

## UC56 — Người thuê không phản hồi đề xuất cọc trong 48 giờ

**Hợp đồng độc lập:** ký/hiệu lực 16/10/2026, thuê 3 tháng; không dùng ID UC43/UC44. `homespace.closure.response-hours=48`. Gọi cọc là D.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới UC56, nhận nhà 16/10, thuê 3 tháng, hoàn cọc 15 ngày; trả cọc + tháng 1, chủ nhà xác nhận; công tơ đầu 1500/85; ký đủ SmartCA. | ACTIVE; hết hạn 16/01/2027 00:00, ngày này không thuộc thời gian thuê. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ cuối 1550/90. | Nháp kỳ thanh toán 2; dùng 50 kWh/5 m³. |
| `2026-11-16T10:00:00` | Hệ thống → hai bên | Phát hành; người thuê thanh toán, gửi chứng từ; chủ nhà xác nhận đủ tiền. | PAID: chi phí tháng 1 + tiền phòng tháng 2. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ cuối 1610/96. | Nháp kỳ thanh toán 3; dùng 60 kWh/6 m³. |
| `2026-12-16T10:00:00` | Hệ thống → hai bên | Phát hành; người thuê thanh toán, gửi chứng từ; chủ nhà xác nhận đủ tiền. Không yêu cầu gia hạn thuê. | PAID: chi phí tháng 2 + tiền phòng tháng 3. |
| `2027-01-15T10:00:00` | Chủ nhà | Lưu công tơ cuối 1660/101 trong Hóa đơn. | Nháp quyết toán cuối, dùng 50 kWh/5 m³; không tiền phòng tháng 4. |
| `2027-01-16T10:00:00` | Hệ thống → hai bên | Chờ phát hành bill cuối; người thuê chuyển đủ tiền, gửi chứng từ; chủ nhà xác nhận. | EXPIRED; tất cả bill PAID. Bước 1 thu gọn, Bước 2 mở. Không tạo hóa đơn bổ sung. |
| `2027-01-18T10:00:00` | Chủ nhà | Bàn giao thực tế, để danh sách khấu trừ trống, ghi 'Đã nhận đủ phòng và chìa khóa, hoàn nguyên cọc'; tích xác nhận, gửi đề xuất. | PROPOSED v1, hoàn D, hạn phản hồi 20/01 10:00. Chưa QR, chưa CLOSED. |
| `2027-01-20T09:59:59` | Hai bên | Không phản hồi; làm mới hồ sơ. | Chưa cảnh báo quá hạn phản hồi. |
| `2027-01-20T10:00:00` | Hệ thống | Chờ đồng bộ rồi làm mới hai tài khoản, không thao tác đồng ý. | Vẫn PROPOSED; cảnh báo quá hạn phản hồi và 1 sự kiện RESPONSE_OVERDUE. Không tạo QR, không tự giải phóng tin/phòng, không tự trừ cọc. |
| `2027-01-21T10:00:00` | Hai bên | Restart/làm mới nhiều lần, vẫn chưa đồng ý. | Không ghi trùng cảnh báo; số cọc và phiên bản không đổi. |
| `2027-01-21T11:00:00` | Người thuê | Đọc đề xuất v1, xác nhận bàn giao/khấu trừ và Đồng ý quyết toán. | Vẫn được phản hồi sau hạn; REFUND_PENDING. QR chủ nhà → người thuê, hoàn D; hạn chuyển tiền 05/02/2027 11:00. |
| `2027-01-22T10:00:00` | Chủ nhà | Chuyển đủ D, gửi chứng từ. | Chờ người thuê xác nhận, chưa CLOSED. |
| `2027-01-22T11:00:00` | Người thuê | Kiểm tra tiền thực nhận rồi xác nhận. | CLOSED; lịch sử giữ đề xuất, quá hạn phản hồi, đồng ý và hoàn tất. |

---

## UC57 — Chủ nhà không phản hồi khiếu nại cọc trong 48 giờ

**Hợp đồng độc lập:** ký/hiệu lực 16/10/2026, thuê 3 tháng; không dùng ID UC56. `homespace.closure.response-hours=48`. Gọi cọc là D, cần D lớn hơn 500.000đ.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới UC57, nhận nhà 16/10, thuê 3 tháng, hoàn cọc 15 ngày; trả cọc + tháng 1, chủ nhà xác nhận; công tơ đầu 1500/85; ký đủ SmartCA. | ACTIVE; hết hạn 16/01/2027 00:00. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ cuối 1550/90. | Nháp kỳ thanh toán 2; dùng 50 kWh/5 m³. |
| `2026-11-16T10:00:00` | Hệ thống → hai bên | Phát hành; người thuê thanh toán, gửi chứng từ; chủ nhà xác nhận đủ tiền. | PAID: chi phí tháng 1 + tiền phòng tháng 2. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ cuối 1610/96. | Nháp kỳ thanh toán 3; dùng 60 kWh/6 m³. |
| `2026-12-16T10:00:00` | Hệ thống → hai bên | Phát hành; người thuê thanh toán, gửi chứng từ; chủ nhà xác nhận đủ tiền. Không gia hạn thuê. | PAID: chi phí tháng 2 + tiền phòng tháng 3. |
| `2027-01-15T10:00:00` | Chủ nhà | Lưu công tơ cuối 1660/101 trong Hóa đơn. | Nháp quyết toán cuối; không tiền phòng tháng 4. |
| `2027-01-16T10:00:00` | Hệ thống → hai bên | Phát hành bill cuối; người thuê thanh toán và gửi chứng từ, chủ nhà xác nhận đủ tiền. | EXPIRED; tất cả bill PAID, mở Bước 2. |
| `2027-01-18T10:00:00` | Chủ nhà | Nhận lại phòng/chìa khóa; thêm khoản 'Thay kính cửa bị vỡ' 500000đ và ảnh/PDF; ghi nội dung bàn giao, xác nhận không thu trùng, gửi. | PROPOSED v1, dự kiến hoàn D−500000; người thuê mở được minh chứng đã gắn. |
| `2027-01-18T11:00:00` | Người thuê | Mở yêu cầu đối soát; nhập 'Kính đã vỡ trước khi nhận nhà, đề nghị không khấu trừ'; đính kèm ảnh cũ, gửi. | DISPUTED, lý do/minh chứng lưu lịch sử; chủ nhà cần phản hồi trước 20/01 11:00. Không QR hoàn cọc. |
| `2027-01-20T10:59:59` | Chủ nhà | Không gửi đề xuất mới, chỉ mở hồ sơ. | Chưa quá hạn phản hồi khiếu nại; chưa QR. |
| `2027-01-20T11:00:00` | Hệ thống | Chờ đồng bộ, làm mới nhiều lần. | Vẫn DISPUTED, đánh dấu quá hạn chủ nhà và ghi lịch sử một lần. Không tự bác khiếu nại, không tự đồng ý đề xuất v1, không tự CLOSED. |
| `2027-01-21T10:00:00` | Chủ nhà | Đối chiếu ảnh; lấy nội dung hiện tại, xóa khoản kính, ghi đồng ý hoàn nguyên cọc; xác nhận bàn giao và gửi lại. | PROPOSED v2, khấu trừ 0, hoàn D; hạn phản hồi mới 23/01 10:00. Lịch sử v1/khiếu nại/quá hạn vẫn nguyên. |
| `2027-01-21T11:00:00` | Người thuê | Làm mới, đọc đúng v2, tích xác nhận và đồng ý. | REFUND_PENDING; QR đúng D, hạn 05/02/2027 11:00. Đồng ý v1 từ tab cũ phải bị từ chối. |
| `2027-01-22T10:00:00` | Chủ nhà | Chuyển D theo QR, tải chứng từ, báo chuyển khoản. | TRANSFER_REPORTED, chưa CLOSED. |
| `2027-01-22T11:00:00` | Người thuê | Xác nhận đã thực nhận đủ D. | CLOSED; vẫn xem lịch sử và minh chứng các phiên bản. |

**Kiểm tra quyền bằng API/fixture, không gọi là thao tác UI:** người ngoài không đọc được minh chứng PRIVATE; không gắn file của người khác/hợp đồng khác/file chưa READY; tổng khấu trừ sửa tay không khớp các khoản phải bị từ chối. API xóa minh chứng đã gắn vào lịch sử phải bị chặn, còn tệp upload chưa gửi vào đề xuất được xử lý theo quyền kho tệp thông thường.

---

## Phụ lục điều chỉnh — vị trí và quy tắc chạy

Chủ nhà vào **Hợp đồng → Chi tiết → Điều chỉnh hợp đồng**: text chuyển thành input/textarea tại chính các mục; bảng đặc điểm/phí/tiện ích/nội thất/chính sách có nút +/− để thêm/xóa dòng nhập tay. Không chọn bắt buộc từ Listing, không gọi danh mục phụ lục từ Listing. Bấm **Xem lại thay đổi** để mở modal đối chiếu, tích xác nhận rồi **Gửi đề xuất cho người thuê**. Người thuê chỉ có **Đề nghị điều chỉnh** và textarea gửi mong muốn; chủ nhà **Chấp thuận trao đổi · Soạn phụ lục** rồi mới nhập dữ liệu cụ thể để người thuê duyệt lại. Sau khi duyệt, **kết xuất template Word của hợp đồng gốc với snapshot mới và bản đối chiếu → chủ nhà ký SmartCA trước → người thuê ký cùng PDF**. Chỉ áp dụng khi đủ chữ ký đúng revision, PDF cuối READY và đến ngày hiệu lực. Bản nháp chỉ chủ nhà xem được; PDF gốc, người ký/tài khoản nhận tiền, cọc đã nhận, lịch thuê và công tơ lịch sử không bị ghi đè. Đổi đơn giá/cách tính phí được chuẩn hóa khi đối chiếu; không cho làm đứt chuỗi công tơ. Các UC cũ nói chọn tiện ích/nội thất/nội quy được thao tác bằng nhập dòng tương ứng, không bằng select/checkbox.

`homespace.amendment.response-hours=48` và `homespace.amendment.signing-hours=48` nằm trực tiếp trong `application.properties`. Hạn phản hồi tính từ lúc gửi; hạn ký tính từ lúc duyệt, không vượt 00:00 ngày hiệu lực. Với ngày áp dụng 16/11, phải ký đủ **trước 00:00 ngày 16/11**; nếu hóa đơn ảnh hưởng đã phát hành sớm ngày 15/11 thì khóa từ lúc phát hành. Quá hạn không tự đồng ý hoặc tự ký. MỌI thay đổi, kể cả nội quy/tài sản/tiện ích không tính tiền, phải chọn đầu kỳ tương lai chưa phát hành hóa đơn. Không có áp dụng ngay. Phụ lục SCHEDULED chưa đến ngày hiệu lực được soạn bản thay thế cùng ngày áp dụng, duyệt/ký lại; bản cũ chỉ SUPERSEDED sau khi bản mới ký đủ, không xóa file cũ.

Mỗi UC58–UC76 dưới đây tạo một hợp đồng mới ngày 16/10/2026. Không tái sử dụng hợp đồng của UC khác và không lùi thời gian trên hợp đồng đã chạy. Các mốc chỉ áp dụng sau khi đã restart Core theo giá trị đồng hồ nghiệp vụ mới. SmartCA vẫn dùng giao dịch/chứng thư thật; không đổi trạng thái ký bằng tay.

## UC58 — Chủ nhà đề nghị đổi giá thuê, hai bên ký phụ lục

**Hợp đồng độc lập:** 3 tháng, tiền phòng 12.400.000đ/tháng, cọc 24.800.000đ, WiFi 90.000đ/tháng, dịch vụ 30.000đ/người/tháng × 4 người, điện 3.925đ/kWh, nước 15.750đ/m³; không phạt. Công tơ đầu 1500/85.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới UC58, thuê từ 16/10 trong 3 tháng; trả cọc + tháng 1, xác nhận nhận tiền; lưu công tơ 1500/85; ký đủ SmartCA. | ACTIVE; tháng 1 đã trả, cọc đang giữ 24.800.000đ; hết hạn 16/01/2027 00:00. |
| `2026-10-16T11:00:00` | Chủ nhà | Bấm Điều chỉnh hợp đồng, sửa ngay tại Giá thuê, nhập 12.000.000đ, áp dụng 16/11/2026, lý do thỏa thuận giảm giá; đối chiếu và gửi. | REQUESTED, bảng 12.400.000 → 12.000.000; chờ người thuê phản hồi đến 18/10 11:00; chưa áp dụng. |
| `2026-10-16T12:00:00` | Người thuê | Đọc đúng đề nghị, tích đã đọc và đồng ý nội dung. | APPROVED, tạo văn bản `-PL-...`; hạn ký 18/10 12:00; chưa thu thêm tiền. |
| `2026-10-16T13:00:00` | Chủ nhà | Mở phụ lục, kết xuất PDF; đọc và ký SmartCA. | PDF là PHỤ LỤC ĐIỀU CHỈNH, tham chiếu HĐ gốc; chờ người thuê ký, chưa áp dụng. |
| `2026-10-16T14:00:00` | Người thuê | Xem PDF đã có chữ ký chủ nhà, ký SmartCA; chờ xác minh. | SCHEDULED; đủ hai chữ ký và PDF hoàn chỉnh. Giá đang áp dụng vẫn 12.400.000đ đến trước 16/11. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu cuối kỳ điện 1550/nước 90 trong Hóa đơn. | Nháp kỳ thanh toán 2, dùng 50 kWh/5 m³. |
| `2026-11-16T10:00:00` | Hai bên | Mở hóa đơn kỳ thanh toán 2, thanh toán và xác nhận nhận đủ tiền. | Phụ lục APPLIED. Tiền phòng tháng 2 = 12.000.000đ; chi phí tháng 1 vẫn đơn giá cũ. Tổng 12.485.000đ theo đúng cấu hình ca này. Cọc giữ nguyên, không thu lại tháng 1. |

## UC59 — Người thuê đề nghị đổi phí, áp dụng đúng kỳ chi phí

**Hợp đồng độc lập:** 3 tháng, tiền phòng 12.400.000đ, cọc 24.800.000đ, 4 người, dịch vụ 30.000đ/người; WiFi 90.000đ, điện 3.925đ/kWh, nước 15.750đ/m³; công tơ 1500/85, không phạt.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo riêng UC59, thuê 3 tháng; trả và xác nhận cọc + tháng 1; lưu công tơ 1500/85; ký đủ HĐ gốc. | ACTIVE, có nút đề nghị ở cả hai tài khoản. |
| `2026-10-16T11:00:00` | Người thuê | Bấm Đề nghị điều chỉnh, nhập mong muốn WiFi 100.000đ/tháng và điện 4.000đ/kWh từ 16/11; gửi yêu cầu bằng chữ. | TENANT_REQUESTED; chưa có thay đổi có cấu trúc, chưa có PDF phụ lục. |
| `2026-10-16T12:00:00` | Chủ nhà | Chấp thuận trao đổi · Soạn phụ lục; sửa hai đơn giá ngay tại Phí dịch vụ, giữ cách tính điện; chọn 16/11, Xem lại thay đổi, tích kiểm tra và gửi. | TENANT_FULFILLED cho yêu cầu bằng chữ; đề xuất cụ thể REQUESTED, liên kết yêu cầu người thuê. |
| `2026-10-16T13:00:00` | Người thuê | Xem nội dung đề xuất, kiểm tra đúng hai đơn giá, tích đã đọc và Đồng ý nội dung. | APPROVED; chưa áp dụng; chủ nhà ký trước. |
| `2026-10-16T14:00:00` | Chủ nhà | Mở phụ lục, kết xuất PDF và ký SmartCA. | Chờ người thuê ký; điều khoản cũ vẫn áp dụng. |
| `2026-10-16T15:00:00` | Người thuê | Ký cùng PDF, chờ xác minh và PDF đầy đủ. | SCHEDULED từ 16/11. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ 1550/90. | 50 kWh/5 m³ tháng 1. |
| `2026-11-16T10:00:00` | Hai bên | Phát hành, thanh toán và xác nhận kỳ thanh toán 2. | WiFi vẫn 90.000đ; điện vẫn 3.925đ/kWh; tiền phòng tháng 2 vẫn 12.400.000đ. Hóa đơn này không bị tính phí mới cho tháng cũ. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ 1610/96. | 60 kWh/6 m³ tháng 2. |
| `2026-12-16T10:00:00` | Hai bên | Phát hành, thanh toán và xác nhận kỳ thanh toán 3. | WiFi 100.000đ, điện 4.000đ/kWh; tiền phòng tháng 3 = 12.400.000đ. Hóa đơn tháng 11 không đổi. |
| `2027-01-15T10:00:00` | Chủ nhà | Lưu công tơ 1660/101, không gia hạn. | Nháp chi phí tháng 3. |
| `2027-01-16T10:00:00` | Hai bên | Thanh toán hóa đơn cuối và xác nhận. | WiFi/điện dùng giá mới; không tiền phòng tháng 4. Hóa đơn đã trả đầy đủ rồi mới mở lập đề xuất hoàn cọc. |

## UC60 — Đổi số người cư trú và phí theo người

**Hợp đồng độc lập:** 3 tháng, 4 người, tối đa 4; phí dịch vụ 30.000đ/người/tháng; tiền phòng 12.400.000đ, cọc 24.800.000đ; công tơ 1500/85, không phạt.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo riêng UC60, trả và xác nhận cọc + tháng 1; chốt 4 người/công tơ 1500/85; ký đủ HĐ. | ACTIVE, phí dịch vụ đang áp dụng 120.000đ/tháng. |
| `2026-10-16T11:00:00` | Chủ nhà | Điều chỉnh trực tiếp số người ở từ 4 xuống 3, áp dụng 16/11; ghi người chuyển đi và lý do. | Bảng tự tính phí theo người 120.000 → 90.000đ/tháng. |
| `2026-10-16T12:00:00` | Người thuê | Đọc đề xuất và đồng ý nội dung. | APPROVED, chưa áp dụng. |
| `2026-10-16T12:30:00` | Chủ nhà | Kết xuất và ký PDF phụ lục. | Chờ chữ ký người thuê. |
| `2026-10-16T13:00:00` | Người thuê | Ký phụ lục; chờ xác minh. | SCHEDULED, chưa đổi số người đang áp dụng. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ 1550/90. | Nháp chi phí tháng 1. |
| `2026-11-16T10:00:00` | Hai bên | Thanh toán kỳ thanh toán 2 và xác nhận. | Người ở đang áp dụng = 3, nhưng phí dịch vụ tháng 1 vẫn 120.000đ. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ 1610/96. | Nháp chi phí tháng 2. |
| `2026-12-16T10:00:00` | Hai bên | Thanh toán kỳ thanh toán 3 và xác nhận. | Phí dịch vụ tháng 2 = 90.000đ; HĐ gốc/PDF gốc vẫn ghi lịch sử 4 người. |

## UC61 — Từ chối phụ lục, điều khoản cũ tiếp tục áp dụng

**Hợp đồng độc lập:** 3 tháng, tiền phòng 12.400.000đ; công tơ 1500/85, không phạt; cọc 2 tháng.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới UC61, trả và xác nhận ban đầu; lưu công tơ 1500/85; ký đủ. | ACTIVE. |
| `2026-10-16T11:00:00` | Chủ nhà | Đề nghị tăng tiền phòng lên 13.000.000đ từ 16/11. | REQUESTED. |
| `2026-10-16T12:00:00` | Người thuê | Nhập lý do 'Không đồng ý tăng giá trong kỳ thuê' rồi Từ chối. | REJECTED; không tạo phiên ký phụ lục; lịch sử đề nghị giữ nguyên. |
| `2026-10-16T13:00:00` | Chủ nhà | Tạo đề nghị mới giảm tiền phòng về 12.000.000đ từ 16/11 rồi tự rút đề nghị này. | Được gửi yêu cầu mới sau từ chối; yêu cầu mới WITHDRAWN. Không tự coi từ chối là chấm dứt HĐ. |
| `2026-11-15T10:00:00` | Chủ nhà | Chốt công tơ 1550/90. | Nháp kỳ thanh toán 2. |
| `2026-11-16T10:00:00` | Hai bên | Phát hành và thanh toán hóa đơn, xác nhận nhận đủ tiền. | Tiền phòng tháng 2 vẫn 12.400.000đ; không dùng cả giá đề nghị bị từ chối lẫn giá đã rút. |

## UC62 — Không phản hồi phụ lục trong 48 giờ

**Hợp đồng độc lập:** 3 tháng, tiền phòng 12.400.000đ, cọc 2 tháng, công tơ 1500/85; hạn phản hồi phụ lục 48 giờ.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo riêng UC62, trả/xác nhận ban đầu, nhập công tơ 1500/85 và ký đủ. | ACTIVE. |
| `2026-10-16T11:00:00` | Chủ nhà | Gửi đề nghị giảm tiền phòng 12.000.000đ từ 16/11. | REQUESTED; hạn phản hồi 18/10 11:00. |
| `2026-10-18T10:59:59` | Người thuê | Mở đề nghị, không phản hồi. | Vẫn REQUESTED. |
| `2026-10-18T11:00:00` | Hệ thống | Chờ đồng bộ/làm mới hai tài khoản, không bấm đồng ý. | EXPIRED; không tạo PDF ký, không áp dụng, HĐ gốc vẫn ACTIVE. |
| `2026-10-18T12:00:00` | Chủ nhà | Tạo đề nghị mới với giá 12.200.000đ từ 16/11. | Được gửi yêu cầu mới, lịch sử yêu cầu hết hạn vẫn còn. Bấm đồng ý yêu cầu cũ từ tab cũ phải bị từ chối. |

## UC63 — Yêu cầu chỉnh sửa, ký lại đúng phiên bản mới

**Hợp đồng độc lập:** 3 tháng, tiền phòng 12.400.000đ, cọc 2 tháng; công tơ 1500/85.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo riêng UC63, trả và xác nhận ban đầu, lưu công tơ 1500/85, ký đủ. | ACTIVE. |
| `2026-10-16T11:00:00` | Chủ nhà | Gửi đề nghị giảm tiền thuê 12.000.000đ từ 16/11; đính kèm ảnh/PDF thỏa thuận của chính HĐ này. | REQUESTED v1; người thuê mở được minh chứng đã gắn. |
| `2026-10-16T12:00:00` | Người thuê | Nhập 'Đề nghị chốt giá 11.800.000đ theo trao đổi' và bấm Xem nội dung đề xuất → Yêu cầu chỉnh lại. | CHANGES_REQUESTED; chưa có ký phụ lục. |
| `2026-10-16T13:00:00` | Chủ nhà | Chỉnh sửa và gửi lại giá 11.800.000đ, cùng ngày áp dụng. | v1 SUPERSEDED; v2 REQUESTED, dữ liệu và minh chứng v1 giữ nguyên. |
| `2026-10-16T14:00:00` | Người thuê | Đọc đúng v2, tích đồng ý và duyệt. | Tạo phiên ký riêng cho v2; tab cũ duyệt v1 bị từ chối. |
| `2026-10-16T15:00:00` | Chủ nhà | Kết xuất PDF v2 và ký SmartCA. | PDF ghi giá 11.800.000đ và tham chiếu HĐ gốc. |
| `2026-10-16T16:00:00` | Người thuê | Ký cùng PDF v2, chờ xác minh. | SCHEDULED; chỉ v2 được chọn làm điều khoản mới. Người ngoài không xem được minh chứng PRIVATE; xóa file đã gắn lịch sử bị chặn. |

## UC64 — Chỉ ký một bên rồi quá hạn ký phụ lục

**Hợp đồng độc lập:** 3 tháng, tiền phòng 12.400.000đ, cọc 2 tháng, công tơ 1500/85; hạn ký 48 giờ sau khi duyệt.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo riêng UC64, trả và xác nhận cọc + tháng 1, chốt công tơ 1500/85, ký đủ HĐ gốc. | ACTIVE. |
| `2026-10-16T11:00:00` | Chủ nhà | Gửi đề nghị tiền phòng 12.000.000đ từ 16/11. | REQUESTED. |
| `2026-10-16T12:00:00` | Người thuê | Đồng ý nội dung. | APPROVED, hạn ký đủ 18/10 12:00. |
| `2026-10-16T13:00:00` | Chủ nhà | Kết xuất và hoàn tất chữ ký đầu. | Có PDF một chữ ký; chưa áp dụng; người thuê không ký tiếp. |
| `2026-10-18T12:00:00` | Hệ thống | Làm mới hồ sơ sau đồng bộ. | EXPIRED; văn bản phụ lục CANCELLED; giữ PDF/chữ ký đầu để tra cứu, không xóa. |
| `2026-10-18T13:00:00` | Người thuê | Mở phụ lục cũ và thử ký; sau đó về HĐ gốc. | Ký cũ bị chặn. HĐ gốc vẫn ACTIVE, giá 12.400.000đ và cọc cũ giữ nguyên; có thể lập đề nghị mới. |

## UC65 — Phát hiện sai sau chữ ký đầu, dừng phiên và sửa phụ lục

**Hợp đồng độc lập:** 3 tháng, tiền phòng 12.400.000đ, cọc 2 tháng, công tơ 1500/85.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo riêng UC65, trả/xác nhận ban đầu, nhập công tơ 1500/85 và ký đủ. | ACTIVE. |
| `2026-10-16T11:00:00` | Chủ nhà | Đề nghị giá 12.000.000đ từ 16/11. | REQUESTED v1. |
| `2026-10-16T12:00:00` | Người thuê | Đồng ý v1. | APPROVED. |
| `2026-10-16T13:00:00` | Chủ nhà | Kết xuất PDF và ký SmartCA v1. | Có chữ ký chủ nhà, chưa chữ ký người thuê. |
| `2026-10-16T14:00:00` | Người thuê | Mở Xem nội dung đề xuất, nhập 'Giá đúng cần chốt là 11.900.000đ' và yêu cầu chỉnh sửa/dừng phiên ký. | CHANGES_REQUESTED; văn bản v1 CANCELLED. Giữ PDF đã ký một bên; request ký chưa hoàn tất bị dừng ở HomeSpace. Kết quả provider cũ không được áp dụng điều khoản. |
| `2026-10-16T15:00:00` | Chủ nhà | Chỉnh sửa gửi v2 giá 11.900.000đ từ 16/11. | v1 SUPERSEDED; tạo yêu cầu mới, không dùng lại chữ ký v1. |
| `2026-10-16T16:00:00` | Người thuê | Đọc và đồng ý v2. | Tạo phiên ký v2. |
| `2026-10-16T17:00:00` | Chủ nhà | Kết xuất và ký PDF v2. | Chữ ký mới trên PDF mới. |
| `2026-10-16T18:00:00` | Người thuê | Ký cùng PDF v2, chờ xác minh. | SCHEDULED v2; v1 không áp dụng. Sau ký đủ không có nút rút đơn phương. |

## UC66 — Chặn ngày áp dụng đã chốt hóa đơn và trường dữ liệu được bảo vệ

**Hợp đồng độc lập:** 3 tháng, tiền phòng 12.400.000đ, cọc 2 tháng, công tơ 1500/85, không phạt. Đây là ca kiểm tra UI và API riêng; không dùng ID của ca khác.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo riêng UC66, trả/xác nhận cọc + tháng 1, lưu công tơ 1500/85, ký đủ. | ACTIVE. |
| `2026-10-16T11:00:00` | Chủ nhà | Mở form và thử ngày 17/10 hoặc 16/01/2027; thử giá âm/lẻ; thử số người vượt tối đa. | UI/API không chấp nhận. Không có trường sửa CCCD, nhà/phòng, ngày bắt đầu, lịch sử tiền cọc hoặc công tơ ban đầu. |
| `2026-11-15T10:00:00` | Chủ nhà | Chốt công tơ 1550/90 và phát hành thủ công hóa đơn kỳ thanh toán 2. | Hóa đơn đã phát hành với giá gốc, chưa sửa bất kỳ khoản nào. |
| `2026-11-15T11:00:00` | Chủ nhà | Mở đề nghị điều chỉnh giá thuê; kiểm tra lịch và cố gửi áp dụng 16/11 qua API/tab cũ. | Không thể chọn/gửi 16/11: đã qua hạn chốt và đã phát hành. Kỳ phù hợp tiếp theo là 16/12. |
| `2026-11-15T12:00:00` | Chủ nhà | Gửi giá 12.000.000đ từ 16/12, đúng bản điều khoản hiện tại. | REQUESTED hợp lệ; hóa đơn đã phát hành không đổi. |

**Kiểm tra API với token của đúng hai bên:** gửi `changes` chứa `lease`, `meters`, `tenant.idNumber`, `depositAmount` hoặc tài khoản ngân hàng phải bị từ chối; `baseRevisionId` cũ bị từ chối; người ngoài không GET/POST được; không gửi bằng chứng của tài khoản khác/hợp đồng khác/chưa READY. Mã request UUID dùng lại với cùng nội dung trả lại cùng đề nghị; dùng lại với nội dung khác bị từ chối. Không gọi các kiểm tra này là thao tác có sẵn trên UI.

## UC67 — Phụ lục nội quy/tài sản cũng chờ đầu kỳ tương lai

**Hợp đồng độc lập:** 3 tháng, tiền phòng 12.400.000đ, cọc 2 tháng, công tơ 1500/85; ban đầu không cho nuôi vật nuôi, đã có danh mục thiết bị bàn giao.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo riêng UC67, trả/xác nhận ban đầu, lưu công tơ 1500/85, ký đủ HĐ gốc. | ACTIVE. |
| `2026-10-16T11:00:00` | Chủ nhà | Điều chỉnh ở Nội quy và Nội thất; đề nghị cho phép vật nuôi với nội quy vệ sinh; thêm bàn làm việc 1 cái/hiện trạng mới; chọn đầu kỳ 16/11; đính kèm ảnh bàn giao và gửi. | REQUESTED; không có nút áp dụng ngay; API cũng từ chối applyImmediately=true. |
| `2026-10-16T12:00:00` | Người thuê | Đồng ý nội dung đề xuất. | APPROVED, chưa áp dụng. |
| `2026-10-16T12:30:00` | Chủ nhà | Mở phụ lục, kết xuất PDF và ký. | PDF nêu ngày áp dụng 16/11 sau khi ký đủ; giữ biên bản bàn giao cũ. |
| `2026-10-16T13:00:00` | Người thuê | Ký cùng PDF và chờ xác minh. | SCHEDULED; điều khoản hiện tại chưa thay đổi. |
| `2026-11-16T00:00:00` | Hai bên | Mở Điều khoản đang áp dụng, tải PDF gốc và phụ lục. | APPLIED; nội quy mới và bàn mới có trong dữ liệu hiện tại; PDF gốc giữ nội dung ban đầu; giá thuê/cọc/công tơ/thanh toán không đổi. |

## UC68 — Chỗ xe qua phụ lục và luồng gia hạn sau đó

**Hợp đồng độc lập:** 3 tháng, 0 xe máy ban đầu, phòng cho phép ít nhất 1 xe, pool còn 1 chỗ; tiền phòng 12.400.000đ, cọc 2 tháng; công tơ 1500/85, không phạt.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo riêng UC68, trả/xác nhận cọc + tháng 1, chốt 0 xe và công tơ 1500/85, ký đủ. | ACTIVE, chưa giữ thêm chỗ xe. |
| `2026-10-16T11:00:00` | Chủ nhà | Đề nghị đăng ký 1 xe máy từ 16/11. | Kiểm tra chỗ chi nhánh ngay khi xem lại/gửi; REQUESTED khi đủ, chưa giữ chỗ mới. Listing độc lập không bị giới hạn bởi số xe của bài đăng. |
| `2026-10-16T12:00:00` | Người thuê | Đồng ý nội dung đề xuất. | Kiểm tra lại rồi giữ phần chỗ xe tăng thêm cho thời gian 16/11–trước 16/01. Nếu chỗ chi nhánh đã bị hồ sơ khác giữ sau lúc đề xuất, duyệt báo số chỗ còn lại, không tạo phụ lục áp dụng. |
| `2026-10-16T12:30:00` | Chủ nhà | Mở phụ lục, kết xuất PDF và ký. | Chờ người thuê ký đủ. |
| `2026-10-16T13:00:00` | Người thuê | Ký cùng PDF, chờ xác minh. | SCHEDULED; chỗ xe tương lai đã được xác nhận, dữ liệu chỗ xe lịch sử không bị viết lại. |
| `2026-11-15T10:00:00` | Chủ nhà | Lưu công tơ 1550/90. | Nháp kỳ thanh toán 2. |
| `2026-11-16T10:00:00` | Hai bên | Thanh toán và xác nhận kỳ thanh toán 2. | APPLIED, đăng ký 1 xe máy từ kỳ mới. |
| `2026-12-15T10:00:00` | Chủ nhà | Lưu công tơ 1610/96. | Nháp kỳ thanh toán 3. |
| `2026-12-16T10:00:00` | Hai bên | Thanh toán và xác nhận kỳ thanh toán 3. | Đủ tiền phòng tháng 3. |
| `2026-12-17T10:00:00` | Chủ nhà | Thử tạo phụ lục mới khi không còn đầu kỳ tương lai trong thời hạn thuê. | Bị chặn; không dùng áp dụng ngay để lách giới hạn. Gia hạn thời gian thuê vẫn theo luồng riêng. |
| `2026-12-17T12:30:00` | Người thuê | Làm mới Gia hạn, gửi yêu cầu gia hạn 3 tháng. | Gia hạn REQUESTED hợp lệ; không cộng thời hạn vì phụ lục xe. |
| `2026-12-17T13:00:00` | Chủ nhà | Duyệt gia hạn, mở văn bản gia hạn và ký sau kết xuất. | Văn bản gia hạn dùng điều khoản mới đã áp dụng và đăng ký 1 xe; chưa kéo dài đến khi đủ chữ ký. |
| `2026-12-17T14:00:00` | Người thuê | Ký cùng PDF gia hạn, chờ xác minh. | Gia hạn APPLIED; hợp đồng tiếp tục đến trước 16/04/2027. Phụ lục và văn bản gia hạn được phân biệt, không thu lại cọc ban đầu. |

Kiểm tra biến thể độc lập của UC68 (tạo hợp đồng khác cho mỗi ca):

- Listing không liên kết chi nhánh, giới hạn xe trên bài đăng 0: phụ lục đề xuất 5 xe máy + 2 ô tô vẫn được xem lại/gửi/duyệt/ký, không sửa Listing. Số âm, thập phân hoặc trên 100 mỗi loại bị chặn. Không áp dụng việc mở giới hạn cho yêu cầu thuê ban đầu.
- Listing liên kết chi nhánh, Listing cho 0 xe nhưng chi nhánh có 10 chỗ xe máy, hợp đồng này đang giữ 2 và hồ sơ khác giữ 6: đề xuất 4 xe máy được chấp nhận, chỉ giữ thêm 2; đề xuất 5 bị chặn ngay khi xem lại/gửi (chỉ còn 4 chỗ cho hợp đồng này). Chi nhánh có 0 chỗ ô tô: thêm 1 ô tô bị chặn riêng, không lấy chỗ xe máy bù vào.
- Hai hồ sơ khác mỗi hồ sơ giữ 3 xe trên hai khoảng không chồng nhau, chi nhánh có 5 chỗ: phụ lục giữ 2 xe được duyệt; không cộng nhầm thành 6 chỗ đã dùng. Ngày trả chỗ là end-exclusive, có thể tái sử dụng đúng ngày đó.
- Phụ lục kéo dài thuê: gốc giữ 2 xe đến 16/12, đề xuất 3 xe đến 16/01; giữ thêm 1 xe 16/11–trước 16/12, rồi giữ đủ 3 xe 16/12–trước 16/01. Nếu đoạn sau không đủ chỗ chi nhánh, chặn trước khi gửi/duyệt.
- Thay bản chờ hiệu lực: chỗ cũ và lịch sử chưa đổi khi gửi/duyệt bản thay. Chỉ khi đủ hai chữ ký mới thay đoạn tương lai; từ chối/rút/hết hạn chỉ giải phóng chỗ giữ của bản thay. Khi ký không cộng trùng phần HELD của chính bản đó.

## UC69 — Phụ lục trong hợp đồng 12 tháng, quyết toán cuối không thu tháng 13

**Hợp đồng độc lập:** 12 tháng từ 16/10/2026; tiền phòng 12.400.000đ, cọc 24.800.000đ, WiFi 90.000đ/tháng; điện 3.925đ/kWh, nước 15.750đ/m³; công tơ đầu 1500/85, không phạt. Không gia hạn. Phụ lục chỉ đổi tiền thuê từ 16/09/2027 thành 12.000.000đ.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo riêng UC69, chọn thời hạn 12 tháng; trả và xác nhận cọc + tháng 1; công tơ 1500/85; ký đủ. | ACTIVE; hết hạn 16/10/2027 00:00; lịch phụ lục có các kỳ từ 16/11/2026 đến 16/09/2027, không có 16/10/2027. |
| `2026-10-16T11:00:00` | Chủ nhà | Gửi đề nghị tiền thuê 12.000.000đ, chọn ngày 16/09/2027. | REQUESTED, không hard-code ba tháng. |
| `2026-10-16T12:00:00` | Người thuê | Đồng ý nội dung. | APPROVED, hạn ký 18/10 12:00 dù ngày áp dụng xa. |
| `2026-10-16T13:00:00` | Chủ nhà | Kết xuất và ký phụ lục. | Chờ ký người thuê. |
| `2026-10-16T14:00:00` | Người thuê | Ký cùng PDF và chờ xác minh. | SCHEDULED từ 16/09/2027. |
| `2026-11-15T10:00:00` | Chủ nhà | Chốt 1550/90. | Nháp chi phí tháng 1. |
| `2026-11-16T10:00:00` | Hai bên | Thanh toán và xác nhận kỳ thanh toán 2. | Tiền phòng tháng 2 = 12.400.000đ. |
| `2026-12-15T10:00:00` | Chủ nhà | Chốt 1600/95. | Nháp chi phí tháng 2. |
| `2026-12-16T10:00:00` | Hai bên | Thanh toán và xác nhận kỳ thanh toán 3. | Tiền phòng tháng 3 = 12.400.000đ. |
| `2027-01-15T10:00:00` | Chủ nhà | Chốt 1650/100. | Nháp chi phí tháng 3. |
| `2027-01-16T10:00:00` | Hai bên | Thanh toán và xác nhận kỳ thanh toán 4. | Tiền phòng tháng 4 = 12.400.000đ, hợp đồng còn hiệu lực. |
| `2027-02-15T10:00:00` | Chủ nhà | Chốt 1700/105. | Nháp chi phí tháng 4. |
| `2027-02-16T10:00:00` | Hai bên | Thanh toán và xác nhận kỳ thanh toán 5. | Tiền phòng tháng 5 = 12.400.000đ. |
| `2027-03-15T10:00:00` | Chủ nhà | Chốt 1750/110. | Nháp chi phí tháng 5. |
| `2027-03-16T10:00:00` | Hai bên | Thanh toán và xác nhận kỳ thanh toán 6. | Tiền phòng tháng 6 = 12.400.000đ. |
| `2027-04-15T10:00:00` | Chủ nhà | Chốt 1800/115. | Nháp chi phí tháng 6. |
| `2027-04-16T10:00:00` | Hai bên | Thanh toán và xác nhận kỳ thanh toán 7. | Tiền phòng tháng 7 = 12.400.000đ. |
| `2027-05-15T10:00:00` | Chủ nhà | Chốt 1850/120. | Nháp chi phí tháng 7. |
| `2027-05-16T10:00:00` | Hai bên | Thanh toán và xác nhận kỳ thanh toán 8. | Tiền phòng tháng 8 = 12.400.000đ. |
| `2027-06-15T10:00:00` | Chủ nhà | Chốt 1900/125. | Nháp chi phí tháng 8. |
| `2027-06-16T10:00:00` | Hai bên | Thanh toán và xác nhận kỳ thanh toán 9. | Tiền phòng tháng 9 = 12.400.000đ. |
| `2027-07-15T10:00:00` | Chủ nhà | Chốt 1950/130. | Nháp chi phí tháng 9. |
| `2027-07-16T10:00:00` | Hai bên | Thanh toán và xác nhận kỳ thanh toán 10. | Tiền phòng tháng 10 = 12.400.000đ. |
| `2027-08-15T10:00:00` | Chủ nhà | Chốt 2000/135. | Nháp chi phí tháng 10. |
| `2027-08-16T10:00:00` | Hai bên | Thanh toán và xác nhận kỳ thanh toán 11. | Tiền phòng tháng 11 = 12.400.000đ. |
| `2027-09-15T10:00:00` | Chủ nhà | Chốt 2050/140. | Nháp chi phí tháng 11. |
| `2027-09-16T10:00:00` | Hai bên | Thanh toán và xác nhận kỳ thanh toán 12; không gửi gia hạn. | Phụ lục APPLIED, tiền phòng tháng 12 = 12.000.000đ; chi phí tháng 11 vẫn theo giá đã thỏa thuận trước. |
| `2027-10-15T10:00:00` | Chủ nhà | Chốt 2100/145. | Nháp chi phí tháng 12. |
| `2027-10-16T10:00:00` | Hai bên | Thanh toán và xác nhận hóa đơn chi phí cuối. | Không tiền phòng tháng 13; tổng tiền phòng toàn HĐ = 11 × 12.400.000 + 12.000.000đ. Cọc giữ nguyên 24.800.000đ. |
| `2027-10-17T10:00:00` | Chủ nhà | Nhận lại tài sản/chìa khóa, lập đề xuất hoàn nguyên cọc, khấu trừ 0, gửi. | PROPOSED trong Hóa đơn → Hoàn cọc, không trừ điện/nước thêm lần nữa. |
| `2027-10-17T11:00:00` | Người thuê | Đồng ý đề xuất bàn giao và số tiền hoàn. | REFUND_PENDING; QR chủ nhà → người thuê, chưa đóng hợp đồng. |
| `2027-10-18T10:00:00` | Chủ nhà | Chuyển 24.800.000đ và gửi chứng từ. | Chờ người thuê xác nhận nhận tiền. |
| `2027-10-18T11:00:00` | Người thuê | Xác nhận đã thực nhận đủ tiền. | CLOSED, đã kết thúc hợp đồng. PDF gốc, phụ lục và 12 hóa đơn vẫn tra cứu được. |

## UC70 — Người thuê gửi yêu cầu bằng chữ, chủ nhà không phản hồi

**Hợp đồng riêng:** 3 tháng từ 16/10/2026, giá 12.400.000đ, cọc 24.800.000đ, công tơ 1500/85. Hoàn tất thanh toán ban đầu và đủ chữ ký SmartCA trước bước gửi yêu cầu.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo và ký đủ HĐ mới UC70. | ACTIVE, mặc định chỉ đọc. |
| `2026-10-16T11:00:00` | Người thuê | Bấm Đề nghị điều chỉnh; nhập 'Tôi muốn giảm tiền phòng xuống 12 triệu từ tháng sau'; gửi. | TENANT_REQUESTED. Không input giá, phí, nội thất; không changes/PDF/phiên ký. |
| `2026-10-18T10:59:59` | Chủ nhà | Mở yêu cầu, không bấm chấp thuận. | Vẫn chờ phản hồi, hạn 18/10 11:00. |
| `2026-10-18T11:00:00` | Hệ thống | Làm mới sau đồng bộ. | TENANT_EXPIRED, nhãn Không xử lý trong thời hạn; không ghi chủ nhà đã từ chối. Giá/cọc/PDF giữ nguyên. |
| `2026-10-18T12:00:00` | Người thuê | Gửi một yêu cầu bằng chữ mới. | Được gửi mới; yêu cầu hết hạn vẫn tra cứu trong lịch sử. |

## UC71 — Chủ nhà từ chối yêu cầu bằng chữ của người thuê

**Hợp đồng riêng:** 3 tháng từ 16/10/2026, giá 12.400.000đ, cọc 24.800.000đ, công tơ 1500/85; hai bên hoàn tất thanh toán ban đầu và ký số.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo và ký đủ HĐ mới UC71. | ACTIVE. |
| `2026-10-16T11:00:00` | Người thuê | Gửi mong muốn giảm giá và thêm máy giặt bằng textarea. | TENANT_REQUESTED; chưa có đề xuất có cấu trúc. |
| `2026-10-16T12:00:00` | Chủ nhà | Nhập 'Không thể giảm giá và bổ sung tài sản trong kỳ này'; bấm Từ chối yêu cầu. | TENANT_REJECTED, lưu lý do rõ ràng. Không có PDF phụ lục hoặc cập nhật Listing. |
| `2026-10-16T13:00:00` | Người thuê | Làm mới và mở lịch sử. | Thấy đúng lý do; hợp đồng ACTIVE, giá/cọc và nội thất cũ giữ nguyên. |

## UC72 — Chủ nhà lưu và tiếp tục bản nháp riêng

**Hợp đồng riêng:** 3 tháng từ 16/10/2026, giá 12.400.000đ, cọc 24.800.000đ, công tơ 1500/85; đã thanh toán ban đầu và ký đủ.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ mới UC72 và ký đủ. | ACTIVE; chưa có phụ lục. |
| `2026-10-16T11:00:00` | Chủ nhà | Điều chỉnh hợp đồng, nhập giá 12.000.000đ tại Giá thuê, chọn 16/11, lý do giảm giá; Lưu nháp rồi reload. | Có Tiếp tục bản nháp. Dữ liệu lưu server; chưa gửi, chưa có hạn phản hồi hoặc PDF. |
| `2026-10-16T11:10:00` | Người thuê | Mở cùng hợp đồng. | Không thấy bản nháp/giá dự kiến; vẫn giá 12.400.000đ. GET/PUT/DELETE draft bằng token người thuê bị chặn. |
| `2026-10-16T12:00:00` | Chủ nhà | Tiếp tục bản nháp, kiểm tra giá và ngày đã lưu; Xem lại thay đổi. | Modal đúng giá cũ/mới, không hiển thị toàn bộ hợp đồng. Gửi bị khóa trước khi tích xác nhận. |
| `2026-10-16T12:10:00` | Chủ nhà | Tích đã kiểm tra và Gửi đề xuất cho người thuê. | REQUESTED; bản nháp đã chuyển thành đề xuất. Chưa thay giá đang áp dụng hoặc cọc. |
| `2026-10-16T13:00:00` | Người thuê | Xem nội dung đề xuất, đồng ý. | APPROVED; chủ nhà ký trước. |

## UC73 — Yêu cầu người thuê được chuyển thành đề xuất, sửa lại phải duyệt lại

**Hợp đồng riêng:** 3 tháng từ 16/10/2026, giá 12.400.000đ, cọc 24.800.000đ, công tơ 1500/85; đã trả ban đầu và ký đủ.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ UC73 và ký đủ. | ACTIVE. |
| `2026-10-16T11:00:00` | Người thuê | Nhập mong muốn giảm giá xuống 12 triệu từ tháng sau; gửi. | TENANT_REQUESTED. |
| `2026-10-16T12:00:00` | Chủ nhà | Chấp thuận trao đổi · Soạn phụ lục. | TENANT_ACCEPTED; mở input tại từng mục. Không tự lấy con số trong đoạn chữ để đổi giá. |
| `2026-10-16T12:10:00` | Chủ nhà | Nhập giá 12.200.000đ, chọn 16/11; modal đối chiếu, tích xác nhận, gửi. | TENANT_FULFILLED cho yêu cầu bằng chữ; REQUESTED v1 liên kết tenantRequestId; chờ người thuê duyệt giá cụ thể. |
| `2026-10-16T13:00:00` | Người thuê | Xem v1, nhập 'Xin chốt đúng giá 12 triệu như mong muốn'; Yêu cầu chỉnh lại. | CHANGES_REQUESTED, chưa có hiệu lực. |
| `2026-10-16T14:00:00` | Chủ nhà | Chỉnh lại trên hợp đồng, nhập 12.000.000đ, modal xác nhận và gửi v2. | v1 SUPERSEDED; v2 giữ liên kết yêu cầu gốc; phải duyệt lại. |
| `2026-10-16T15:00:00` | Người thuê | Đọc v2, tích xác nhận, Đồng ý nội dung. | APPROVED v2; v1 không được dùng để ký/áp dụng. |
| `2026-10-16T16:00:00` | Chủ nhà | Kết xuất và ký v2. | Chờ người thuê ký PDF đúng v2. |
| `2026-10-16T17:00:00` | Người thuê | Ký v2 và chờ xác minh. | SCHEDULED; giá mới chỉ dùng từ 16/11; bản gốc/hóa đơn cũ giữ nguyên. |

## UC74 — Chỉnh trực tiếp nội thất và tiện ích nhà nguyên căn

**Hợp đồng riêng:** HOUSE, 3 tháng từ 16/10/2026, giá 12.400.000đ, cọc 24.800.000đ. Listing có Giường và Máy giặt; đã thanh toán ban đầu, nhập công tơ 1500/85 và ký đủ.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo và ký đủ HĐ HOUSE UC74. | ACTIVE; nội dung thuộc đúng căn nhà. |
| `2026-10-16T11:00:00` | Chủ nhà | Điều chỉnh hợp đồng; tại Nội thất xóa dòng Giường, thêm dòng Máy giặt; nhập số lượng 1/hiện trạng Còn tốt/ghi chú bổ sung. | Nhập tay không cần chọn từ Listing; modal ghi rõ thu hồi/bổ sung, chưa sửa dữ liệu đang áp dụng hoặc bàn giao gốc. |
| `2026-10-16T11:10:00` | Chủ nhà | Chọn áp dụng đầu kỳ 16/11, nhập lý do; đối chiếu, tích xác nhận và gửi. | REQUESTED; diff hiển thị tài sản cũ và danh sách mới. Không sửa Listing hoặc bàn giao cũ. |
| `2026-10-16T12:00:00` | Người thuê | Đọc danh sách, đồng ý nội dung. | APPROVED. |
| `2026-10-16T13:00:00` | Chủ nhà | Kết xuất và ký phụ lục. | Chờ ký người thuê. |
| `2026-10-16T14:00:00` | Người thuê | Ký cùng PDF, chờ xác minh. | SCHEDULED, chưa đổi nội thất hiện tại. |
| `2026-11-16T00:00:00` | Hai bên | Mở lại mục Nội thất. | APPLIED; danh sách mới áp dụng, bản gốc vẫn có Giường. Không tự khấu trừ cọc vì thu hồi thiết bị. |

## UC75 — Chỉnh trực tiếp bảng tiện ích chung cư bằng nội dung nhập tay

**Hợp đồng riêng:** APARTMENT, 3 tháng từ 16/10/2026, giá 12.400.000đ, cọc 24.800.000đ; Listing có WiFi, tài sản chung có Hồ bơi. Hai bên đã thanh toán ban đầu và ký đủ; công tơ 1500/85.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ APARTMENT UC75 và ký đủ. | ACTIVE; ID tài sản, người ký, giao dịch và lịch thuê được bảo vệ. |
| `2026-10-16T11:00:00` | Chủ nhà | Điều chỉnh hợp đồng; nhập dòng Hồ bơi, phạm vi Dùng chung, ghi điều kiện/giờ sử dụng theo thỏa thuận. | Không có select bắt buộc hoặc tải danh mục Listing; từng ô bảng là input. Nội dung chưa áp dụng. |
| `2026-10-16T11:10:00` | Chủ nhà | Thử nhập tên dòng trùng hoặc gửi thêm trường branchId trong một dòng bảng. | Backend chặn dữ liệu trùng/trường ngoài schema; không dựa riêng vào UI. |
| `2026-10-16T11:20:00` | Chủ nhà | Sửa tên cho hợp lệ, nhập phạm vi/điều kiện tự do; nếu đổi đơn giá WiFi thì chỉnh ở Phí dịch vụ, chọn đầu kỳ 16/11. | Không ghi đè thầm nội dung nhập tay bằng Listing. Phí thực tế lấy từ bảng phí có cấu trúc, không suy tiền từ costText của tiện ích; kiểm tra nội dung hai bảng trước khi gửi. |
| `2026-10-16T12:00:00` | Chủ nhà | Đối chiếu và gửi đề xuất hợp lệ. | REQUESTED; dữ liệu bản gốc và tin đăng giữ nguyên. |
| `2026-10-16T13:00:00` | Người thuê | Đọc và từ chối kèm lý do. | REJECTED; quyền tiện ích đang áp dụng không đổi. |

## UC76 — Chỉnh trực tiếp phòng trọ, kiểm tra người ở và loại công tơ

**Hợp đồng riêng:** ROOM, 3 tháng từ 16/10/2026, giá 12.400.000đ, cọc 24.800.000đ, tối đa 4 người; điện PER_KWH, nước PER_M3; công tơ 1500/85; đã trả ban đầu và ký đủ.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-16T10:00:00` | Hai bên | Tạo HĐ ROOM UC76, đăng ký 2 người và ký đủ. | ACTIVE; trường loại công tơ/phòng thuê/chỉ số ban đầu không mở chỉnh. |
| `2026-10-16T11:00:00` | Chủ nhà | Điều chỉnh hợp đồng; nhập 5 người trong khi giới hạn vẫn 4; thử đổi điện theo công tơ thành khoán tháng. | Input nhập tay mở nhưng backend từ chối số người vượt giới hạn và cách tính làm đứt chuỗi công tơ. Không cập nhật âm thầm. |
| `2026-10-16T11:10:00` | Chủ nhà | Sửa 3 người, điện 4.000đ/kWh, giữ loại tính phí; chọn 16/11 và nhập lý do. | Modal tính lại phí theo người nếu có; không áp dụng ngay thay đổi ảnh hưởng tiền. |
| `2026-10-16T12:00:00` | Chủ nhà | Tích kiểm tra và gửi. | REQUESTED, chờ người thuê duyệt. |
| `2026-10-16T13:00:00` | Người thuê | Kiểm tra đề xuất, chưa đồng ý; mở Đề nghị điều chỉnh qua tab khác. | Không có input chỉnh trực tiếp; chỉ một hồ sơ đang xử lý nên không gửi yêu cầu mới song song. |
| `2026-10-16T14:00:00` | Người thuê | Xem đề xuất hiện tại và đồng ý. | APPROVED; ký theo thứ tự chủ nhà trước → người thuê sau, điều khoản cũ giữ đến ngày áp dụng. |

## UC77 — Mốc thanh toán và giới hạn phạt do hệ thống quản lý

**Hợp đồng riêng:** hợp đồng đã ký đủ, bắt đầu 16/10/2026, còn đầu kỳ tương lai 16/11/2026 để đề xuất thay đổi phí.

| Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- |
| Chủ nhà | Mở Điều chỉnh hợp đồng, xem mục giá thuê và chính sách. | Không có ô nhập số ngày đến hạn, ân hạn hoặc trần tiền phạt. Nhãn chỉ đọc giải thích 5 ngày lịch tính cả ngày chốt kỳ; không cộng ân hạn; tối đa 10 ngày tính phạt. |
| Chủ nhà | Mở dropdown cách tính, chọn Mỗi ngày, nhập 100.000đ. | Đúng 3 lựa chọn Không phạt / Một lần / Mỗi ngày, gửi mã enum thay vì tên gõ tay. Hiện mức tối đa tự tính: 100.000đ/ngày × 10 ngày = 1.000.000đ/hóa đơn. Đây là giới hạn, không phải số tiền phạt mặc định phải trả. |
| Chủ nhà | Đổi cách tính Một lần rồi Không phạt. | Mức tối đa tương ứng 100.000đ và 0đ; không nhân 10 khi thu một lần. |
| Người kiểm thử | Gọi API bản nháp/phụ lục, thử đổi graceDays, cap, deadline offset hoặc billingRules. | Từ chối thay đổi quy trình; không chỉ khóa giao diện. Payload hợp lệ chỉ đổi cách tính và mức phí. |
| Hai bên | Gửi đề xuất hợp lệ, người thuê duyệt, chủ nhà ký trước rồi người thuê ký. | Word/PDF mới tự điền hạn thanh toán và điều khoản phạt; đoạn quy trình hệ thống giữ nguyên ngoài phần ghi chú tự do. Không thêm mã trường Word mới; không sửa bản đã ký hoặc phí đã chốt. |
| Hai bên | Kiểm tra hóa đơn chốt ngày 16/11. | Hạn 20/11 lúc 23:59:59; ngày quá hạn đầu tiên 21/11. Sau hết 5 ngày quá hạn mới mở phương án xử lý; sau hết 10 ngày xử lý công nợ theo điều kiện, không tự chấm dứt. |
| Hai bên | Đổi Mỗi ngày → Một lần 100.000đ qua phụ lục hiệu lực 16/11; kiểm tra sau ký đủ. | Hóa đơn chốt chi phí kỳ 16/10–15/11 giữ chính sách cũ. Hóa đơn chốt chi phí kỳ bắt đầu 16/11 dùng Một lần: trễ 1/3/10 ngày vẫn chỉ 100.000đ, một dòng phí, không cộng lặp khi tải lại. Đổi ngược sang Mỗi ngày thì tính theo ngày và tối đa 10 ngày trên kỳ mới; chọn Không phạt không tạo dòng phí. Bản đã ký, hóa đơn cũ và công nợ giữ nguyên. |

## UC78 — Chỉnh lại phụ lục đã ký nhưng chưa áp dụng

**Hợp đồng độc lập:** bắt đầu 16/10/2026, 3 tháng, giá 12.400.000đ; đã thanh toán ban đầu và ký đủ. Phụ lục A giảm giá còn 12.000.000đ từ 16/11, được hai bên ký đủ ngày 17/10; SCHEDULED, chưa chốt hóa đơn liên quan.

| homespace.time.simulated-at | Người thực hiện | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- | --- |
| `2026-10-21T10:00:00` | Chủ nhà | Bấm Chỉnh lại phụ lục A. | Input nạp 12.000.000đ; lịch chỉ chọn 16/11; không có áp dụng ngay, không cho đổi sang 16/12. |
| `2026-10-21T11:00:00` | Chủ nhà | Sửa 11.500.000đ, đối chiếu và gửi B. | So với A: 12.000.000đ → 11.500.000đ. A vẫn SCHEDULED, chưa SUPERSEDED; B REQUESTED liên kết A. Không có đề xuất thay thế thứ hai song song. |
| `2026-10-21T12:00:00` | Người thuê | Đồng ý B. | APPROVED; chủ nhà phải ký trước, không tái sử dụng chữ ký trên A. |
| `2026-10-21T13:00:00` | Chủ nhà | Kết xuất và ký B. | Word ghi rõ thay thế toàn bộ A, ngày hiệu lực 16/11 và trước/sau; A vẫn SCHEDULED. |
| `2026-10-21T14:00:00` | Người thuê | Ký B, chờ xác minh đủ hai chữ ký đúng revision và PDF hoàn chỉnh. | B SCHEDULED; A SUPERSEDED, không xóa PDF/chữ ký và không giả hủy hợp đồng con A. Nội dung đang áp dụng vẫn là hợp đồng gốc. |
| `2026-11-16T00:00:00` | Hai bên | Mở hợp đồng và hồ sơ văn bản. | Chỉ B APPLIED; A hiển thị Đã được thay thế — Không áp dụng, vẫn xem/tải bản ký cũ. Không phát sinh chênh lệch cọc hoặc phí theo A. |

## UC79 — Bản sửa lỗi không hoàn tất thì giữ phụ lục đã ký trước đó

**Hợp đồng riêng:** chuẩn bị như UC78, không dùng ID đã chạy UC78. Mỗi nhánh dưới đây nên dùng hợp đồng độc lập.

- Người thuê từ chối B / chủ nhà rút B / quá hạn phản hồi hoặc ký B: A vẫn SCHEDULED và được áp dụng đúng 16/11. B không tự thay A, không dùng chữ ký dở dang.
- Người thuê yêu cầu sửa B: chủ nhà gửi C, liên kết chuỗi B→A; A chỉ SUPERSEDED khi C ký đủ. So sánh với A đang chờ, không làm mất các thay đổi đã giữ lại.
- Chủ nhà sửa mọi giá trị về hợp đồng gốc: vẫn tạo B để hai bên duyệt/ký; so sánh A→gốc. Không tự hủy A chỉ vì payload không còn khác hợp đồng gốc.
- Thử sửa ngay trước 00:00 ngày 16/11 khi chưa có hóa đơn chốt: còn được phép; từ 00:00 ngày 16/11 thì khóa. Nếu hóa đơn kỳ ảnh hưởng đã phát hành ngày 15/11, khóa sớm từ thời điểm phát hành.
- Nếu A thay đổi xe/thời hạn/cọc, B trở về điều khoản gốc: đoạn parking và thời hạn tương lai phải trở lại đúng B; không sửa reservation lịch sử hoặc tạo khoản chênh lệch cọc của A đã bị thay thế. PDF hợp đồng gốc giữ nguyên.
