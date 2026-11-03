# Quá hạn: tối đa 5 ngày phạt, đơn phương chấm dứt và khiếu nại 24 giờ

Quy tắc hệ thống do chủ dự án chốt ngày **04/11/2026**, thay cho nhánh đề nghị chấm dứt do nợ ở màn hình hóa đơn. Không thay luồng hai bên thỏa thuận chấm dứt vì lý do khác hoặc xử lý cọc trước ký. Tài liệu này là tiêu chí hiện hành; các UC 10 ngày phạt/khách đồng ý chấm dứt trong SIMULATED_TIME là đối chiếu phiên bản cũ.

## Các mốc độc lập

| Quy tắc | Giá trị |
|---|---|
| Cửa sổ thanh toán | 5 ngày lịch tính cả ngày chốt kỳ |
| Mở các phương án chủ nhà | Sau hết 5 ngày quá hạn, từ 00:00 ngày quá hạn thứ 6 |
| Trần phạt theo ngày | Tối đa tổng **5 ngày/hóa đơn**, tính chung trước/sau mọi lần gia hạn |
| Tự chốt nợ khi không có phương án | Sau hết **10 ngày quá hạn**, chỉ khi còn kỳ sau chưa phát hành và không vướng phương án/đối soát |
| Cửa sổ khiếu nại quyết định đơn phương | **24 giờ thực** kể từ thời điểm thông báo theo đồng hồ nghiệp vụ |

Ví dụ hạn 05/12/2026 lúc 23:59:59, mức 100.000đ/ngày: 06/12 = 100.000đ; 10/12 = 500.000đ; từ **11/12 không tăng thêm** và chủ nhà bắt đầu được chọn phương án. Ngày 10/12 vẫn là ngày phạt thứ 5. Nếu không chọn phương án, tự chốt nợ sớm nhất từ 16/12 lúc 00:00 khi đủ điều kiện.

Gia hạn sớm cộng ngày vào hạn hiện tại; gia hạn khi đã quá hạn tính từ lúc duyệt. Không tính phạt thời gian được gia hạn. Đã phạt 3 ngày thì sau hạn mới chỉ thêm tối đa 2 ngày. Phạt một lần vẫn một lần; NONE không có phí. Giữ trần thấp hơn trong bản ký cũ. Không tính lại giao dịch đã trả, khoản phạt cũ đã chốt cao hơn trần mới, hoặc quyết định miễn tăng phí cũ; không giảm tiền lịch sử hoặc viết lại PDF đã ký.

## Quyết định đơn phương

1. Chủ nhà đủ điều kiện quá hạn nhập lý do 10–1000 ký tự và gửi thông báo đơn phương. Hệ thống lưu thông báo trên hồ sơ để hai bên đọc; không cần người thuê đồng ý, không ghi giả `terminationAcceptedAt`.
2. Trong 24 giờ, người thuê có thể bấm **Khiếu nại — xin đổi phương án**, chọn ROLLOVER (cộng nợ) hoặc PAYMENT_EXTENSION (dời 1–30 ngày), nêu lý do. Không có kỳ sau chưa phát hành thì không xin cộng nợ.
3. Gửi khiếu nại đúng hạn chuyển sang REVIEW_REQUESTED và tạm dừng tự chấm dứt. Không tự coi chủ nhà im lặng là đồng ý/từ chối. Chờ quyết định có lý do của chủ nhà.
4. Chủ nhà đồng ý: chuyển REPLACED, rút quyết định đơn phương và áp dụng đúng phương án/đúng số ngày khách đã xin, trong cùng transaction. Kiểm tra lại điều kiện kỳ sau trước khi áp dụng. Không thể đồng thời vừa chuyển nợ vừa dời hạn.
5. Chủ nhà từ chối: hợp đồng TERMINATED ngay, không chờ hết 24 giờ, không cần xác nhận thêm của khách.
6. Không khiếu nại: tại hoặc sau hạn 24 giờ, scheduler hoặc API đọc hồ sơ tự chuyển TERMINATED. Job quét mỗi phút; không phụ thuộc việc tenant mở trang. Giao diện đang mở hồ sơ kiểm tra lại mỗi 30 giây và không dùng đồng hồ trình duyệt để cho phép thao tác.
7. Đang TRANSFER_REPORTED hoặc DISPUTED thì không tự kết thúc và không cho chủ nhà quyết định khiếu nại. Đã CONFIRMED thì rút quyết định và đánh dấu hóa đơn đã trả. Chứng từ bị REJECTED thì điều kiện quá hạn tiếp tục được xét lại; khiếu nại đã gửi đúng hạn vẫn chờ chủ nhà.
8. Chủ nhà có thể rút quyết định khi chưa kết thúc. Rút xong có thể chọn phương án khác. Lịch sử cũ không bị xóa, tab cũ không thể thao tác lên quyết định mới.

