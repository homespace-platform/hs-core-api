# Bàn giao giai đoạn 04 — Khiếu nại hai bên và HomeSpace

Ngày: **03/11/2026**. Phạm vi: **giai đoạn 04 / G01**, dựa trên giai đoạn 02–03. Đã triển khai backend, web, admin và đạt kiểm thử hồi quy. **Dừng trước giai đoạn 05**.

## 1. Kết quả và chính sách đã áp dụng

Người thuê và chủ nhà có thể mở, phản hồi, gửi chứng cứ, đề xuất thỏa thuận, đồng ý/từ chối, rút hoặc mở lại khiếu nại. Khi hai bên chưa thống nhất hoặc hết hạn phản hồi, có đường chuyển HomeSpace. Admin tiếp nhận, yêu cầu bổ sung, ghi kết luận và theo dõi thực hiện.

Người dùng đã chọn **48 giờ, có thể cấu hình**. Cấu hình riêng: `homespace.complaint.response-hours=${COMPLAINT_RESPONSE_HOURS:48}`. Thời hạn được lưu trên hồ sơ, dùng đồng hồ nghiệp vụ; giao diện dùng cờ quá hạn từ backend. Đổi cấu hình không viết lại hạn đã lưu. Quá hạn không tự đồng ý, xóa nợ, giữ cọc, xác nhận tiền hoặc giải phóng phòng.

| Vấn đề | Cách xử lý |
|---|---|
| Tiền đã chuyển/cọc/tháng đầu/hóa đơn/hoàn/chênh lệch | Giữ luồng đối soát thanh toán của giai đoạn 02 trên cùng nền tảng hồ sơ/lịch sử |
| Khấu trừ/quyết toán cọc | Phản đối đúng phiên bản quyết toán tự tạo/tiếp tục hồ sơ khiếu nại |
| Bàn giao, hiện trạng, sửa chữa, điều khoản, chấm dứt và vấn đề khác | Hồ sơ khiếu nại chung gắn yêu cầu thuê và hợp đồng nguồn nếu đã có |
| Sự cố an toàn hoặc bàn giao cần khẩn | Cho người mở khai báo cần chuyển HomeSpace ngay, kèm lý do; không tự áp dụng xử phạt hay xử lý tiền |
| Khiếu nại trước khi có hợp đồng | Theo dõi trên yêu cầu thuê; vẫn trao đổi/chuyển cấp được sau khi hợp đồng được tạo, giữ nguồn lúc mở làm lịch sử |

Mỗi yêu cầu thuê có tối đa một hồ sơ đang mở trong cùng nhóm vấn đề. Phản hồi tiếp vào hồ sơ đó; không nhân bản vụ việc đang xử lý. Danh sách ưu tiên hồ sơ còn mở, kèm tối đa 50 hồ sơ gần nhất để hồ sơ cũ còn hoạt động không bị che bởi lịch sử mới.

## 2. Tiến trình và quyền

Dùng lại `dispute_cases` / `dispute_case_events`; không tạo kho tranh chấp tiền khác. Chủ thể `PAYMENT` giữ nguyên trạng thái/quyền xử lý cũ. Chủ thể `RENTAL` thêm nhóm vấn đề, bước xử lý, hạn, người cần phản hồi, nguồn phiên bản quyết toán, kết luận và tiến trình thực hiện. Các trường mới nullable cho bản ghi cũ; enum trạng thái thanh toán không bị mở rộng.

