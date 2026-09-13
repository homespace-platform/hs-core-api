Dưới đây là **5 bộ Prompt hoàn chỉnh**, được bổ sung thêm phần **BỐI CẢNH HỆ THỐNG & NHIỆM VỤ CHI TIẾT** ngay trên đầu. Nhờ đó, ChatGPT sẽ hiểu ngay mình đang đóng vai trò gì, hệ thống HomeSpace hoạt động ra sao và lý do vì sao bắt buộc phải dùng chuẩn xác các thẻ `{{...}}`.

---

## Quy ước chung (áp dụng cho cả 5 mẫu)

**Mỗi mẫu hợp đồng gắn với đúng một loại hình bất động sản.** Không còn khái niệm "hình thức thuê" ở cấp mẫu: nguyên căn hay một phần / phòng riêng được hệ thống điền động vào văn bản qua mã trường `{{lease.rentalMode}}`, nên mẫu nào cũng phải chèn thẻ này.

**Thanh toán qua hệ thống.** Tiền thuê và tiền cọc được thanh toán trực tuyến qua hệ thống HomeSpace, không chuyển khoản trực tiếp vào tài khoản cá nhân của chủ nhà. Vì vậy **không được đưa số tài khoản hay tên ngân hàng vào hợp đồng**; phương thức thanh toán đã nằm trong `{{rent.paymentMethod}}`.

**Thuật ngữ cư trú.** Sổ hộ khẩu giấy đã hết hiệu lực từ 01/01/2023 theo Luật Cư trú số 68/2020/QH14. Trong toàn bộ văn bản dùng cách gọi **"Nơi thường trú"**, tuyệt đối không dùng "hộ khẩu" hay "hộ khẩu thường trú".

**Chu kỳ thanh toán & Tiền cọc.** Nền tảng HomeSpace chuẩn hóa chu kỳ thanh toán là hàng tháng (mã trường `{{rent.paymentCycle}}` được hệ thống điền tự động giá trị "Hàng tháng"). Quy định tiền đặt cọc áp dụng theo số tháng thuê, số tiền cố định hoặc không cọc; hệ thống không áp dụng hình thức cọc thỏa thuận.

### Danh mục mã trường được hệ thống hỗ trợ (39 trường)

Mã nào không có trong bảng này sẽ bị hệ thống báo lỗi khi Admin tải file Word lên.

| Nhóm | Mã trường | Bắt buộc |
| --- | --- | --- |
| Pháp lý hợp đồng | `contract.number`, `contract.signingDate` | Cả 5 loại hình |
| Pháp lý hợp đồng | `contract.signingCity` | Tùy chọn |
| Bên A | `landlord.fullName`, `landlord.phone` | Cả 5 loại hình |
| Bên A | `landlord.idNumber`, `landlord.permanentAddress`, `landlord.email` | Tùy chọn |
| Bên B | `tenant.fullName`, `tenant.phone` | Cả 5 loại hình |
| Bên B | `tenant.occupantCount` | APARTMENT, HOUSE, ROOM |
| Bên B | `tenant.organizationName`, `tenant.representativeName`, `tenant.representativePosition` | OFFICE, COMMERCIAL_SPACE |
| Bên B | `tenant.idNumber`, `tenant.permanentAddress`, `tenant.email` | Tùy chọn |
| Bất động sản | `property.fullAddress`, `property.areaText`, `property.propertyType` | Cả 5 loại hình |
| Bất động sản | `property.unitNumber`, `property.floor` | Tùy chọn |
| Thời hạn thuê | `lease.rentalMode`, `lease.startDateText`, `lease.endDateText`, `lease.durationMonths`, `lease.durationText` | Cả 5 loại hình |
| Thời hạn thuê | `lease.handoverDateText` | Tùy chọn |
| Giá thuê & Cọc | `rent.amountNumber`, `rent.amountWords`, `rent.paymentCycle`, `rent.paymentDueDay`, `deposit.amountNumber`, `deposit.amountWords` | Cả 5 loại hình |
| Giá thuê & Cọc | `rent.paymentMethod`, `deposit.description` | Tùy chọn |
| Chỉ số bàn giao | `meters.electricityInitial`, `meters.waterInitial` | Tùy chọn |
| Bảng động | `#chargesTable` | Cả 5 loại hình |
| Bảng động | `#equipmentTable` | Tùy chọn |

---

### Prompt 1: Hợp đồng Thuê Nhà Nguyên Căn / Biệt Thự / Nhà Phố (`HOUSE`)

