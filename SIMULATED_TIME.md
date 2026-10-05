Dưới đây là các giá trị **giờ Việt Nam** để nhập lần lượt vào `HOMESPACE_TIME_SIMULATED_AT` — **không thêm `Z`**. Bảng áp dụng khi ngày bắt đầu thuê ghi trong hợp đồng là **05/10/2026**; backend hiện tính kỳ hóa đơn từ ngày này, không phải từ ngày ký.

| Mốc giờ VN / Giá trị nhập vào `.env` | Kiểm tra trên hai tài khoản                                                                                                        |
| ------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------- |
| `2026-10-05T15:00:00`                | Hợp đồng hiệu lực; chưa có hóa đơn tháng.                                                                                          |
| `2026-11-04T00:01:00`                | Chủ nhà thấy hóa đơn nháp kỳ 1; nhập điện/nước, phí phát sinh và bấm **Lưu để tự phát hành**. Người thuê chưa có QR.               |
| `2026-11-05T10:01:00`                | Nếu đã lưu đủ chỉ số, hóa đơn tự phát hành. Hai bên thấy **Chưa thanh toán**; người thuê thấy QR. Kỳ đầu không thu lại tiền phòng. |
| `2026-11-07T00:01:00`                | Hiện nhắc sắp đến hạn; backend ghi log nhắc thanh toán.                                                                            |
| `2026-11-09T23:59:00`                | Vẫn trong hạn thanh toán.                                                                                                          |
| `2026-11-10T00:01:00`                | Chuyển **Quá hạn**; bắt đầu cộng phí phạt nếu hợp đồng có cấu hình.                                                                |
| `2026-11-11T00:01:00`                | Nếu phạt 100.000đ/ngày, phí phạt tăng thành 200.000đ.                                                                              |
| `2026-11-14T00:01:00`                | Hiện cảnh báo quá hạn kéo dài cho chủ nhà; backend ghi log yêu cầu xử lý. Với mức trên, phí phạt là 500.000đ.                      |
| `2026-12-04T00:01:00`                | Hóa đơn nháp kỳ 2 xuất hiện để thử chu kỳ tiếp theo.                                                                               |

Mỗi lần chỉ đặt **một mốc**, khởi động lại core API, chờ khoảng 1 phút rồi làm mới trang của cả chủ nhà và người thuê. Sau khi thử quá hạn, bạn có thể cho người thuê gửi chứng từ và chủ nhà xác nhận để kiểm tra trạng thái **Đã thanh toán**.
