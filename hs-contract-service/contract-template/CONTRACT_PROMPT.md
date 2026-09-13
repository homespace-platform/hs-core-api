# Bộ prompt tạo mẫu hợp đồng HomeSpace

Tài liệu này là đặc tả đầu vào để tạo ba mẫu hợp đồng Word cho HomeSpace. Mỗi mẫu gắn với đúng một loại bất động sản và phải sử dụng chính xác các mã trường được hỗ trợ bên dưới.

## Quy ước chung

### Phạm vi mẫu hợp đồng

Hệ thống chỉ sử dụng ba mẫu:

1. Hợp đồng thuê nhà nguyên căn (`HOUSE`).
2. Hợp đồng thuê căn hộ chung cư (`APARTMENT`).
3. Hợp đồng thuê phòng trọ (`ROOM`).

Mỗi mẫu phải diễn đạt trực tiếp đối tượng và phạm vi tài sản được thuê trong nội dung Điều 1. Không tạo thêm trường động để mô tả phạm vi này.

### Tên file đầu ra bắt buộc

Mỗi prompt chỉ tạo đúng một file Word tương ứng với một loại tài sản:

| Loại tài sản | Tên file |
| --- | --- |
| Nhà nguyên căn | `HomeSpace_01_Hop_Dong_Thue_Nha_Nguyen_Can.docx` |
| Căn hộ chung cư | `HomeSpace_02_Hop_Dong_Thue_Can_Ho_Chung_Cu.docx` |
| Phòng trọ | `HomeSpace_03_Hop_Dong_Thue_Phong_Tro.docx` |

Không ghép tên của nhiều loại tài sản trong cùng một tên file.

### Cách sử dụng mã trường

- Dữ liệu động phải dùng đúng cú pháp hai dấu ngoặc nhọn, ví dụ `{{contract.number}}`.
- Bảng động phải dùng đúng `{{#chargesTable}}` và `{{#equipmentTable}}`.
- Không tự tạo tên biến ngoài danh mục được hỗ trợ.
- Không dùng dấu chấm, dấu gạch ngang hoặc khoảng trống thủ công thay cho dữ liệu động.
- Mỗi mã trường trong file Word phải là một chuỗi liên tục, không bị chia thành nhiều Word run.
- Chỉ chèn trường tùy chọn khi nội dung hợp đồng thực sự cần trường đó. Một trường đã xuất hiện trong mẫu sẽ được kiểm tra dữ liệu trước khi kết xuất.

### Thanh toán qua HomeSpace

Tiền thuê và tiền đặt cọc được thanh toán trực tuyến qua HomeSpace. Không ghi số tài khoản, tên ngân hàng hoặc hướng dẫn chuyển tiền trực tiếp vào tài khoản cá nhân của Bên A. Phương thức thanh toán được điền bằng `{{rent.paymentMethod}}` khi mẫu sử dụng trường này.

### Thuật ngữ cư trú

Sử dụng thuật ngữ **Nơi thường trú**. Không sử dụng các cách gọi liên quan đến sổ hộ khẩu giấy.

### Chu kỳ thanh toán và tiền đặt cọc

Chu kỳ thanh toán tiêu chuẩn là hàng tháng và được điền bằng `{{rent.paymentCycle}}`. Tiền đặt cọc có thể được xác định theo số tháng thuê, số tiền cố định hoặc không đặt cọc; không mô tả tiền cọc là nội dung chưa xác định giữa hai bên.

## Danh mục mã trường được hỗ trợ cho ba mẫu hợp đồng

Danh mục gồm 36 mã trường. Mã không có trong bảng sẽ bị hệ thống từ chối khi tải mẫu Word lên.