| Bước | Điều kiện và kết quả |
|---|---|
| Chờ phản hồi → trao đổi | Bên được yêu cầu phản hồi gửi nội dung/chứng cứ. Người mở gửi thêm nhiều lần không kéo dài hạn của bên còn lại |
| Đề xuất thỏa thuận | Chỉ bên còn lại được đồng ý/từ chối. Đồng ý ghi thỏa thuận và đóng phần khiếu nại; không sửa hợp đồng hay số tiền bằng văn bản trao đổi |
| Chuyển HomeSpace | Có trao đổi từ bên kia trong lượt hiện tại hoặc hết hạn; khai báo khẩn được chuyển ngay ở nhóm phù hợp |
| Admin tiếp nhận | Phải có quyền ADMIN, đúng danh tính context, nhận xử lý trước khi kết luận; không tự phân xử hồ sơ mình là một bên |
| Yêu cầu bổ sung | Chỉ yêu cầu một trong hai bên thật của hồ sơ; lưu hạn và người cần phản hồi. Bên khác bình luận không làm mất yêu cầu bổ sung |
| Kết luận thông thường | Ghi căn cứ và theo dõi việc thực hiện. Hai bên có thể xác nhận hoàn tất; admin có thể đóng có lý do, người tham gia còn quyền mở lại |
| Rút | Chỉ người mở lượt khiếu nại hiện tại được rút khi chưa chuyển admin; không hoàn tác nghĩa vụ tài chính |
| Mở lại | Giữ lịch sử cũ, kiểm tra không có vụ cùng nhóm đang mở, tính lượt phản hồi mới; người mở cũ không được rút thay bên đã mở lại |

Mọi thay đổi gửi phiên bản đã xem, có khóa hàng và lịch sử theo thứ tự phiên bản. Chuyển admin vô hiệu hóa đề xuất song phương cũ. Khi mở lại, trao đổi từ lượt trước không được dùng làm căn cứ cho chuyển cấp tức thì.

API/queue thanh toán cũ được giới hạn đúng chủ thể PAYMENT; không cố đọc ID yêu cầu thuê như ID khoản tiền. Admin có tab khiếu nại chung và tab đối soát chuyển tiền, giữ các thao tác tài chính đã ổn định.

## 3. Khiếu nại khấu trừ cọc đến hoàn tiền

Luồng đã kiểm thử bằng service/transaction/PostgreSQL thật:

1. Chủ nhà gửi quyết toán khấu trừ 100.000 đ từ cọc 20 triệu.
2. Người thuê phản đối đúng phiên bản; transaction ghi trạng thái phản đối, chứng cứ và hồ sơ khiếu nại cùng lúc.
3. Chủ nhà phản hồi, người thuê chuyển HomeSpace, admin tiếp nhận.
4. Admin đề xuất khấu trừ lại 50.000 đ có căn cứ. Hệ thống dùng lại kiểm tra hóa đơn, số dư cọc, dòng khấu trừ và phiên bản nguồn; tạo một đề xuất quyết toán mới có lịch sử.
5. Chưa sinh khoản hoàn. Chủ nhà phải bấm đồng ý đề xuất đó; người thuê sau đó xác nhận bàn giao/khấu trừ/phiên bản bằng luồng quyết toán hiện có.
6. Khi đủ hai sự đồng ý, tạo nghĩa vụ hoàn 19.950.000 đ. Đã báo chuyển vẫn chưa phải thực nhận.
7. Người thuê xác nhận nhận tiền qua thanh toán; sổ cọc/đóng hợp đồng và hồ sơ khiếu nại mới hoàn tất theo nguồn thật.

Các chốt bổ sung:

- Khiếu nại quyết toán đang mở chặn ghi đè đề xuất hoặc chấp thuận để vượt qua vụ việc. Khiếu nại sửa chữa/điều khoản khác không khóa toàn bộ hợp đồng.
- Đóng khiếu nại bằng kết luận thông thường không làm đề xuất tài chính của admin tự có sự đồng ý của chủ nhà.
- Phản đối lại bằng nút quyết toán cũ cũng cập nhật hồ sơ, hủy sự đồng ý với đề xuất cũ và đưa admin xem xét lại; không để hai tiến trình lệch nhau.
- Sau khi hai bên đã chấp thuận và nghĩa vụ tiền đã tạo, không dùng phản đối kết luận để đảo tiền. Tranh chấp việc chuyển/nhận tiền tiếp tục đi qua hồ sơ thanh toán giai đoạn 02.
- Hồ sơ theo dõi khoản quyết toán chưa đóng chỉ vì có kết luận hoặc đã báo chuyển. Không tạo receipt, khoản chuyển 0 đồng hay thao tác admin nhận tiền thay người nhận.

