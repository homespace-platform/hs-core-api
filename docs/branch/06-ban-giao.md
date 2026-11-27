# 06 · Bàn giao cho model tiếp theo

## Bắt đầu từ đâu

1. Đọc [00-concept.md](00-concept.md), [README.md](README.md), [khảo sát backend](khao-sat/backend.md), [khảo sát web](khao-sat/web.md), rồi `AGENTS.md` của repo core. Concept là nguồn chuẩn. Xem ngay [05-tien-do.md](05-tien-do.md) sau đó để biết cổng hiện tại và task nào dở. Nếu README còn ghi Phase 0 đang làm, lấy tiến độ từng task và mốc duyệt từ 05; chỉ điều phối cập nhật README.
2. Đọc phần của task trong [01-thiet-ke.md](01-thiet-ke.md), [02-phase.md](02-phase.md), [03-test.md](03-test.md), [04-cau-hoi.md](04-cau-hoi.md). Đối chiếu code hiện tại ở file liên quan khi HEAD đã đổi hoặc hành vi chưa rõ. Trước khi sửa code, áp dụng `AGENTS.md` của module/repo liên quan; web cần đọc guide Next tương ứng trong `node_modules/next/dist/docs/`.
3. Xác nhận đã có dòng chủ dự án mở phase trong 05. Chỉ nhận task đã mở, có câu trả lời cho phần bị chặn và không đụng phạm vi đang có người ghi. Nếu chưa có duyệt, dừng ở cổng; không chuyển phase bằng suy đoán.

## Kiểm tra trạng thái repo

Hai repo Git riêng. Từ core dùng PowerShell. So với mốc ban đầu core **925158e** và web **6dff15b**, rồi so mốc/check gần nhất trong nhật ký. HEAD khác có thể là commit của chủ dự án. So nội dung liên quan và giữ nguyên thay đổi người khác.

```powershell
git status --short
git rev-parse --short HEAD
git -C ../hs-web-app status --short
git -C ../hs-web-app rev-parse --short HEAD
```

Nếu cần xem chênh lệch, chỉ xem đường dẫn liên quan bằng `git diff -- <path>` hoặc `git diff <mốc> -- <path>` trong repo đúng. Git diff thông thường bỏ qua file mới chưa theo dõi; đọc file đó trực tiếp. File trong `docs/branch/` đã có sẵn chưa theo dõi ở Phase 0. Không mở `.env`/tệp bí mật để kiểm môi trường.

## Nhận task và bàn giao giữa chừng

| Lúc nào | Ghi ngay |
|---|---|
| Trước khi đụng file | Báo điều phối mã task, repo/HEAD, phạm vi file, câu hỏi đã chốt và test dự kiến. Codex cập nhật `Đang làm`, tên người giữ và file vào 05, chốt giao diện trước khi tách web/core/test. Một người ghi 02/05 tại một thời điểm. |
| Xong từng task | **Cập nhật ngay**, trước khi nhận việc khác: Codex đánh `[x]` đúng mã trong 02, đổi hàng tương ứng sang `Xong` trong 05, thêm nhật ký ngày/ai/thay đổi/lệnh và kết quả test hoặc phần chưa kiểm. Sonnet gửi chứng cứ cho Codex; nếu Codex chưa có mặt, gửi ngay bản ghi cho điều phối để lưu vào 05 trước khi chuyển task. |
| Tạm dừng hoặc bị chặn | Giữ checklist `[ ]`. Ghi trạng thái trong 05, repo/HEAD, file đang sửa, phần đã chạy được, bước kế cụ thể và lệnh kiểm tiếp theo. Ghi lỗi/sai khác còn lại, phần test chưa chạy và locks/file người kế được tiếp quản. |
| Nhận lại việc dở | Kiểm status/diff đúng file đã bàn giao, đọc bản ghi cuối, xác nhận quyền giữ rồi tiếp tục từ bước kế. Không lặp lại kiểm tra đã đạt trên đầu vào chưa đổi. |
| Kết thúc phase | Ghi mốc, từng kết quả theo 03, API/web chưa xác minh, câu hỏi/giao diện còn chờ và xin nghiệm thu. **DỪNG**, kể cả khi agent còn thời gian. Chủ dự án quyết mở phase kế tiếp. |

Mẫu dòng bàn giao dở trong nhật ký: `YYYY-MM-DD | người/mã task | repo@HEAD, file đang sửa, phần đã xong | lệnh + kết quả hoặc chưa chạy | lỗi/chờ gì; bước tiếp theo cụ thể`.

## Quy tắc cấm và ưu tiên

- **Phiên lập kế hoạch** chỉ tạo/sửa 01–06 trong `docs/branch/`. Chưa viết code/test. Không sửa concept hoặc README. Khi chủ dự án mở phase, chỉ ghi file thuộc task đã nhận của phase đó.
- Không commit, push, reset, checkout, clean, xóa/hoàn tác phần của người khác, đọc `.env`/tệp bí mật, triển khai hay đổi cấu hình thật. Không viết SQL migration hoặc xóa DB/volume. Chủ dự án tự commit và xử lý dữ liệu dev nếu cần.
- Sonnet viết code; Codex viết test/docs. Web đi trước với fixture khi API chưa sẵn. Mọi ghi nghiệp vụ tiếp tục ở trang hiện có, GET tổng quan không có tác dụng ghi. Không thêm việc ngoài concept. Không sửa lỗi bãi xe EXT-BE-01 cho tới khi chủ dự án quyết riêng.
- Nếu code và khảo sát khác nhau, ghi dòng chứng cứ trong 01 hoặc nhật ký; ghi quyết định thật ở 04 và cập nhật tài liệu bị ảnh hưởng. Chưa rõ nghiệp vụ thì chờ chủ dự án ở phần đó. Nêu cụ thể quyền, luồng cũ và tích hợp nào còn chưa được xác minh.