| Nhóm | Mã trường | Phạm vi áp dụng | Yêu cầu |
| --- | --- | --- | --- |
| Pháp lý hợp đồng | `contract.number` | Cả ba mẫu | Bắt buộc |
| Pháp lý hợp đồng | `contract.signingDate` | Cả ba mẫu | Bắt buộc |
| Pháp lý hợp đồng | `contract.signingCity` | Cả ba mẫu | Tùy chọn |
| Bên A | `landlord.fullName` | Cả ba mẫu | Bắt buộc |
| Bên A | `landlord.idNumber` | Cả ba mẫu | Tùy chọn |
| Bên A | `landlord.permanentAddress` | Cả ba mẫu | Tùy chọn |
| Bên A | `landlord.phone` | Cả ba mẫu | Bắt buộc |
| Bên A | `landlord.email` | Cả ba mẫu | Tùy chọn |
| Bên B | `tenant.fullName` | Cả ba mẫu | Bắt buộc |
| Bên B | `tenant.idNumber` | Cả ba mẫu | Tùy chọn |
| Bên B | `tenant.permanentAddress` | Cả ba mẫu | Tùy chọn |
| Bên B | `tenant.phone` | Cả ba mẫu | Bắt buộc |
| Bên B | `tenant.email` | Cả ba mẫu | Tùy chọn |
| Bên B | `tenant.occupantCount` | Cả ba mẫu | Bắt buộc |
| Bất động sản | `property.fullAddress` | Cả ba mẫu | Bắt buộc |
| Bất động sản | `property.areaText` | Cả ba mẫu | Bắt buộc |
| Bất động sản | `property.propertyType` | Cả ba mẫu | Bắt buộc |
| Bất động sản | `property.unitNumber` | Căn hộ và phòng | Bắt buộc với căn hộ và phòng |
| Bất động sản | `property.floor` | Cả ba mẫu | Tùy chọn |
| Thời hạn thuê | `lease.startDateText` | Cả ba mẫu | Bắt buộc |
| Thời hạn thuê | `lease.endDateText` | Cả ba mẫu | Bắt buộc |
| Thời hạn thuê | `lease.durationMonths` | Cả ba mẫu | Bắt buộc |
| Thời hạn thuê | `lease.durationText` | Cả ba mẫu | Bắt buộc |
| Thời hạn thuê | `lease.handoverDateText` | Cả ba mẫu | Tùy chọn |
| Giá thuê | `rent.amountNumber` | Cả ba mẫu | Bắt buộc |
| Giá thuê | `rent.amountWords` | Cả ba mẫu | Bắt buộc |
| Giá thuê | `rent.paymentCycle` | Cả ba mẫu | Bắt buộc |
| Giá thuê | `rent.paymentDueDay` | Cả ba mẫu | Bắt buộc |
| Giá thuê | `rent.paymentMethod` | Cả ba mẫu | Tùy chọn |
| Tiền đặt cọc | `deposit.amountNumber` | Cả ba mẫu | Bắt buộc |
| Tiền đặt cọc | `deposit.amountWords` | Cả ba mẫu | Bắt buộc |
| Tiền đặt cọc | `deposit.description` | Cả ba mẫu | Tùy chọn |
| Chỉ số bàn giao | `meters.electricityInitial` | Cả ba mẫu | Tùy chọn |
| Chỉ số bàn giao | `meters.waterInitial` | Cả ba mẫu | Tùy chọn |
| Bảng động | `#chargesTable` | Cả ba mẫu | Bắt buộc |
| Bảng động | `#equipmentTable` | Cả ba mẫu | Tùy chọn |

---

## Prompt 1 Hợp đồng thuê nhà nguyên căn