## Kết thúc và bàn giao

TERMINATED chấm dứt việc sinh kỳ thuê mới; nháp chưa phát hành bị hủy, hóa đơn/công nợ đã phát hành giữ nguyên để thanh toán. Không suy đã nhận lại phòng từ việc khách im lặng.

Sau khi thực tế nhận lại phòng trống, chìa khóa và tài sản, chủ nhà dùng **Xác nhận đã nhận lại phòng**. Lúc này mới mở lại tin, giải phóng chỗ xe và hoàn tất giữ cọc thực nhận theo nhánh vi phạm hiện có. Hợp đồng 0đ cọc không tạo dòng giữ/hoàn cọc giả. Ngày hợp đồng kết thúc giữ nguyên, không bị ghi đè bằng ngày bàn giao. Xác nhận lặp không xử lý cọc hoặc mở tin thêm lần nữa.

## Ca nghiệm thu thủ công

Mỗi ca dùng hồ sơ thử riêng. Ví dụ thuê từ 01/11/2026, kỳ hóa đơn đầu quyết toán ngày 01/12, hạn 05/12 23:59:59; đã ký/ACTIVE, khoản tháng đầu thực trả riêng. Không lùi đồng hồ trên hồ sơ đã tiến thời gian. Các mốc dưới đây là giờ Việt Nam.

| Ca | Thao tác | Kết quả |
|---|---|---|
| C01 — trần 5 ngày | Không gia hạn; mở ngày 06/12, 10/12, 11/12, 15/12 | Phạt 100k, 500k, 500k, 500k; không tự chấm dứt khi chưa có quyết định |
| C02 — trước hạn gia hạn | Ngày 04/12 duyệt thêm 1 ngày | Hạn mới 06/12 23:59:59; phạt 07–11/12 tối đa 500k |
| C03 — đã phạt rồi gia hạn | Ngày 08/12 lúc 10:00, đã phạt 300k, xin/duyệt 1 ngày | Hạn 09/12 10:00; 10/12 = 400k, 11/12 = 500k; không đặt lại bộ đếm |
| C04 — đúng biên 24 giờ | Chủ nhà gửi 11/12 10:00; mở 12/12 09:59:59 rồi 10:00 | Trước hạn còn ACTIVE; từ hạn không khiếu nại thì TERMINATED; không có tenant consent giả |
| C05 — khiếu nại xin cộng nợ | Gửi đúng hạn; chủ nhà đồng ý dù đã qua 24 giờ | REPLACED, ACTIVE, chốt công nợ vào kỳ sau, không dời hạn đồng thời |
| C06 — khiếu nại xin dời 5 ngày | Chủ nhà đồng ý 13/12 10:00 | REPLACED, hạn trả 18/12 10:00; cọc/tin không thay đổi; trần phí cũ giữ nguyên |
| C07 — khiếu nại bị từ chối | Khách khiếu nại 11/12 11:00; chủ nhà từ chối 12:00 | TERMINATED ngay 11/12 12:00; nợ còn nguyên |
| C08 — chủ nhà chưa quyết định | Khách khiếu nại đúng hạn, mở sau 24 giờ | REVIEW_REQUESTED, chờ chủ nhà; không tự kết thúc hoặc tự chấp thuận |
| C09 — có chứng từ | Báo chuyển trước hạn 24 giờ, chưa được xác nhận | Không kết thúc trong lúc đối soát. CONFIRMED rút quyết định; REJECTED xét lại điều kiện |
| C10 — stale/race | Hai tab chủ nhà cùng duyệt/từ chối cùng phiên bản | Chỉ một quyết định commit; tab còn lại tải lại, không tự POST lại |
| C11 — 0đ cọc | Tự kết thúc rồi xác nhận nhận lại phòng | Không có giữ/hoàn 0đ; tin mở sau nhận lại phòng, không mở lúc hết 24 giờ |
| C12 — cọc thực nhận | Kết thúc, xem sổ cọc, rồi chủ nhà xác nhận bàn giao | Trước bàn giao vẫn HELD; sau bàn giao giữ đúng thực nhận, không đổi `endedAt`, chống xác nhận lặp |
| C13 — kỳ cuối/đã phát hành kỳ sau | Khách xin đổi phương án | Không cho cộng nợ vào hóa đơn đã phát hành; vẫn có thể xin dời hạn |
| C14 — quyền/thời gian sai | Actor khác, thiếu phiên bản, thiếu approve, lý do ngắn, số ngày 0/31, lùi đồng hồ trước sự kiện | Bị chặn, không tạo quyết định, không ngầm hiểu thiếu approve là từ chối |
| C15 — job không có người mở trang | Tạo thông báo rồi để qua 24 giờ | Job tự kết thúc, không bỏ sót hợp đồng khi các hàng trước rời tập ACTIVE |
| C16 — không chọn gì | Qua hết 10 ngày quá hạn | Tự chốt nợ có điều kiện, không bị đổi thành tự chuyển nợ sau 5 ngày |

