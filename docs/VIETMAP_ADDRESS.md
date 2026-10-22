# Vietmap v4 — địa chỉ theo schema mới

Thay thế cấu trúc địa chỉ cũ; không backfill, không ánh xạ mã cũ, không sửa/chạy listing seed trong đợt này.
Nguồn hợp đồng API: 4 phần đầu của `hs-infrastructure/postman/vietmap/response.txt` và mẫu gọi thật tại `postman/vietmap/verified-v4-sample.json`.

## Lưu trữ

Ba bảng độc lập `user_addresses`, `listing_addresses`, `branch_addresses` dùng chung các field giá trị trong `AddressFields`, không dùng chung hàng hay bảng đa chủ thể.

| Field trong CSDL | Nguồn Vietmap |
| --- | --- |
| `ref_id` TEXT | ref_id được chọn từ Autocomplete/Geocode/Reverse và chuyển nguyên vẹn vào Place.refid |
| `display` | Place.display, chuỗi địa chỉ hiển thị đầy đủ |
| `name` | Place.name, tên điểm/địa chỉ nếu có |
| `house_number` | Place.hs_num |
| `street` | Place.street; giữ chuỗi rỗng nếu provider chưa tách tên đường cho một địa điểm cụ thể, không tự đoán từ name |
| `address` | Place.address; trong response thực tế là phần địa chỉ hành chính, KHÔNG phải street |
| `city_id`, `city` | Place.city_id/city; bao gồm tỉnh hoặc thành phố theo tên provider |
| `ward_id`, `ward` | Place.ward_id/ward |
| `district_id`, `district` | Luôn null; chỉ chấp nhận Place có district_id=0 và district rỗng (địa chỉ mới hai cấp) |
| `latitude`, `longitude` | Place.lat/lng; NUMERIC(10,7), NOT NULL, có CHECK giới hạn tọa độ |
| `address_note` | Ghi chú bổ sung của người dùng, tối đa 255 ký tự |
| `resolved_at`, `version`, audit | Thời điểm resolve và kiểm soát cập nhật |

Không còn province_code/province_name/ward_code/ward_name/street_line/full_address trong ba bảng này.
Không tạo hay nhập danh mục tỉnh/phường từ service cũ. Không lưu toàn bộ gợi ý hoặc cây data_old/data_new cho mỗi địa chỉ.
Không tạo bảng geocoding cache chứa chung địa chỉ riêng tư.

- User: `user_id` NOT NULL + UNIQUE + FK users.
- Listing: `listing_id` NOT NULL + UNIQUE + FK listings; index `(city_id,ward_id,active)`.
- Branch: PropertyBranch.address_id UNIQUE + FK branch_addresses, cascade và orphan removal.
- `ref_id` không UNIQUE toàn bảng: nhiều người/tin có thể chọn cùng một địa chỉ.
- SAVED sao chép toàn bộ giá trị địa chỉ hồ sơ sang Listing sau khi kiểm tra chủ sở hữu/active; không chia sẻ mutable row.
- Tin có Branch dùng bản sao địa chỉ Branch thuộc đúng chủ tin, không lấy địa chỉ tùy ý từ request.

## Luồng

Web/Admin → Gateway → Core → Vietmap; key chỉ ở Core.
Người dùng gõ → Autocomplete hoặc Geocode → chọn kết quả → Place → xem địa chỉ/tọa độ.
Giao diện khách hàng chỉ gồm ô nhập tên đường/số nhà và danh sách gợi ý; chọn gợi ý sẽ tự gọi Place để lấy tọa độ.
Geocode và Reverse vẫn giữ ở API backend để phục vụ các luồng khác, không đưa nút tìm nâng cao hay nhập tọa độ lên form địa chỉ.
Khi sửa chuỗi tìm kiếm, lựa chọn cũ và tọa độ cũ bị vô hiệu hóa.

Profile Web/Admin có bản đồ preview dùng trực tiếp latitude/longitude của địa chỉ tải từ CSDL.
Khi chọn địa chỉ mới, preview đổi theo kết quả Place; sau khi lưu dùng tọa độ chuẩn hóa trong response backend.
Iframe và link Google Maps cùng dùng cặp tọa độ, không geocode lại tên đường, không gọi thêm 4 API Vietmap để hiển thị.
Thiếu/sai tọa độ thì không dựng bản đồ mặc định; bản đồ chỉ xem, không ghi hay sửa CSDL.

Khi Lưu, client chỉ gửi refId/ghi chú. Core kiểm tra quyền và gọi lại Place để lấy dữ liệu tin cậy.
Không nhận tọa độ/tỉnh/phường do client tự gán; không tự chọn dòng gợi ý đầu tiên; không fallback thành địa chỉ giả khi provider lỗi.
Phường/tỉnh và tọa độ phải tồn tại trước khi lưu. Phải có đường hoặc tên địa điểm cụ thể; không chấp nhận kết quả chỉ cấp tỉnh/phường.
Vietmap có thể trả `name="194 Đình Phong Phú"`, `street=""`, `hs_num=""` nhưng vẫn đủ địa chỉ mới và tọa độ:
kết quả này hợp lệ, được lưu nguyên dữ liệu provider và tính hoàn thành bước địa chỉ trong onboarding.
Không yêu cầu khách tự nhập/hiểu tọa độ hay bổ sung field kỹ thuật mà provider chưa tách.
Lỗi provider làm rollback thao tác lưu, không xóa địa chỉ đã lưu.