```markdown
BỐI CẢNH VÀ VAI TRÒ:
Bạn là chuyên gia pháp lý kiêm kỹ sư thiết kế mẫu hợp đồng cho nền tảng bất động sản HomeSpace tại Việt Nam.

HomeSpace tự động lấy dữ liệu từ hồ sơ người dùng, tin đăng và yêu cầu thuê để điền vào các mã trường như {{contract.number}} trong file Word. Hệ thống dùng poi-tl để kết xuất hợp đồng và tạo các bảng động.

NHIỆM VỤ:
Soạn toàn văn mẫu HỢP ĐỒNG THUÊ NHÀ NGUYÊN CĂN áp dụng cho nhà phố, biệt thự và nhà riêng lẻ. Văn bản phải chặt chẽ, dễ hiểu, bảo vệ tài sản và kiến trúc của căn nhà.

1. CĂN CỨ PHÁP LÝ
- Bộ luật Dân sự số 91/2015/QH13 và các quy định về hợp đồng thuê tài sản.
- Luật Nhà ở số 27/2023/QH15 và quy định về cho thuê nhà ở riêng lẻ.
- Luật Cư trú số 68/2020/QH14.
- Quy định hiện hành về phòng cháy chữa cháy, an ninh trật tự và quản lý cư trú.

2. THÔNG TIN CẦN ĐẶT VÀO HỢP ĐỒNG
- Số hợp đồng: {{contract.number}}.
- Ngày ký: {{contract.signingDate}}.
- Nơi ký nếu sử dụng: {{contract.signingCity}}.
- Bên A: {{landlord.fullName}}, CCCD {{landlord.idNumber}}, nơi thường trú {{landlord.permanentAddress}}, điện thoại {{landlord.phone}}, email {{landlord.email}}.
- Bên B: {{tenant.fullName}}, CCCD {{tenant.idNumber}}, nơi thường trú {{tenant.permanentAddress}}, điện thoại {{tenant.phone}}, email {{tenant.email}}, số người ở {{tenant.occupantCount}}.
- Căn nhà: địa chỉ {{property.fullAddress}}, diện tích {{property.areaText}}, loại tài sản {{property.propertyType}}, kết cấu hoặc số tầng {{property.floor}}.
- Phải viết trực tiếp rằng Bên A đồng ý cho Bên B thuê toàn bộ căn nhà được mô tả tại Điều 1.
- Thời hạn: từ {{lease.startDateText}} đến {{lease.endDateText}}, tương đương {{lease.durationText}} và {{lease.durationMonths}} tháng.
- Ngày bàn giao nếu sử dụng: {{lease.handoverDateText}}.
- Giá thuê: {{rent.amountNumber}}, bằng chữ {{rent.amountWords}}.
- Chu kỳ thanh toán: {{rent.paymentCycle}}; hạn thanh toán: {{rent.paymentDueDay}}.
- Phương thức thanh toán nếu sử dụng: {{rent.paymentMethod}}.
- Tiền đặt cọc: {{deposit.amountNumber}}, bằng chữ {{deposit.amountWords}}.
- Điều kiện hoàn trả cọc nếu sử dụng: {{deposit.description}}.
- Chỉ số điện, nước khi bàn giao nếu sử dụng: {{meters.electricityInitial}}, {{meters.waterInitial}}.
- Bảng phí dịch vụ: {{#chargesTable}}.
- Bảng tài sản và thiết bị bàn giao nếu sử dụng: {{#equipmentTable}}.

3. ĐIỀU KHOẢN ĐẶC THÙ
- Phạm vi quản lý toàn bộ căn nhà, khuôn viên, cổng, mái và hệ thống kỹ thuật thuộc tài sản thuê.
- Không tự ý khoan đục tường chịu lực, cơi nới hoặc thay đổi kết cấu khi chưa có chấp thuận bằng văn bản của Bên A.
- Bảo đảm an ninh trật tự, phòng cháy chữa cháy và sử dụng đúng mục đích ở hợp pháp.
- Trách nhiệm sửa chữa hư hỏng do lỗi sử dụng và hoàn trả căn nhà theo hiện trạng đã bàn giao, trừ hao mòn tự nhiên.
- Tiền thuê và tiền cọc được thanh toán qua HomeSpace; không ghi thông tin tài khoản ngân hàng cá nhân của Bên A.

4. HÌNH THỨC TRÌNH BÀY
- Viết đầy đủ từ Quốc hiệu, Tiêu ngữ, tên hợp đồng, thông tin hai bên, các điều khoản đến phần ký tên.
- Không dùng chỗ trống thủ công cho dữ liệu động.
- Không tự tạo mã trường mới.
- Trình bày theo phong cách hợp đồng hành chính Việt Nam, khổ A4, font Times New Roman, cỡ chữ nội dung khoảng 13 pt.
```

---

## Prompt 2 Hợp đồng thuê căn hộ chung cư