## API, lưu trữ và triển khai

- `POST /api/v1/monthly-invoices/{invoiceId}/unilateral-termination` — `{reason}`.
- `POST .../{invoiceId}/unilateral-termination/{noticeId}/review` — `{expectedVersion,option,days?,reason}`.
- `POST .../{noticeId}/decision` — `{expectedVersion,approve,reason}`; approve là Boolean bắt buộc.
- `POST .../{noticeId}/withdraw` — `{expectedVersion}`.
- `POST .../{noticeId}/handover` — `{expectedVersion,vacantPossessionConfirmed:true,keysAndAssetsReturned:true}`.

Vai trò lấy từ UserContext; khóa contract → invoice → payment; phiên bản tăng mỗi quyết định. Các sự kiện nguồn hệ thống không được giả mạo qua API ghi overdueAction. Endpoint `termination/propose` cũ từ chối tạo mới để không biến thao tác khách từng hiểu là đề nghị đồng thuận thành quyết định đơn phương mà chưa đọc lại. Các endpoint accept/decline/complete/force cũ chỉ phục vụ hồ sơ cũ và bị chặn khi gặp notice mới.

Schema thêm cột nullable `contracts.unilateral_termination_snapshot` kiểu TEXT. Với môi trường quản lý migration thủ công, áp dụng trước khi chạy code:

```sql
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS unilateral_termination_snapshot TEXT;
```

Không có backfill quyết định, không sửa dữ liệu đã ký/đã trả. JPA dev đang cấu hình update có thể tự thêm cột khi chủ dự án restart. Không chạy SQL này vào CSDL vận hành trong phiên làm việc.

Đã cập nhật prompt `hs-infra/prompts/HOMESPACE_CONTRACT_PROMPT.md` cho cả ba loại tài sản, cảnh giới phạm vi ở prompt cọc, và nguồn RAG thanh toán 0.3.3. Prompt mới là đầu vào để tạo/upload mẫu Word mới; DOCX/PDF đã lưu/đã ký không được tự thay bằng sửa prompt. Không ingest RAG hoặc tích hợp hệ thống email/push. Thông báo trong thay đổi này được lưu bền vững và hiển thị trên hồ sơ hóa đơn cho hai bên.

Kết quả kiểm thử và log cuối được ghi trong `OVERDUE_5_DAY_UNILATERAL_24H_REPORT_2026-11-04.md`.