```markdown
BỐI CẢNH DỰ ÁN & VAI TRÒ CỦA BẠN:
Bạn đang đóng vai trò là Chuyên gia Pháp lý kiêm Kỹ sư Thiết kế Mẫu Hợp đồng cho nền tảng công nghệ bất động sản HomeSpace (Việt Nam).
Hệ thống HomeSpace có tính năng tạo hợp đồng thuê điện tử tự động:
- Quản trị viên (Admin) tải file mẫu Word (.docx) lên hệ thống.
- Backend (sử dụng thư viện Java poi-tl) sẽ tự động quét và phân tích các mã trường dạng {{object.field}} trong văn bản.
- Khi chủ nhà duyệt yêu cầu thuê, hệ thống sẽ tự động lấy dữ liệu từ cơ sở dữ liệu và điền vào các thẻ {{...}}, đồng thời vẽ tự động 2 bảng động {{#chargesTable}} và {{#equipmentTable}}, sau đó xuất ra file PDF/Word cho các bên ký kết.
- YÊU CẦU CỐT LÕI: Tuyệt đối KHÔNG ĐƯỢC dùng dấu gạch ngang (.......) chừa trống thủ công. Phải dùng đúng 100% các biến {{...}} để hệ thống tự động điền dữ liệu.

---

NHIỆM VỤ CỦA BẠN:
Hãy soạn thảo toàn bộ nội dung mẫu "HỢP ĐỒNG THUÊ NHÀ NGUYÊN CĂN" (áp dụng cho Nhà phố, Biệt thự, Nhà riêng lẻ) có tính pháp lý vững chắc, bảo vệ toàn vẹn tài sản kiến trúc của căn nhà.

1. CĂN CỨ PHÁP LÝ (Hãy tra cứu và viện dẫn chính xác):
- Bộ luật Dân sự số 91/2015/QH13 (các điều từ 472 đến 482 về quyền sở hữu, quyền sử dụng đất và nhà ở).
- Luật Nhà ở số 27/2023/QH15 (quy định về cho thuê nhà ở riêng lẻ).
- Luật Cư trú số 68/2020/QH14 (nghĩa vụ khai báo tạm trú và quản lý nhân khẩu).
- Các quy định an toàn PCCC dân dụng và trật tự đô thị, an ninh khu dân cư.

2. CÁC MÃ TRƯỜNG BẮT BUỘC ĐẶT VÀO HỢP ĐỒNG:
- Số hợp đồng: {{contract.number}}, ngày ký: {{contract.signingDate}}, tại: {{contract.signingCity}}
- Bên cho thuê (Bên A): {{landlord.fullName}}, CCCD: {{landlord.idNumber}}, nơi thường trú: {{landlord.permanentAddress}}, SĐT: {{landlord.phone}}, email: {{landlord.email}}
- Bên thuê (Bên B): {{tenant.fullName}}, CCCD: {{tenant.idNumber}}, nơi thường trú: {{tenant.permanentAddress}}, SĐT: {{tenant.phone}}, email: {{tenant.email}}, số lượng người ở: {{tenant.occupantCount}} người.
- Căn nhà cho thuê: Địa chỉ: {{property.fullAddress}}, kết cấu/số tầng: {{property.floor}}, diện tích: {{property.areaText}}, loại nhà: {{property.propertyType}}
- Hình thức thuê: {{lease.rentalMode}}
- Thời hạn thuê: Từ ngày {{lease.startDateText}} đến ngày {{lease.endDateText}} (thời hạn {{lease.durationText}} - {{lease.durationMonths}} tháng). Ngày bàn giao chìa khóa: {{lease.handoverDateText}}
- Giá thuê & Đặt cọc: Đơn giá thuê: {{rent.amountNumber}} (bằng chữ: {{rent.amountWords}}). Kỳ thanh toán: {{rent.paymentCycle}}, hạn thanh toán: {{rent.paymentDueDay}}, hình thức: {{rent.paymentMethod}}. Tiền đặt cọc: {{deposit.amountNumber}} (bằng chữ: {{deposit.amountWords}}), điều khoản hoàn trả cọc: {{deposit.description}}
- Bàn giao kỹ thuật: Số công tơ điện: {{meters.electricityInitial}}, công tơ nước: {{meters.waterInitial}}
- BẢNG ĐỘNG TỰ SINH (Giữ nguyên dấu #):
  + Bảng chi phí rác sinh hoạt, dịch vụ phụ trợ: {{#chargesTable}}
  + Bảng biên bản kiểm kê hiện trạng kết cấu nhà, thiết bị và nội thất bàn giao: {{#equipmentTable}}

3. ĐIỀU KHOẢN ĐẶC THÙ CHO NHÀ NGUYÊN CĂN:
- Quyền quản lý toàn bộ khuôn viên, tường rào, cổng ngõ, mái nhà, hệ thống điện nước ngầm và đồng hồ tổng.
- Nghiêm cấm Bên B tự ý khoan đục tường chịu lực, cơi nới, thay đổi kết cấu chịu lực hoặc kiến trúc của căn nhà khi chưa có sự đồng ý bằng văn bản của Bên A.
- Trách nhiệm của Bên B về an ninh trật tự khu phố, không chứa chấp hàng cấm, không tổ chức tệ nạn xã hội.
- Nghĩa vụ hoàn trả căn nhà đúng hiện trạng ban đầu, chịu trách nhiệm sửa chữa các hư hỏng phát sinh do quá trình sử dụng.
- Thanh toán qua hệ thống: Tiền thuê và tiền cọc được nộp trực tuyến qua nền tảng HomeSpace theo phương thức {{rent.paymentMethod}}; không ghi số tài khoản hay tên ngân hàng cá nhân của Bên A vào hợp đồng.

Hãy viết hoàn chỉnh văn bản với ngôn từ pháp lý chuẩn mực, logic và chuyên nghiệp.
```