```markdown
BỐI CẢNH VÀ VAI TRÒ:
Bạn là chuyên gia pháp lý kiêm kỹ sư thiết kế mẫu hợp đồng cho nền tảng bất động sản HomeSpace tại Việt Nam.

HomeSpace tự động lấy dữ liệu từ hồ sơ người dùng, tin đăng và yêu cầu thuê để điền vào các mã trường như {{contract.number}} trong file Word. Hệ thống dùng poi-tl để kết xuất hợp đồng và tạo các bảng động.

NHIỆM VỤ:
Soạn toàn văn mẫu HỢP ĐỒNG THUÊ CĂN HỘ CHUNG CƯ hoàn chỉnh, chặt chẽ và phù hợp quy định quản lý, sử dụng nhà chung cư.

1. CĂN CỨ PHÁP LÝ
- Bộ luật Dân sự số 91/2015/QH13 và các quy định về hợp đồng thuê tài sản.
- Luật Nhà ở số 27/2023/QH15.
- Luật Kinh doanh bất động sản số 29/2023/QH15 trong phạm vi áp dụng.
- Quy định hiện hành về quản lý nhà chung cư và phòng cháy chữa cháy nhà cao tầng.

2. THÔNG TIN CẦN ĐẶT VÀO HỢP ĐỒNG
- Số hợp đồng: {{contract.number}}.
- Ngày ký: {{contract.signingDate}}.
- Nơi ký nếu sử dụng: {{contract.signingCity}}.
- Bên A: {{landlord.fullName}}, CCCD {{landlord.idNumber}}, nơi thường trú {{landlord.permanentAddress}}, điện thoại {{landlord.phone}}, email {{landlord.email}}.
- Bên B: {{tenant.fullName}}, CCCD {{tenant.idNumber}}, nơi thường trú {{tenant.permanentAddress}}, điện thoại {{tenant.phone}}, email {{tenant.email}}, số người cư trú {{tenant.occupantCount}}.
- Căn hộ: số căn {{property.unitNumber}}, tầng {{property.floor}}, địa chỉ {{property.fullAddress}}, diện tích {{property.areaText}}, loại tài sản {{property.propertyType}}.
- Phải viết trực tiếp rằng Bên A đồng ý cho Bên B thuê toàn bộ căn hộ được mô tả tại Điều 1.
- Thời hạn: từ {{lease.startDateText}} đến {{lease.endDateText}}, tương đương {{lease.durationText}} và {{lease.durationMonths}} tháng.
- Ngày bàn giao nếu sử dụng: {{lease.handoverDateText}}.
- Giá thuê: {{rent.amountNumber}}, bằng chữ {{rent.amountWords}}.
- Chu kỳ thanh toán: {{rent.paymentCycle}}; hạn thanh toán: {{rent.paymentDueDay}}.
- Phương thức thanh toán nếu sử dụng: {{rent.paymentMethod}}.
- Tiền đặt cọc: {{deposit.amountNumber}}, bằng chữ {{deposit.amountWords}}.
- Điều kiện hoàn trả cọc nếu sử dụng: {{deposit.description}}.
- Chỉ số điện, nước khi bàn giao nếu sử dụng: {{meters.electricityInitial}}, {{meters.waterInitial}}.
- Bảng phí quản lý, gửi xe, điện, nước và dịch vụ: {{#chargesTable}}.
- Bảng thiết bị, nội thất bàn giao nếu sử dụng: {{#equipmentTable}}.

3. ĐIỀU KHOẢN ĐẶC THÙ
- Tuân thủ nội quy của Ban quản lý hoặc Ban quản trị tòa nhà.
- Quy định về thẻ cư dân, thang máy, gửi xe, chuyển đồ và đăng ký người cư trú.
- Không can thiệp hệ thống báo cháy, đầu phun, lối thoát hiểm hoặc thiết bị an toàn chung.
- Phân định rõ trách nhiệm đối với phí quản lý, gửi xe và các dịch vụ sử dụng thực tế.
- Quy định kiểm tra định kỳ, bảo quản nội thất và khấu trừ tiền cọc khi có thiệt hại do lỗi của Bên B.
- Tiền thuê và tiền cọc được thanh toán qua HomeSpace; không ghi thông tin tài khoản ngân hàng cá nhân của Bên A.

4. HÌNH THỨC TRÌNH BÀY
- Viết đầy đủ từ Quốc hiệu, Tiêu ngữ, tên hợp đồng, thông tin hai bên, các điều khoản đến phần ký tên.
- Không dùng chỗ trống thủ công cho dữ liệu động.
- Không tự tạo mã trường mới.
- Trình bày theo phong cách hợp đồng hành chính Việt Nam, khổ A4, font Times New Roman, cỡ chữ nội dung khoảng 13 pt.
```

---

## Prompt 3 Hợp đồng thuê phòng trọ

