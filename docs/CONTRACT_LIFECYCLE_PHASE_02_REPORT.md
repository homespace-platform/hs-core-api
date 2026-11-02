# Bàn giao giai đoạn 02 — Giải quyết tranh chấp thanh toán

Ngày: **03/11/2026**. Phạm vi được duyệt: **giai đoạn 02 / F02**. Các kiểm thử cuối đã đạt; dừng chờ chủ dự án nghiệm thu trước khi triển khai giai đoạn 03.

## 1. Kết quả

Khoản `DISPUTED` đã có hồ sơ và đường xử lý trên core API, web và admin. Hai bên có thể phản hồi, bổ sung chứng cứ, đề xuất hướng xử lý, đồng ý/từ chối hoặc chuyển HomeSpace. Admin tiếp nhận và ghi quyết định có phiên bản, căn cứ và lịch sử.

Hai hướng xử lý hiện có:

| Hướng | Kết quả thực tế | Bước tiếp theo |
|---|---|---|
| `REVIEW_TRANSFER` | Đưa khoản tiền về `TRANSFER_REPORTED`, mở lại hạn xác nhận; yêu cầu chứng cứ hợp lệ | Bên nhận kiểm tra tài khoản rồi tự xác nhận thực nhận bằng API thanh toán hiện có |
| `REOPEN_TRANSFER` | Mở lại gửi chứng từ/báo chuyển tiền; nợ định kỳ đã quá hạn vẫn là `OVERDUE` | Bên chuyển gửi chứng từ, bên nhận kiểm tra và xác nhận theo luồng hiện có |

Đóng hồ sơ đối soát chỉ hoàn tất quyết định về bước xử lý. Nó **không xác nhận tiền**, không sửa số tiền, không xóa nợ và không tự hoàn tiền. Khoản đã nhận và chứng từ cũ tiếp tục được giữ. Việc điều chỉnh, quyết toán hay tạo chênh lệch/hoàn vẫn đi qua nghiệp vụ tài chính tương ứng; không mở một API cho admin sửa nợ tùy ý.

## 2. Dữ liệu và quyền

- Thêm `dispute_cases` và `dispute_case_events`: chủ thể nghĩa vụ, hai bên, người mở, lý do, số tiền/tiền tệ và trạng thái trước tranh chấp, người tiếp nhận, đề xuất, kết luận và lịch sử từng hành động.
- Nền tảng có `subjectType`/`subjectId`; giai đoạn này chỉ bật chủ thể `PAYMENT`. Chưa triển khai khiếu nại bàn giao/sửa chữa/điều khoản của giai đoạn 04.
- Mỗi khoản tiền chỉ có một hồ sơ đang mở. Giải quyết xong vẫn giữ hồ sơ cũ; phát sinh tranh chấp mới tạo hồ sơ mới.
- Người chuyển/người nhận mới được đọc và thao tác hồ sơ của khoản tiền. Người đề xuất không được tự chấp nhận đề xuất của mình. Từ chối giữ lịch sử và cho gửi đề xuất mới.
- Chuyển admin làm đề xuất song phương cũ mất hiệu lực. Hai bên vẫn bổ sung phản hồi/chứng cứ, nhưng không tự đóng đề xuất đã chuyển cấp.
- Admin phải có quyền `ADMIN`, đúng danh tính từ context, tiếp nhận hồ sơ trước quyết định và không được phân xử khoản tiền của chính mình. Chuyển việc từ admin khác là thao tác tiếp nhận rõ ràng, có phiên bản và lý do.
- Mọi thay đổi kiểm tra `expectedRevision`; ghi chú cũng tăng phiên bản dù đồng hồ thử nghiệm không đổi. Lưu khóa nghiệp vụ đang mở, khóa hàng DB và phiên bản JPA để chống xử lý lặp/đồng thời.
- Chứng cứ phải thuộc người tham gia, còn hoạt động, `READY`, `PRIVATE`, đúng mục đích `PAYMENT_PROOF` và đúng nghĩa vụ. Gửi chứng cứ không đồng nghĩa đã báo chuyển tiền.
- Mỗi hồ sơ chụp danh sách chứng cứ lúc mở và ghi bổ sung của chính hồ sơ đó. Chứng cứ của một lần tranh chấp sau không xuất hiện như căn cứ của quyết định trước.

## 3. Tích hợp luồng hiện có