Đọc tin, hồ sơ, lịch xem, yêu cầu thuê và hợp đồng từ CSDL, không gọi Vietmap mỗi lần xem.
Danh sách tin cá nhân/công khai đọc địa chỉ theo batch thay vì một query Address cho từng tin.
Văn bản hợp đồng vẫn dùng field template `property.fullAddress`/`permanentAddress` ở lớp snapshot/hiển thị,
nhưng lấy giá trị từ schema Vietmap mới; đây không phải cột địa chỉ cũ. Không thay đổi snapshot/văn bản đã ký.

## API Core (qua Gateway thêm `/api/v1`)

Tài liệu request/response, ví dụ gọi và Postman cho cả bốn endpoint: [VIETMAP_API.md](VIETMAP_API.md).

Các endpoint provider yêu cầu đăng nhập và User active:

- GET `/locations/autocomplete?text=...`
- GET `/locations/geocode?text=...`
- GET `/locations/place?refId=...`
- GET `/locations/reverse?latitude=...&longitude=...`

Response dùng envelope `ApiResponse.result`. Suggestions: `refId,display,name,address`.
Place: các field camelCase tương ứng bảng trên (không bao gồm ID bản ghi CSDL).
Upstream luôn host cố định `https://maps.vietmap.vn`; autocomplete/geocode/reverse dùng `display_type=1` (chỉ địa chỉ mới sau sáp nhập).
Nếu provider trả payload hai định dạng, lấy `data_new` khi có, bao gồm đúng `ref_id` của nhánh mới;
nếu `data_new=null` thì dùng object chính mới, tuyệt đối không fallback sang `data_old`.
Loại bỏ gợi ý còn boundary type=1 (quận/huyện). Place kế thừa định dạng của ref_id:
Core từ chối ref cũ còn district_id khác 0 hoặc district không rỗng trước khi ghi CSDL.
Không xóa tên quận/huyện nằm trong tên riêng POI; kiểm tra cấu trúc hành chính thay vì sửa chuỗi tên địa điểm.
Ví dụ response người dùng cung cấp: object chính là `Phường An Nhơn, Thành phố Hồ Chí Minh`,
còn `data_old` là `Phường 5, Quận Gò Vấp, Thành phố Hồ Chí Minh`. `data_new=null` trong trường hợp này là bình thường.
Tham chiếu: https://maps.vietmap.vn/docs/map-api/autocomplete-version/autocomplete-v4/ và https://maps.vietmap.vn/docs/map-api/place-v4/.
Timeout 4 giây kết nối, 8 giây request; không follow redirect; tối đa 8 request đồng thời và 300 request/phút trên mỗi instance Core.
Không log URI upstream/key; không retry vô hạn; response giới hạn 2 MB.

Lưu địa chỉ User: PUT `/users/me/address`

```json
{ "refId": "geocode:opaque-reference-from-vietmap", "addressNote": "Căn 401, tầng 4" }
```

Listing.addressSource NEW:

```json
{ "type": "NEW", "address": { "refId": "geocode:opaque-reference-from-vietmap" } }
```

Listing.addressSource SAVED vẫn dùng savedAddressId của User hiện tại và chỉ sao chép bản ghi active.
Create/Update Branch: thay toàn bộ field địa chỉ cũ bằng `refId` và `addressNote` bên cạnh các field nghiệp vụ Branch.
Response địa chỉ User/Listing/Branch cung cấp các thành phần mới và tọa độ.

Bộ lọc tỉnh/thành và phường dùng `cityId`, `wardId`. Dropdown khu vực tìm thuê lấy từ địa chỉ các tin PUBLISHED:

- GET `/public/locations/cities`
- GET `/public/locations/cities/{cityId}/wards`

Đây là facets của tin đăng thật, không phải danh mục đầy đủ hành chính. DB chưa có tin thì chọn Toàn quốc.
Nhập địa chỉ hồ sơ/tin/Branch không phụ thuộc facets này: luôn tìm trực tiếp bằng 4 API Vietmap.
Tìm kiếm AI/native SQL và JPQL lịch xem đã chuyển sang listing_addresses cùng field city_id/city/ward_id/ward/street/display.

## Chạy với CSDL mới

1. Cấu hình `VIETMAP_API_KEY` ở môi trường chạy Core; `.env.example` chỉ chứa placeholder, không chứa key thật.
2. Trỏ Core vào CSDL mới và khởi động với cấu hình JPA tạo schema như môi trường dev hiện tại.
3. Khởi động Web/Admin/Gateway cùng phiên bản code mới. Không cần khởi động hs-location-service.
4. Chọn địa chỉ qua Vietmap trước khi lưu hồ sơ, Branch hoặc tin mới.
5. KHÔNG chạy listing seed cũ trong đợt này; seed sẽ được làm lại riêng.

Không có lệnh tự drop/rename CSDL dev trong thay đổi này. ddl-auto=update không tự xóa bảng addresses cũ nếu chạy trên DB cũ.
Thư mục repo hs-location-service chưa bị xóa vật lý; các consumer/runtime URL cũ trên Web/Admin đã được bỏ.
4 API tìm địa chỉ không bao gồm map tiles/SDK: thay toàn bộ bản đồ nền là phạm vi riêng, không phát sinh ở đợt này.