```markdown
BỐI CẢNH VÀ VAI TRÒ:
Bạn là chuyên gia pháp lý kiêm kỹ sư thiết kế mẫu hợp đồng cho nền tảng bất động sản HomeSpace tại Việt Nam.

HomeSpace tự động lấy dữ liệu từ hồ sơ người dùng, tin đăng và yêu cầu thuê để điền vào các mã trường như {{contract.number}} trong file Word. Hệ thống dùng poi-tl để kết xuất hợp đồng và tạo các bảng động.

NHIỆM VỤ:
Soạn toàn văn mẫu HỢP ĐỒNG THUÊ PHÒNG TRỌ rõ ràng, dễ áp dụng, tập trung vào an toàn phòng cháy chữa cháy, an ninh trật tự và quản lý cư trú.

1. CĂN CỨ PHÁP LÝ
- Bộ luật Dân sự số 91/2015/QH13.
- Luật Nhà ở số 27/2023/QH15.
- Luật Cư trú số 68/2020/QH14.
- Quy định hiện hành về phòng cháy chữa cháy, nhà ở nhiều tầng, cơ sở lưu trú và nhà trọ cho thuê.

2. THÔNG TIN CẦN ĐẶT VÀO HỢP ĐỒNG
- Số hợp đồng: {{contract.number}}.
- Ngày ký: {{contract.signingDate}}.
- Nơi ký nếu sử dụng: {{contract.signingCity}}.
- Bên A: {{landlord.fullName}}, CCCD {{landlord.idNumber}}, nơi thường trú {{landlord.permanentAddress}}, điện thoại {{landlord.phone}}, email {{landlord.email}}.
- Bên B: {{tenant.fullName}}, CCCD {{tenant.idNumber}}, nơi thường trú {{tenant.permanentAddress}}, điện thoại {{tenant.phone}}, email {{tenant.email}}, số người ở {{tenant.occupantCount}}.
- Phòng trọ: số phòng {{property.unitNumber}}, tầng {{property.floor}}, địa chỉ {{property.fullAddress}}, diện tích {{property.areaText}}, loại tài sản {{property.propertyType}}.
- Phải viết trực tiếp rằng Bên A đồng ý cho Bên B thuê phòng trọ được mô tả tại Điều 1.
- Thời hạn: từ {{lease.startDateText}} đến {{lease.endDateText}}, tương đương {{lease.durationText}} và {{lease.durationMonths}} tháng.
- Ngày bàn giao nếu sử dụng: {{lease.handoverDateText}}.
- Tiền thuê: {{rent.amountNumber}}, bằng chữ {{rent.amountWords}}.
- Chu kỳ thanh toán: {{rent.paymentCycle}}; hạn thanh toán: {{rent.paymentDueDay}}.
- Phương thức thanh toán nếu sử dụng: {{rent.paymentMethod}}.
- Tiền đặt cọc: {{deposit.amountNumber}}, bằng chữ {{deposit.amountWords}}.
- Điều kiện hoàn trả cọc nếu sử dụng: {{deposit.description}}.
- Chỉ số điện, nước khi bàn giao nếu sử dụng: {{meters.electricityInitial}}, {{meters.waterInitial}}.
- Bảng đơn giá điện, nước, internet, rác, giặt sấy và gửi xe: {{#chargesTable}}.
- Bảng thiết bị, nội thất bàn giao nếu sử dụng: {{#equipmentTable}}.

3. ĐIỀU KHOẢN ĐẶC THÙ
- Không sử dụng vượt quá {{tenant.occupantCount}} người đã đăng ký.
- Bên B cung cấp thông tin cần thiết để thực hiện đăng ký tạm trú.
- Tuân thủ nội quy chung về khách ở qua đêm, giờ giấc, tiếng ồn, vệ sinh và khu vực sử dụng chung.
- Không sạc pin xe điện trái quy định; không sử dụng nguồn lửa, thiết bị đun nấu hoặc thiết bị điện không bảo đảm an toàn.
- Không che chắn lối thoát nạn, hành lang hoặc phương tiện phòng cháy chữa cháy.
- Quy định trách nhiệm bồi thường tài sản và xử lý vi phạm nội quy.
- Tiền thuê và tiền cọc được thanh toán qua HomeSpace; không ghi thông tin tài khoản ngân hàng cá nhân của Bên A.

4. HÌNH THỨC TRÌNH BÀY
- Viết đầy đủ từ Quốc hiệu, Tiêu ngữ, tên hợp đồng, thông tin hai bên, các điều khoản đến phần ký tên.
- Không dùng chỗ trống thủ công cho dữ liệu động.
- Không tự tạo mã trường mới.
- Trình bày theo phong cách hợp đồng hành chính Việt Nam, khổ A4, font Times New Roman, cỡ chữ nội dung khoảng 13 pt.
```

## Yêu cầu kiểm tra trước khi sử dụng mẫu Word

- Mẫu chỉ chứa các mã thuộc danh mục 36 trường.
- Các trường bắt buộc phù hợp với loại tài sản đều xuất hiện trong mẫu.
- Mỗi mã trường là một chuỗi liên tục trong Word.
- `{{#chargesTable}}` và `{{#equipmentTable}}` giữ nguyên dấu `#`.
- Không có chỗ trống thủ công dành cho dữ liệu hệ thống.
- Không có số tài khoản hoặc tên ngân hàng cá nhân của Bên A.
- Nội dung xác định rõ đối tượng thuê tương ứng với tên của từng mẫu.
