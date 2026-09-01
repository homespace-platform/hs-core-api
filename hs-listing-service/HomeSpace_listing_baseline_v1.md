# HomeSpace — Baseline Listing v1

Ngày rà soát: **2026-09-01**  
Frontend đã đối chiếu: `hs-web-app` commit `374e3df`  
Backend đã đối chiếu: `hs-core-api` commit `4a2e23e`

## 1. Mục đích và nguyên tắc sử dụng

Tài liệu này là bản chụp contract hiện tại của luồng tạo, sửa, lưu nháp, gửi duyệt và quản lý tin đăng cho thuê HomeSpace.

Thứ tự ưu tiên khi có khác biệt:

1. DTO, enum, validation và service backend đang chạy là nguồn sự thật của API và dữ liệu được lưu.
2. Frontend đang chạy là nguồn sự thật của nhãn hiển thị, thứ tự section và option người dùng nhìn thấy.
3. Tài liệu này ghi lại cả contract đúng và các sai lệch frontend/backend đã phát hiện; không được dùng phần “sai lệch” như hành vi mong muốn.
4. Danh mục động như tỉnh/thành, phường/xã, tiện ích và nội thất phải lấy từ API, không hard-code lại ở frontend.

Phạm vi rà soát chính:

- `hs-web-app/app/dashboard/properties/new/**`
- `hs-web-app/app/dashboard/properties/upsert/page.tsx`
- `hs-web-app/app/dashboard/properties/page.tsx`
- `hs-web-app/config/listing-status.config.ts`
- `hs-web-app/services/listing.service.ts`
- `hs-core-api/hs-listing-service/**`
- Các Listing controller trong `hs-core-api/hs-api-service`

## 2. Luồng và route frontend

- Tạo mới: `/dashboard/properties/new`.
- Upsert/chỉnh sửa: `/dashboard/properties/upsert?id={listingId}`.
- Trang upsert tái sử dụng nguyên component của trang tạo mới.
- Danh sách tin của tôi: `/dashboard/properties`.
- Xem chi tiết: `/dashboard/properties/view?id={listingId}`.

Thứ tự section của form:

1. Thông tin cơ bản.
2. Thông tin chi tiết theo loại hình.
3. Tiện ích & Dịch vụ.
4. Chi phí hàng tháng.
5. Giá & Điều kiện thuê.
6. Vị trí.
7. Lịch xem nhà.
8. Mô tả chi tiết.
9. Hành động cuối form.

Hành động cuối form:

- Hủy.
- Kiểm tra dữ liệu.
- Lưu nháp → `submissionAction = SAVE_DRAFT` → trạng thái `DRAFT`.
- Gửi duyệt/Cập nhật & Gửi duyệt → `submissionAction = SUBMIT_FOR_REVIEW` → trạng thái `PENDING_REVIEW`.

Lưu ý quan trọng: frontend hiện chạy cùng một hàm validation đầy đủ cho cả **Lưu nháp** và **Gửi duyệt**. Backend cũng dùng cùng `CreateListingRequest` có validation bắt buộc. Vì vậy “Lưu nháp” hiện không phải bản nháp thiếu dữ liệu; vẫn cần đủ ảnh, địa chỉ, lịch xem, mô tả, giá và detail theo category.

## 3. Contract option tổng quát

### 3.1. Loại hình

| Nhãn frontend | Giá trị frontend | Giá trị API |
|---|---|---|
| Căn hộ / Chung cư | `apartment` | `APARTMENT` |
| Nhà nguyên căn | `house` | `HOUSE` |
| Văn phòng | `office` | `OFFICE` |
| Mặt bằng kinh doanh | `commercial` | `COMMERCIAL_SPACE` |
| Nhà trọ / Căn hộ dịch vụ | `room` | `ROOM` |

### 3.2. Loại chi tiết — subtype

#### Căn hộ / Chung cư

| Nhãn | Giá trị frontend | Giá trị API dự kiến |
|---|---|---|
| Căn hộ thường | `apartment_normal` | `APARTMENT_STANDARD` |
| Studio | `studio` | `APARTMENT_STUDIO` |
| Duplex | `duplex` | `APARTMENT_DUPLEX` |
| Penthouse | `penthouse` | `APARTMENT_PENTHOUSE` |
| Officetel | `officetel` | `APARTMENT_OFFICETEL` |
| Loại khác | `other` | `APARTMENT_OTHER` |

#### Nhà nguyên căn

| Nhãn | Giá trị frontend | Giá trị API dự kiến |
|---|---|---|
| Nhà phố | `townhouse` | `HOUSE_TOWNHOUSE` |
| Nhà trong hẻm | `alley_house` | `HOUSE_ALLEY` |
| Biệt thự | `villa` | `HOUSE_VILLA` |
| Nhà cấp 4 | `level4_house` | `HOUSE_GRADE_4` |
| Loại khác | `other` | `HOUSE_OTHER` |

#### Văn phòng

| Nhãn | Giá trị frontend | Giá trị API dự kiến |
|---|---|---|
| Văn phòng truyền thống | `traditional_office` | `OFFICE_TRADITIONAL` |
| Văn phòng dịch vụ | `serviced_office` | `OFFICE_SERVICED` |
| Coworking | `coworking` | `OFFICE_COWORKING` |
| Văn phòng chia sẻ | `shared_office` | `OFFICE_SHARED` |
| Loại khác | `other` | `OFFICE_OTHER` |

#### Mặt bằng kinh doanh

| Nhãn | Giá trị frontend | Giá trị API |
|---|---|---|
| Cửa hàng | `shop` | `COMMERCIAL_STORE` |
| Ki-ốt | `kiosk` | `COMMERCIAL_KIOSK` |
| Showroom | `showroom` | `COMMERCIAL_SHOWROOM` |
| Shophouse | `shophouse` | `COMMERCIAL_SHOPHOUSE` |
| Mặt bằng trong trung tâm thương mại | `mall_space` | `COMMERCIAL_MALL` |
| Loại khác | `other` | `COMMERCIAL_OTHER` |