---

### Prompt 2: Hợp đồng Thuê Căn Hộ Chung Cư (`APARTMENT`)

```markdown
BỐI CẢNH DỰ ÁN & VAI TRÒ CỦA BẠN:
Bạn đang đóng vai trò là Chuyên gia Pháp lý kiêm Kỹ sư Thiết kế Mẫu Hợp đồng cho nền tảng công nghệ bất động sản HomeSpace (Việt Nam).
Hệ thống HomeSpace có tính năng tạo hợp đồng thuê điện tử tự động:
- Quản trị viên (Admin) tải file mẫu Word (.docx) lên hệ thống.
- Backend (sử dụng thư viện Java poi-tl) sẽ tự động quét và phân tích các mã trường dạng {{object.field}} trong văn bản.
- Khi chủ nhà duyệt yêu cầu thuê, hệ thống sẽ tự động lấy dữ liệu từ cơ sở dữ liệu và điền vào các thẻ {{...}}, đồng thời vẽ tự động 2 bảng động {{#chargesTable}} và {{#equipmentTable}}, sau đó xuất ra file PDF/Word cho các bên ký kết.
- YÊU CẦU CỐT LÕI: Bạn KHÔNG ĐƯỢC để dấu chấm ba chấm (.......) thủ công ở các trường thông tin. Phải cắm chuẩn xác 100% các biến {{...}} đã được định nghĩa bên dưới, không tự ý bịa thêm tên biến khác.

---

NHIỆM VỤ CỦA BẠN:
Hãy soạn thảo toàn bộ nội dung mẫu "HỢP ĐỒNG THUÊ CĂN HỘ CHUNG CƯ" hoàn chỉnh, chặt chẽ, chuẩn quy chuẩn hành chính và pháp luật Việt Nam hiện hành.

1. CĂN CỨ PHÁP LÝ (Hãy tra cứu và viện dẫn chính xác):
- Bộ luật Dân sự số 91/2015/QH13 (các điều từ 472 đến 482 về hợp đồng thuê tài sản).
- Luật Nhà ở số 27/2023/QH15 (hiệu lực từ 01/08/2024 - các quy định về quản lý sử dụng chung cư, bảo trì, đỗ xe).
- Luật Kinh doanh Bất động sản số 29/2023/QH15.
- Quy chế quản lý, sử dụng nhà chung cư do Bộ Xây dựng ban hành và quy định an toàn PCCC nhà cao tầng.

2. CÁC MÃ TRƯỜNG BẮT BUỘC ĐẶT VÀO HỢP ĐỒNG:
- Pháp lý hợp đồng: Số hợp đồng: {{contract.number}}, ngày ký: {{contract.signingDate}}, nơi ký: {{contract.signingCity}}
- Bên cho thuê (Bên A): {{landlord.fullName}}, CCCD: {{landlord.idNumber}}, nơi thường trú: {{landlord.permanentAddress}}, SĐT: {{landlord.phone}}, email: {{landlord.email}}
- Bên thuê (Bên B): {{tenant.fullName}}, CCCD: {{tenant.idNumber}}, nơi thường trú: {{tenant.permanentAddress}}, SĐT: {{tenant.phone}}, email: {{tenant.email}}, số người cư trú: {{tenant.occupantCount}}
- Căn hộ cho thuê: Tầng {{property.floor}}, Căn số {{property.unitNumber}}, thuộc tòa nhà tại {{property.fullAddress}}, diện tích: {{property.areaText}}, loại hình: {{property.propertyType}}
- Hình thức thuê: {{lease.rentalMode}}
- Thời hạn thuê: Từ ngày {{lease.startDateText}} đến ngày {{lease.endDateText}} (thời hạn {{lease.durationText}} - {{lease.durationMonths}} tháng). Ngày bàn giao: {{lease.handoverDateText}}
- Giá thuê & Đặt cọc: Giá thuê bằng số: {{rent.amountNumber}} (bằng chữ: {{rent.amountWords}}). Kỳ hạn thanh toán: {{rent.paymentCycle}}, hạn đóng tiền: {{rent.paymentDueDay}}, phương thức: {{rent.paymentMethod}}. Tiền cọc: {{deposit.amountNumber}} (bằng chữ: {{deposit.amountWords}}), điều khoản hoàn trả cọc: {{deposit.description}}
- Chỉ số bàn giao: Đồng hồ điện: {{meters.electricityInitial}}, Đồng hồ nước: {{meters.waterInitial}}
- BẢNG ĐỘNG TỰ SINH BỞI HỆ THỐNG (Giữ nguyên dấu #):
  + Bảng biểu phí dịch vụ tòa nhà, gửi xe, điện nước: đặt tag {{#chargesTable}}
  + Bảng danh mục trang thiết bị, nội thất bàn giao: đặt tag {{#equipmentTable}}

3. ĐIỀU KHOẢN ĐẶC THÙ CHO CĂN HỘ CHUNG CƯ:
- Tuân thủ nghiêm ngặt Nội quy Ban Quản lý / Ban Quản trị tòa nhà chung cư, quy định chuyển đồ, đăng ký thẻ cư dân, thẻ thang máy, thẻ gửi xe.
- An toàn PCCC nhà cao tầng: Cấm can thiệp hoặc che chắn đầu báo khói/đầu phun cứu hỏa; cấm nướng than, đốt vàng mã ngoài ban công; cấm mang xe điện/pin xe điện lên căn hộ sạc sai quy định.
- Trách nhiệm thanh toán: Phân định rõ Bên nào chịu phí quản lý chung cư, phí gửi xe, phí dịch vụ.
- Thanh toán qua hệ thống: Tiền thuê và tiền cọc được nộp trực tuyến qua nền tảng HomeSpace theo phương thức {{rent.paymentMethod}}; không ghi số tài khoản hay tên ngân hàng cá nhân của Bên A vào hợp đồng.
- Quy định kiểm tra căn hộ định kỳ và khấu trừ tiền đặt cọc nếu làm hư hại nội thất hoặc vi phạm nội quy tòa nhà.

Hãy viết hoàn chỉnh văn bản từ Quốc hiệu, Tiêu ngữ, Tên hợp đồng, các Điều khoản (Điều 1 đến Điều 10) đến phần ký tên của hai bên.
```