| Khoản / tình huống | Cách xử lý |
|---|---|
| Cọc giữ chỗ, tháng đầu | Chỉ mở lại khi hồ sơ còn `ACCEPTED`, listing đang giữ chỗ và đúng hai bên, không chờ hủy. Mở lại hạn theo cấu hình hiện có; giữ ngày bắt đầu, tiền và thời hạn thuê |
| Hóa đơn định kỳ | Giữ hạn/nghĩa vụ hiện tại; quyết định chưa làm hóa đơn thành đã trả. Xác nhận tiền thật đi qua đối chiếu hóa đơn hiện có |
| Hoàn cọc | Chỉ phục hồi nhãn `REFUND_DUE` / `REFUND_REPORTED` của đúng khoản hoàn. Số tiền đang giữ, sẽ hoàn và đã giữ lại không bị sửa bởi quyết định |
| Chênh lệch cọc | Chỉ cộng/trừ sổ tiền sau xác nhận thực nhận qua luồng hiện có; gửi xác nhận lại không áp dụng chênh lệch lần hai |
| Hoàn trước thuê | Nghĩa vụ hoàn vẫn xử lý được sau khi hồ sơ thuê đã hủy; không làm sống lại hợp đồng/listing cũ |
| Dữ liệu `DISPUTED` cũ | Hai bên có thể tiếp nhận thành hồ sơ có lịch sử. Nếu không tìm được trạng thái trước đó thì giữ là chưa biết, không tự đoán đã trả/chưa trả |
| Đối soát onboarding cũ | Chặn mở lại/xác nhận đơn phương khi khoản tiền đang `DISPUTED`. Nhánh cũ cho chứng từ `REJECTED` hoặc hạn báo chuyển đã qua vẫn được giữ và có test tương thích |

Hồ sơ thuê đã kết thúc hoặc listing đang thuộc lượt thuê khác không được khôi phục bằng quyết định đối soát. Không gỡ các chốt ký, hủy, giữ cọc và kết thúc hợp đồng hiện có chỉ để vượt qua trạng thái tranh chấp. Xử lý các ngoại lệ nghiệp vụ đó vẫn thuộc các giai đoạn đã lên lịch.

## 4. API và giao diện

Các đường dẫn dưới là đường dẫn core API; web gọi qua tiền tố gateway `/api/v1` theo cấu hình hiện có.

| API | Chức năng |
|---|---|
| `POST /payment-requests/{id}/dispute` | API hiện có; nay tạo hồ sơ/lịch sử trong cùng transaction với đổi trạng thái |
| `GET /payment-requests/{id}/dispute-cases` | Lấy tối đa 20 hồ sơ gần nhất, lịch sử và chứng cứ thuộc từng hồ sơ |
| `POST /payment-requests/{id}/dispute-cases/adopt` | Tiếp nhận khoản `DISPUTED` cũ; không tạo trùng hồ sơ đang mở |
| `POST /payment-disputes/{id}/actions/{action}` | `COMMENT`, `PROPOSE`, `ACCEPT`, `DECLINE`, `ESCALATE`; yêu cầu phiên bản và nội dung |
| `GET /admin/payment-disputes` | Hàng đợi admin có lọc trạng thái và phân trang |
| `GET /admin/payment-disputes/{id}` | Hồ sơ chi tiết, chứng cứ, lịch sử và quyền thao tác |
| `POST /admin/payment-disputes/{id}/actions/{action}` | `CLAIM` / `DECIDE`; không có hành động xác nhận tiền thay người nhận |

Web dùng chung `PaymentDisputePanel` tại onboarding, modal thanh toán, hóa đơn, quyết toán và danh sách thanh toán. Nút dựa trên quyền backend trả về; phần gửi tiền/QR không thúc khách chuyển lại khi khoản đang tranh chấp. Thao tác gửi kèm phiên bản đã xem, chống bấm lặp, giữ nội dung khi lỗi phiên bản và tải lại để người dùng xem trước khi thử lại. Không tự gửi lại quyết định. Phản hồi đến muộn sau khi đổi khoản tiền/rời màn hình không xóa ghi chú mới hoặc cập nhật lại hồ sơ cũ.

Trang admin khiếu nại trước đây trống nay có hàng đợi **đối soát thanh toán** tối thiểu: đọc chứng cứ, nhận/chuyển người xử lý, xem lịch sử và quyết định bước xử lý. Chức năng khiếu nại tổng quát, SLA và thông báo tự động chưa nằm trong giai đoạn này.

## 5. Các lỗi bổ sung đã phát hiện và sửa trong cùng phạm vi

1. Entity thanh toán đã được đọc trong persistence context có thể còn trạng thái cũ sau khi transaction khác mở dispute. Khóa hàng rồi kiểm tra object cũ có nguy cơ xác nhận sai; một số cách khóa còn gây lỗi phiên bản Hibernate trước khi refresh. Các hành động chuyển/xác nhận/từ chối/tranh chấp nay refresh **kèm khóa ghi** trước khi kiểm tra trạng thái. Có test PostgreSQL tái hiện transaction giữ bản `TRANSFER_REPORTED` cũ trong lúc dispute được commit.
2. Luồng đối soát onboarding cũ có thể bỏ qua hồ sơ song phương. Nay chặn riêng `DISPUTED`, vẫn giữ nhánh hợp lệ cũ. Nhánh xác nhận cũ flush trạng thái trung gian trước khi gọi API xác nhận để refresh không loại bỏ thay đổi chưa lưu.
3. Chứng cứ mới có thể làm lịch sử hồ sơ đã giải quyết bị thay đổi về căn cứ. Nay chụp chứng cứ theo từng hồ sơ và không lấy toàn bộ chứng cứ hiện tại của payment làm lịch sử cũ.
4. `ddl-auto=update` không tự mở rộng enum CHECK PostgreSQL của `payment_events`. Thêm `PaymentEventTypeSchemaMigration` theo cách migration Java hiện có trong dự án, mở rộng CHECK cho `DISPUTE_ROUTED` trong transaction, không xóa sự kiện cũ. Nếu có giá trị lịch sử không nhận diện được, migration thất bại và rollback ràng buộc cũ để tránh mất dữ liệu.