#### Nhà trọ / Căn hộ dịch vụ

| Nhãn | Giá trị frontend | Giá trị API |
|---|---|---|
| Phòng trọ | `boarding_room` | `ROOM_BOARDING` |
| Phòng trong nhà nguyên căn | `house_room` | `ROOM_IN_HOUSE` |
| Căn hộ dịch vụ | `serviced_apartment` | `ROOM_SERVICED_APARTMENT` |
| Ký túc xá | `dormitory` | `ROOM_DORMITORY` |
| Loại khác | `other` | `ROOM_OTHER` |

Backend bắt buộc subtype phải thuộc đúng category; sai cặp trả `DETAIL_CATEGORY_CONFLICT`.

### 3.3. Hình thức cho thuê hiển thị trên frontend

| Category | Option frontend |
|---|---|
| Căn hộ | `WHOLE` — Cho thuê nguyên căn; `PRIVATE_ROOM` — Phòng riêng trong căn hộ; `SHARED_ROOM` — Ở ghép |
| Nhà nguyên căn | `WHOLE` — Cho thuê nguyên căn; `PARTIAL` — Một phần căn nhà |
| Văn phòng | `WHOLE_FLOOR` — Nguyên sàn/diện tích lớn; `PRIVATE_OFFICE` — Phòng làm việc riêng; `HOT_DESK` — Chỗ ngồi/Coworking |
| Mặt bằng | `WHOLE` — Nguyên mặt bằng; `PARTIAL` — Một phần mặt bằng; `KIOSK` — Ki-ốt/Quầy kinh doanh |
| Nhà trọ/Phòng | `PRIVATE_ROOM` — Phòng riêng; `SHARED_ROOM` — Ở ghép |

Backend hiện chỉ có hai enum `RentalMode`: `WHOLE_UNIT`, `PARTIAL`. Payload frontend hiện chỉ gửi `PARTIAL` cho Nhà nguyên căn khi chọn `PARTIAL`; mọi option còn lại đều bị quy về `WHOLE_UNIT`. Xem mục sai lệch ở cuối tài liệu.

### 3.4. Tình trạng nội thất

Dropdown dùng chung ở Căn hộ, Nhà nguyên căn và Phòng:

| Nhãn frontend | Giá trị frontend | Enum backend cần nhận |
|---|---|---|
| Bàn giao thô / Chưa có nội thất | `RAW` | `UNFURNISHED` |
| Nội thất cơ bản | `BASIC` | `BASIC` |
| Đầy đủ nội thất | `FULL` | `FULLY_FURNISHED` |
| Nội thất cao cấp | `LUXURY` | Chưa có enum tương ứng |

Backend còn có `PARTIALLY_FURNISHED` nhưng frontend chưa có option riêng.

### 3.5. Hướng cửa và hướng ban công

Các option dùng chung:

- `EAST` — Đông.
- `WEST` — Tây.
- `SOUTH` — Nam.
- `NORTH` — Bắc.
- `SOUTH_EAST` — Đông Nam.
- `NORTH_EAST` — Đông Bắc.
- `SOUTH_WEST` — Tây Nam.
- `NORTH_WEST` — Tây Bắc.

Hướng cửa có thêm option rỗng “Không xác định”. Hướng ban công có thêm option rỗng “Không xác định / Không có ban công”. Backend hiện lưu chuỗi, chưa dùng enum.

### 3.6. Giấy tờ pháp lý

- `PENDING` — Đang chờ sổ.
- `PINK_BOOK` — Sổ hồng / Sổ đỏ.
- `CONTRACT` — Hợp đồng mua bán.
- `OTHER` — Giấy tờ hợp lệ khác.

Backend hiện lưu chuỗi, chưa dùng enum.

## 4. Thông tin cơ bản và media

Field:

- Tiêu đề: frontend yêu cầu tối thiểu 15 ký tự, input tối đa 150 ký tự; backend `@NotBlank`, tối đa 255 ký tự.
- Ảnh: tối thiểu 1, frontend tối đa 6; ảnh đầu tiên làm cover.
- Video: không bắt buộc, frontend tối đa 3.
- Loại hình, subtype, hình thức thuê.
- Ngày có thể vào thuê/bàn giao.

Frontend chấp nhận:

- Ảnh: JPEG, PNG, WEBP; UI ghi tối đa 10 MB/ảnh.
- Video: MP4, WebM, MOV; UI ghi tối đa 50 MB/video.

Backend storage thực tế:

- `LISTING_IMAGE`: tối đa 25 MiB.
- `LISTING_VIDEO`: tối đa 200 MiB.
- Frontend gửi upload với `purpose = GENERAL`, `referenceType = LISTING`; storage backend tự chuẩn hóa thành `LISTING_IMAGE` hoặc `LISTING_VIDEO` theo content type.
- Listing backend chỉ nhận storage object có trạng thái `READY`, đúng owner, còn active, đúng purpose và đúng content type.
- Không được trùng `storageObjectId`.
- Tối đa một cover và cover phải là ảnh.

## 5. Detail theo từng category và toàn bộ dropdown

### 5.1. Căn hộ / Chung cư

Field:

- Tên dự án/chung cư, bắt buộc.
- Tòa/Block.
- Mã căn.
- Diện tích, bắt buộc và lớn hơn 0.
- Tầng căn hộ, bắt buộc; backend cho phép từ 0.
- Tổng số tầng tòa nhà.
- Số phòng ngủ; Studio cho phép 0, subtype khác phải lớn hơn 0.
- Số phòng tắm/WC.
- Số phòng khách.
- Số phòng bếp.
- Tình trạng nội thất: toàn bộ option tại mục 3.4.
- Hướng cửa chính: toàn bộ option tại mục 3.5.
- Hướng ban công: toàn bộ option tại mục 3.5.
- View căn hộ.
- Số người tối đa.
- Giấy tờ pháp lý: toàn bộ option tại mục 3.6.