Các luồng thuận lợi hai HĐ cọc/thuê, ký SmartCA, hóa đơn, gia hạn và sổ cọc giai đoạn 01–03 được chạy hồi quy. Nhánh phản đối đã thành khiếu nại chính thức nay đi qua hồ sơ có lịch sử thay cho việc một bên tự ghi đè nguồn.

## 4. Chứng cứ và giao diện

Chứng cứ mới dùng upload hiện có, PRIVATE, đã READY, đúng người gửi, đúng yêu cầu thuê, đúng loại ảnh/PDF, tối đa 5 tệp/lần và 15 MB/tệp. Chỉ file đã gắn vào hồ sơ/sự kiện thật mới được chia sẻ cho bên còn lại. Không chia sẻ chỉ vì người gửi đã upload một file với reference trùng.

`ComplaintEvidenceAccessChecker` kiểm tra quyền, dấu vết gắn file và ngăn xóa chứng cứ đã dùng kể cả sau khi đóng hồ sơ. Lịch sử lỗi/malformed từ chối cấp quyền đọc và chặn xóa để tránh mất bằng chứng. Chứng cứ quyết toán trước đó được chụp từ lịch sử nguồn. Lịch sử giữ từng lần phản hồi, chứng cứ gửi kèm, thỏa thuận và số tiền/phiên bản của kết luận.

Web có điểm mở/xem khiếu nại tại onboarding/yêu cầu thuê, hợp đồng, hóa đơn/quyết toán và modal thanh toán. Nút dựa trên quyền backend. Khi đang nhập phản hồi, polling không tự thay phiên bản đang xem; lỗi phiên bản giữ nội dung và tải lại để người dùng kiểm tra, không tự gửi lại POST. Phản hồi mạng cũ sau khi đổi hồ sơ/tài khoản không xóa ghi chú mới hoặc hiển thị hồ sơ khác.

Admin có lọc trạng thái, nhóm vấn đề, quá hạn, phân trang, tiếp nhận/chuyển người xử lý, chứng cứ, yêu cầu bổ sung, kết luận thông thường và đề xuất lại quyết toán. Thao tác sửa đề xuất yêu cầu kiểm tra nguồn rõ ràng, mặc định không tích sẵn. Giao diện nêu bước cần hai bên đồng ý và không có nút admin xác nhận nhận tiền.

## 5. API

Web gọi qua tiền tố gateway `/api/v1` như các API hiện có.

| API core | Chức năng |
|---|---|
| GET /rental-requests/{id}/complaints | Hồ sơ còn mở và lịch sử của đúng hai bên |
| POST /rental-requests/{id}/complaints | Mở hoặc tiếp nhận phản đối quyết toán cũ chưa có hồ sơ |
| GET /complaints/{id} | Chi tiết, hạn, quyền thao tác, lịch sử và chứng cứ |
| POST /complaints/{id}/actions/{action} | Phản hồi, thỏa thuận, chuyển cấp, rút/mở lại, đồng ý đề xuất admin, xác nhận thực hiện hoặc đề nghị xem lại kết luận |
| GET /admin/complaints | Lọc trạng thái/nhóm/quá hạn, phân trang |
| GET /admin/complaints/{id} | Chi tiết và theo dõi nguồn thực hiện |
| POST /admin/complaints/{id}/actions/{action} | CLAIM, REQUEST_INFO, DECIDE, CLOSE theo trạng thái và người được phân công |

Các route thanh toán cũ giữ nguyên. Không thêm endpoint sửa tay tiền, trạng thái thuê, chữ ký, listing hoặc receipt. Nguồn `ContractClosure` bổ sung ID hồ sơ khiếu nại và dấu đồng ý của chủ nhà cho đề xuất admin.

## 6. Kết quả kiểm thử cuối

