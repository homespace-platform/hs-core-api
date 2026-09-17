# BỘ ĐẶC TẢ VÀ PROMPT TẠO MẪU HỢP ĐỒNG HOMESPACE (SCHEMA V2)

Tài liệu này là đặc tả chuẩn hóa (Contract Schema V2) để tạo ba mẫu hợp đồng Word (.docx) cho nền tảng HomeSpace. Mỗi mẫu gắn liền với một loại bất động sản cụ thể và sử dụng công nghệ tạo tài liệu [poi-tl](https://github.com/Sayi/poi-tl).

> **LƯU Ý PHÁP LÝ QUAN TRỌNG:**
> Các mẫu hợp đồng và prompt trong tài liệu này được thiết kế để chuẩn hóa luồng nghiệp vụ giao dịch bất động sản trực tuyến. Tài liệu **không** tuyên bố là "được chứng nhận bởi luật sư" hoặc thay thế tư vấn pháp lý chính thức. Mọi mẫu hợp đồng cần được chuyên gia pháp lý rà soát để phù hợp với điều kiện thực tế của từng địa phương và đối tượng giao dịch trước khi đưa vào môi trường Production.

---

## I. QUY ƯỚC CHUNG VÀ QUY TẮC BẤT BIẾN

### 1. Phạm vi ba mẫu hợp đồng
Hệ thống sử dụng đúng 3 mẫu hợp đồng chuẩn:
1. **Hợp đồng thuê nhà nguyên căn** (`HOUSE`): Dành cho nhà riêng lẻ, nhà phố, biệt thự.
2. **Hợp đồng thuê căn hộ chung cư** (`APARTMENT`): Dành cho căn hộ trong chung cư hoặc tòa nhà phức hợp.
3. **Hợp đồng thuê phòng trọ** (`ROOM`): Dành cho phòng trọ trong dãy trọ hoặc căn hộ dịch vụ chia phòng.

Tên file xuất ra bắt buộc:
- `HomeSpace_01_Hop_Dong_Thue_Nha_Nguyen_Can.docx`
- `HomeSpace_02_Hop_Dong_Thue_Can_Ho_Chung_Cu.docx`
- `HomeSpace_03_Hop_Dong_Thue_Phong_Tro.docx`

### 2. Quy tắc về Chi nhánh (PropertyBranch) - TUYỆT ĐỐI KHÔNG ĐƯA VÀO HỢP ĐỒNG
- Listing có thể thuộc một chi nhánh hoặc là tài sản độc lập. Chi nhánh chỉ là nguồn dữ liệu nội bộ.
- **NGHIÊM CẤM** đưa các thông tin sau vào văn bản hợp đồng:
  - `branchId`, mã chi nhánh, tên chi nhánh (ví dụ: "Chi nhánh A", "Tòa nhà Parkview").
  - Tổng số căn của chi nhánh, tổng sức chứa bãi xe của chi nhánh.
  - Không tạo placeholder dạng `{{property.branchName}}` hay tương tự.
- Dữ liệu địa chỉ, tiện ích, nội quy phải được giải quyết thành giá trị thực tế của tài sản bàn giao.

### 3. Quy tắc về Thanh toán trực tuyến qua HomeSpace
- Tiền thuê và tiền cọc được thanh toán trực tuyến qua HomeSpace.
- Không ghi số tài khoản, tên ngân hàng cá nhân của Bên A vào hợp đồng.
- Bắt buộc có điều khoản ghi nhận thanh toán ban đầu:
  > *"Trước thời điểm ký hợp đồng, Bên B đã thanh toán khoản tiền ban đầu theo thông tin tại Phụ lục thanh toán kèm theo hợp đồng qua hệ thống HomeSpace."*

### 4. Quy tắc về Tiện ích dùng chung và Thú cưng
- **Tiện ích dùng chung**: Không được diễn đạt thành cam kết vận hành liên tục tuyệt đối. Phải ghi rõ nguyên tắc:
  > *"Đối với tiện ích dùng chung (nếu có), Bên B được quyền sử dụng các tiện ích chung theo nội quy, khung giờ và tình trạng vận hành của đơn vị quản lý."*
- **Nuôi thú cưng (`PETS_ALLOWED`)**: Thể hiện quyền nuôi, điều kiện tuân thủ nội quy, Bên B chịu hoàn toàn trách nhiệm về vệ sinh, tiếng ồn và bồi thường thiệt hại. Không tự bịa thêm phụ phí, giống loài hay tiền cọc thú cưng khi hệ thống chưa có dữ liệu.

### 5. Quy tắc phân biệt Tiện ích (Amenities) và Thiết bị/Nội thất (Equipments)
- Máy lạnh, máy giặt, tủ lạnh, máy nước nóng, giường, tủ, bàn ghế... được xếp vào Bảng tài sản bàn giao (`#equipmentTable`).
- Bảng tiện ích (`#amenitiesTable`) chỉ phản ánh quyền sử dụng không gian và dịch vụ đi kèm (Wifi, thang máy, bảo vệ, hồ bơi, giờ giấc tự do...).

### 6. Quy tắc kỹ thuật tạo file Word (Tránh lỗi poi-tl)
- Tất cả placeholder phải nằm trọn vẹn trong **một Word run**. Nếu gõ trong Word bị ngắt định dạng, hãy xóa và gõ lại liền mạch hoặc dán dạng Plain Text.
- Không dùng dấu chấm thủ công (ví dụ: `Họ tên: ....................`).
- Không tạo placeholder ngoài danh mục 52 trường được hỗ trợ.

---

## II. DANH MỤC 52 MÃ TRƯỜNG VÀ BẢNG ĐỘNG ĐƯỢC HỖ TRỢ (SCHEMA V2)

### 1. Nhóm Pháp lý & Hợp đồng
| Mã trường | Bắt buộc | Mô tả & Ý nghĩa |
|---|:---:|---|
| `{{contract.number}}` | Có | Số hợp đồng theo định dạng hệ thống (ví dụ: `HDT-2026-00123`) |
| `{{contract.signingDate}}` | Có | Ngày ký hợp đồng (định dạng `dd/MM/yyyy`) |
| `{{contract.signingCity}}` | Không | Tỉnh/thành phố nơi xác lập hợp đồng |
| `{{contract.schemaVersion}}` | Không | Phiên bản schema dữ liệu hợp đồng (`2`) |
| `{{contract.revisionNumber}}` | Không | Số hiệu bản sửa đổi hiện tại (`1, 2, ...`) |
| `{{contract.specialTerms}}` | Không | Các điều khoản thỏa thuận bổ sung đã được hai bên xác nhận |

### 2. Nhóm Bên A (Chủ nhà / Landlord)
| Mã trường | Bắt buộc | Mô tả & Ý nghĩa |
|---|:---:|---|
| `{{landlord.fullName}}` | Có | Họ và tên chủ nhà |
| `{{landlord.idNumber}}` | Không | Số CCCD/Hộ chiếu của chủ nhà |
| `{{landlord.permanentAddress}}` | Không | Nơi thường trú của chủ nhà (không dùng từ sổ hộ khẩu) |
| `{{landlord.phone}}` | Có | Số điện thoại liên hệ của chủ nhà |
| `{{landlord.email}}` | Không | Email của chủ nhà |

### 3. Nhóm Bên B (Người thuê / Tenant)
| Mã trường | Bắt buộc | Mô tả & Ý nghĩa |
|---|:---:|---|
| `{{tenant.fullName}}` | Có | Họ và tên người thuê đại diện |
| `{{tenant.idNumber}}` | Không | Số CCCD/Hộ chiếu người thuê |
| `{{tenant.permanentAddress}}` | Không | Nơi thường trú người thuê |
| `{{tenant.phone}}` | Có | Số điện thoại liên hệ |
| `{{tenant.email}}` | Không | Email người thuê |
| `{{tenant.occupantCount}}` | Có | Số lượng người cư trú đăng ký chính thức |
| `{{tenant.motorbikeCount}}` | Không | Số lượng xe máy đăng ký gửi |
| `{{tenant.carCount}}` | Không | Số lượng ô tô đăng ký gửi |

### 4. Nhóm Bất động sản (Property)
| Mã trường | Bắt buộc | Mô tả & Ý nghĩa |
|---|:---:|---|
| `{{property.fullAddress}}` | Có | Địa chỉ đầy đủ của bất động sản |
| `{{property.areaText}}` | Có | Diện tích sử dụng (ví dụ: `45 m²`) |
| `{{property.propertyType}}` | Có | Loại hình bất động sản bằng tiếng Việt |
| `{{property.unitNumber}}` | Căn hộ & Phòng | Số căn hộ hoặc số phòng (ví dụ: `Căn hộ A-12.04` hoặc `Phòng 302`) |
| `{{property.floor}}` | Không | Tầng của căn hộ/phòng hoặc số tầng nhà nguyên căn |
| `{{property.listingCode}}` | Không | Mã định danh tin đăng đối chiếu nội bộ |
| `{{property.rentalScope}}` | Không | Phạm vi cho thuê (Toàn bộ / Tầng thuê / Phòng riêng) |
| `{{property.maxOccupants}}` | Không | Số người tối đa cho phép theo nội quy |
| `{{property.maxVehicles}}` | Không | Số xe tối đa cho phép |

### 5. Nhóm Thời hạn thuê (Lease)
| Mã trường | Bắt buộc | Mô tả & Ý nghĩa |
|---|:---:|---|
| `{{lease.startDateText}}` | Có | Ngày bắt đầu tính tiền thuê (`dd/MM/yyyy`) |
| `{{lease.endDateText}}` | Có | Ngày kết thúc thời hạn thuê (`dd/MM/yyyy`) |
| `{{lease.durationMonths}}` | Có | Thời hạn thuê tính bằng tháng (ví dụ: `12`) |
| `{{lease.durationText}}` | Có | Thời hạn bằng chữ và số (ví dụ: `12 tháng`) |
| `{{lease.handoverDateText}}` | Không | Ngày bàn giao thực tế bất động sản |

### 6. Nhóm Giá thuê & Thanh toán (Rent)
| Mã trường | Bắt buộc | Mô tả & Ý nghĩa |
|---|:---:|---|
| `{{rent.amountNumber}}` | Có | Giá thuê hàng tháng bằng số định dạng vi-VN (ví dụ: `8.500.000 VNĐ`) |
| `{{rent.amountWords}}` | Có | Giá thuê hàng tháng bằng chữ |
| `{{rent.paymentCycle}}` | Có | Chu kỳ thanh toán (Hàng tháng / 3 tháng / ...) |
| `{{rent.paymentDueDay}}` | Có | Hạn thanh toán định kỳ (ví dụ: `Ngày 05 hàng tháng`) |
| `{{rent.paymentMethod}}` | Không | Phương thức thanh toán qua nền tảng HomeSpace |

### 7. Nhóm Tiền đặt cọc (Deposit)
| Mã trường | Bắt buộc | Mô tả & Ý nghĩa |
|---|:---:|---|
| `{{deposit.amountNumber}}` | Có | Tiền đặt cọc bằng số định dạng vi-VN |
| `{{deposit.amountWords}}` | Có | Tiền đặt cọc bằng chữ |
| `{{deposit.description}}` | Không | Điều kiện và thời hạn hoàn trả tiền đặt cọc |

### 8. Nhóm Thanh toán ban đầu (Initial Payment)
| Mã trường | Bắt buộc | Mô tả & Ý nghĩa |
|---|:---:|---|
| `{{payment.initial.status}}` | Không | Trạng thái thanh toán (Đã thanh toán) |
| `{{payment.initial.paidAt}}` | Không | Thời điểm hoàn tất thanh toán ban đầu |
| `{{payment.initial.transactionCode}}` | Không | Mã giao dịch thanh toán trực tuyến |
| `{{payment.initial.totalAmount}}` | Không | Tổng số tiền ban đầu đã thanh toán (VNĐ) |

### 9. Nhóm Chỉ số bàn giao (Meters - Tùy chọn)
| Mã trường | Bắt buộc | Mô tả & Ý nghĩa |
|---|:---:|---|
| `{{meters.electricityInitial}}` | Không | Chỉ số công tơ điện lúc bàn giao (hoặc ghi nhận tại biên bản) |
| `{{meters.waterInitial}}` | Không | Chỉ số đồng hồ nước lúc bàn giao (hoặc ghi nhận tại biên bản) |

### 10. Danh mục Bảng động (Dynamic Tables)
| Cú pháp bảng | Bắt buộc | Cấu trúc cột |
|---|:---:|---|
| `{{#chargesTable}}` | Có | `STT` \| `Khoản phí` \| `Mức phí / Đơn giá` \| `Đơn vị tính` \| `Ghi chú / Điều kiện` |
| `{{#equipmentTable}}` | Không | `STT` \| `Tên tài sản / Thiết bị` \| `Số lượng` \| `Hiện trạng` \| `Ghi chú` |
| `{{#propertyFeaturesTable}}` | Không | `Đặc điểm` \| `Giá trị` |
| `{{#amenitiesTable}}` | Không | `STT` \| `Tiện ích / Quyền sử dụng` \| `Phạm vi` \| `Chi phí` \| `Điều kiện / Ghi chú` |
| `{{#initialPaymentTable}}` | Không | `Khoản thanh toán` \| `Số tiền` \| `Trạng thái / Ghi chú` |

---

## III. HƯỚNG DẪN KIỂM TRA FILE WORD SAU KHI TẠO
1. Mở file Word, bật hiển thị Field Codes (`Alt + F9`) để đảm bảo không có mã trường bị lỗi tách ký tự (word run splitting).
2. Không để sót placeholder rác không có trong danh mục 52 trường trên.
3. Các bảng động `{{#chargesTable}}`, `{{#equipmentTable}}`, `{{#propertyFeaturesTable}}`, `{{#amenitiesTable}}`, `{{#initialPaymentTable}}` phải giữ nguyên ký tự `#` ở đầu tên bảng.
4. Đảm bảo file được lưu đúng định dạng Word 2007+ (.docx).

---

## IV. BA PROMPT HOÀN CHỈNH CHO CHATGPT (COPY & PASTE)

---

### PROMPT 1: HỢP ĐỒNG THUÊ NHÀ NGUYÊN CĂN

```markdown
BỐI CẢNH VÀ VAI TRÒ:
Bạn là chuyên gia soạn thảo hợp đồng bất động sản tại Việt Nam cho nền tảng HomeSpace. HomeSpace sử dụng thư viện poi-tl để tự động điền các placeholder dạng {{variable}} và bảng động dạng {{#table}} vào file Word (.docx).

NHIỆM VỤ:
Soạn toàn văn file Word mẫu "HỢP ĐỒNG THUÊ NHÀ NGUYÊN CĂN" (dành cho nhà phố, nhà riêng lẻ, biệt thự). Văn bản phải chặt chẽ, trang trọng, bảo vệ toàn vẹn kiến trúc nhà, an toàn PCCC, an ninh trật tự và quy định cư trú.

TÊN FILE WORD ĐẦU RA BẮT BUỘC:
HomeSpace_01_Hop_Dong_Thue_Nha_Nguyen_Can.docx

I. CĂN CỨ PHÁP LÝ
- Bộ luật Dân sự số 91/2015/QH13;
- Luật Nhà ở số 27/2023/QH15;
- Luật Giao dịch điện tử số 20/2023/QH15;
- Luật Cư trú số 68/2020/QH14;
- Luật Phòng cháy, chữa cháy và cứu nạn, cứu hộ số 55/2024/QH15;
- Luật Kinh doanh bất động sản số 29/2023/QH15 (trong phạm vi áp dụng).

II. QUY TẮC BẮT BUỘC VỀ DỮ LIỆU
1. Chỉ sử dụng các placeholder trong danh mục được hỗ trợ sau đây:
   - Hợp đồng: {{contract.number}}, {{contract.signingDate}}, {{contract.signingCity}}, {{contract.schemaVersion}}, {{contract.revisionNumber}}, {{contract.specialTerms}}
   - Bên A: {{landlord.fullName}}, {{landlord.idNumber}}, {{landlord.permanentAddress}}, {{landlord.phone}}, {{landlord.email}}
   - Bên B: {{tenant.fullName}}, {{tenant.idNumber}}, {{tenant.permanentAddress}}, {{tenant.phone}}, {{tenant.email}}, {{tenant.occupantCount}}, {{tenant.motorbikeCount}}, {{tenant.carCount}}
   - Bất động sản: {{property.fullAddress}}, {{property.areaText}}, {{property.propertyType}}, {{property.floor}}, {{property.listingCode}}, {{property.rentalScope}}, {{property.maxOccupants}}, {{property.maxVehicles}}
   - Thời hạn thuê: {{lease.startDateText}}, {{lease.endDateText}}, {{lease.durationMonths}}, {{lease.durationText}}, {{lease.handoverDateText}}
   - Giá thuê & Cọc: {{rent.amountNumber}}, {{rent.amountWords}}, {{rent.paymentCycle}}, {{rent.paymentDueDay}}, {{rent.paymentMethod}}, {{deposit.amountNumber}}, {{deposit.amountWords}}, {{deposit.description}}
   - Thanh toán ban đầu: {{payment.initial.status}}, {{payment.initial.paidAt}}, {{payment.initial.transactionCode}}, {{payment.initial.totalAmount}}
   - Chỉ số bàn giao: {{meters.electricityInitial}}, {{meters.waterInitial}}
   - Bảng động: {{#chargesTable}}, {{#equipmentTable}}, {{#propertyFeaturesTable}}, {{#amenitiesTable}}, {{#initialPaymentTable}}
2. TUYỆT ĐỐI KHÔNG đưa branchId, tên chi nhánh, mã chi nhánh vào văn bản.
3. Không tự tạo thêm placeholder mới. Không dùng dấu chấm thủ công (....).
4. Tiền thuê và cọc được thanh toán trực tuyến qua HomeSpace; KHÔNG ghi số tài khoản ngân hàng cá nhân của Bên A.
5. Ghi nhận rõ: "Trước thời điểm ký hợp đồng, Bên B đã hoàn tất thanh toán khoản tiền ban đầu qua hệ thống HomeSpace theo thông tin tại Phụ lục thanh toán đính kèm."
6. Nguyên tắc tiện ích chung: "Đối với tiện ích dùng chung (nếu có), Bên B được quyền sử dụng theo nội quy, khung giờ và tình trạng vận hành của đơn vị quản lý, không cấu thành cam kết vận hành liên tục tuyệt đối."

III. CẤU TRÚC ĐIỀU KHOẢN CHI TIẾT
1. QUỐC HIỆU - TIÊU NGỮ - TÊN HỢP ĐỒNG: HỢP ĐỒNG THUÊ NHÀ NGUYÊN CĂN
   Số: {{contract.number}} - Ngày ký: {{contract.signingDate}} tại {{contract.signingCity}}.
2. CĂN CỨ PHÁP LÝ (Như mục I).
3. THÔNG TIN CÁC BÊN:
   - BÊN CHO THUÊ (BÊN A): {{landlord.fullName}}, CCCD: {{landlord.idNumber}}, Thường trú: {{landlord.permanentAddress}}, Điện thoại: {{landlord.phone}}, Email: {{landlord.email}}.
   - BÊN THUÊ (BÊN B): {{tenant.fullName}}, CCCD: {{tenant.idNumber}}, Thường trú: {{tenant.permanentAddress}}, Điện thoại: {{tenant.phone}}, Email: {{tenant.email}}, Số người ở: {{tenant.occupantCount}}, Xe máy: {{tenant.motorbikeCount}}, Ô tô: {{tenant.carCount}}.
4. ĐIỀU 1: ĐỐI TƯỢNG VÀ PHẠM VI CHO THUÊ
   - Bên A đồng ý cho Bên B thuê toàn bộ căn nhà tại địa chỉ: {{property.fullAddress}}.
   - Diện tích: {{property.areaText}}; Kết cấu/Số tầng: {{property.floor}}; Phạm vi thuê: {{property.rentalScope}}; Mã tin: {{property.listingCode}}.
   - Bảng thông số đặc điểm căn nhà:
     {{#propertyFeaturesTable}}
   - Mục đích thuê: Dùng để ở và sinh hoạt hợp pháp, không sử dụng vào mục đích vi phạm pháp luật.
5. ĐIỀU 2: THỜI HẠN THUÊ VÀ BÀN GIAO
   - Thời hạn thuê: {{lease.durationText}} ({{lease.durationMonths}} tháng), từ ngày {{lease.startDateText}} đến ngày {{lease.endDateText}}.
   - Ngày bàn giao: {{lease.handoverDateText}}.
   - Bàn giao chỉ số công tơ điện ban đầu: {{meters.electricityInitial}}; nước ban đầu: {{meters.waterInitial}} (nếu chưa có sẽ lập tại Biên bản bàn giao khi nhận nhà).
6. ĐIỀU 3: GIÁ THUÊ, TIỀN CỌC VÀ PHƯƠNG THỨC THANH TOÁN
   - Giá thuê: {{rent.amountNumber}} VNĐ/tháng (Bằng chữ: {{rent.amountWords}}).
   - Chu kỳ thanh toán: {{rent.paymentCycle}}; Hạn thanh toán định kỳ: {{rent.paymentDueDay}}.
   - Phương thức thanh toán: {{rent.paymentMethod}} qua nền tảng HomeSpace.
   - Tiền đặt cọc: {{deposit.amountNumber}} VNĐ (Bằng chữ: {{deposit.amountWords}}).
   - Điều kiện hoàn trả/khấu trừ cọc: {{deposit.description}}.
   - Xác nhận thanh toán ban đầu:
     {{#initialPaymentTable}}
     Mã giao dịch: {{payment.initial.transactionCode}}, hoàn tất lúc: {{payment.initial.paidAt}}, trạng thái: {{payment.initial.status}}.
7. ĐIỀU 4: CÁC KHOẢN CHI PHÍ KHÁC VÀ DỊCH VỤ
   - Bảng chi phí định kỳ và dịch vụ đi kèm:
     {{#chargesTable}}
   - Bảng tiện ích và quyền sử dụng dịch vụ:
     {{#amenitiesTable}}
8. ĐIỀU 5: TÀI SẢN VÀ THIẾT BỊ BÀN GIAO
   - Danh mục trang thiết bị, nội thất gắn liền căn nhà:
     {{#equipmentTable}}
   - Bên B có trách nhiệm bảo quản nguyên vẹn, không tự ý thay đổi kết cấu chịu lực, không đục phá tường, cơi nới nếu chưa có sự đồng ý bằng văn bản của Bên A.
9. ĐIỀU 6: QUYỀN VÀ NGHĨA VỤ CỦA BÊN A
   - Bàn giao nhà và thiết bị đúng hạn; bảo đảm quyền sử dụng độc lập, hợp pháp cho Bên B.
   - Hướng dẫn thủ tục đăng ký tạm trú theo Luật Cư trú; bảo trì kết cấu căn nhà theo thỏa thuận.
10. ĐIỀU 7: QUYỀN VÀ NGHĨA VỤ CỦA BÊN B
    - Sử dụng đúng số lượng người cư trú ({{tenant.occupantCount}} người), đúng số lượng phương tiện đăng ký ({{tenant.motorbikeCount}} xe máy, {{tenant.carCount}} ô tô).
    - Chấp hành tuyệt đối các quy định an toàn PCCC theo Luật PCCC 2024, an ninh trật tự địa phương.
    - Thanh toán tiền thuê và chi phí đầy đủ, đúng hạn qua nền tảng HomeSpace.
11. ĐIỀU 8: ĐIỀU KHOẢN ĐẶC THÙ VỀ THÚ CƯNG VÀ NỘI QUY
    - Quy định về nuôi thú cưng (nếu được phép theo Bảng tiện ích): Bên B cam kết giữ vệ sinh, đảm bảo an toàn, không gây tiếng ồn ảnh hưởng xung quanh và bồi thường 100% nếu gây thiệt hại.
    - Điều khoản đặc biệt khác: {{contract.specialTerms}}
12. ĐIỀU 9: CHẤM DỨT HỢP ĐỒNG VÀ GIẢI QUYẾT TRANH CHẤP
    - Quy định thông báo trước khi chấm dứt hợp đồng; hoàn trả nhà và khấu trừ cọc theo đúng hiện trạng (trừ hao mòn tự nhiên).
    - Trường hợp bất khả kháng; giải quyết tranh chấp thông qua thương lượng hoặc Tòa án có thẩm quyền.
13. ĐIỀU 10: GIAO KẾT ĐIỆN TỬ VÀ HIỆU LỰC
    - Hợp đồng được giao kết điện tử/xác nhận thông qua nền tảng HomeSpace phù hợp Luật Giao dịch điện tử.
    - Phiên bản schema: {{contract.schemaVersion}}, Số hiệu bản sửa đổi: {{contract.revisionNumber}}.
    - Hợp đồng có hiệu lực kể từ thời điểm hai bên hoàn tất xác nhận/ký điện tử.
14. CHỮ KÝ CÁC BÊN:
    - ĐẠI DIỆN BÊN A (Ký, ghi rõ họ tên)
    - ĐẠI DIỆN BÊN B (Ký, ghi rõ họ tên)
```

---

### PROMPT 2: HỢP ĐỒNG THUÊ CĂN HỘ CHUNG CƯ

```markdown
BỐI CẢNH VÀ VAI TRÒ:
Bạn là chuyên gia soạn thảo hợp đồng bất động sản tại Việt Nam cho nền tảng HomeSpace. HomeSpace sử dụng thư viện poi-tl để tự động điền các placeholder dạng {{variable}} và bảng động dạng {{#table}} vào file Word (.docx).

NHIỆM VỤ:
Soạn toàn văn file Word mẫu "HỢP ĐỒNG THUÊ CĂN HỘ CHUNG CƯ" (dành cho căn hộ trong tòa nhà chung cư/khu phức hợp). Văn bản phải chặt chẽ, chuyên nghiệp, tuân thủ quy chế quản lý nhà chung cư, quy định PCCC nhà cao tầng, thẻ cư dân, phí quản lý tòa nhà và các dịch vụ dùng chung.

TÊN FILE WORD ĐẦU RA BẮT BUỘC:
HomeSpace_02_Hop_Dong_Thue_Can_Ho_Chung_Cu.docx

I. CĂN CỨ PHÁP LÝ
- Bộ luật Dân sự số 91/2015/QH13;
- Luật Nhà ở số 27/2023/QH15 và quy chế quản lý, sử dụng nhà chung cư hiện hành;
- Luật Giao dịch điện tử số 20/2023/QH15;
- Luật Cư trú số 68/2020/QH14;
- Luật Phòng cháy, chữa cháy và cứu nạn, cứu hộ số 55/2024/QH15;
- Luật Kinh doanh bất động sản số 29/2023/QH15 (trong phạm vi áp dụng).

II. QUY TẮC BẮT BUỘC VỀ DỮ LIỆU
1. Chỉ sử dụng các placeholder trong danh mục được hỗ trợ sau đây:
   - Hợp đồng: {{contract.number}}, {{contract.signingDate}}, {{contract.signingCity}}, {{contract.schemaVersion}}, {{contract.revisionNumber}}, {{contract.specialTerms}}
   - Bên A: {{landlord.fullName}}, {{landlord.idNumber}}, {{landlord.permanentAddress}}, {{landlord.phone}}, {{landlord.email}}
   - Bên B: {{tenant.fullName}}, {{tenant.idNumber}}, {{tenant.permanentAddress}}, {{tenant.phone}}, {{tenant.email}}, {{tenant.occupantCount}}, {{tenant.motorbikeCount}}, {{tenant.carCount}}
   - Bất động sản: {{property.fullAddress}}, {{property.areaText}}, {{property.propertyType}}, {{property.unitNumber}}, {{property.floor}}, {{property.listingCode}}, {{property.rentalScope}}, {{property.maxOccupants}}, {{property.maxVehicles}}
   - Thời hạn thuê: {{lease.startDateText}}, {{lease.endDateText}}, {{lease.durationMonths}}, {{lease.durationText}}, {{lease.handoverDateText}}
   - Giá thuê & Cọc: {{rent.amountNumber}}, {{rent.amountWords}}, {{rent.paymentCycle}}, {{rent.paymentDueDay}}, {{rent.paymentMethod}}, {{deposit.amountNumber}}, {{deposit.amountWords}}, {{deposit.description}}
   - Thanh toán ban đầu: {{payment.initial.status}}, {{payment.initial.paidAt}}, {{payment.initial.transactionCode}}, {{payment.initial.totalAmount}}
   - Chỉ số bàn giao: {{meters.electricityInitial}}, {{meters.waterInitial}}
   - Bảng động: {{#chargesTable}}, {{#equipmentTable}}, {{#propertyFeaturesTable}}, {{#amenitiesTable}}, {{#initialPaymentTable}}
2. TUYỆT ĐỐI KHÔNG đưa branchId, tên chi nhánh, mã chi nhánh vào văn bản.
3. Không tự tạo thêm placeholder mới. Không dùng dấu chấm thủ công (....).
4. Tiền thuê và cọc được thanh toán trực tuyến qua HomeSpace; KHÔNG ghi số tài khoản ngân hàng cá nhân của Bên A.
5. Ghi nhận rõ: "Trước thời điểm ký hợp đồng, Bên B đã hoàn tất thanh toán khoản tiền ban đầu qua hệ thống HomeSpace theo thông tin tại Phụ lục thanh toán đính kèm."
6. Nguyên tắc tiện ích chung: "Đối với tiện ích dùng chung (hồ bơi, phòng gym, công viên, thang máy nếu có), Bên B được quyền sử dụng theo nội quy, khung giờ và tình trạng vận hành của Ban Quản trị / Ban Quản lý tòa nhà, không cấu thành cam kết vận hành liên tục tuyệt đối."

III. CẤU TRÚC ĐIỀU KHOẢN CHI TIẾT
1. QUỐC HIỆU - TIÊU NGỮ - TÊN HỢP ĐỒNG: HỢP ĐỒNG THUÊ CĂN HỘ CHUNG CƯ
   Số: {{contract.number}} - Ngày ký: {{contract.signingDate}} tại {{contract.signingCity}}.
2. CĂN CỨ PHÁP LÝ (Như mục I).
3. THÔNG TIN CÁC BÊN:
   - BÊN CHO THUÊ (BÊN A): {{landlord.fullName}}, CCCD: {{landlord.idNumber}}, Thường trú: {{landlord.permanentAddress}}, Điện thoại: {{landlord.phone}}, Email: {{landlord.email}}.
   - BÊN THUÊ (BÊN B): {{tenant.fullName}}, CCCD: {{tenant.idNumber}}, Thường trú: {{tenant.permanentAddress}}, Điện thoại: {{tenant.phone}}, Email: {{tenant.email}}, Số người cư trú: {{tenant.occupantCount}}, Xe máy đăng ký: {{tenant.motorbikeCount}}, Ô tô đăng ký: {{tenant.carCount}}.
4. ĐIỀU 1: ĐỐI TƯỢNG VÀ ĐẶC ĐIỂM CĂN HỘ CHO THUÊ
   - Bên A cho Bên B thuê căn hộ: {{property.unitNumber}}, tọa lạc tại tầng {{property.floor}}, thuộc địa chỉ: {{property.fullAddress}}.
   - Diện tích căn hộ: {{property.areaText}}; Phạm vi thuê: {{property.rentalScope}}; Mã tin đăng: {{property.listingCode}}.
   - Bảng thông số chi tiết của căn hộ:
     {{#propertyFeaturesTable}}
   - Mục đích sử dụng: Dùng để ở và sinh hoạt hợp pháp của hộ gia đình/cá nhân cư trú.
5. ĐIỀU 2: THỜI HẠN THUÊ VÀ BÀN GIAO CĂN HỘ
   - Thời hạn thuê: {{lease.durationText}} ({{lease.durationMonths}} tháng), từ ngày {{lease.startDateText}} đến ngày {{lease.endDateText}}.
   - Ngày bàn giao: {{lease.handoverDateText}}.
   - Chỉ số điện ban đầu: {{meters.electricityInitial}}; nước ban đầu: {{meters.waterInitial}} (hoặc lập tại Biên bản bàn giao khi nhận bàn giao căn hộ).
6. ĐIỀU 3: GIÁ THUÊ, TIỀN CỌC VÀ THANH TOÁN
   - Giá thuê căn hộ: {{rent.amountNumber}} VNĐ/tháng (Bằng chữ: {{rent.amountWords}}).
   - Chu kỳ thanh toán: {{rent.paymentCycle}}; Hạn thanh toán định kỳ: {{rent.paymentDueDay}}.
   - Phương thức thanh toán: {{rent.paymentMethod}} qua HomeSpace.
   - Tiền đặt cọc: {{deposit.amountNumber}} VNĐ (Bằng chữ: {{deposit.amountWords}}).
   - Điều kiện hoàn cọc: {{deposit.description}}.
   - Xác nhận thanh toán ban đầu đã hoàn tất:
     {{#initialPaymentTable}}
     Mã giao dịch: {{payment.initial.transactionCode}}, thanh toán lúc: {{payment.initial.paidAt}}, trạng thái: {{payment.initial.status}}.
7. ĐIỀU 4: PHÍ QUẢN LÝ, GỬI XE, ĐIỆN NƯỚC VÀ DỊCH VỤ CHUNG CƯ
   - Bảng phí quản lý tòa nhà, gửi xe máy/ô tô và các chi phí sinh hoạt:
     {{#chargesTable}}
   - Bảng tiện ích chung cư và quyền sử dụng dịch vụ:
     {{#amenitiesTable}}
8. ĐIỀU 5: DANH MỤC TRANG THIẾT BỊ, NỘI THẤT BÀN GIAO
   - Chi tiết danh mục nội thất, máy móc gắn liền căn hộ:
     {{#equipmentTable}}
   - Bên B có trách nhiệm giữ gìn nội thất, không tự ý đục tường, tháo gỡ thiết bị hoặc can thiệp hệ thống phòng cháy tự động (đầu phun sprinkler, đầu báo khói nhiệt).
9. ĐIỀU 6: QUYỀN VÀ NGHĨA VỤ CỦA BÊN A
   - Bàn giao căn hộ, thẻ thang máy, chìa khóa/mật mã cửa đúng thỏa thuận.
   - Hỗ trợ đăng ký định mức điện nước (nếu có) và thủ tục tạm trú cho Bên B với Ban Quản lý và Công an địa phương.
10. ĐIỀU 7: QUYỀN VÀ NGHĨA VỤ CỦA BÊN B
    - Chấp hành nghiêm chỉnh Nội quy tòa nhà chung cư, quy chế cư dân, quy định gửi xe và chuyển đồ đạc lớn.
    - Cư trú đúng số lượng: {{tenant.occupantCount}} người; gửi xe đúng số lượng: {{tenant.motorbikeCount}} xe máy, {{tenant.carCount}} ô tô.
    - Tuân thủ quy định PCCC nhà cao tầng; không lưu trữ chất dễ cháy nổ, không thắp hương hoặc gây khói kích hoạt báo cháy giả.
11. ĐIỀU 8: ĐIỀU KHOẢN VỀ VẬT NUÔI VÀ THỎA THUẬN KHÁC
    - Quy định vật nuôi: Chỉ được nuôi thú cưng nếu Nội quy chung cư và Bảng tiện ích cho phép; Bên B chịu hoàn toàn trách nhiệm vệ sinh, tiếng ồn trong thang máy/hành lang và bồi thường nếu xảy ra sự cố.
    - Điều khoản đặc biệt: {{contract.specialTerms}}
12. ĐIỀU 9: CHẤM DỨT HỢP ĐỒNG VÀ XỬ LÝ VI PHẠM
    - Thông báo trước khi kết thúc hợp đồng; điều kiện trừ cọc nếu Bên B đơn phương chấm dứt trái thỏa thuận.
    - Bàn giao lại thẻ cư dân, hiện trạng căn hộ và thanh lý công nợ dịch vụ với Ban Quản lý.
13. ĐIỀU 10: GIAO KẾT ĐIỆN TỬ VÀ HIỆU LỰC
    - Hợp đồng được giao kết điện tử qua nền tảng HomeSpace theo Luật Giao dịch điện tử.
    - Phiên bản schema: {{contract.schemaVersion}}, Số hiệu bản sửa đổi: {{contract.revisionNumber}}.
    - Hợp đồng có hiệu lực sau khi hai bên hoàn tất xác nhận điện tử.
14. CHỮ KÝ CÁC BÊN:
    - ĐẠI DIỆN BÊN A (Ký, ghi rõ họ tên)
    - ĐẠI DIỆN BÊN B (Ký, ghi rõ họ tên)
```

---

### PROMPT 3: HỢP ĐỒNG THUÊ PHÒNG TRỌ

```markdown
BỐI CẢNH VÀ VAI TRÒ:
Bạn là chuyên gia soạn thảo hợp đồng bất động sản tại Việt Nam cho nền tảng HomeSpace. HomeSpace sử dụng thư viện poi-tl để tự động điền các placeholder dạng {{variable}} và bảng động dạng {{#table}} vào file Word (.docx).

NHIỆM VỤ:
Soạn toàn văn file Word mẫu "HỢP ĐỒNG THUÊ PHÒNG TRỌ" (dành cho phòng trọ trong dãy trọ, nhà nhiều phòng cho thuê hoặc căn hộ dịch vụ chia phòng). Văn bản phải rõ ràng, thiết thực, tập trung vào an ninh trật tự, giờ giấc ra vào, PCCC (đặc biệt là sạc xe điện và thiết bị đun nấu), đăng ký tạm trú và chia sẻ không gian chung.

TÊN FILE WORD ĐẦU RA BẮT BUỘC:
HomeSpace_03_Hop_Dong_Thue_Phong_Tro.docx

I. CĂN CỨ PHÁP LÝ
- Bộ luật Dân sự số 91/2015/QH13;
- Luật Nhà ở số 27/2023/QH15 và quy định về quản lý nhà ở nhiều phòng cho thuê;
- Luật Giao dịch điện tử số 20/2023/QH15;
- Luật Cư trú số 68/2020/QH14;
- Luật Phòng cháy, chữa cháy và cứu nạn, cứu hộ số 55/2024/QH15 (quy định về nhà trọ và lối thoát nạn khẩn cấp);
- Luật Kinh doanh bất động sản số 29/2023/QH15 (trong phạm vi áp dụng).

II. QUY TẮC BẮT BUỘC VỀ DỮ LIỆU
1. Chỉ sử dụng các placeholder trong danh mục được hỗ trợ sau đây:
   - Hợp đồng: {{contract.number}}, {{contract.signingDate}}, {{contract.signingCity}}, {{contract.schemaVersion}}, {{contract.revisionNumber}}, {{contract.specialTerms}}
   - Bên A: {{landlord.fullName}}, {{landlord.idNumber}}, {{landlord.permanentAddress}}, {{landlord.phone}}, {{landlord.email}}
   - Bên B: {{tenant.fullName}}, {{tenant.idNumber}}, {{tenant.permanentAddress}}, {{tenant.phone}}, {{tenant.email}}, {{tenant.occupantCount}}, {{tenant.motorbikeCount}}, {{tenant.carCount}}
   - Bất động sản: {{property.fullAddress}}, {{property.areaText}}, {{property.propertyType}}, {{property.unitNumber}}, {{property.floor}}, {{property.listingCode}}, {{property.rentalScope}}, {{property.maxOccupants}}, {{property.maxVehicles}}
   - Thời hạn thuê: {{lease.startDateText}}, {{lease.endDateText}}, {{lease.durationMonths}}, {{lease.durationText}}, {{lease.handoverDateText}}
   - Giá thuê & Cọc: {{rent.amountNumber}}, {{rent.amountWords}}, {{rent.paymentCycle}}, {{rent.paymentDueDay}}, {{rent.paymentMethod}}, {{deposit.amountNumber}}, {{deposit.amountWords}}, {{deposit.description}}
   - Thanh toán ban đầu: {{payment.initial.status}}, {{payment.initial.paidAt}}, {{payment.initial.transactionCode}}, {{payment.initial.totalAmount}}
   - Chỉ số bàn giao: {{meters.electricityInitial}}, {{meters.waterInitial}}
   - Bảng động: {{#chargesTable}}, {{#equipmentTable}}, {{#propertyFeaturesTable}}, {{#amenitiesTable}}, {{#initialPaymentTable}}
2. TUYỆT ĐỐI KHÔNG đưa branchId, tên chi nhánh, mã chi nhánh vào văn bản.
3. Không tự tạo thêm placeholder mới. Không dùng dấu chấm thủ công (....).
4. Tiền thuê và cọc được thanh toán trực tuyến qua HomeSpace; KHÔNG ghi số tài khoản ngân hàng cá nhân của Bên A.
5. Ghi nhận rõ: "Trước thời điểm ký hợp đồng, Bên B đã hoàn tất thanh toán khoản tiền ban đầu qua hệ thống HomeSpace theo thông tin tại Phụ lục thanh toán đính kèm."
6. Nguyên tắc tiện ích chung: "Đối với tiện ích dùng chung (khu giặt phơi, nhà để xe, lối đi chung nếu có), Bên B được quyền sử dụng theo nội quy nhà trọ, khung giờ và tình trạng vận hành thực tế, không cấu thành cam kết vận hành liên tục tuyệt đối."

III. CẤU TRÚC ĐIỀU KHOẢN CHI TIẾT
1. QUỐC HIỆU - TIÊU NGỮ - TÊN HỢP ĐỒNG: HỢP ĐỒNG THUÊ PHÒNG TRỌ
   Số: {{contract.number}} - Ngày ký: {{contract.signingDate}} tại {{contract.signingCity}}.
2. CĂN CỨ PHÁP LÝ (Như mục I).
3. THÔNG TIN CÁC BÊN:
   - BÊN CHO THUÊ (BÊN A): {{landlord.fullName}}, CCCD: {{landlord.idNumber}}, Thường trú: {{landlord.permanentAddress}}, Điện thoại: {{landlord.phone}}, Email: {{landlord.email}}.
   - BÊN THUÊ (BÊN B): {{tenant.fullName}}, CCCD: {{tenant.idNumber}}, Thường trú: {{tenant.permanentAddress}}, Điện thoại: {{tenant.phone}}, Email: {{tenant.email}}, Số người cư trú: {{tenant.occupantCount}}, Xe máy đăng ký: {{tenant.motorbikeCount}}, Ô tô: {{tenant.carCount}}.
4. ĐIỀU 1: ĐỐI TƯỢNG VÀ ĐẶC ĐIỂM PHÒNG TRỌ
   - Bên A cho Bên B thuê phòng số: {{property.unitNumber}}, tại tầng {{property.floor}}, thuộc địa chỉ: {{property.fullAddress}}.
   - Diện tích phòng: {{property.areaText}}; Phạm vi thuê: {{property.rentalScope}}; Mã tin đăng: {{property.listingCode}}.
   - Bảng đặc điểm kỹ thuật và tiện nghi phòng:
     {{#propertyFeaturesTable}}
   - Mục đích sử dụng: Để ở và sinh hoạt cá nhân, tuyệt đối không sử dụng làm kho chứa hàng nguy hiểm hoặc địa điểm hoạt động trái pháp luật.
5. ĐIỀU 2: THỜI HẠN THUÊ VÀ NHẬN PHÒNG
   - Thời hạn thuê: {{lease.durationText}} ({{lease.durationMonths}} tháng), từ ngày {{lease.startDateText}} đến ngày {{lease.endDateText}}.
   - Ngày bàn giao nhận phòng: {{lease.handoverDateText}}.
   - Chỉ số công tơ điện lúc nhận phòng: {{meters.electricityInitial}}; chỉ số đồng hồ nước: {{meters.waterInitial}} (nếu dùng đồng hồ riêng, hoặc cập nhật tại Biên bản bàn giao nhận phòng).
6. ĐIỀU 3: TIỀN THUÊ PHÒNG, ĐẶT CỌC VÀ THANH TOÁN
   - Tiền thuê phòng: {{rent.amountNumber}} VNĐ/tháng (Bằng chữ: {{rent.amountWords}}).
   - Chu kỳ thanh toán: {{rent.paymentCycle}}; Hạn thanh toán định kỳ: {{rent.paymentDueDay}}.
   - Phương thức thanh toán: {{rent.paymentMethod}} qua HomeSpace.
   - Tiền đặt cọc: {{deposit.amountNumber}} VNĐ (Bằng chữ: {{deposit.amountWords}}).
   - Điều kiện hoàn cọc: {{deposit.description}}.
   - Xác nhận thanh toán ban đầu đã hoàn tất:
     {{#initialPaymentTable}}
     Mã giao dịch: {{payment.initial.transactionCode}}, hoàn tất lúc: {{payment.initial.paidAt}}, trạng thái: {{payment.initial.status}}.
7. ĐIỀU 4: CÁC KHOẢN PHÍ DỊCH VỤ, ĐIỆN, NƯỚC VÀ GỬI XE
   - Bảng đơn giá điện, nước, internet, rác, vệ sinh và gửi xe:
     {{#chargesTable}}
   - Bảng tiện ích và quyền sử dụng không gian chung:
     {{#amenitiesTable}}
8. ĐIỀU 5: TRANG THIẾT BỊ VÀ NỘI THẤT TRONG PHÒNG
   - Danh mục thiết bị bàn giao trong phòng:
     {{#equipmentTable}}
   - Bên B có trách nhiệm sử dụng đúng tính năng, giữ gìn vệ sinh và đền bù nếu làm hư hỏng, mất mát.
9. ĐIỀU 6: QUYỀN VÀ NGHĨA VỤ CỦA BÊN A
   - Giao phòng và tiện nghi đúng hiện trạng thỏa thuận.
   - Đăng ký tạm trú cho Bên B theo đúng quy định Luật Cư trú; đảm bảo an ninh khu trọ và bảo trì hệ thống cấp thoát nước, điện tổng.
10. ĐIỀU 7: QUYỀN VÀ NGHĨA VỤ CỦA BÊN B (NỘI QUY VÀ AN TOÀN PCCC)
    - Cư trú đúng số người đăng ký: {{tenant.occupantCount}} người; giữ đúng số lượng xe: {{tenant.motorbikeCount}} xe máy. Khách ở qua đêm phải báo trước với Bên A và đăng ký theo quy định.
    - TUÂN THỦ NGHIÊM NGẶT PCCC: Không sạc pin/ắc quy xe điện qua đêm không có người trông coi; không đun nấu bằng bếp gas mini không bảo đảm an toàn; không che chắn hành lang, cầu thang thoát nạn.
    - Giữ gìn an ninh trật tự, không mở nhạc lớn sau 22h00; giữ vệ sinh khu vực chung (sân phơi, nhà xe, hành lang).
11. ĐIỀU 8: QUY ĐỊNH VỀ VẬT NUÔI VÀ THỎA THUẬN KHÁC
    - Quy định vật nuôi: Chỉ được nuôi nếu khu trọ cho phép (thể hiện tại Bảng tiện ích); Bên B chịu trách nhiệm giữ gìn vệ sinh và không gây ồn ào.
    - Thỏa thuận bổ sung: {{contract.specialTerms}}
12. ĐIỀU 9: CHẤM DỨT HỢP ĐỒNG VÀ HOÀN TRẢ PHÒNG
    - Bên B muốn trả phòng trước hạn phải báo trước tối thiểu theo thỏa thuận, dọn dẹp sạch sẽ và bàn giao lại chìa khóa, hiện trạng phòng.
    - Xử lý tiền cọc khi vi phạm hợp đồng hoặc hư hỏng tài sản.
13. ĐIỀU 10: GIAO KẾT ĐIỆN TỬ VÀ HIỆU LỰC
    - Hợp đồng được giao kết điện tử qua nền tảng HomeSpace theo Luật Giao dịch điện tử.
    - Phiên bản schema: {{contract.schemaVersion}}, Số hiệu bản sửa đổi: {{contract.revisionNumber}}.
    - Hợp đồng có hiệu lực sau khi hai bên hoàn tất xác nhận điện tử.
14. CHỮ KÝ CÁC BÊN:
    - ĐẠI DIỆN BÊN A (Ký, ghi rõ họ tên)
    - ĐẠI DIỆN BÊN B (Ký, ghi rõ họ tên)
```