## 6. Kết quả kiểm thử cuối

| Kiểm tra | Kết quả |
|---|---|
| Backend: listing, payment, contract và API liên quan | **609 ca được báo cáo: 608 đạt, 1 bỏ qua**, 0 failure/error; `BUILD SUCCESS` |
| PostgreSQL thực, nằm trong tổng backend | **42/42 đạt**: 5 chấp thuận thuê, 3 kho HĐ cọc, 6 bản nháp, 14 onboarding, 14 tranh chấp/migration |
| Service tranh chấp mới, nằm trong tổng backend | **34/34 đạt**; các loại tiền, hai chiều cọc, quyền, chứng cứ, đề xuất, chuyển cấp, phiên bản, lịch sử |
| Controller tranh chấp mới, nằm trong tổng backend | **4/4 đạt**; context danh tính, body sai/thiếu phiên bản, quyết định giả xác nhận tiền và phân quyền thật qua Spring Method Security |
| Web: bộ hồi quy hợp đồng, hóa đơn, thanh toán, cọc và onboarding | **163/163 đạt**, gồm 13 ca panel tranh chấp mới |
| Admin: thao tác hàng đợi/chi tiết tranh chấp mới | **6/6 đạt** |
| TypeScript web, build admin gồm typecheck | Đạt |
| ESLint các file triển khai mới/sửa trong giai đoạn 02 | Đạt |
| Kiểm tra diff của ba repo | Đạt |

Các ca PostgreSQL mới kiểm tra: mở dispute/lịch sử nguyên tử; lỗi ghi lịch sử rollback toàn bộ; thống nhất kiểm tra chứng từ rồi xác nhận thật hoàn tất hóa đơn; hoàn cọc rồi đồng bộ quyết toán/kết thúc; bổ sung/hoàn chênh lệch đúng một lần; hoàn trước thuê sau hủy; lỗi quyết định rollback và retry; hai bên đồng ý đồng thời; hai admin tiếp nhận đồng thời; bản thanh toán đã đọc bị cũ; admin quyết định vẫn không được nhận tiền thay bên nhận; nâng CHECK cũ idempotent; nâng CHECK gặp dữ liệu không nhận diện được phải rollback và giữ lịch sử. Hai ca PostgreSQL onboarding bổ sung kiểm tra nhánh đối soát cũ còn lưu/mở lại đúng.

Ca bỏ qua duy nhất là chẩn đoán SmartCA cần CCCD/chứng thư và dịch vụ thật. Không bỏ qua test bị lỗi để lấy kết quả đạt. Một số harness frontend cũ được bổ sung mock cho component độc lập mới; giữ nguyên assertion cũ và có suite riêng kiểm tra handler/render thật của panel.

Kiểm thử dùng ba PostgreSQL tạm trên localhost: `hs_phase01_test`, `hs_contract_test`, `hs_phase02_test`. Không dùng URL DB vận hành, không seed/drop dữ liệu vận hành, không gọi ngân hàng, S3 hoặc SmartCA thật. Container kiểm thử được dọn sau nghiệm thu tự động.

Log cuối tại `%TEMP%`: `hs-phase02-core-regression.log`, `hs-phase02-web-regression.log`, `hs-phase02-admin-tests.log`, `hs-phase02-web-types.log`, `hs-phase02-web-lint.log`, `hs-phase02-admin-build.log`, `hs-phase02-admin-lint.log`.

Đây là kiểm thử service/transaction/PostgreSQL/API và frontend handler/render, **chưa phải E2E trình duyệt với ngân hàng hoặc SmartCA thật**. Build admin đạt, vẫn có cảnh báo kích thước chunk; chưa thay cấu trúc bundle trong giai đoạn này. Kết quả kiểm thử không phải cam kết mọi tình huống production đều không còn lỗi.

## 7. Phạm vi dừng và bước tiếp theo

Chưa triển khai giai đoạn 03–09. F03 (`retainedAmount` lúc chấp thuận quyết toán) và F04 (gửi lại đề nghị gia hạn) vẫn chờ giai đoạn 03. Khiếu nại tổng quát, SLA, gửi thông báo thật, cọc đã nhận nhưng khách không ký/nhận nhà, biên bản bàn giao và chấm dứt sớm theo thỏa thuận vẫn theo checklist.

Không commit/push, triển khai hoặc restart dịch vụ vận hành. Giữ nguyên các thay đổi giai đoạn 01 và sửa PDF viewer có sẵn. **Dừng tại đây; chỉ làm giai đoạn 03 khi chủ dự án đồng ý.**
