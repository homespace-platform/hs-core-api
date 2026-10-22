# Thời hạn thuê trong file kết xuất

Mẫu có thể dùng `{{lease.durationText}} ({{lease.durationMonths}} tháng)` trong khi snapshot đã có `1 năm (12 tháng)`. Renderer nhận diện đúng cặp mã này và chuẩn hóa phần số tháng dư trong bản sao làm việc trước khi chèn dữ liệu. Không tìm/thay thế văn bản sau render, nên câu nhập tay trong cùng đoạn văn cũng được giữ nguyên.

- `1 năm (12 tháng) (12 tháng)` → `1 năm (12 tháng)`.
- `6 tháng (6 tháng)` → `6 tháng`.
- `1 năm 6 tháng (18 tháng) (18 tháng)` → `1 năm 6 tháng (18 tháng)`.
- Nếu durationText chỉ là `1 năm`, phần `(12 tháng)` của mẫu vẫn được giữ.
- Mã xuất riêng, đoạn văn viết tay, dữ liệu snapshot và mẫu nguồn không bị sửa. Không tìm/thay thế toàn bộ file theo số tháng.
- Xử lý đoạn văn trong thân tài liệu, bảng (kể cả bảng lồng), header/footer; giữ định dạng các run, không gộp đoạn văn thành một run.
- Tăng phiên bản fingerprint để bản nháp không tái sử dụng preview cũ. Restart Core rồi kết xuất lại bản nháp (hoặc chờ job tự kết xuất). Không ghi đè file đã ký, không cần drop dữ liệu hay upload lại mẫu chỉ để khắc phục lỗi này.