---

### Prompt 3: Hợp đồng Thuê Phòng Trọ / Căn Hộ Dịch Vụ (`ROOM`)

```markdown
BỐI CẢNH DỰ ÁN & VAI TRÒ CỦA BẠN:
Bạn đang đóng vai trò là Chuyên gia Pháp lý kiêm Kỹ sư Thiết kế Mẫu Hợp đồng cho nền tảng công nghệ bất động sản HomeSpace (Việt Nam).
Hệ thống HomeSpace có tính năng tạo hợp đồng thuê điện tử tự động:
- Quản trị viên (Admin) tải file mẫu Word (.docx) lên hệ thống.
- Backend (sử dụng thư viện Java poi-tl) sẽ tự động quét và phân tích các mã trường dạng {{object.field}} trong văn bản.
- Khi chủ nhà duyệt yêu cầu thuê, hệ thống sẽ tự động lấy dữ liệu từ cơ sở dữ liệu và điền vào các thẻ {{...}}, đồng thời vẽ tự động 2 bảng động {{#chargesTable}} và {{#equipmentTable}}, sau đó xuất ra file PDF/Word cho các bên ký kết.
- YÊU CẦU CỐT LÕI: Không dùng dấu chấm (.......) thủ công. Sử dụng chuẩn xác các thẻ {{...}} theo danh mục bên dưới.

---

NHIỆM VỤ CỦA BẠN:
Hãy soạn thảo toàn văn mẫu "HỢP ĐỒNG THUÊ PHÒNG TRỌ / CĂN HỘ DỊCH VỤ" ngắn gọn, rõ ràng, tập trung vào an toàn PCCC, an ninh trật tự và quy chế phòng trọ.

1. CĂN CỨ PHÁP LÝ (Hãy tra cứu và viện dẫn chính xác):
- Bộ luật Dân sự số 91/2015/QH13.
- Luật Nhà ở số 27/2023/QH15 (Điều 57 về quy định PCCC và điều kiện phát triển nhà ở nhiều tầng có nhiều căn hộ, nhà trọ cho thuê).
- Chỉ thị của Thủ tướng Chính phủ và Bộ Công an về kiểm tra, chấn chỉnh an toàn PCCC tại các loại hình nhà trọ, phòng trọ.
- Luật Cư trú số 68/2020/QH14 (thủ tục đăng ký tạm trú).

2. CÁC MÃ TRƯỜNG BẮT BUỘC ĐẶT VÀO HỢP ĐỒNG:
- Số hợp đồng: {{contract.number}}, ngày lập: {{contract.signingDate}}, tại: {{contract.signingCity}}
- Bên cho thuê (Chủ trọ - Bên A): {{landlord.fullName}}, CCCD: {{landlord.idNumber}}, nơi thường trú: {{landlord.permanentAddress}}, SĐT: {{landlord.phone}}, email: {{landlord.email}}
- Bên thuê (Bên B): {{tenant.fullName}}, CCCD: {{tenant.idNumber}}, nơi thường trú: {{tenant.permanentAddress}}, SĐT: {{tenant.phone}}, email: {{tenant.email}}, số lượng người ở: {{tenant.occupantCount}}
- Phòng cho thuê: Phòng số {{property.unitNumber}}, Tầng {{property.floor}}, tại địa chỉ {{property.fullAddress}}, diện tích: {{property.areaText}}, loại hình: {{property.propertyType}}
- Hình thức thuê: {{lease.rentalMode}}
- Thời hạn thuê: Từ {{lease.startDateText}} đến {{lease.endDateText}} (thời hạn: {{lease.durationText}} - {{lease.durationMonths}} tháng). Bàn giao ngày: {{lease.handoverDateText}}
- Giá thuê & Đặt cọc: Tiền phòng: {{rent.amountNumber}} (bằng chữ: {{rent.amountWords}}). Kỳ thanh toán: {{rent.paymentCycle}}, hạn đóng: {{rent.paymentDueDay}}, hình thức: {{rent.paymentMethod}}. Tiền cọc: {{deposit.amountNumber}} (bằng chữ: {{deposit.amountWords}}), điều khoản hoàn trả cọc: {{deposit.description}}
- Số đồng hồ bàn giao: Điện: {{meters.electricityInitial}}, Nước: {{meters.waterInitial}}
- BẢNG ĐỘNG TỰ SINH (Giữ nguyên dấu #):
  + Bảng đơn giá điện, nước, internet, rác, máy giặt, gửi xe: {{#chargesTable}}
  + Bảng trang thiết bị bàn giao trong phòng (máy lạnh, quạt, giường nệm, tủ áo...): {{#equipmentTable}}

3. ĐIỀU KHOẢN ĐẶC THÙ CHO PHÒNG TRỌ / CĂN HỘ DỊCH VỤ:
- Giới hạn người ở: Nghiêm cấm ở quá số lượng {{tenant.occupantCount}} người đã đăng ký; khách bên ngoài ở lại qua đêm phải báo trước với Bên A và xuất trình giấy tờ tùy thân.
- An toàn PCCC nghiêm ngặt: Tuyệt đối cấm sạc pin xe đạp điện/xe máy điện qua đêm không người giám sát; cấm sử dụng bếp gas mini không rõ nguồn gốc; cấm đốt nến, vàng mã trong phòng.
- Giờ giấc đóng mở cửa chung, giữ gìn trật tự sau 22h00, không gây ồn ào ảnh hưởng phòng xung quanh.
- Nghĩa vụ cung cấp thông tin để đăng ký tạm trú với Công an phường sở tại.
- Thanh toán qua hệ thống: Tiền phòng và tiền cọc được nộp trực tuyến qua nền tảng HomeSpace theo phương thức {{rent.paymentMethod}}; không ghi số tài khoản hay tên ngân hàng cá nhân của Bên A vào hợp đồng.

Hãy trình bày văn bản đầy đủ từ mở đầu đến kết thúc, có các điều khoản xử lý vi phạm hợp đồng và chữ ký hai bên.
```