Đơn vị giá backend cho phép: chỉ `MONTH`.

### 5.2. Nhà nguyên căn

Field:

- Tổng diện tích sử dụng, bắt buộc.
- Diện tích đất.
- Chiều ngang mặt tiền.
- Chiều dài.
- Độ rộng đường/hẻm phía trước.
- Số mặt tiền.
- Tổng số tầng, bắt buộc.
- Tình trạng nội thất.
- Số phòng ngủ, số phòng tắm/WC, số phòng khách, số phòng bếp.
- Có sân thượng.
- Có garage/sân để xe.
- Lối đi.
- Số người tối đa, số xe tối đa.
- Giấy tờ pháp lý.

Dropdown Số mặt tiền:

- `1` — 1 mặt tiền.
- `2` — 2 mặt tiền (Căn góc).
- `3` — 3 mặt tiền.

Dropdown Lối đi:

- `PRIVATE` — Lối đi riêng biệt.
- `SHARED` — Lối đi chung.

Khi `rentalType = PARTIAL`, frontend hiển thị thêm:

- Phạm vi cho thuê.
- Tầng được cho thuê.
- Lối đi sử dụng: `SHARED` — Đi chung với chủ nhà/người khác; `PRIVATE` — Có lối đi riêng độc lập.

Backend cho phép `RentalMode`: `WHOLE_UNIT`, `PARTIAL`. Đơn vị giá: chỉ `MONTH`.

### 5.3. Văn phòng

Field:

- Tên tòa nhà; bắt buộc với `OFFICE_TRADITIONAL`.
- Diện tích cho thuê, bắt buộc.
- Tầng cho thuê, bắt buộc.
- Tình trạng bàn giao.
- Hạng văn phòng.
- Số chỗ ngồi dự kiến.
- Khả năng chia nhỏ diện tích.
- Số nhà vệ sinh.
- Hệ thống nhà vệ sinh.
- Pantry/Khu ăn uống.
- Giờ hoạt động.
- Sức chứa chỗ đỗ ô tô và xe máy.

Dropdown Tình trạng bàn giao:

- `RAW` — Bàn giao thô (sàn bê tông, trần thô).
- `BASIC` — Hoàn thiện cơ bản (trần, sàn, chiếu sáng, điều hòa).
- `FULL` — Đầy đủ nội thất (bàn ghế, tủ hồ sơ, vách ngăn).

Dropdown Hạng văn phòng:

- `GRADE_A` — Hạng A.
- `GRADE_B` — Hạng B.
- `GRADE_C` — Hạng C.
- `ECONOMY` — Văn phòng giá rẻ/Tòa nhà tư nhân.

Dropdown Khả năng chia nhỏ:

- `NO` — Cho thuê nguyên diện tích.
- `YES` — Có thể chia nhỏ linh hoạt.

Dropdown Hệ thống nhà vệ sinh:

- `SHARED` — Nhà vệ sinh chung tầng.
- `PRIVATE` — Nhà vệ sinh riêng trong văn phòng.

Dropdown Pantry:

- `PRIVATE` — Pantry riêng.
- `SHARED` — Pantry chung tòa nhà.
- `NONE` — Không có.

Preset Giờ hoạt động:

- `24/7 (Tự do ra vào)`.
- `08:00 – 17:30 (Thứ 2 – Thứ 6)`.
- `07:30 – 18:30 (Thứ 2 – Thứ 7)`.
- Tùy chỉnh giờ mở/đóng và dải ngày: `Thứ 2 – Thứ 6`, `Thứ 2 – Thứ 7`, `Cả tuần (Thứ 2 – CN)`, `Tất cả các ngày`.

Backend `OperatingMode`: `ALWAYS_OPEN`, `CUSTOM_SCHEDULE`. Với custom schedule phải có ít nhất một dòng giờ, không trùng ngày và `openTime < closeTime`.

Đơn vị giá backend cho phép: `MONTH`, `M2_MONTH`, `SEAT_MONTH`.

### 5.4. Mặt bằng kinh doanh

Field:

- Diện tích sử dụng, bắt buộc.
- Vị trí không gian, bắt buộc.
- Chiều ngang mặt tiền; bắt buộc với Cửa hàng, Showroom, Shophouse.
- Chiều dài.
- Tình trạng bàn giao, bắt buộc.
- Chỗ để xe cho khách và nhân viên.
- Độ rộng mặt đường phía trước.
- Số mặt tiền.
- Số tầng cho thuê.
- Số nhà vệ sinh.
- Lối đi.
- Giờ hoạt động được phép.
- Ngành nghề bị hạn chế.
- Có điện 3 pha.
- Hệ thống PCCC đạt chuẩn.
- Có gác lửng.
- Khu vực bốc dỡ hàng hóa.

Dropdown Vị trí không gian:

- `GROUND_LEVEL` — Mặt đất/Tầng trệt → backend `GROUND_FLOOR`.
- `UPPER_FLOOR` — Tầng lầu → backend `UPPER_FLOOR`.
- `MALL_SPACE` — Trong trung tâm thương mại/Tòa nhà phức hợp → backend cần `SHOPPING_MALL`.
- `OTHER` — Loại khác → backend `OTHER`.

Dropdown Tình trạng bàn giao:

- `RAW` — Bàn giao thô.
- `BASIC` — Hoàn thiện cơ bản.
- `FINISHED` — Đã hoàn thiện, sẵn sàng kinh doanh.

Dropdown Chỗ để xe:

- `NONE` — Không có chỗ để xe riêng → backend `NONE`.
- `MOTORBIKE` — Chỗ để xe máy → backend `MOTORBIKE`.
- `CAR` — Chỗ đỗ ô tô → backend `CAR`.
- `BOTH` — Cả xe máy và ô tô → backend `MOTORBIKE_AND_CAR`.

Dropdown Số mặt tiền:

- `1` — 1 mặt tiền.
- `2` — 2 mặt tiền (Lô góc).
- `3` — 3 mặt tiền.

Dropdown Lối đi:

- `PRIVATE` — Lối đi riêng biệt hoàn toàn.
- `SHARED` — Chung lối đi với tầng trên/chủ nhà.

Đơn vị giá backend cho phép: `MONTH`, `M2_MONTH`.

### 5.5. Nhà trọ / Căn hộ dịch vụ

Field:

- Diện tích phòng, bắt buộc.
- Mã phòng/Tên phòng.
- Nhà vệ sinh, bắt buộc.
- Khu bếp.
- Cửa sổ.
- Ban công.
- Tầng của phòng.
- Tình trạng nội thất, bắt buộc.
- Có gác lửng.
- Nội thất/trang thiết bị có sẵn.
- Số người tối đa/phòng, bắt buộc.
- Số xe tối đa/phòng.
- Lối đi.
- Giờ giấc sinh hoạt.
- Đồng hồ điện.
- Đồng hồ nước.
- Chính sách chỗ để xe.

Dropdown Nhà vệ sinh:

- `PRIVATE` — Nhà vệ sinh riêng khép kín.
- `SHARED` — Nhà vệ sinh chung ngoài phòng.

Dropdown Khu bếp:

- `PRIVATE` — Kệ bếp riêng trong phòng.
- `SHARED` — Khu bếp chung.
- `NONE` — Không nấu ăn/Không có bếp.

Dropdown Cửa sổ:

- `YES` — Có cửa sổ.
- `NO` — Không có cửa sổ.

Dropdown Ban công:

- `PRIVATE` — Ban công riêng.
- `SHARED` — Ban công/Sân phơi chung.
- `NONE` — Không có ban công.

Backend chỉ lưu `hasBalcony: boolean`, do đó `PRIVATE` và `SHARED` hiện cùng lưu `true` và không thể phân biệt khi đọc lại.

Dropdown Lối đi:

- `PRIVATE` — Lối đi riêng biệt.
- `SHARED` — Lối đi chung với chủ nhà.

Dropdown Giờ giấc sinh hoạt:

- `FREE` — Tự do 24/7 → backend `FLEXIBLE`.
- `CURFEW` — Có giờ đóng cửa → backend `CURFEW`.

Dropdown Đồng hồ điện và Đồng hồ nước:

- `PRIVATE` — Đồng hồ riêng.
- `SHARED` — Dùng chung.

Dropdown Chính sách chỗ để xe:

- `FREE` — Miễn phí.
- `PAID` — Có thu phí.
- `NONE` — Không có chỗ để xe.

Đơn vị giá backend cho phép: `ROOM_MONTH`, `PERSON_MONTH`. Backend không cho `MONTH` đối với category `ROOM`.

## 6. Danh mục động từ backend

Frontend gọi:

`GET /api/v1/public/listing-catalog?category={ListingCategory}`

Response gồm `amenities` theo category và `furnishings` chỉ có dữ liệu khi category là `ROOM`. Mỗi item gồm `code`, `name`, `sortOrder`.

### 6.1. Tiện ích theo category

#### APARTMENT

- `WIFI` — WiFi.
- `AIR_CONDITIONER` — Máy lạnh.
- `WATER_HEATER` — Máy nước nóng.
- `REFRIGERATOR` — Tủ lạnh.
- `WASHING_MACHINE` — Máy giặt.
- `ELEVATOR` — Thang máy.
- `PARKING` — Chỗ để xe.
- `SECURITY_24_7` — Bảo vệ 24/7.
- `CAMERA` — Camera.
- `PETS_ALLOWED` — Cho nuôi thú cưng.
- `SWIMMING_POOL` — Hồ bơi.
- `GYM` — Phòng gym.

#### HOUSE

- `WIFI` — WiFi.
- `AIR_CONDITIONER` — Máy lạnh.
- `WATER_HEATER` — Máy nước nóng.
- `REFRIGERATOR` — Tủ lạnh.
- `WASHING_MACHINE` — Máy giặt.
- `ELEVATOR` — Thang máy.
- `SECURITY_24_7` — Bảo vệ 24/7.
- `CAMERA` — Camera.
- `PETS_ALLOWED` — Cho nuôi thú cưng.
- `SWIMMING_POOL` — Hồ bơi.
- `GYM` — Phòng gym.

#### OFFICE

- `WIFI` — WiFi.
- `ELEVATOR` — Thang máy.
- `SECURITY_24_7` — Bảo vệ 24/7.
- `CAMERA` — Camera.
- `RECEPTION` — Lễ tân.
- `GENERATOR` — Máy phát điện.
- `CENTRAL_AIR_CONDITIONING` — Điều hòa trung tâm.
- `MEETING_ROOM` — Phòng họp.
- `INTERNET` — Internet.
- `FIRE_SAFETY` — Hệ thống PCCC.

#### COMMERCIAL_SPACE

- `WIFI` — WiFi.
- `ELEVATOR` — Thang máy.
- `SECURITY_24_7` — Bảo vệ 24/7.
- `SECURITY` — Bảo vệ.
- `CAMERA` — Camera.
- `SIGNAGE_POSITION` — Vị trí đặt biển hiệu.

#### ROOM

- `WIFI` — WiFi.
- `ELEVATOR` — Thang máy.
- `SECURITY_24_7` — Bảo vệ 24/7.
- `CAMERA` — Camera.
- `PETS_ALLOWED` — Cho nuôi thú cưng.
- `SWIMMING_POOL` — Hồ bơi.
- `GYM` — Phòng gym.

