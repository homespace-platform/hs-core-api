# Chi nhánh: điểm bắt đầu cho mọi model / người làm tiếp

## Trạng thái hiện tại
**P1–P5 XONG CODE + TEST TỰ ĐỘNG (27/11). CHƯA commit. CHƯA thử tay trên web qua Gateway, chưa kiểm Kafka → thông báo thật, cron 08:00 thật, tải lớn.** Ứng dụng core đã khởi động được với hạ tầng giả (`nhat-ky/P5-FIX.md`).
Deploy: `hs-notification-service` TRƯỚC, core SAU (rollback ngược lại). Quyết định điều phối chọn thay chủ dự án: cuối `04-cau-hoi.md` và cuối `07`. Còn để sau: lỗi bãi xe #1, #3 (Q13, Q14).
Thiết kế P3–P5 ở `07-thiet-ke-p3-p5.md`.

## Đọc theo thứ tự
| File | Nội dung |
|---|---|
| `00-concept.md` | Ý định chủ dự án (nguồn chuẩn, thắng mọi file khác) |
| `06-ban-giao.md` | Cách nhận việc, cập nhật tiến độ, quy tắc cấm |
| `05-tien-do.md` | Trạng thái từng task, nhật ký |
| `02-phase.md` | Phase, task, người làm, file sở hữu, checklist |
| `01-thiet-ke.md` | Dữ liệu, API, màn hình, bất biến |
| `03-test.md` | Ca test, lệnh chạy, mốc lỗi sẵn |
| `04-cau-hoi.md` | Câu hỏi chờ chủ dự án (Q1–Q11) |
| `khao-sat/backend.md`, `khao-sat/web.md` | Bằng chứng từ code (chỗ khác với 01 thì theo 01 mục 5) |

## Bước tiếp theo
Chủ dự án thử tay (checklist còn trống trong `02-phase.md`); lỗi phát hiện thì sửa theo quy trình ở `06`.

## Quy tắc
Mục 7 của `00-concept.md`. Hai git repo riêng `hs-core-api`, `hs-web-app`. Mốc trước khi bắt đầu: core-api `925158e`, web `6dff15b`.
