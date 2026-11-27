# Chi nhánh: concept (nguồn chuẩn về ý định của chủ dự án)

Ghi ngày 2026-11-26. **Đọc file này trước mọi file khác.** Không tự thêm tính năng hay quy tắc ngoài file này; chỗ chưa rõ thì hỏi chủ dự án.

## 1. Mục đích

Chi nhánh để **gom nhóm, giám sát, quản lý, tránh bỏ sót** cho chủ nhà.

Lời chủ dự án: "hãy làm chi nhánh là giám sát quản lý tránh sót là chính thay vì làm phức tạp thay thế các tính năng đã có!"

Quy tắc: chi nhánh cho biết **phòng nào, việc gì, còn bao lâu**; bấm vào thì **điều hướng đến đúng hợp đồng / hoá đơn / trang đã có** để xử lý ở đó. Ví dụ của chủ dự án: tới ngày nhập điện thì chỉ báo "HĐ A cần nhập", bấm vào mở đúng HĐ A và nhập trong HĐ đó (tính năng nhập đã có).

## 2. KHÔNG làm

- Không làm màn nhập chỉ số riêng, không nhập hàng loạt, không có chế độ "đi ghi số".
- Không phát hành hoá đơn hàng loạt.
- Không xác nhận tiền ngay trên sơ đồ.
- Không đổi quy tắc hoá đơn, tiền, hợp đồng, bãi xe. Kể cả thời điểm được ghi chỉ số.
- Không theo dõi tiền cho phòng thuê ngoài hệ thống (chỉ hiện tình trạng).
- Đợt này không làm: mobile, báo cáo doanh thu, báo hỏng, tài khoản nhân viên, nhắc người thuê, kéo thả.

## 3. Sơ đồ chi nhánh

- Cấu trúc: Chi nhánh → Tầng/Khu → Dãy → Phòng (ô). **Phòng là dữ liệu riêng**, không suy ra từ tin đăng.
- Tạo sơ đồ là một bước khi tạo chi nhánh, **được bỏ qua**, sửa sau. Chi nhánh cũ / không có sơ đồ vẫn dùng như hiện nay.
- Chủ nhà chọn mẫu sẵn hoặc tự thiết kế (bấm **+** thêm phòng theo lưới).
  - Mẫu: dãy trọ một dãy; hai dãy đối diện; nhà nhiều tầng (số tầng + số phòng mỗi tầng, đánh số 101, 102, 201…); tự thiết kế.
  - Tiện ích: nhân bản tầng, thêm nhiều phòng, đặt lại mã hàng loạt, ô trống (cầu thang/lối đi), hoàn tác, di chuyển bằng menu.
- Nhà nguyên căn: không cần sơ đồ, hoặc **sơ đồ 1 ô tự tạo** (đã chọn: tự tạo 1 ô để dùng chung một cách xem).
- Hình phác thảo của chủ dự án: mỗi cột là một dãy ô xếp dọc, dưới mỗi cột có nút **+**.

## 4. Gắn tin và hợp đồng

- Bấm ô trống: gắn hợp đồng có sẵn; gắn tin có sẵn; đăng tin cho phòng này (điền sẵn thông tin chi nhánh); đánh dấu thuê ngoài hệ thống hoặc đang sửa chữa.
- Tạo hợp đồng: nếu tin gắn chi nhánh thì hợp đồng **tự gắn** (và tự vào đúng ô nếu tin đã gắn phòng). Không có thì để trống.
- Một chi nhánh có nhiều hợp đồng; mỗi hợp đồng thuộc tối đa một chi nhánh. Chủ nhà **thêm hoặc gỡ** liên kết hợp đồng với chi nhánh bất cứ lúc nào.
- Hợp đồng chưa có vị trí nằm ở khay **"Chưa xếp phòng"**.
- Một phòng không có hai hợp đồng trùng thời gian; người thuê kế tiếp hiện "Sắp vào".
- Hợp đồng liên kết bằng **trường mới** (đề xuất tên `management_branch_id` + liên kết phòng), **không dùng cột `contracts.branch_id` cũ**: cột cũ dùng cho bãi xe, đồng bộ tin và bị phụ lục ghi đè. Gắn/gỡ không đổi điều khoản, phí, bãi xe, tin đăng.
- Phụ lục, gia hạn đi theo hợp đồng gốc. Hợp đồng chỉ được gắn vào chi nhánh cùng chủ nhà.
- Xóa chi nhánh còn hợp đồng đang gắn thì chặn, yêu cầu gỡ trước. Gỡ phòng chỉ gỡ liên kết; không xóa phòng còn hợp đồng hiệu lực.