---

### Prompt 4: Hợp đồng Thuê Văn Phòng Làm Việc (`OFFICE`)

```markdown
BỐI CẢNH DỰ ÁN & VAI TRÒ CỦA BẠN:
Bạn đang đóng vai trò là Chuyên gia Pháp chế Doanh nghiệp kiêm Kỹ sư Thiết kế Mẫu Hợp đồng cho nền tảng công nghệ bất động sản HomeSpace (Việt Nam).
Hệ thống HomeSpace có tính năng tạo hợp đồng thuê điện tử tự động:
- Quản trị viên (Admin) tải file mẫu Word (.docx) lên hệ thống.
- Backend (sử dụng thư viện Java poi-tl) sẽ tự động quét và phân tích các mã trường dạng {{object.field}} trong văn bản.
- Khi chủ nhà duyệt yêu cầu thuê, hệ thống sẽ tự động lấy dữ liệu từ cơ sở dữ liệu và điền vào các thẻ {{...}}, đồng thời vẽ tự động 2 bảng động {{#chargesTable}} và {{#equipmentTable}}, sau đó xuất ra file PDF/Word cho các bên ký kết.
- YÊU CẦU CỐT LÕI: Phải dùng chuẩn xác 100% các biến {{...}} để hệ thống tự động điền dữ liệu, không dùng dấu chấm (.......) thủ công.

---

NHIỆM VỤ CỦA BẠN:
Hãy soạn thảo toàn văn mẫu "HỢP ĐỒNG THUÊ VĂN PHÒNG LÀM VIỆC" chuẩn mực giao dịch B2B chuyên nghiệp giữa doanh nghiệp và chủ tòa nhà.

1. CĂN CỨ PHÁP LÝ (Hãy tra cứu và viện dẫn chính xác):
- Bộ luật Dân sự số 91/2015/QH13.
- Luật Doanh nghiệp số 59/2020/QH14 (quy định về đặt trụ sở chính, chi nhánh, văn phòng đại diện).
- Luật Kinh doanh Bất động sản số 29/2023/QH15 (Điều 44, 45, 46 về cho thuê phần diện tích sàn xây dựng văn phòng).
- Luật Thuế Giá trị gia tăng và quy định xuất hóa đơn tài chính (VAT).

2. CÁC MÃ TRƯỜNG BẮT BUỘC ĐẶT VÀO HỢP ĐỒNG:
- Số hợp đồng: {{contract.number}}, ngày ký: {{contract.signingDate}}, tại: {{contract.signingCity}}
- Bên cho thuê (Bên A): {{landlord.fullName}}, Mã số thuế/CCCD: {{landlord.idNumber}}, nơi thường trú: {{landlord.permanentAddress}}, SĐT: {{landlord.phone}}, email: {{landlord.email}}
- Bên thuê (Bên B - Khách hàng Doanh nghiệp):
  + Tên công ty/tổ chức: {{tenant.organizationName}}
  + Đại diện pháp luật: {{tenant.representativeName}}, Chức vụ: {{tenant.representativePosition}}
  + Người phụ trách liên hệ: {{tenant.fullName}}, CCCD: {{tenant.idNumber}}, nơi thường trú: {{tenant.permanentAddress}}, SĐT: {{tenant.phone}}, email: {{tenant.email}}
- Diện tích văn phòng: Phòng/Phân khu: {{property.unitNumber}}, Tầng: {{property.floor}}, Tòa nhà tại: {{property.fullAddress}}, Diện tích thuê văn phòng: {{property.areaText}}, Loại hình: {{property.propertyType}}
- Hình thức thuê: {{lease.rentalMode}}
- Thời hạn thuê: Từ ngày {{lease.startDateText}} đến hết ngày {{lease.endDateText}} (thời hạn {{lease.durationText}} - {{lease.durationMonths}} tháng). Ngày bàn giao văn phòng: {{lease.handoverDateText}}

LƯU Ý: Hợp đồng thuê văn phòng KHÔNG dùng mã trường {{tenant.occupantCount}} (số người vào ở) vì không phù hợp nghiệp vụ B2B. Ba mã trường về pháp nhân bên thuê ở trên là bắt buộc.
- Giá thuê & Đặt cọc: Tiền thuê văn phòng: {{rent.amountNumber}} (bằng chữ: {{rent.amountWords}}). Kỳ thanh toán: {{rent.paymentCycle}}, hạn nộp: {{rent.paymentDueDay}}, hình thức: {{rent.paymentMethod}}. Tiền đặt cọc bảo đảm: {{deposit.amountNumber}} (bằng chữ: {{deposit.amountWords}}), điều kiện hoàn cọc: {{deposit.description}}
- Chỉ số bàn giao: Đồng hồ điện: {{meters.electricityInitial}}, Đồng hồ nước: {{meters.waterInitial}}
- BẢNG ĐỘNG TỰ SINH (Giữ nguyên dấu #):
  + Bảng chi tiết phí dịch vụ văn phòng (phí quản lý tòa nhà, phí điều hòa ngoài giờ, phí đỗ xe ô tô/xe máy): {{#chargesTable}}
  + Bảng biên bản kiểm kê bàn giao trang thiết bị văn phòng: {{#equipmentTable}}

3. ĐIỀU KHOẢN ĐẶC THÙ CHO VĂN PHÒNG LÀM VIỆC:
- Quyền đăng ký địa chỉ trụ sở doanh nghiệp / văn phòng đại diện tại Sở Kế hoạch & Đầu tư.
- Quyền đặt biển tên công ty tại sảnh tòa nhà hoặc trước cửa văn phòng.
- Giờ làm việc tiêu chuẩn và cách tính phụ phí làm thêm ngoài giờ (Overtime air-conditioning fee).
- Nghĩa vụ phát hành hóa đơn giá trị gia tăng (Hóa đơn điện tử VAT) hợp pháp của Bên A cho Bên B theo từng đợt thanh toán.
- Quy định về bảo hiểm tài sản, bảo hiểm trách nhiệm công cộng và bảo mật tài liệu doanh nghiệp.
- Thanh toán qua hệ thống: Tiền thuê và tiền cọc bảo đảm được nộp trực tuyến qua nền tảng HomeSpace theo phương thức {{rent.paymentMethod}}; không ghi số tài khoản hay tên ngân hàng của Bên A vào hợp đồng.

Hãy viết chi tiết và đầy đủ toàn văn hợp đồng chuẩn phong cách hợp đồng thương mại B2B cao cấp.
```