Frontend cho nhập tiện ích tùy chỉnh. Hiện text tùy chỉnh được gửi chung trong `amenityCodes`; backend không tìm thấy code/name trong catalog thì lưu vào `listing_custom_amenities`. Trường `customAmenities` trong payload frontend hiện luôn là mảng rỗng.

### 6.2. Nội thất/trang thiết bị cho ROOM

- `BED` — Giường.
- `WARDROBE` — Tủ quần áo.
- `WORK_DESK` — Bàn làm việc.
- `KITCHEN_SHELF` — Kệ bếp.
- `REFRIGERATOR` — Tủ lạnh.
- `WASHING_MACHINE` — Máy giặt.
- `AIR_CONDITIONER` — Máy lạnh.
- `WATER_HEATER` — Máy nước nóng.
- `CURTAIN` — Rèm cửa.

Các item này là catalog dùng chung, không tạo lại mỗi lần đăng tin. Listing chỉ lưu quan hệ qua `listing_furnishings`.

## 7. Chi phí hàng tháng và toàn bộ dropdown

### 7.1. Tiền điện

- `KWH` — Tính theo số công tơ → `PER_KWH`, có `amount`, đơn vị `kWh`.
- `STATE_PRICE` — Theo giá nhà nước/EVN → backend hiện dùng `STATE_WATER_RATE`, không có amount.
- `INCLUDED` — Đã bao gồm → `INCLUDED`, `includedInRent = true`.
- `NEGOTIATE` — Thỏa thuận → `NEGOTIABLE`.

### 7.2. Tiền nước

- `M3` — Theo m³ → `PER_M3`.
- `PER_PERSON` — Theo người/tháng → `PER_PERSON_MONTH`.
- `FLAT_ROOM` — Khoán theo phòng/tháng → `PER_MONTH`.
- `INCLUDED` — Đã bao gồm → `INCLUDED`.

### 7.3. Phí quản lý

Chỉ hiển thị cho Căn hộ, Văn phòng và Mặt bằng:

- `MONTHLY` — Thu cố định theo tháng → `PER_MONTH`.
- `PER_M2` — Theo m²/tháng → `PER_M2_MONTH`.
- `INCLUDED` — Đã bao gồm → `INCLUDED`.
- `NONE` — Không có → `NOT_APPLICABLE`.

### 7.4. Internet/WiFi

- `INCLUDED` — Đã bao gồm → `INCLUDED`.
- `MONTHLY` — Thu theo phòng/tháng → `PER_MONTH`.
- `SELF_PAY` — Người thuê tự đăng ký → `NOT_APPLICABLE`.

### 7.5. Phí rác/vệ sinh

- `MONTHLY` — Thu định kỳ → `PER_MONTH`.
- `INCLUDED` — Đã bao gồm → `INCLUDED`.

### 7.6. Phí gửi xe máy và ô tô

- `INCLUDED` — Miễn phí/Đã bao gồm → `INCLUDED`.
- `PER_VEHICLE` — Theo xe/tháng → `PER_VEHICLE_MONTH`.
- `NONE` — Không có → `NOT_APPLICABLE`.

### 7.7. Điều hòa ngoài giờ

Chỉ hiển thị cho Văn phòng. Nếu có số tiền, frontend gửi:

- `chargeType = OVERTIME_AIR_CONDITIONING`.
- `billingMethod = PER_HOUR`.
- `includedInRent = false`.

### 7.8. Khoản phí tùy chỉnh

Người dùng nhập tên, số tiền và đơn vị. Payload hiện gửi `chargeType = OTHER`, `billingMethod = PER_MONTH`, `customName`, `amount`; trường đơn vị người dùng nhập hiện chưa được đưa vào payload.

Backend enum `ChargeType` đầy đủ:

`ELECTRICITY`, `WATER`, `MANAGEMENT`, `INTERNET`, `SERVICE_OR_GARBAGE`, `MOTORBIKE_PARKING`, `CAR_PARKING`, `OVERTIME_AIR_CONDITIONING`, `OTHER`.

Backend enum `BillingMethod` đầy đủ:

`PER_KWH`, `STATE_WATER_RATE`, `PER_M3`, `PER_PERSON_MONTH`, `PER_MONTH`, `PER_M2_MONTH`, `PER_VEHICLE_MONTH`, `PER_HOUR`, `FREE`, `INCLUDED`, `NOT_APPLICABLE`, `NEGOTIABLE`, `CUSTOM`.

## 8. Giá và điều kiện thuê

### 8.1. Đơn vị giá theo category

| Category | Option frontend | Enum backend |
|---|---|---|
| Căn hộ | VNĐ/tháng (`VND_MONTH`) | `MONTH` |
| Nhà nguyên căn | VNĐ/tháng (`VND_MONTH`) | `MONTH` |
| Văn phòng | VNĐ/tháng; VNĐ/m²/tháng; VNĐ/chỗ ngồi/tháng | `MONTH`, `M2_MONTH`, `SEAT_MONTH` |
| Mặt bằng | VNĐ/tháng; VNĐ/m²/tháng | `MONTH`, `M2_MONTH` |
| Phòng | VNĐ/phòng/tháng; VNĐ/người/tháng; VNĐ/tháng | `ROOM_MONTH`, `PERSON_MONTH`; `MONTH` hiện bị backend từ chối |

### 8.2. Tiền cọc

- `NONE` — Không đặt cọc → backend `NONE`; không gửi amount/months.
- `AMOUNT` — Cọc theo số tiền → `FIXED_AMOUNT`; bắt buộc `depositAmount`, không gửi `depositMonths`.
- `MONTHS` — Cọc theo số tháng → `MONTH_COUNT`; bắt buộc `depositMonths`, không gửi `depositAmount`.
- `NEGOTIATE` — Thỏa thuận → `NEGOTIABLE`; không gửi amount/months.

### 8.3. Chu kỳ thanh toán