## 5. Lớp xem trên sơ đồ (mỗi lúc một lớp)

| Lớp | Hiện |
|---|---|
| Phòng | trống, đang đăng tin, giữ chỗ, đang thuê, sắp vào, thuê ngoài, sửa chữa |
| Thu tiền (chọn tháng) | xem bảng màu |
| Ghi chỉ số | cần ghi hôm nay, sắp đến, đã ghi |
| Hợp đồng | sắp hết hạn 30 / 60 ngày |

Màu lớp Thu tiền: xanh lá = đã thu; tím = chờ chủ nhà xác nhận (kèm đếm ngược tự xác nhận); xanh dương = đã gửi hoá đơn, chưa đến hạn; cam = còn ≤ 3 ngày hoặc đang gia hạn; đỏ = quá hạn; xám/trắng = chưa phát hành. Ô có nhiều khoản hiện mức khẩn nhất + số khoản. **Mỗi ô có chữ/biểu tượng, không chỉ dựa vào màu.**

"Tháng N" = hoá đơn có **hạn thanh toán** trong tháng N (đã chốt). Nợ quá hạn tháng cũ vẫn hiện cảnh báo.

Có chú giải + số tổng bấm để làm nổi phòng; tìm theo mã phòng / tên người thuê. Màu được **suy ra** từ trạng thái hoá đơn và thanh toán đang có.

## 6. "Cần chú ý"

| Nhóm | Việc |
|---|---|
| Điện nước | sắp đến ngày ghi (báo trước 3 ngày), phải ghi hôm nay; hoá đơn bị tự điền chỉ số; quá ngày mà vẫn nháp |
| Tiền | chờ xác nhận (còn bao lâu thì tự xác nhận), quá hạn, xin gia hạn; cọc/tiền chủ nhà phải hoàn |
| Hợp đồng | chờ chủ nhà ký; phụ lục / yêu cầu chấm dứt chờ phản hồi; sắp hết hạn 60 và 30 ngày |
| Khách mới | yêu cầu thuê, lịch xem nhà chờ duyệt |
| Phòng | trống mà chưa đăng tin; tin bị từ chối / hết hạn |
| Dữ liệu | hợp đồng hoặc tin chưa xếp vào phòng |

- Trang danh sách chi nhánh: mỗi thẻ có số việc, chi nhánh gấp nhất lên đầu.
- Trong chi nhánh: hai chế độ **Sơ đồ** và **Cần chú ý** (gấp lên trước).
- Việc **tự biến mất** khi xử lý ở trang gốc, không đánh dấu tay.
- Một thông báo gộp mỗi ngày, chỉ gửi khi có việc.
- Một số nhóm chưa kiểm dữ liệu có đủ không → xem khảo sát; thiếu dữ liệu thì báo chủ dự án, không tự chế quy tắc.

## 7. Nguyên tắc và cách làm việc

- Phần mới chỉ **đọc dữ liệu và dẫn đường**; thứ được ghi thêm chỉ là sơ đồ và liên kết phòng/chi nhánh.
- Không phá luồng đang ổn định.
- Làm **web trước** (`hs-web-app`), nâng cấp backend `hs-core-api` khi cần. Mobile để sau.
- Phân vai: **Claude Sonnet viết code**; **Codex viết test, viết docs, cập nhật tiến độ**. Chia sub-agent chạy song song.
- **Chia phase, xong một phase thì dừng** chờ chủ dự án. Không làm một lèo.
- Tài liệu phải đủ để **model AI khác tiếp tục đúng** khi bị dừng giữa chừng (hết quota, đổi model).
- Chủ dự án tự commit. Không commit, push, reset, checkout. Không đọc `.env` / tệp bí mật.
- Không tự bịa; thắc mắc thì hỏi lại.
- Dữ liệu chưa chính thức (chưa phát hành cho khách): không viết SQL migrate; chủ dự án tự xóa DB/volume khi cần.
- Trả lời chủ dự án bằng tiếng Việt, ngắn gọn.

## 8. Lộ trình đã thống nhất

1. Tạo và sửa sơ đồ, mẫu sẵn, nhà nguyên căn 1 ô.
2. Gắn tin và hợp đồng vào phòng, khay chưa xếp.
3. Lớp màu Phòng và Thu tiền, chọn tháng; bấm để mở đúng trang.
4. Danh sách "Cần chú ý", số việc trên thẻ chi nhánh.
5. Lớp Ghi chỉ số, lớp Hợp đồng, thông báo gộp.