---

### Prompt 5: Hợp đồng Thuê Mặt Bằng Kinh Doanh / Cửa Hàng / Shophouse (`COMMERCIAL_SPACE`)

```markdown
BỐI CẢNH DỰ ÁN & VAI TRÒ CỦA BẠN:
Bạn đang đóng vai trò là Chuyên gia Pháp lý Thương mại kiêm Kỹ sư Thiết kế Mẫu Hợp đồng cho nền tảng công nghệ bất động sản HomeSpace (Việt Nam).
Hệ thống HomeSpace có tính năng tạo hợp đồng thuê điện tử tự động:
- Quản trị viên (Admin) tải file mẫu Word (.docx) lên hệ thống.
- Backend (sử dụng thư viện Java poi-tl) sẽ tự động quét và phân tích các mã trường dạng {{object.field}} trong văn bản.
- Khi chủ nhà duyệt yêu cầu thuê, hệ thống sẽ tự động lấy dữ liệu từ cơ sở dữ liệu và điền vào các thẻ {{...}}, đồng thời vẽ tự động 2 bảng động {{#chargesTable}} và {{#equipmentTable}}, sau đó xuất ra file PDF/Word cho các bên ký kết.
- YÊU CẦU CỐT LÕI: Tuyệt đối không để chỗ trống thủ công (.......). Toàn bộ dữ liệu động phải được gắn đúng thẻ {{...}}.

---

NHIỆM VỤ CỦA BẠN:
Hãy soạn thảo toàn văn mẫu "HỢP ĐỒNG THUÊ MẶT BẰNG KINH DOANH" (áp dụng cho Shophouse, Mặt bằng bán lẻ, Cửa hàng, Ki-ốt thương mại) với các điều khoản bảo vệ tính liên tục trong kinh doanh và tài sản mặt bằng.

1. CĂN CỨ PHÁP LÝ (Hãy tra cứu và viện dẫn chính xác):
- Bộ luật Dân sự số 91/2015/QH13.
- Luật Thương mại số 36/2005/QH11 (về quyền và nghĩa vụ trong hoạt động thương mại).
- Luật Kinh doanh Bất động sản số 29/2023/QH15 (quy định về cho thuê công trình xây dựng có sẵn).
- Luật Quảng cáo số 16/2012/QH13 và các quy chuẩn PCCC cơ sở kinh doanh, dịch vụ.

2. CÁC MÃ TRƯỜNG BẮT BUỘC ĐẶT VÀO HỢP ĐỒNG:
- Số hợp đồng: {{contract.number}}, ngày lập: {{contract.signingDate}}, tại: {{contract.signingCity}}
- Bên cho thuê (Bên A): {{landlord.fullName}}, CCCD: {{landlord.idNumber}}, nơi thường trú: {{landlord.permanentAddress}}, SĐT: {{landlord.phone}}, email: {{landlord.email}}
- Bên thuê (Bên B):
  + Tên tổ chức/doanh nghiệp: {{tenant.organizationName}}
  + Người đại diện pháp luật: {{tenant.representativeName}}, Chức vụ: {{tenant.representativePosition}}
  + Cá nhân đại diện ký kết: {{tenant.fullName}}, CCCD: {{tenant.idNumber}}, nơi thường trú: {{tenant.permanentAddress}}, SĐT: {{tenant.phone}}, email: {{tenant.email}}
- Mặt bằng kinh doanh: Địa chỉ: {{property.fullAddress}}, Mã gian/ki-ốt: {{property.unitNumber}}, Diện tích kinh doanh: {{property.areaText}}, Loại hình: {{property.propertyType}}
- Hình thức thuê: {{lease.rentalMode}}
- Thời hạn thuê: Từ ngày {{lease.startDateText}} đến ngày {{lease.endDateText}} (thời hạn {{lease.durationText}} - {{lease.durationMonths}} tháng). Ngày bàn giao mặt bằng: {{lease.handoverDateText}}

LƯU Ý: Hợp đồng thuê mặt bằng kinh doanh KHÔNG dùng mã trường {{tenant.occupantCount}} (số người vào ở) vì không phù hợp nghiệp vụ. Ba mã trường về pháp nhân bên thuê ở trên là bắt buộc.
- Giá thuê & Đặt cọc: Đơn giá thuê: {{rent.amountNumber}} (bằng chữ: {{rent.amountWords}}). Kỳ thanh toán: {{rent.paymentCycle}}, hạn thanh toán: {{rent.paymentDueDay}}, hình thức: {{rent.paymentMethod}}. Tiền đặt cọc bảo đảm: {{deposit.amountNumber}} (bằng chữ: {{deposit.amountWords}}), điều khoản hoàn trả cọc: {{deposit.description}}
- Chỉ số bàn giao: Đồng hồ điện: {{meters.electricityInitial}}, Đồng hồ nước: {{meters.waterInitial}}
- BẢNG ĐỘNG TỰ SINH (Giữ nguyên dấu #):
  + Bảng biểu phí dịch vụ chung, chi phí hạ tầng (nếu có): {{#chargesTable}}
  + Bảng biên bản kiểm kê hiện trạng mặt bằng bàn giao (thô/hoàn thiện cơ bản): {{#equipmentTable}}

3. ĐIỀU KHOẢN ĐẶC THÙ CHO MẶT BẰNG THƯƠNG MẠI:
- Mục đích thuê: Sử dụng mặt bằng đúng ngành nghề đăng ký kinh doanh hợp pháp; không kinh doanh chất cấm, ngành nghề độc hại hoặc gây ô nhiễm môi trường.
- Thời gian cải tạo, lắp đặt (Fit-out period): Quy định thời gian miễn tiền thuê để Bên B tiến hành sửa chữa, trang trí trước ngày khai trương.
- Biển hiệu & Quảng cáo: Quy định về kích thước, vị trí treo lắp bảng hiệu theo Luật Quảng cáo, không gây ảnh hưởng đến mặt tiền và cảnh quan đô thị.
- Giấy phép con và PCCC: Bên B tự chịu trách nhiệm xin giấy phép kinh doanh, an toàn vệ sinh thực phẩm và thẩm duyệt nghiệm thu PCCC cơ sở của mình.
- Điều khoản bồi thường phạt cọc nghiêm khắc nếu Bên A đơn phương thu hồi mặt bằng trước hạn gây thiệt hại kinh doanh cho Bên B.
- Thanh toán qua hệ thống: Tiền thuê và tiền cọc bảo đảm được nộp trực tuyến qua nền tảng HomeSpace theo phương thức {{rent.paymentMethod}}; không ghi số tài khoản hay tên ngân hàng của Bên A vào hợp đồng.

Hãy viết hoàn chỉnh văn bản pháp lý chuyên nghiệp từ mở đầu đến kết thúc.
```

---

### Mẹo thêm:
Nếu bạn muốn ChatGPT **tự động tạo ra luôn file Word (.docx) tải về được**, bạn có thể thêm một câu ngắn ở cuối mỗi prompt:
> *"Hãy viết kèm một đoạn mã Python sử dụng thư viện `python-docx` để tôi có thể chạy và tạo ngay file `.docx` chuẩn định dạng A4, font Times New Roman 13pt từ nội dung hợp đồng trên."*


---

### Mẹo thêm:
Nếu bạn muốn ChatGPT **tự động tạo ra luôn file Word (.docx) tải về được**, bạn có thể thêm một câu ngắn ở cuối mỗi prompt:
> *"Hãy viết kèm một đoạn mã Python sử dụng thư viện `python-docx` để tôi có thể chạy và tạo ngay file `.docx` chuẩn định dạng A4, font Times New Roman 13pt từ nội dung hợp đồng trên."*