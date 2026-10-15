# Rà soát nguồn dữ liệu hợp đồng và phụ lục — 14/10/2026

## Nguồn có thẩm quyền

| Nhóm | Nguồn | Quy tắc |
| --- | --- | --- |
| Tài sản, địa chỉ, diện tích | ListingSnapshotDto của yêu cầu thuê | Giữ bản chụp khi thỏa thuận thuê. Không lấy dữ liệu Listing mới để lấp chỗ trống trong bản chụp cũ. |
| HOUSE | houseDetail | Số tầng, phòng, phạm vi/tầng thuê, giới hạn người/xe từ đúng chi tiết nhà. |
| APARTMENT | apartmentDetail | Dự án, block, mã căn, tầng, phòng, hướng/view từ đúng chi tiết căn hộ. |
| ROOM | roomDetail | Phòng/tầng, vệ sinh/bếp/ban công/lối đi riêng-chung, loại công tơ và giới hạn cư trú từ chi tiết phòng. |
| Người ở, phương tiện | RentalRequest, sau đó phụ lục đã ký có hiệu lực | Không lấy sức chứa tối đa của Listing làm số người/xe thực tế. |
| Giá thuê, tiền cọc | Giá đã chốt trong yêu cầu thuê/thanh toán | Giá thương lượng có quyền ưu tiên hơn giá quảng cáo; cọc NONE là 0, MONTH_COUNT theo số tháng. |
| Phí | Cost breakdown đã chốt hoặc charges của bản chụp | Giữ chargeType, billingMethod, unitAmount và số lượng tương ứng. Phụ lục tính lại phí; không đổi danh tính khoản WiFi thành phí gửi xe. |
| Tiện ích | ListingAmenitySnapshots | Nhánh tài sản dùng chung ưu tiên khi trùng mã; không đưa tên/mã chi nhánh vào hợp đồng. |
| Nội thất | furnishings | Đúng assetName, quantity, handoverCondition, conditionNote. Checkbox máy lạnh/máy giặt không đủ chứng cứ tạo một thiết bị số lượng 1. Thiết bị dùng chung không phải tài sản riêng bàn giao. |
| Danh tính, tài khoản ngân hàng | Hồ sơ và snapshot thanh toán của hai bên | Không thay bằng field của Listing, không sửa qua phụ lục này. |
| Thời hạn thuê | RentalRequest + văn bản gia hạn đã ký | Ngày ký khác ngày bắt đầu thuê nếu nguồn nhập khác; phụ lục không kéo dài thời hạn. |
| Nội quy, phạt, cọc, lịch sử | Điều khoản đã chốt của hợp đồng | Không tự suy đoán từ tiện ích hay sửa hóa đơn đã phát hành. |

## Sửa trong đợt này

- Dropdown cách tính lấy danh sách backend theo loại phí: WiFi không có theo xe/người; phí rác có theo người; quản lý có theo m²; xe máy/ô tô giữ đúng loại xe. Backend chặn payload giả mạo, kể cả gọi API trực tiếp.
- Điện/nước giữ phương pháp tính đã ký; chỉ điều chỉnh đơn giá. Nước theo người vẫn tính theo số người, không đổi thành công tơ.
- Tính phí quản lý theo diện tích hợp đồng, cập nhật nhãn đơn vị từ phương pháp tính, đồng bộ số tiền/bảo gồm/miễn phí. Giới hạn xe kiểm tra cả tổng và từng loại khi đổi số lượng.
- Tiện ích chỉ sao chép đúng tên/mã đã chọn trên Listing và tên tiện ích nhập thêm; bảng UI/Word có STT và tên. Không suy phạm vi từ tên, không tự thêm phí/điều kiện hoặc chỗ để xe, không đưa tiện ích chỉ thuộc chi nhánh vào hợp đồng. Thiết bị đã chọn làm tiện ích vẫn được giữ dù cũng xuất hiện trong bảng nội thất. Chính sách và phí lấy từ các bảng nghiệp vụ riêng.
- Với snapshot đã ký từng lưu sai phạm vi: giữ nguyên dữ liệu gốc, hiển thị cảnh báo và nút đề nghị hiệu chỉnh; chỉ áp dụng sau luồng duyệt/ký. Các dòng lịch sử không chỉnh được phép giữ nguyên khi sửa nhóm khác.
- WiFi trong bảng tiện ích dùng mức phí canonical, không tuyên bố miễn phí/bao gồm khi có phí riêng. Bảng phí rỗng không tự khẳng định mọi dịch vụ miễn phí.
- Nội thất không còn mất ghi chú vào chuỗi hiện trạng; số lượng và ghi chú lấy từ từng dòng furnishings. Không tự thay thiết bị chỉ vì Listing mới có thêm nội thất.
- Cách tính tiền nước theo phòng không còn ghi đơn vị theo người trong payload đăng tin.

## Phạm vi kiểm chứng

Kiểm thử Java bao gồm snapshot HOUSE/APARTMENT/ROOM, dữ liệu Listing đổi sau khi thỏa thuận, tiện ích dùng chung/custom, tài sản bàn giao, đơn vị phí, số người/xe/m², cùng các regression hợp đồng/hóa đơn/SmartCA. Kiểm thử trình duyệt dùng component thật với API giả lập, bao gồm dropdown đúng loại phí và đề xuất sửa phạm vi; không thao tác chuyển tiền hay ký SmartCA thật.

Không sửa hàng loạt DB, không thay nội dung/PDF đã ký, không thay `.env` hoặc đồng hồ giả lập. Không tuyên bố đã kiểm tra dữ liệu DB của từng hợp đồng đang chạy, hay đã render lại cả ba file Word/PDF thực tế trong đợt rà soát code này.