- `MONTHLY` — Hằng tháng → backend `MONTHLY`.
- `TWO_MONTHS` — Mỗi 2 tháng → `EVERY_2_MONTHS`.
- `QUARTERLY` — Mỗi quý → `QUARTERLY`.
- `HALF_YEAR` — Mỗi 6 tháng → `EVERY_6_MONTHS`.
- `NEGOTIATE` — Thỏa thuận → `NEGOTIABLE`.

Field khác:

- Thời hạn thuê tối thiểu: backend tối thiểu 1 tháng.
- Giá có thương lượng: boolean.
- Giá đã gồm VAT: chỉ hiển thị với Văn phòng và Mặt bằng.
- `managementFeeIncluded` lấy từ dropdown Phí quản lý có chọn `INCLUDED` hay không.
- `priceAmount` là giá thuê cơ bản, không cộng tự động các khoản điện/nước/phí phát sinh.

## 9. Vị trí

Option nguồn địa chỉ:

- `SAVED` — Sử dụng địa chỉ đã lưu trong tài khoản.
- `NEW` — Nhập địa chỉ mới.

Dropdown Tỉnh/Thành và Phường/Xã là dữ liệu động, có tìm kiếm:

- Tỉnh/Thành: `GET {NEXT_PUBLIC_LOCATION_SERVICE_URL}/api/v1/provinces`.
- Phường/Xã: `GET {NEXT_PUBLIC_LOCATION_SERVICE_URL}/api/v1/provinces/{provinceCode}/wards`.

Danh sách option cụ thể phụ thuộc dữ liệu của `hs-location-service` tại thời điểm chạy nên không được đóng băng thành mảng hard-code trong baseline. Mỗi option dùng `code` và `name` do API trả về.

Backend yêu cầu:

- `SAVED`: có `savedAddressId`, không có object `address`; địa chỉ phải thuộc owner.
- `NEW`: có object `address`, không có `savedAddressId`.
- Địa chỉ mới bắt buộc `provinceCode`, `provinceName`, `wardCode`, `wardName`, `streetLine`; `fullAddress` tối đa 500 ký tự.
- Backend tạo/cập nhật bản ghi địa chỉ riêng của listing và đặt `listing_id`; không sửa địa chỉ hồ sơ người dùng.

## 10. Lịch xem nhà

Lịch xem nhà **đã được lưu ở backend**, không còn là feature chỉ có trên UI.

Ngày trong tuần:

- `MONDAY` — Thứ 2.
- `TUESDAY` — Thứ 3.
- `WEDNESDAY` — Thứ 4.
- `THURSDAY` — Thứ 5.
- `FRIDAY` — Thứ 6.
- `SATURDAY` — Thứ 7.
- `SUNDAY` — Chủ nhật.

Khung giờ:

- `MORNING` — Buổi sáng, 08:00–12:00.
- `AFTERNOON` — Buổi chiều, 13:00–17:00.
- `EVENING` — Buổi tối, 18:00–21:00.

Frontend bắt buộc chọn ít nhất một ngày và một khung giờ. DTO backend hiện cho phép list rỗng/null, nhưng dữ liệu được lưu vào:

- `listing_viewing_days`.
- `listing_viewing_slots`.

## 11. Mô tả chi tiết

- Frontend bắt buộc tối thiểu 30 ký tự.
- UI khuyến nghị 100–1000 từ.
- Backend bắt buộc `@NotBlank`, tối đa 5000 ký tự.
- Nút “Tạo mô tả AI” hiện chỉ validate các section phía trên rồi hiển thị “Tính năng đang phát triển”; chưa gọi AI API.

## 12. Trạng thái và workflow

### 12.1. Danh sách trạng thái đầy đủ

| Enum | Nhãn client | Ý nghĩa |
|---|---|---|
| `DRAFT` | Tin nháp | Đã lưu nhưng chưa gửi duyệt |
| `PENDING_REVIEW` | Chờ duyệt | Đang chờ admin duyệt |
| `PUBLISHED` | Đang hiển thị | Đã duyệt và đang public |
| `RENTED` | Đã cho thuê qua HomeSpace | Dành cho luồng hợp đồng trong hệ thống |
| `RENTED_EXTERNALLY` | Cho thuê ngoài hệ thống | Chủ tin tự tìm được khách bên ngoài |
| `EXPIRED` | Hết hạn | Hết thời hạn hiển thị |
| `REJECTED` | Bị từ chối | Admin từ chối duyệt |
| `HIDDEN` | Đã ẩn | Tin tạm ngừng public |
| `VIOLATION` | Vi phạm | Admin khóa do vi phạm |

### 12.2. Hành động đổi trạng thái của chủ tin

| Từ trạng thái | Hành động hiển thị | Trạng thái đích | Điều kiện |
|---|---|---|---|
| `PUBLISHED` | Ẩn tin | `HIDDEN` | Luôn cho phép |
| `PUBLISHED` | Đã cho thuê ngoài hệ thống | `RENTED_EXTERNALLY` | Luôn cho phép; ghi chú không bắt buộc |
| `HIDDEN` | Hiển thị lại tin | `PUBLISHED` | `expiresAt` vẫn còn hạn |
| `HIDDEN` | Đã cho thuê ngoài hệ thống | `RENTED_EXTERNALLY` | Cho phép |
| `HIDDEN` | Gửi duyệt lại | `PENDING_REVIEW` | Chỉ khi thời hạn cũ đã hết |
| `RENTED_EXTERNALLY` | Phòng trống, hiển thị lại | `PUBLISHED` | `expiresAt` vẫn còn hạn |
| `EXPIRED` | Gửi duyệt lại | `PENDING_REVIEW` | Cho phép |
| `REJECTED` | Gửi duyệt lại | `PENDING_REVIEW` | Cho phép |

