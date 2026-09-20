# BỘ ĐẶC TẢ VÀ PROMPT TẠO MẪU HỢP ĐỒNG HOMESPACE (SCHEMA V3)

Tài liệu này là đặc tả chuẩn hóa (Contract Schema V3) để tạo ba mẫu hợp đồng Word (.docx) cho nền tảng HomeSpace. Mỗi mẫu gắn liền với một loại bất động sản cụ thể và sử dụng công nghệ tạo tài liệu [poi-tl](https://github.com/Sayi/poi-tl).

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

### 3. Quy tắc về Chuyển khoản trực tiếp giữa Chủ nhà và Người thuê
- HomeSpace KHÔNG phải ví điện tử, KHÔNG phải cổng thanh toán, KHÔNG nhận tiền, KHÔNG giữ tiền và KHÔNG tự động xác nhận giao dịch ngân hàng.
- Tiền luôn chuyển trực tiếp giữa hai bên:
  - Người thuê $\rightarrow$ tài khoản ngân hàng chủ nhà (tiền thuê, tiền cọc, chi phí).
  - Chủ nhà $\rightarrow$ tài khoản ngân hàng người thuê khi hoàn cọc/hoàn tiền.
- Hợp đồng ghi rõ tài khoản ngân hàng của cả hai bên theo snapshot giao dịch tại thời điểm xác nhận:
  - **Bên A (Nhận thanh toán):** Chủ tài khoản: `{{landlord.bankAccountHolder}}`, Số tài khoản: `{{landlord.bankAccountNumber}}`, Ngân hàng: `{{landlord.bankName}}`.
  - **Bên B (Nhận hoàn trả/hoàn cọc):** Chủ tài khoản: `{{tenant.bankAccountHolder}}`, Số tài khoản: `{{tenant.bankAccountNumber}}`, Ngân hàng: `{{tenant.bankName}}`.
- Bắt buộc đưa đầy đủ 7 điều khoản pháp lý về chuyển khoản trực tiếp vào hợp đồng:
  1. **Phương thức thanh toán:** "Bên B thanh toán tiền thuê, tiền đặt cọc và các khoản phải trả khác bằng hình thức chuyển khoản trực tiếp vào tài khoản do Bên A chỉ định trong Hợp đồng này hoặc tài khoản thay thế được hai bên xác nhận bằng văn bản hoặc thông điệp dữ liệu."
  2. **Vai trò HomeSpace:** "HomeSpace cung cấp công cụ tính toán khoản phải trả, tạo thông tin VietQR, lưu trữ yêu cầu thanh toán và ghi nhận trạng thái do các bên khai báo. HomeSpace không nhận tiền, không giữ tiền, không chuyển tiền thay các bên và không thay thế ngân hàng xác nhận việc ghi Có hoặc ghi Nợ trên tài khoản."
  3. **Xác nhận hai chiều:** "Một khoản thanh toán được ghi nhận hoàn tất trên HomeSpace sau khi Bên B khai báo đã thực hiện chuyển khoản và Bên A xác nhận đã nhận đủ số tiền tương ứng. Việc ghi nhận này được lập trên cơ sở xác nhận của các bên và không phải là xác nhận giao dịch do ngân hàng phát hành."
  4. **Trách nhiệm kiểm tra:** "Bên B có trách nhiệm kiểm tra tên chủ tài khoản, số tài khoản, ngân hàng thụ hưởng, số tiền và nội dung chuyển khoản trước khi thực hiện. Bên A có trách nhiệm kiểm tra tài khoản nhận và xác nhận trung thực trạng thái nhận tiền."
  5. **Sai lệch/tranh chấp:** "Trường hợp thông tin khai báo giữa các bên không thống nhất, khoản thanh toán được chuyển sang trạng thái chờ đối soát. Các bên có trách nhiệm cung cấp chứng từ ngân hàng và phối hợp xác minh; HomeSpace chỉ lưu trữ thông tin, chứng từ và lịch sử xác nhận do các bên cung cấp."
  6. **Thay đổi tài khoản:** "Mọi thay đổi tài khoản nhận thanh toán hoặc tài khoản nhận hoàn trả chỉ có hiệu lực đối với nghĩa vụ phát sinh sau khi bên còn lại đã nhận được và xác nhận thông báo thay đổi. Việc thay đổi tài khoản trên hồ sơ HomeSpace không tự động sửa đổi phiên bản hợp đồng đang có hiệu lực."
  7. **Hoàn cọc:** "Khi chấm dứt hoặc hết hạn hợp đồng, sau khi hai bên hoàn tất bàn giao và quyết toán, Bên A chuyển khoản số tiền cọc còn phải hoàn trực tiếp vào tài khoản nhận hoàn trả của Bên B ghi trong Hợp đồng hoặc tài khoản khác được hai bên xác nhận. Việc hoàn cọc được ghi nhận hoàn tất sau khi Bên A khai báo đã chuyển và Bên B xác nhận đã nhận đủ tiền."

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
- Không tạo placeholder ngoài danh mục 63 trường được hỗ trợ.

---

## II. DANH MỤC 63 MÃ TRƯỜNG VÀ BẢNG ĐỘNG ĐƯỢC HỖ TRỢ (SCHEMA V3)

### 1. Nhóm Pháp lý & Hợp đồng
| Mã trường | Bắt buộc | Mô tả & Ý nghĩa |
|---|:---:|---|
| `{{contract.number}}` | Có | Số hợp đồng theo định dạng hệ thống (ví dụ: `HDT-2026-00123`) |
| `{{contract.signingDate}}` | Có | Ngày ký hợp đồng (định dạng `dd/MM/yyyy`) |
| `{{contract.signingCity}}` | Không | Tỉnh/thành phố nơi xác lập hợp đồng |
| `{{contract.schemaVersion}}` | Không | Phiên bản schema dữ liệu hợp đồng (`3`) |
| `{{contract.revisionNumber}}` | Không | Số hiệu bản sửa đổi hiện tại (`1, 2, ...`) |
| `{{contract.specialTerms}}` | Không | Các điều khoản thỏa thuận bổ sung đã được hai bên xác nhận |

### 2. Nhóm Bên A (Chủ nhà / Landlord)
| Mã trường | Bắt buộc | Mô tả & Ý nghĩa |
|---|:---:|---|
| `{{landlord.fullName}}` | Có | Họ và tên chủ nhà |
| `{{landlord.idNumber}}` | Không | Số CCCD/Hộ chiếu của chủ nhà |
| `{{landlord.permanentAddress}}` | Không | Nơi thường trú của chủ nhà |
| `{{landlord.phone}}` | Có | Số điện thoại liên hệ của chủ nhà |
| `{{landlord.email}}` | Không | Email của chủ nhà |
| `{{landlord.bankAccountHolder}}` | Không | Tên chủ tài khoản nhận thanh toán của Bên A |
| `{{landlord.bankAccountNumber}}` | Không | Số tài khoản nhận thanh toán của Bên A |
| `{{landlord.bankName}}` | Không | Tên ngân hàng nhận thanh toán của Bên A |

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
| `{{tenant.bankAccountHolder}}` | Không | Tên chủ tài khoản nhận hoàn cọc của Bên B |
| `{{tenant.bankAccountNumber}}` | Không | Số tài khoản nhận hoàn cọc của Bên B |
| `{{tenant.bankName}}` | Không | Tên ngân hàng nhận hoàn cọc của Bên B |

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
| `{{rent.paymentMethod}}` | Không | Phương thức thanh toán: Chuyển khoản trực tiếp ngân hàng |

### 7. Nhóm Tiền đặt cọc (Deposit)
| Mã trường | Bắt buộc | Mô tả & Ý nghĩa |
|---|:---:|---|
| `{{deposit.amountNumber}}` | Có | Tiền đặt cọc bằng số định dạng vi-VN |
| `{{deposit.amountWords}}` | Có | Tiền đặt cọc bằng chữ |
| `{{deposit.description}}` | Không | Điều kiện và thời hạn hoàn trả tiền đặt cọc |

### 8. Nhóm Thanh toán ban đầu (Initial Payment - Schema V3)
| Mã trường | Bắt buộc | Mô tả & Ý nghĩa |
|---|:---:|---|
| `{{payment.initial.status}}` | Không | Trạng thái xác nhận (Đã xác nhận) |
| `{{payment.initial.payerReportedAt}}` | Không | Thời điểm Bên B khai báo chuyển khoản |
| `{{payment.initial.payeeConfirmedAt}}` | Không | Thời điểm Bên A xác nhận đã nhận đủ |
| `{{payment.initial.confirmedAt}}` | Không | Thời điểm hoàn tất xác nhận hai chiều |
| `{{payment.initial.transferReference}}` | Không | Mã nội dung chuyển khoản VietQR duy nhất |
| `{{payment.initial.bankTransactionReference}}` | Không | Mã tham chiếu giao dịch ngân hàng khai báo |
| `{{payment.initial.totalAmount}}` | Không | Tổng số tiền ban đầu đã thanh toán (VNĐ) |
| `{{payment.initial.paidAt}}` | Không | Thời điểm hoàn tất (tương thích V2) |
| `{{payment.initial.transactionCode}}` | Không | Mã giao dịch đối chiếu (tương thích V2) |

### 9. Nhóm Chỉ số bàn giao (Meters - Tùy chọn)
| Mã trường | Bắt buộc | Mô tả & Ý nghĩa |
|---|:---:|---|
| `{{meters.electricityInitial}}` | Không | Chỉ số công tơ điện lúc bàn giao |
| `{{meters.waterInitial}}` | Không | Chỉ số đồng hồ nước lúc bàn giao |

### 10. Danh mục Bảng động (Dynamic Tables)
| Cú pháp bảng | Bắt buộc | Cấu trúc cột |
|---|:---:|---|
| `{{#chargesTable}}` | Có | `STT` \| `Khoản phí` \| `Mức phí / Đơn giá` \| `Đơn vị tính` \| `Ghi chú / Điều kiện` |
| `{{#equipmentTable}}` | Không | `STT` \| `Tên tài sản / Thiết bị` \| `Số lượng` \| `Hiện trạng` \| `Ghi chú` |
| `{{#propertyFeaturesTable}}` | Không | `Đặc điểm` \| `Giá trị` |
| `{{#amenitiesTable}}` | Không | `STT` \| `Tiện ích / Quyền sử dụng` \| `Phạm vi` \| `Chi phí` \| `Điều kiện / Ghi chú` |
| `{{#initialPaymentTable}}` | Không | `Khoản thanh toán` \| `Số tiền` \| `Trạng thái / Chi tiết xác nhận` |

---

## III. HƯỚNG DẪN KIỂM TRA FILE WORD SAU KHI TẠO
1. Mở file Word, bật hiển thị Field Codes (`Alt + F9`) để đảm bảo không có mã trường bị lỗi tách ký tự (word run splitting).
2. Không để sót placeholder rác không có trong danh mục 63 trường trên.
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
Soạn toàn văn file Word mẫu "HỢP ĐỒNG THUÊ NHÀ NGUYÊN CĂN" (dành cho nhà phố, nhà riêng lẻ, biệt thự) theo chuẩn Schema V3. Văn bản phải chặt chẽ, trang trọng, minh bạch về tài khoản ngân hàng hai bên, cơ chế chuyển khoản trực tiếp, bảo vệ toàn vẹn kiến trúc nhà, an toàn PCCC, an ninh trật tự và quy định cư trú.

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
1. Chỉ sử dụng các placeholder trong danh mục 63 trường được hỗ trợ:
   - Hợp đồng: {{contract.number}}, {{contract.signingDate}}, {{contract.signingCity}}, {{contract.schemaVersion}}, {{contract.revisionNumber}}, {{contract.specialTerms}}
   - Bên A: {{landlord.fullName}}, {{landlord.idNumber}}, {{landlord.permanentAddress}}, {{landlord.phone}}, {{landlord.email}}, {{landlord.bankAccountHolder}}, {{landlord.bankAccountNumber}}, {{landlord.bankName}}
   - Bên B: {{tenant.fullName}}, {{tenant.idNumber}}, {{tenant.permanentAddress}}, {{tenant.phone}}, {{tenant.email}}, {{tenant.occupantCount}}, {{tenant.motorbikeCount}}, {{tenant.carCount}}, {{tenant.bankAccountHolder}}, {{tenant.bankAccountNumber}}, {{tenant.bankName}}
   - Bất động sản: {{property.fullAddress}}, {{property.areaText}}, {{property.propertyType}}, {{property.floor}}, {{property.listingCode}}, {{property.rentalScope}}, {{property.maxOccupants}}, {{property.maxVehicles}}
   - Thời hạn thuê: {{lease.startDateText}}, {{lease.endDateText}}, {{lease.durationMonths}}, {{lease.durationText}}, {{lease.handoverDateText}}
   - Giá thuê & Cọc: {{rent.amountNumber}}, {{rent.amountWords}}, {{rent.paymentCycle}}, {{rent.paymentDueDay}}, {{rent.paymentMethod}}, {{deposit.amountNumber}}, {{deposit.amountWords}}, {{deposit.description}}
   - Thanh toán ban đầu: {{payment.initial.status}}, {{payment.initial.payerReportedAt}}, {{payment.initial.payeeConfirmedAt}}, {{payment.initial.confirmedAt}}, {{payment.initial.transferReference}}, {{payment.initial.bankTransactionReference}}, {{payment.initial.totalAmount}}
   - Chỉ số bàn giao: {{meters.electricityInitial}}, {{meters.waterInitial}}
   - Bảng động: {{#chargesTable}}, {{#equipmentTable}}, {{#propertyFeaturesTable}}, {{#amenitiesTable}}, {{#initialPaymentTable}}
2. TUYỆT ĐỐI KHÔNG đưa branchId, tên chi nhánh, mã chi nhánh vào văn bản.
3. Không tự tạo thêm placeholder mới. Không dùng dấu chấm thủ công (....).
4. Thanh toán chuyển khoản trực tiếp: Tiền thuê, tiền cọc và hoàn cọc được chuyển khoản trực tiếp giữa tài khoản ngân hàng của Bên A và Bên B.
5. Ghi nhận rõ đầy đủ 7 điều khoản chuyển khoản trực tiếp:
   - Phương thức thanh toán trực tiếp vào tài khoản Bên A.
   - Vai trò HomeSpace: Chỉ tính toán khoản phải trả, tạo thông tin VietQR, lưu trữ chứng từ và ghi nhận trạng thái xác nhận; không nhận tiền, không giữ tiền, không chuyển tiền thay các bên.
   - Xác nhận hai chiều: Bên B báo chuyển, Bên A xác nhận đã nhận đủ.
   - Trách nhiệm kiểm tra thông tin tài khoản trước khi chuyển.
   - Xử lý sai lệch, tranh chấp và phối hợp cung cấp sao kê/biên lai ngân hàng.
   - Thay đổi tài khoản ngân hàng phải thông báo và xác nhận trước.
   - Hoàn cọc trực tiếp vào tài khoản Bên B sau khi quyết toán và bàn giao.

III. CẤU TRÚC ĐIỀU KHOẢN CHI TIẾT
1. QUỐC HIỆU - TIÊU NGỮ - TÊN HỢP ĐỒNG: HỢP ĐỒNG THUÊ NHÀ NGUYÊN CĂN
   Số: {{contract.number}} - Ngày ký: {{contract.signingDate}} tại {{contract.signingCity}}.
2. CĂN CỨ PHÁP LÝ (Như mục I).
3. THÔNG TIN CÁC BÊN:
   - BÊN CHO THUÊ (BÊN A): {{landlord.fullName}}, CCCD: {{landlord.idNumber}}, Thường trú: {{landlord.permanentAddress}}, Điện thoại: {{landlord.phone}}, Email: {{landlord.email}}.
     Tài khoản nhận thanh toán: Chủ tài khoản: {{landlord.bankAccountHolder}} - Số tài khoản: {{landlord.bankAccountNumber}} - Ngân hàng: {{landlord.bankName}}.
   - BÊN THUÊ (BÊN B): {{tenant.fullName}}, CCCD: {{tenant.idNumber}}, Thường trú: {{tenant.permanentAddress}}, Điện thoại: {{tenant.phone}}, Email: {{tenant.email}}, Số người ở: {{tenant.occupantCount}}, Xe máy: {{tenant.motorbikeCount}}, Ô tô: {{tenant.carCount}}.
     Tài khoản nhận hoàn trả: Chủ tài khoản: {{tenant.bankAccountHolder}} - Số tài khoản: {{tenant.bankAccountNumber}} - Ngân hàng: {{tenant.bankName}}.
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
6. ĐIỀU 3: GIÁ THUÊ, TIỀN CỌC VÀ PHƯƠNG THỨC CHUYỂN KHOẢN TRỰC TIẾP
   - Giá thuê: {{rent.amountNumber}} VNĐ/tháng (Bằng chữ: {{rent.amountWords}}).
   - Chu kỳ thanh toán: {{rent.paymentCycle}}; Hạn thanh toán định kỳ: {{rent.paymentDueDay}}.
   - Phương thức thanh toán: {{rent.paymentMethod}}.
   - Tiền đặt cọc: {{deposit.amountNumber}} VNĐ (Bằng chữ: {{deposit.amountWords}}).
   - Điều kiện hoàn trả/khấu trừ cọc: {{deposit.description}}.
   - Quy định chi tiết về chuyển khoản trực tiếp và vai trò HomeSpace:
     + Bên B thanh toán tiền thuê, tiền đặt cọc và các khoản phải trả khác bằng hình thức chuyển khoản trực tiếp vào tài khoản do Bên A chỉ định trong Hợp đồng này.
     + HomeSpace cung cấp công cụ tính toán khoản phải trả, tạo thông tin VietQR, lưu trữ yêu cầu thanh toán và ghi nhận trạng thái do các bên khai báo. HomeSpace không nhận tiền, không giữ tiền, không chuyển tiền thay các bên và không thay thế ngân hàng xác nhận việc ghi Có hoặc ghi Nợ trên tài khoản.
     + Một khoản thanh toán được ghi nhận hoàn tất trên HomeSpace sau khi Bên B khai báo đã thực hiện chuyển khoản và Bên A xác nhận đã nhận đủ số tiền tương ứng.
     + Bên B có trách nhiệm kiểm tra tên chủ tài khoản, số tài khoản, ngân hàng thụ hưởng, số tiền và nội dung chuyển khoản trước khi thực hiện. Bên A có trách nhiệm kiểm tra tài khoản nhận và xác nhận trung thực trạng thái nhận tiền.
     + Trường hợp thông tin khai báo giữa các bên không thống nhất, khoản thanh toán được chuyển sang trạng thái chờ đối soát. Các bên có trách nhiệm cung cấp chứng từ ngân hàng và phối hợp xác minh; HomeSpace chỉ lưu trữ thông tin, chứng từ và lịch sử xác nhận do các bên cung cấp.
     + Mọi thay đổi tài khoản nhận thanh toán hoặc nhận hoàn trả chỉ có hiệu lực đối với nghĩa vụ phát sinh sau khi bên còn lại đã nhận được và xác nhận thông báo thay đổi.
     + Khi chấm dứt hoặc hết hạn hợp đồng, sau khi hai bên hoàn tất bàn giao và quyết toán, Bên A chuyển khoản số tiền cọc còn phải hoàn trực tiếp vào tài khoản nhận hoàn trả của Bên B ghi trong Hợp đồng này.
   - Bảng ghi nhận thanh toán ban đầu đã được hai bên xác nhận:
     {{#initialPaymentTable}}
     Mã nội dung chuyển khoản: {{payment.initial.transferReference}}, Bên B khai báo chuyển: {{payment.initial.payerReportedAt}}, Bên A xác nhận nhận: {{payment.initial.payeeConfirmedAt}}, Trạng thái xác nhận: {{payment.initial.status}}.
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
   - Cung cấp tài khoản ngân hàng chính xác để nhận thanh toán; kiểm tra và xác nhận kịp thời khi nhận được tiền.
   - Hướng dẫn thủ tục đăng ký tạm trú theo Luật Cư trú; bảo trì kết cấu căn nhà theo thỏa thuận.
10. ĐIỀU 7: QUYỀN VÀ NGHĨA VỤ CỦA BÊN B
    - Sử dụng đúng số lượng người cư trú ({{tenant.occupantCount}} người), đúng số lượng phương tiện đăng ký ({{tenant.motorbikeCount}} xe máy, {{tenant.carCount}} ô tô).
    - Chấp hành tuyệt đối các quy định an toàn PCCC theo Luật PCCC 2024, an ninh trật tự địa phương.
    - Chuyển khoản tiền thuê và chi phí đầy đủ, đúng hạn vào tài khoản ngân hàng của Bên A.
11. ĐIỀU 8: ĐIỀU KHOẢN ĐẶC THÙ VỀ THÚ CƯNG VÀ NỘI QUY
    - Quy định về nuôi thú cưng (nếu được phép theo Bảng tiện ích): Bên B cam kết giữ vệ sinh, đảm bảo an toàn, không gây tiếng ồn ảnh hưởng xung quanh và bồi thường 100% nếu gây thiệt hại.
    - Điều khoản đặc biệt khác: {{contract.specialTerms}}
12. ĐIỀU 9: CHẤM DỨT HỢP ĐỒNG VÀ GIẢI QUYẾT TRANH CHẤP
    - Quy định thông báo trước khi chấm dứt hợp đồng; hoàn trả nhà và quyết toán hoàn cọc chuyển khoản trực tiếp.
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
Soạn toàn văn file Word mẫu "HỢP ĐỒNG THUÊ CĂN HỘ CHUNG CƯ" (dành cho căn hộ trong tòa nhà chung cư/khu phức hợp) theo chuẩn Schema V3. Văn bản phải chặt chẽ, chuyên nghiệp, thể hiện rõ tài khoản ngân hàng hai bên, thanh toán chuyển khoản trực tiếp, tuân thủ quy chế quản lý nhà chung cư, quy định PCCC nhà cao tầng, thẻ cư dân, phí quản lý tòa nhà và các dịch vụ dùng chung.

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
1. Chỉ sử dụng các placeholder trong danh mục 63 trường được hỗ trợ:
   - Hợp đồng: {{contract.number}}, {{contract.signingDate}}, {{contract.signingCity}}, {{contract.schemaVersion}}, {{contract.revisionNumber}}, {{contract.specialTerms}}
   - Bên A: {{landlord.fullName}}, {{landlord.idNumber}}, {{landlord.permanentAddress}}, {{landlord.phone}}, {{landlord.email}}, {{landlord.bankAccountHolder}}, {{landlord.bankAccountNumber}}, {{landlord.bankName}}
   - Bên B: {{tenant.fullName}}, {{tenant.idNumber}}, {{tenant.permanentAddress}}, {{tenant.phone}}, {{tenant.email}}, {{tenant.occupantCount}}, {{tenant.motorbikeCount}}, {{tenant.carCount}}, {{tenant.bankAccountHolder}}, {{tenant.bankAccountNumber}}, {{tenant.bankName}}
   - Bất động sản: {{property.fullAddress}}, {{property.areaText}}, {{property.propertyType}}, {{property.unitNumber}}, {{property.floor}}, {{property.listingCode}}, {{property.rentalScope}}, {{property.maxOccupants}}, {{property.maxVehicles}}
   - Thời hạn thuê: {{lease.startDateText}}, {{lease.endDateText}}, {{lease.durationMonths}}, {{lease.durationText}}, {{lease.handoverDateText}}
   - Giá thuê & Cọc: {{rent.amountNumber}}, {{rent.amountWords}}, {{rent.paymentCycle}}, {{rent.paymentDueDay}}, {{rent.paymentMethod}}, {{deposit.amountNumber}}, {{deposit.amountWords}}, {{deposit.description}}
   - Thanh toán ban đầu: {{payment.initial.status}}, {{payment.initial.payerReportedAt}}, {{payment.initial.payeeConfirmedAt}}, {{payment.initial.confirmedAt}}, {{payment.initial.transferReference}}, {{payment.initial.bankTransactionReference}}, {{payment.initial.totalAmount}}
   - Chỉ số bàn giao: {{meters.electricityInitial}}, {{meters.waterInitial}}
   - Bảng động: {{#chargesTable}}, {{#equipmentTable}}, {{#propertyFeaturesTable}}, {{#amenitiesTable}}, {{#initialPaymentTable}}
2. TUYỆT ĐỐI KHÔNG đưa branchId, tên chi nhánh, mã chi nhánh vào văn bản.
3. Không tự tạo thêm placeholder mới. Không dùng dấu chấm thủ công (....).
4. Thanh toán chuyển khoản trực tiếp: Tiền thuê và tiền cọc chuyển khoản trực tiếp vào tài khoản ngân hàng Bên A.
5. Ghi nhận rõ đầy đủ 7 điều khoản chuyển khoản trực tiếp:
   - Phương thức thanh toán trực tiếp vào tài khoản Bên A.
   - Vai trò HomeSpace: Công cụ tính toán khoản phải trả, tạo VietQR, lưu trữ chứng từ và trạng thái khai báo; không nhận, không giữ, không chuyển tiền thay các bên.
   - Xác nhận hai chiều: Bên B khai báo chuyển khoản, Bên A xác nhận nhận đủ tiền.
   - Trách nhiệm kiểm tra thông tin chuyển khoản của hai bên.
   - Xử lý sai lệch, tranh chấp và cung cấp chứng từ ngân hàng.
   - Thay đổi tài khoản ngân hàng phải thông báo và xác nhận trước.
   - Hoàn cọc chuyển khoản trực tiếp vào tài khoản Bên B sau bàn giao và quyết toán.
6. Nguyên tắc tiện ích chung: "Đối với tiện ích dùng chung (hồ bơi, phòng gym, thang máy nếu có), Bên B được quyền sử dụng theo nội quy, khung giờ và tình trạng vận hành của Ban Quản lý tòa nhà, không cấu thành cam kết vận hành liên tục tuyệt đối."

III. CẤU TRÚC ĐIỀU KHOẢN CHI TIẾT
1. QUỐC HIỆU - TIÊU NGỮ - TÊN HỢP ĐỒNG: HỢP ĐỒNG THUÊ CĂN HỘ CHUNG CƯ
   Số: {{contract.number}} - Ngày ký: {{contract.signingDate}} tại {{contract.signingCity}}.
2. CĂN CỨ PHÁP LÝ (Như mục I).
3. THÔNG TIN CÁC BÊN:
   - BÊN CHO THUÊ (BÊN A): {{landlord.fullName}}, CCCD: {{landlord.idNumber}}, Thường trú: {{landlord.permanentAddress}}, Điện thoại: {{landlord.phone}}, Email: {{landlord.email}}.
     Tài khoản nhận thanh toán: Chủ tài khoản: {{landlord.bankAccountHolder}} - Số tài khoản: {{landlord.bankAccountNumber}} - Ngân hàng: {{landlord.bankName}}.
   - BÊN THUÊ (BÊN B): {{tenant.fullName}}, CCCD: {{tenant.idNumber}}, Thường trú: {{tenant.permanentAddress}}, Điện thoại: {{tenant.phone}}, Email: {{tenant.email}}, Số người cư trú: {{tenant.occupantCount}}, Xe máy đăng ký: {{tenant.motorbikeCount}}, Ô tô đăng ký: {{tenant.carCount}}.
     Tài khoản nhận hoàn trả: Chủ tài khoản: {{tenant.bankAccountHolder}} - Số tài khoản: {{tenant.bankAccountNumber}} - Ngân hàng: {{tenant.bankName}}.
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
6. ĐIỀU 3: GIÁ THUÊ, TIỀN CỌC VÀ CHUYỂN KHOẢN TRỰC TIẾP
   - Giá thuê căn hộ: {{rent.amountNumber}} VNĐ/tháng (Bằng chữ: {{rent.amountWords}}).
   - Chu kỳ thanh toán: {{rent.paymentCycle}}; Hạn thanh toán định kỳ: {{rent.paymentDueDay}}.
   - Phương thức thanh toán: {{rent.paymentMethod}}.
   - Tiền đặt cọc: {{deposit.amountNumber}} VNĐ (Bằng chữ: {{deposit.amountWords}}).
   - Điều kiện hoàn cọc: {{deposit.description}}.
   - Điều khoản chuyển khoản trực tiếp và vai trò HomeSpace:
     + Bên B thanh toán bằng hình thức chuyển khoản trực tiếp vào tài khoản ngân hàng Bên A chỉ định trong Hợp đồng này.
     + HomeSpace cung cấp công cụ tính toán khoản phải trả, tạo VietQR, lưu trữ yêu cầu thanh toán và ghi nhận trạng thái do các bên khai báo; HomeSpace không nhận tiền, không giữ tiền, không chuyển tiền thay các bên.
     + Khoản thanh toán hoàn tất trên HomeSpace khi Bên B khai báo chuyển khoản và Bên A xác nhận đã nhận đủ tiền.
     + Hai bên tự chịu trách nhiệm kiểm tra thông tin tài khoản ngân hàng trước khi giao dịch.
     + Trường hợp sai lệch, hai bên phối hợp cung cấp chứng từ ngân hàng để đối soát.
     + Mọi thay đổi tài khoản nhận thanh toán phải được bên kia xác nhận trước khi áp dụng.
     + Hoàn cọc trực tiếp vào tài khoản nhận hoàn trả của Bên B sau quyết toán.
   - Bảng ghi nhận thanh toán ban đầu đã được hai bên xác nhận:
     {{#initialPaymentTable}}
     Mã nội dung chuyển khoản: {{payment.initial.transferReference}}, Bên B khai báo chuyển: {{payment.initial.payerReportedAt}}, Bên A xác nhận nhận: {{payment.initial.payeeConfirmedAt}}, Trạng thái xác nhận: {{payment.initial.status}}.
7. ĐIỀU 4: PHÍ QUẢN LÝ, GỬI XE, ĐIỆN NƯỚC VÀ DỊCH VỤ CHUNG CƯ
   - Bảng phí quản lý tòa nhà, gửi xe máy/ô tô và các chi phí sinh hoạt:
     {{#chargesTable}}
   - Bảng tiện ích chung cư và quyền sử dụng dịch vụ:
     {{#amenitiesTable}}
8. ĐIỀU 5: DANH MỤC TRANG THIẾT BỊ, NỘI THẤT BÀN GIAO
   - Chi tiết danh mục nội thất, máy móc gắn liền căn hộ:
     {{#equipmentTable}}
   - Bên B có trách nhiệm giữ gìn nội thất, không tự ý can thiệp hệ thống phòng cháy tự động (đầu phun sprinkler, đầu báo khói).
9. ĐIỀU 6: QUYỀN VÀ NGHĨA VỤ CỦA BÊN A
   - Bàn giao căn hộ, thẻ thang máy, chìa khóa/mật mã cửa đúng thỏa thuận.
   - Cung cấp tài khoản ngân hàng thụ hưởng chính xác; xác nhận kịp thời khi nhận được tiền chuyển khoản.
   - Hỗ trợ đăng ký định mức điện nước và thủ tục tạm trú cho Bên B với Ban Quản lý và Công an địa phương.
10. ĐIỀU 7: QUYỀN VÀ NGHĨA VỤ CỦA BÊN B
    - Chấp hành nghiêm chỉnh Nội quy tòa nhà chung cư, quy chế cư dân, quy định gửi xe.
    - Cư trú đúng số lượng: {{tenant.occupantCount}} người; gửi xe đúng số lượng: {{tenant.motorbikeCount}} xe máy, {{tenant.carCount}} ô tô.
    - Chuyển khoản tiền thuê và các chi phí đúng hạn vào tài khoản ngân hàng của Bên A.
11. ĐIỀU 8: ĐIỀU KHOẢN VỀ VẬT NUÔI VÀ THỎA THUẬN KHÁC
    - Quy định vật nuôi: Chỉ được nuôi thú cưng nếu Nội quy chung cư và Bảng tiện ích cho phép; Bên B chịu hoàn toàn trách nhiệm vệ sinh, an toàn.
    - Điều khoản đặc biệt: {{contract.specialTerms}}
12. ĐIỀU 9: CHẤM DỨT HỢP ĐỒNG VÀ XỬ LÝ VI PHẠM
    - Thông báo trước khi kết thúc hợp đồng; bàn giao lại thẻ cư dân, hiện trạng căn hộ và thanh toán trực tiếp các nghĩa vụ hoàn cọc/quyết toán.
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
Soạn toàn văn file Word mẫu "HỢP ĐỒNG THUÊ PHÒNG TRỌ" (dành cho phòng trọ trong dãy trọ, nhà nhiều phòng cho thuê hoặc căn hộ dịch vụ chia phòng) theo chuẩn Schema V3. Văn bản phải rõ ràng, thiết thực, ghi rõ tài khoản ngân hàng hai bên, chuyển khoản trực tiếp, tập trung vào an ninh trật tự, giờ giấc ra vào, PCCC (đặc biệt là sạc xe điện và thiết bị đun nấu), đăng ký tạm trú và chia sẻ không gian chung.

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
1. Chỉ sử dụng các placeholder trong danh mục 63 trường được hỗ trợ:
   - Hợp đồng: {{contract.number}}, {{contract.signingDate}}, {{contract.signingCity}}, {{contract.schemaVersion}}, {{contract.revisionNumber}}, {{contract.specialTerms}}
   - Bên A: {{landlord.fullName}}, {{landlord.idNumber}}, {{landlord.permanentAddress}}, {{landlord.phone}}, {{landlord.email}}, {{landlord.bankAccountHolder}}, {{landlord.bankAccountNumber}}, {{landlord.bankName}}
   - Bên B: {{tenant.fullName}}, {{tenant.idNumber}}, {{tenant.permanentAddress}}, {{tenant.phone}}, {{tenant.email}}, {{tenant.occupantCount}}, {{tenant.motorbikeCount}}, {{tenant.carCount}}, {{tenant.bankAccountHolder}}, {{tenant.bankAccountNumber}}, {{tenant.bankName}}
   - Bất động sản: {{property.fullAddress}}, {{property.areaText}}, {{property.propertyType}}, {{property.unitNumber}}, {{property.floor}}, {{property.listingCode}}, {{property.rentalScope}}, {{property.maxOccupants}}, {{property.maxVehicles}}
   - Thời hạn thuê: {{lease.startDateText}}, {{lease.endDateText}}, {{lease.durationMonths}}, {{lease.durationText}}, {{lease.handoverDateText}}
   - Giá thuê & Cọc: {{rent.amountNumber}}, {{rent.amountWords}}, {{rent.paymentCycle}}, {{rent.paymentDueDay}}, {{rent.paymentMethod}}, {{deposit.amountNumber}}, {{deposit.amountWords}}, {{deposit.description}}
   - Thanh toán ban đầu: {{payment.initial.status}}, {{payment.initial.payerReportedAt}}, {{payment.initial.payeeConfirmedAt}}, {{payment.initial.confirmedAt}}, {{payment.initial.transferReference}}, {{payment.initial.bankTransactionReference}}, {{payment.initial.totalAmount}}
   - Chỉ số bàn giao: {{meters.electricityInitial}}, {{meters.waterInitial}}
   - Bảng động: {{#chargesTable}}, {{#equipmentTable}}, {{#propertyFeaturesTable}}, {{#amenitiesTable}}, {{#initialPaymentTable}}
2. TUYỆT ĐỐI KHÔNG đưa branchId, tên chi nhánh, mã chi nhánh vào văn bản.
3. Không tự tạo thêm placeholder mới. Không dùng dấu chấm thủ công (....).
4. Thanh toán chuyển khoản trực tiếp: Tiền thuê phòng, tiền cọc và hoàn cọc chuyển khoản trực tiếp giữa tài khoản ngân hàng của Bên A và Bên B.
5. Ghi nhận rõ đầy đủ 7 điều khoản chuyển khoản trực tiếp:
   - Phương thức thanh toán chuyển khoản trực tiếp vào tài khoản ngân hàng Bên A.
   - Vai trò HomeSpace: Công cụ tính toán khoản phải trả, tạo thông tin VietQR, lưu trữ chứng từ và trạng thái khai báo; không nhận, không giữ, không chuyển tiền thay hai bên.
   - Xác nhận hai chiều: Bên B báo chuyển khoản, Bên A xác nhận nhận đủ tiền.
   - Hai bên tự kiểm tra thông tin tài khoản thụ hưởng trước khi chuyển tiền.
   - Sai lệch thông tin thì phối hợp cung cấp chứng từ ngân hàng để đối soát.
   - Thay đổi tài khoản nhận tiền phải thông báo và xác nhận trước bằng văn bản/thông điệp dữ liệu.
   - Hoàn cọc chuyển khoản trực tiếp vào tài khoản Bên B sau bàn giao và quyết toán.
6. Nguyên tắc tiện ích chung: "Đối với tiện ích dùng chung (khu giặt phơi, nhà để xe, lối đi chung nếu có), Bên B được quyền sử dụng theo nội quy nhà trọ, khung giờ và tình trạng vận hành thực tế, không cấu thành cam kết vận hành liên tục tuyệt đối."

III. CẤU TRÚC ĐIỀU KHOẢN CHI TIẾT
1. QUỐC HIỆU - TIÊU NGỮ - TÊN HỢP ĐỒNG: HỢP ĐỒNG THUÊ PHÒNG TRỌ
   Số: {{contract.number}} - Ngày ký: {{contract.signingDate}} tại {{contract.signingCity}}.
2. CĂN CỨ PHÁP LÝ (Như mục I).
3. THÔNG TIN CÁC BÊN:
   - BÊN CHO THUÊ (BÊN A): {{landlord.fullName}}, CCCD: {{landlord.idNumber}}, Thường trú: {{landlord.permanentAddress}}, Điện thoại: {{landlord.phone}}, Email: {{landlord.email}}.
     Tài khoản nhận thanh toán: Chủ tài khoản: {{landlord.bankAccountHolder}} - Số tài khoản: {{landlord.bankAccountNumber}} - Ngân hàng: {{landlord.bankName}}.
   - BÊN THUÊ (BÊN B): {{tenant.fullName}}, CCCD: {{tenant.idNumber}}, Thường trú: {{tenant.permanentAddress}}, Điện thoại: {{tenant.phone}}, Email: {{tenant.email}}, Số người cư trú: {{tenant.occupantCount}}, Xe máy đăng ký: {{tenant.motorbikeCount}}, Ô tô: {{tenant.carCount}}.
     Tài khoản nhận hoàn trả: Chủ tài khoản: {{tenant.bankAccountHolder}} - Số tài khoản: {{tenant.bankAccountNumber}} - Ngân hàng: {{tenant.bankName}}.
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
6. ĐIỀU 3: TIỀN THUÊ PHÒNG, ĐẶT CỌC VÀ CHUYỂN KHOẢN TRỰC TIẾP
   - Tiền thuê phòng: {{rent.amountNumber}} VNĐ/tháng (Bằng chữ: {{rent.amountWords}}).
   - Chu kỳ thanh toán: {{rent.paymentCycle}}; Hạn thanh toán định kỳ: {{rent.paymentDueDay}}.
   - Phương thức thanh toán: {{rent.paymentMethod}}.
   - Tiền đặt cọc: {{deposit.amountNumber}} VNĐ (Bằng chữ: {{deposit.amountWords}}).
   - Điều kiện hoàn cọc: {{deposit.description}}.
   - Điều khoản chuyển khoản trực tiếp và vai trò HomeSpace:
     + Bên B thanh toán bằng hình thức chuyển khoản trực tiếp vào tài khoản ngân hàng của Bên A chỉ định trong Hợp đồng này.
     + HomeSpace cung cấp công cụ tính toán khoản phải trả, tạo thông tin VietQR, lưu trữ yêu cầu thanh toán và ghi nhận trạng thái do các bên khai báo. HomeSpace không nhận tiền, không giữ tiền, không chuyển tiền thay các bên.
     + Khoản thanh toán hoàn tất trên HomeSpace khi Bên B khai báo chuyển khoản và Bên A xác nhận đã nhận đủ tiền.
     + Hai bên tự chịu trách nhiệm kiểm tra thông tin tài khoản ngân hàng trước khi giao dịch.
     + Trường hợp có sai lệch thông tin, hai bên phối hợp cung cấp chứng từ ngân hàng để xác minh.
     + Mọi thay đổi tài khoản nhận thanh toán hoặc nhận hoàn trả phải được thông báo và xác nhận trước.
     + Khi hết hạn hợp đồng hoặc chấm dứt hợp đồng, sau khi hoàn tất bàn giao phòng và quyết toán, Bên A chuyển khoản tiền cọc còn lại trực tiếp vào tài khoản Bên B ghi trong Hợp đồng này.
   - Bảng ghi nhận thanh toán ban đầu đã được hai bên xác nhận:
     {{#initialPaymentTable}}
     Mã nội dung chuyển khoản: {{payment.initial.transferReference}}, Bên B khai báo chuyển: {{payment.initial.payerReportedAt}}, Bên A xác nhận nhận: {{payment.initial.payeeConfirmedAt}}, Trạng thái xác nhận: {{payment.initial.status}}.
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
   - Cung cấp tài khoản ngân hàng chính xác; kiểm tra và xác nhận kịp thời khi nhận được tiền.
   - Đăng ký tạm trú cho Bên B theo đúng quy định Luật Cư trú; đảm bảo an ninh khu trọ và bảo trì hệ thống cấp thoát nước, điện tổng.
10. ĐIỀU 7: QUYỀN VÀ NGHĨA VỤ CỦA BÊN B (NỘI QUY VÀ AN TOÀN PCCC)
    - Cư trú đúng số người đăng ký: {{tenant.occupantCount}} người; giữ đúng số lượng xe: {{tenant.motorbikeCount}} xe máy. Khách ở qua đêm phải báo trước với Bên A và đăng ký theo quy định.
    - TUÂN THỦ NGHIÊM NGẶT PCCC: Không sạc pin/ắc quy xe điện qua đêm không có người trông coi; không đun nấu bằng bếp gas mini không bảo đảm an toàn; không che chắn hành lang, cầu thang thoát nạn.
    - Chuyển khoản tiền thuê và chi phí đầy đủ, đúng hạn vào tài khoản ngân hàng của Bên A.
    - Giữ gìn an ninh trật tự, không mở nhạc lớn sau 22h00; giữ vệ sinh khu vực chung (sân phơi, nhà xe, hành lang).
11. ĐIỀU 8: QUY ĐỊNH VỀ VẬT NUÔI VÀ THỎA THUẬN KHÁC
    - Quy định vật nuôi: Chỉ được nuôi nếu khu trọ cho phép (thể hiện tại Bảng tiện ích); Bên B chịu trách nhiệm giữ gìn vệ sinh và không gây ồn ào.
    - Thỏa thuận bổ sung: {{contract.specialTerms}}
12. ĐIỀU 9: CHẤM DỨT HỢP ĐỒNG VÀ HOÀN TRẢ PHÒNG
    - Bên B muốn trả phòng trước hạn phải báo trước tối thiểu theo thỏa thuận, dọn dẹp sạch sẽ và bàn giao lại chìa khóa, hiện trạng phòng.
    - Quyết toán và chuyển khoản hoàn cọc trực tiếp vào tài khoản ngân hàng của Bên B.
13. ĐIỀU 10: GIAO KẾT ĐIỆN TỬ VÀ HIỆU LỰC
    - Hợp đồng được giao kết điện tử qua nền tảng HomeSpace theo Luật Giao dịch điện tử.
    - Phiên bản schema: {{contract.schemaVersion}}, Số hiệu bản sửa đổi: {{contract.revisionNumber}}.
    - Hợp đồng có hiệu lực sau khi hai bên hoàn tất xác nhận điện tử.
14. CHỮ KÝ CÁC BÊN:
    - ĐẠI DIỆN BÊN A (Ký, ghi rõ họ tên)
    - ĐẠI DIỆN BÊN B (Ký, ghi rõ họ tên)
```
