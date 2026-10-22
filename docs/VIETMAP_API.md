# API địa chỉ HomeSpace — Vietmap v4

Bốn API cơ bản đã được triển khai tại `VietmapController` → `VietmapService` và dùng lại độc lập với form Profile.
Không tạo service location riêng. Không thay đổi giao diện đơn giản hiện tại.

## Cấu hình và xác thực

- Gateway local: `http://localhost:8080/api/v1` (đổi host/port theo môi trường).
- Header: `Authorization: Bearer <access_token>` của người dùng đã đăng nhập, tài khoản active.
- Core đọc `VIETMAP_API_KEY` từ môi trường. Không truyền API key Vietmap trên client hay trong collection Gateway.
- Tất cả endpoint bên dưới là GET chỉ tra cứu; không tự lưu User/Listing/Branch hoặc tự chọn kết quả đầu tiên.
- Chỉ địa chỉ mới sau sáp nhập: backend cố định `display_type=1`, không dùng `data_old`.

## Bốn endpoint

| Chức năng | URL qua Gateway | Tham số bắt buộc | Kết quả `result` |
| --- | --- | --- | --- |
| Autocomplete v4 | `/api/v1/locations/autocomplete` | `text`: 2–255 ký tự | Mảng gợi ý |
| Geocode v4 | `/api/v1/locations/geocode` | `text`: 2–255 ký tự | Mảng ứng viên địa chỉ |
| Place v4 | `/api/v1/locations/place` | `refId`: ref nguyên vẹn, 1–4096 ký tự | Một địa chỉ có tọa độ |
| Reverse v4 | `/api/v1/locations/reverse` | `latitude`: [-90,90], `longitude`: [-180,180] | Mảng địa chỉ gần tọa độ |

Core nhận các path `/locations/...` sau khi Gateway bỏ tiền tố `/api/v1`.
Tên tham số của HomeSpace khác provider: `refId` → `refid`, `latitude` → `lat`, `longitude` → `lng`.
Giữ nguyên toàn bộ giá trị opaque `refId`, kể cả prefix và ký tự đặc biệt; dùng query encoder của HTTP client.
Geocode gọi Vietmap `/api/search/v4` (không phải `/api/geocode/v4`).

Autocomplete, Geocode và Reverse trả tối đa 10 dòng; không khớp thì `result: []`, không phải lỗi.
Geocode/Reverse chưa trả tọa độ đầy đủ trong DTO gợi ý: chọn một kết quả rồi gọi Place nếu cần.

## Ví dụ gọi

Các ví dụ dùng biến môi trường `HS_ACCESS_TOKEN` và `HS_REF_ID`; không hardcode token/key vào mã nguồn.
Trên Windows PowerShell dùng `curl.exe` và `$env:HS_ACCESS_TOKEN`, `$env:HS_REF_ID` thay cho cú pháp shell bên dưới.

```bash
curl --get 'http://localhost:8080/api/v1/locations/autocomplete' \
  -H "Authorization: Bearer $HS_ACCESS_TOKEN" \
  --data-urlencode 'text=Đình Phong Phú'

curl --get 'http://localhost:8080/api/v1/locations/geocode' \
  -H "Authorization: Bearer $HS_ACCESS_TOKEN" \
  --data-urlencode 'text=194 Đình Phong Phú, Thành phố Hồ Chí Minh'

curl --get 'http://localhost:8080/api/v1/locations/place' \
  -H "Authorization: Bearer $HS_ACCESS_TOKEN" \
  --data-urlencode "refId=$HS_REF_ID"

curl --get 'http://localhost:8080/api/v1/locations/reverse' \
  -H "Authorization: Bearer $HS_ACCESS_TOKEN" \
  --data-urlencode 'latitude=10.8344664' \
  --data-urlencode 'longitude=106.7825491'
```

## Response

Envelope thành công: `code=1000`, dữ liệu nằm trong `result`. Ví dụ minh họa — ref phải lấy từ lần tìm thực tế:

```json
{
  "code": 1000,
  "result": [
    {
      "refId": "<opaque-reference-selected-from-vietmap>",
      "display": "194 Đình Phong Phú, Phường Tăng Nhơn Phú, Thành phố Hồ Chí Minh",
      "name": "194 Đình Phong Phú",
      "address": "Phường Tăng Nhơn Phú, Thành phố Hồ Chí Minh"
    }
  ]
}
```

Place trả object thay vì mảng:

```json
{
  "code": 1000,
  "result": {
    "refId": "<opaque-reference-selected-from-vietmap>",
    "display": "194 Đình Phong Phú, Phường Tăng Nhơn Phú, Thành phố Hồ Chí Minh",
    "name": "194 Đình Phong Phú",
    "houseNumber": null,
    "street": "",
    "address": "Phường Tăng Nhơn Phú, Thành phố Hồ Chí Minh",
    "cityId": "12",
    "city": "Thành phố Hồ Chí Minh",
    "districtId": null,
    "district": null,
    "wardId": "4620",
    "ward": "Phường Tăng Nhơn Phú",
    "latitude": 10.8344664,
    "longitude": 106.7825491
  }
}
```

ID thành phần địa chỉ là chuỗi. `street` có thể rỗng khi provider chưa tách đường nhưng có tên địa điểm cụ thể;
`houseNumber`/`name` có thể null. Client không tự đoán tên đường/số nhà từ `display`.
Place hiện yêu cầu kết quả cụ thể có tỉnh/thành, phường/xã và tọa độ; không nhận ref hành chính cũ có quận/huyện.

## Luồng tái sử dụng

- Nhập tên đường → Autocomplete → người dùng chọn `refId` → Place.
- Tìm chuỗi địa chỉ đầy đủ → Geocode → chọn `refId` → Place.
- Chọn điểm trên bản đồ/GPS → Reverse → chọn `refId` → Place nếu cần đầy đủ thành phần.
- Lưu hồ sơ/tin/chi nhánh là request riêng, chỉ gửi `refId` và ghi chú; backend kiểm tra quyền và resolve lại.

Web/Admin đã có client tương ứng tại `services/vietmap.service.ts` và `src/services/vietmap.service.ts`.
Chỉ gọi endpoint cần dùng, debounce khi nhập; không gọi đủ bốn API cho mỗi lần gõ và không thêm nút kỹ thuật vào Profile.

## Lỗi và giới hạn

- Thiếu/sai kiểu query: HTTP 400/code 1001; ngoài miền tọa độ: 400/code 2454; dữ liệu chọn không hợp lệ: 400/code 2453.
- Chưa đăng nhập: HTTP 401; Core dùng code 1002. Tài khoản thiếu/bị khóa dùng lỗi tài khoản hiện có.
- Ref địa chỉ cũ: HTTP 400/code 2456, yêu cầu chọn lại địa chỉ mới.
- Thiếu cấu hình key: HTTP 503/code 2451; provider lỗi/response hỏng: HTTP 502/code 2455.
- Hết quota nội bộ: HTTP 429/code 2452. Gateway cũng có thể trả lỗi xác thực/rate-limit/timeout riêng.
- Core giới hạn 300 lần gọi/phút/instance, tối đa 8 đồng thời, timeout kết nối 4 giây/request 8 giây.
- Không retry vô hạn, không trả dữ liệu giả khi lỗi, không log key hoặc URI chứa key.

## Postman

Import `hs-infrastructure/postman/vietmap/HomeSpace_Address_API_Gateway.postman_collection.json`.
Điền `access_token`, kiểm tra `base_url`, chạy từng request thủ công.
Chọn một dòng từ Autocomplete/Geocode/Reverse rồi dán `refId` vào biến `ref_id` để chạy Place.
Các request có kiểm tra HTTP/envelope/shape; không tự gửi request nối tiếp hoặc tự ghi CSDL.

Collection `HomeSpace_VIETMAP_Postman_Collection.json` bên cạnh dùng gọi trực tiếp provider để khảo sát;
collection Gateway mới dùng API HomeSpace, không cần key Vietmap trên Postman.

Chi tiết schema và luồng lưu: [VIETMAP_ADDRESS.md](VIETMAP_ADDRESS.md).