Chủ tin không được tự chuyển sang `RENTED`. Tin `RENTED` có hợp đồng đang hoạt động bị chặn trong API đổi trạng thái. Tin `VIOLATION` bị khóa với owner.

### 12.3. Workflow admin

- `PENDING_REVIEW → PUBLISHED`.
- `PENDING_REVIEW → REJECTED`.
- `PUBLISHED` hoặc `RENTED_EXTERNALLY → RENTED`.
- `PUBLISHED` hoặc `RENTED → RENTED_EXTERNALLY`.
- `PUBLISHED → EXPIRED`.
- Bất kỳ trạng thái khác `VIOLATION → VIOLATION`.
- Bất kỳ trạng thái khác `HIDDEN → HIDDEN`.
- `DRAFT`, `REJECTED`, `EXPIRED`, `HIDDEN`, `RENTED`, `RENTED_EXTERNALLY`, `VIOLATION → PENDING_REVIEW`.
- `PENDING_REVIEW` hoặc `VIOLATION → PUBLISHED`.
- Admin không được chuyển sang `DRAFT`.

Admin bắt buộc nhập reason khi chuyển sang `REJECTED`, `HIDDEN` hoặc `VIOLATION`. Mọi lần đổi trạng thái được ghi vào `listing_status_history` với from/to status, reason, actor và actor type.

### 12.4. Hết hạn

- Cấu hình `LISTING_PUBLICATION_DURATION_DAYS`, example dev hiện là 30 ngày.
- Khi duyệt từ `PENDING_REVIEW` hoặc mở khóa từ `VIOLATION` sang `PUBLISHED`, backend tạo cửa sổ mới: `publishedAt = now`, `expiresAt = now + duration`.
- Hiển thị lại từ `HIDDEN` hoặc `RENTED_EXTERNALLY` dùng thời hạn còn lại, không tự cấp thêm ngày.
- Scheduler định kỳ chuyển `PUBLISHED` đã quá `expiresAt` sang `EXPIRED`.

## 13. API Listing hiện tại

Prefix qua gateway: `/api/v1`.

### Public

- `GET /api/v1/public/listing-catalog?category={APARTMENT|HOUSE|OFFICE|COMMERCIAL_SPACE|ROOM}` — catalog tiện ích/nội thất.

### Chủ tin

- `POST /api/v1/listings/upsert` — tạo mới hoặc cập nhật; nếu body có `id` thì update, không có `id` thì create.
- `POST /api/v1/listings` — alias cùng handler với upsert.
- `GET /api/v1/listings/me?page=1&status={status}&keyword={text}` — 10 bản ghi/trang, sắp xếp `updatedAt desc`, sau đó `createdAt desc`.
- `GET /api/v1/listings/{listingId}` — owner đọc mọi trạng thái của tin mình; người khác chỉ đọc được `PUBLISHED`.
- `PATCH /api/v1/listings/{listingId}/status` với body `{ "status": "...", "reason": "..." }` — đổi trạng thái theo transition owner.
- `PATCH /api/v1/listings/{listingId}/hide` — alias chỉ để chuyển sang `HIDDEN`.

### Admin

- `GET /api/v1/admin/listings` — filter `page`, `size`, `status`, `keyword`, `ownerId`, `category`, `fromDate`, `toDate`, `sort`.
- `GET /api/v1/admin/listings/{listingId}` — chi tiết đầy đủ kèm lịch sử trạng thái.
- `POST /api/v1/admin/listings` — admin tạo tin cho owner.
- `PUT /api/v1/admin/listings/{listingId}` — admin cập nhật nội dung, giữ nguyên trạng thái hiện tại.
- `PATCH /api/v1/admin/listings/{listingId}/status` — admin đổi trạng thái.

Frontend service còn có hàm gọi `GET /api/v1/listings` để lấy public listing, nhưng backend hiện không có handler `GET /listings` tương ứng. Đây là sai lệch cần xử lý trước khi dùng hàm này.

## 14. Persistence/CSDL hiện tại

- `listings`: dữ liệu chung, pricing, status và publication window.
- `listing_apartment_details`: detail Căn hộ.
- `listing_house_details`: detail Nhà nguyên căn.
- `listing_office_details`: detail Văn phòng.
- `listing_office_operating_hours`: lịch hoạt động của văn phòng.
- `listing_commercial_details`: detail Mặt bằng.
- `listing_room_details`: detail Phòng.
- `addresses`: địa chỉ listing liên kết bằng `listing_id`.
- `listing_media`: media, liên kết `storage_object_id`.
- `amenities`: catalog tiện ích.
- `amenity_categories`: category áp dụng cho tiện ích.
- `listing_amenities`: quan hệ listing–amenity.
- `listing_custom_amenities`: tiện ích tùy chỉnh của từng listing.
- `furnishing_items`: catalog nội thất/trang thiết bị.
- `listing_furnishings`: quan hệ listing–furnishing.
- `listing_charges`: các khoản chi phí.
- `listing_viewing_days`: ngày xem nhà.
- `listing_viewing_slots`: buổi xem nhà.
- `listing_status_history`: lịch sử trạng thái.

Mỗi listing chỉ có đúng một detail one-to-one khớp category. Khi upsert, backend xóa dữ liệu con hiện tại rồi gắn lại detail, media, charge, amenity, furnishing và lịch xem từ payload mới trong cùng transaction.

## 15. Sai lệch frontend/backend đã xác nhận

Các mục dưới đây là lỗi/gap hiện có, không phải contract mong muốn:

1. `apartment_normal` không có trong `resolveSubtype`; hiện rơi vào fallback `APARTMENT_STANDARD`. Khi edit, `APARTMENT_STANDARD` lại được map thành `standard`, không tồn tại trong dropdown (`apartment_normal`).
2. `level4_house` không có trong `resolveSubtype` (code đang chờ `grade4`), nên rơi thành `APARTMENT_STANDARD` và backend từ chối vì subtype không thuộc `HOUSE`.
3. `shared_office` không có trong `resolveSubtype` (code đang chờ `shared_space`), nên rơi thành `APARTMENT_STANDARD` và backend từ chối. Luồng edit cũng trả `shared_space`, không có trong dropdown.
4. Các hình thức thuê riêng của Căn hộ, Văn phòng, Mặt bằng và Phòng đều bị payload quy về `WHOLE_UNIT`; chỉ `HOUSE + PARTIAL` được giữ đúng. Backend hiện chưa có enum đủ chi tiết để lưu toàn bộ option frontend.
5. Dropdown nội thất có `RAW`, `BASIC`, `FULL`, `LUXURY`, nhưng resolver chỉ xử lý `FULL`, `BASIC`, `EMPTY`. `LUXURY` và `RAW` hiện cùng rơi vào `UNFURNISHED`; backend `PARTIALLY_FURNISHED` không có option. Luồng edit đưa enum backend thẳng vào state frontend nên có thể không khớp option dropdown.
6. Mặt bằng chọn `MALL_SPACE`, nhưng payload kiểm tra `MALL`; kết quả hiện lưu `GROUND_FLOOR` thay vì `SHOPPING_MALL`. Luồng edit trả `MALL`, cũng không có trong dropdown.
7. Chỗ để xe Mặt bằng: UI dùng `MOTORBIKE`, `CAR`, `BOTH`, nhưng payload chỉ nhận `MOTORBIKE_ONLY` và `NONE`; `MOTORBIKE` và `CAR` hiện có thể bị lưu thành `MOTORBIKE_AND_CAR`. Luồng edit trả `MOTORBIKE_ONLY`, không có trong dropdown.
8. Ban công Phòng lưu boolean nên mất phân biệt riêng/chung. Luồng edit khi `false` dùng `NO`, trong khi dropdown dùng `NONE`.
9. Giá Phòng có option `VND_MONTH`, nhưng backend chỉ cho `ROOM_MONTH` hoặc `PERSON_MONTH`; chọn `VND_MONTH` sẽ bị `INVALID_FOR_CATEGORY`.
10. Lối đi bổ sung `sharedEntrance` khi thuê một phần Nhà không được đưa vào payload. Payload vẫn lấy `privateEntrance` chung.
11. Tầng thuê một phần Nhà là input text nhưng payload ép `Number`; nội dung dạng “Tầng 1” trở thành `NaN/null`.
12. Giờ hoạt động tùy chỉnh Văn phòng không được payload giữ nguyên giờ người dùng chọn; ngoài chế độ 24/7, payload đang cố định `07:30–18:30` và chỉ suy ra ngày từ chuỗi.
13. Chỗ đỗ ô tô/xe máy Văn phòng là input text nhưng backend cần Integer capacity; text mô tả như “Có hầm xe” bị ép thành 0.
14. Trường `unit` của khoản phí tùy chỉnh được nhập trên frontend nhưng hiện không gửi vào payload.
15. Water charge dạng `FLAT_ROOM` hiện gửi `unit = người` thay vì đơn vị phòng.
16. Phí quản lý bị ẩn ở Nhà và Phòng, nhưng payload vẫn luôn tạo charge `MANAGEMENT` từ state mặc định.
17. Frontend cho sửa mọi trạng thái trừ `VIOLATION`. Upsert backend chỉ chặn `VIOLATION`, nên tin `RENTED` có hợp đồng có thể bị upsert sang `DRAFT/PENDING_REVIEW`, trong khi API đổi trạng thái lại chặn `RENTED` bằng `LISTING_HAS_ACTIVE_CONTRACT`.
18. Khi upload media lỗi, frontend tạo UUID ngẫu nhiên làm fallback rồi vẫn gọi upsert; backend sẽ trả `STORAGE_OBJECT_NOT_FOUND`. Không nên coi đây là fallback thành công.
19. `GET /api/v1/listings` được frontend gọi trong `getPublished()` nhưng backend chưa có endpoint tương ứng.

Khi xử lý các sai lệch này phải sửa đồng bộ option value, mapping tạo payload, mapping load edit, type frontend, enum/validation backend và test; không chỉ đổi nhãn.

---

# Prompt bảo trì baseline

Bạn là AI Agent phụ trách bảo trì Listing của HomeSpace. Hãy đọc `HomeSpace_listing_baseline_v1.md`, sau đó đọc code thực tế trong `hs-web-app` và `hs-core-api` trước khi sửa.

Quy tắc:

- Chỉ sửa đúng phạm vi được yêu cầu.
- Không tự thiết kế lại toàn bộ form, aggregate, API hoặc CSDL.
- Mọi dropdown mới hoặc thay đổi dropdown phải ghi rõ nhãn frontend, value frontend, enum/value API và mapping khi load edit.
- Danh mục tiện ích, nội thất, tỉnh/thành và phường/xã phải lấy từ backend/API, không hard-code lại.
- Bảo toàn đúng một detail tương ứng với category.
- Nếu thay đổi subtype, rental mode, furnishing, pricing, charge, status hoặc lịch xem, phải kiểm tra cả frontend payload và backend validation.
- Không coi danh sách “Sai lệch frontend/backend đã xác nhận” là hành vi đúng; nếu yêu cầu chạm vào mục đó phải sửa tận gốc hoặc báo rõ chưa xử lý.
- Giá chính luôn là giá thuê cơ bản; chi phí phát sinh lưu riêng.
- `RENTED` dành cho hợp đồng HomeSpace; `RENTED_EXTERNALLY` dành cho chủ tin tự tìm khách bên ngoài.
- Sau khi sửa phải cập nhật lại tài liệu này nếu contract hoặc option thay đổi.

Yêu cầu thay đổi mới:

`[DÁN YÊU CẦU CẦN SỬA TẠI ĐÂY]`
