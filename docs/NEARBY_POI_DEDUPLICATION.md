# Lọc địa điểm TomTom trùng trước khi lưu

## Luồng xử lý

1. TomTom trả về tối đa 100 POI từ một truy vấn gộp categorySet, bán kính 2 km.
2. Backend kiểm tra tọa độ, phạm vi và phân loại như trước.
3. `NearbyPoiDeduplicator` sắp xếp POI theo khoảng cách từ tọa độ tin đăng, rồi lọc các điểm có khả năng là cùng địa danh trước khi ghi vào CSDL.
4. Giữ nguyên tên, địa chỉ, tọa độ, ID và phân loại của điểm gần tin đăng nhất. Không tạo tọa độ hay ghép dữ liệu từ điểm bị loại.
5. `NearbyRepository` lưu các điểm được chọn vào kho dùng chung và liên kết với tin đăng. Chống trùng chính xác theo TomTom ID vẫn được giữ.

Không gọi thêm request để bù số POI đã lọc. Số địa điểm được lưu có thể thấp hơn 100.

## Điều kiện nhận diện

- Chuẩn hóa dấu tiếng Việt, viết hoa/thường, dấu câu và một số cách viết tắt như ĐH, TP.HCM; bỏ tiền tố “Trường” trước tên đại học/cao đẳng/trường phổ thông.
- Tên phải trùng hoặc chỉ có sai khác chính tả rất nhỏ; phải cùng loại địa điểm đã xác minh qua danh mục TomTom.
- Giữ riêng tên có số cơ sở hoặc tên chi nhánh khác nhau. Không gộp chỉ vì một tên chứa tên kia, ví dụ một khoa và cả trường.
- Với địa điểm thông thường, hai điểm phải trong 30 m. Tên ngắn như một thương hiệu còn cần trùng địa chỉ để tránh gộp hai cửa hàng thật.
- Với tên trường/bệnh viện cụ thể, dài và cùng loại giáo dục/y tế, cho phép trong 300 m để nhận diện các mặt tiền/cổng khác nhau.
- Mỗi điểm bị loại phải khớp trực tiếp với điểm giữ lại; không gộp dây chuyền khiến các điểm xa nhau thành một địa danh.

Ví dụ hai tên “Trường Đại Học Công Nghiệp Thành Phố Hồ Chí Minh” và “Đại Học Công Nghiệp Thành Phố Hồ Chí Minh” tại Nguyễn Văn Bảo / Nguyễn Văn Nghi được đối chiếu bằng tên, loại và tọa độ, dù địa chỉ khác nhau.

Các ngưỡng nằm trong `hs-api-service/src/main/resources/application.properties`:

```properties
nearby.dedup.same-place-radius-m=30
nearby.dedup.institution-radius-m=300
nearby.dedup.minimum-name-similarity=0.96
```

Đây là nhận diện có điều kiện từ dữ liệu nhà cung cấp; trường hợp chưa đủ bằng chứng được giữ riêng để tránh làm mất địa điểm thật.

## Kho dùng chung và dữ liệu cũ

- Lọc trên kết quả fetch của từng tin đăng. Hai tin ở hai phía có thể giữ hai cổng khác nhau vì đó là cổng gần từng tin hơn.
- Không xóa/gộp toàn cục các bản ghi `nearby_places` / `nearby_place_sources` đang được tin khác sử dụng.
- Khi đổi vị trí, cơ chế thay liên kết của riêng tin đăng vẫn giữ nguyên.
- `returned_count` giữ số kết quả gốc của TomTom; `unique_count` phản ánh số điểm thực sự được liên kết sau lọc.
- Áp dụng cho các lần fetch mới sau khi khởi động lại Core API. Không tự quét lại snapshot cũ, không thêm lịch quét sau 24 giờ, không tạo migration SQL.

## Kiểm thử

`NearbyPoiDeduplicatorTest` kiểm tra cặp địa danh trong báo lỗi, chọn cổng gần nhất, tên viết tắt, sai chính tả nhẹ, cơ sở/chi nhánh khác nhau, thương hiệu trùng và trường hợp gộp dây chuyền.

`NearbyPostgresTest` dùng provider giả và PostgreSQL kiểm tra lọc trước khi insert, thống kê response, một request cho mỗi tin và giữ an toàn kho địa điểm dùng chung. Không cần gọi TomTom thật.