| Kiểm tra | Kết quả |
|---|---|
| Backend listing/payment/contract/API liên quan | **671 ca được báo cáo: 670 đạt, 1 bỏ qua**, 0 failure/error, BUILD SUCCESS |
| PostgreSQL thực, nằm trong tổng backend | **92/92 đạt**: 59 ca giai đoạn trước và 33 ca khiếu nại mới |
| HTTP binding/danh tính/phân quyền khiếu nại mới, nằm trong tổng backend | **4/4 đạt**, gồm Spring Method Security thật |
| Web hồi quy hợp đồng/hóa đơn/cọc/thanh toán/onboarding/khiếu nại | **182/182 đạt**, gồm 13 ca mới cho khiếu nại |
| Admin khiếu nại + hồi quy đối soát/sổ cọc | **18/18 đạt**, gồm 9 ca khiếu nại mới |
| TypeScript web, build admin gồm typecheck | Đạt |
| ESLint các file sửa/thêm giai đoạn 04 | Đạt |
| Kiểm tra diff | Đạt |

Ca bỏ qua duy nhất là chẩn đoán SmartCA cần CCCD/chứng thư/dịch vụ thật. Không bỏ qua test đang lỗi để lấy kết quả xanh. Harness cũ chỉ bổ sung mock component độc lập hoặc trỏ suite thanh toán tới panel giữ nguyên sau khi thêm tab; assertion cũ được giữ. Kiểm tra nhãn lịch sử quyết toán cuối được chạy riêng, không cộng trùng vào tổng web.

Các ca mới gồm: các nhóm khiếu nại; trước khi có hợp đồng; hết hạn đúng ranh giới; không tự kéo dài SLA; cấu hình 12/48 giờ; khẩn cấp; rút/mở lại; hai đề nghị/thao tác admin đồng thời; phiên bản cũ; người thứ ba; admin là một bên/khác danh tính/thiếu quyền; đúng người cần bổ sung; kết luận và xác nhận thực hiện; tạo đề xuất cọc nhưng chưa sinh tiền; hai sự đồng ý và thực nhận; phản đối qua endpoint cũ; đóng kết luận không vượt consent; lỗi audit rollback; chứng cứ chưa gắn/public/sai người; audit hỏng phải giữ bảo vệ file; queue tiền và khiếu nại không lẫn chủ thể.

Năm PostgreSQL tạm localhost dùng DB riêng `hs_phase01_test`, `hs_contract_test`, `hs_phase02_test`, `hs_phase03_test`, `hs_phase04_test`. Không đọc/sửa/seed/drop DB vận hành. Service, dữ liệu và transaction lõi chạy thật; ngân hàng/QR, listing/parking có mock để tránh tác động ngoài và kiểm tra rollback/đếm thao tác. Không gọi SmartCA, ngân hàng hay S3 thật. Container kiểm thử được dọn sau khi chốt kết quả.

Log ở %TEMP%: `hs-phase04-core-final.log`, `hs-phase04-web-regression.log`, `hs-phase04-web-types.log`, `hs-phase04-web-lint.log`, `hs-phase04-admin-tests.log`, `hs-phase04-admin-build.log`, `hs-phase04-admin-lint.log`.

Đây chưa phải E2E trình duyệt với nhà cung cấp thật. Admin build vẫn có cảnh báo kích thước chunk. Bản sửa không cam kết mọi trường hợp production đều đã được mô phỏng.

## 7. Điểm dừng

Chưa làm giai đoạn 05–09. Mốc SLA và queue đã có; **chưa triển khai gửi thông báo/nhắc hạn tự động**, outbox hay worker của giai đoạn 05. Chưa làm biên bản bàn giao chính thức, xử lý khách mất liên lạc sau cọc hoặc cơ chế chấm dứt sớm mới. Khiếu nại các nhóm đó có thể ghi nhận và theo dõi; không tự tạo quyền xử lý tiền thay cho các workflow chưa được duyệt.

Không commit/push, deploy hay restart dịch vụ vận hành; không sửa dữ liệu lịch sử hàng loạt. **Dừng tại giai đoạn 04, chờ chủ dự án duyệt trước khi làm giai đoạn 05.**
