# Giai đoạn 01 — Bản nháp khớp hồ sơ thuê

Ngày thực hiện: **03/11/2026**. Phạm vi được duyệt khi chủ dự án trả lời “tiếp” sau báo cáo giai đoạn 00.

Trạng thái: **đã triển khai và đạt regression; chờ chủ dự án nghiệm thu**. Chưa bắt đầu giai đoạn 02–09.

## 1. Lỗi được xử lý

API `updateRevision` trước đây lưu các map do client gửi lên. Vì vậy, một hồ sơ thuê 3 tháng có thể có bản nháp ghi 12 tháng, tiền cọc khác số tiền đã chốt hoặc số xe khác lượt giữ chỗ. Kiểm tra đủ trường không phát hiện được sự lệch này.

Thêm `ContractDraftIntegrity` để đối chiếu hợp đồng thuê gốc trước khi lưu, kết xuất, gửi người thuê, bắt đầu ký SmartCA của từng bên và hoàn tất nhúng chữ ký. Ký nội bộ cũng phải qua đối chiếu. HĐ cọc, phụ lục và văn bản gia hạn tiếp tục dùng quy tắc riêng hiện có.

## 2. Nguồn dữ liệu và phạm vi chỉnh sửa

| Dữ liệu | Xử lý trong giai đoạn 01 |
|---|---|
| Chủ thể và liên kết tài sản | Đối chiếu ID với hồ sơ thuê; thông tin hai bên/tài sản giữ bản chụp khởi tạo của hợp đồng. Không đọc profile/listing mới để thay lịch sử. |
| Ngày bắt đầu, kết thúc, thời hạn, ngày bàn giao | Dựng cùng phép tính với hồ sơ thuê. Ngày kết thúc in trên văn bản là ngày ở cuối cùng; mốc kết thúc vận hành vẫn exclusive. |
| Tiền thuê và tiền cọc, cả số và bằng chữ | Dựng từ số tiền đã chốt trong hồ sơ. Không sửa receipt, sổ cọc hoặc khoản đã xác nhận để làm văn bản khớp. |
| Người ở, xe máy, ô tô | Dùng số đã chốt; payload sửa bản nháp không tăng sức chứa/giữ chỗ. |
| Thanh toán ban đầu | Dựng từ khoản thanh toán của hệ thống; không nhận số tiền/chứng từ mới từ form. Hồ sơ legacy không có khoản liên kết giữ bản chụp nguồn để đối chiếu. |
| Phí dịch vụ, nội thất, tiện ích, loại công tơ và nội quy từ bài đăng | API nháp giữ nguyên bản chụp khởi tạo. Giao diện nháp hiện không có trình thương lượng các nhóm này; không mở thêm một luồng thay đổi ngày/tiền/tài sản trong đợt sửa F01. Các luồng phụ lục hiện có được giữ. |
| Chỉ số đầu kỳ | Cho hoàn thiện; kiểm tra số không âm, tối đa 3 chữ số thập phân và giới hạn giá trị. Giữ mô tả nước tính theo đầu người do hệ thống tạo. Thiếu công tơ bắt buộc vẫn bị chặn ở kiểm tra đủ dữ liệu. |
| Thỏa thuận riêng, cách tính và mức phí chậm trả | Vẫn cho sửa trước ký, có giới hạn/validation. Hạn, ngày ân hạn và trần phí tiếp tục do quy tắc hệ thống tính. |

Tương thích với form cũ: vẫn nhận payload đầy đủ nếu trường được bảo vệ không đổi; chuẩn hóa số tiền tương đương; giữ số 0 đầu trong CCCD/tài khoản. Payload chỉ gửi trường cần sửa cũng được hỗ trợ, không xóa các nhóm bị bỏ qua.

Với bản nháp cũ lệch nguồn: chặn kết xuất/ký bằng lỗi rõ trường. Khi còn DRAFT, lưu một cập nhật hợp lệ chỉ chứa các trường được phép sẽ tạo revision mới từ nguồn chuẩn và giữ chỉ số/thỏa thuận/phí chậm trả hiện có. Không viết lại revision/PDF đã ký. Nếu thiếu bản chụp gốc hoặc hồ sơ nguồn không hợp lệ thì cần kiểm tra nguồn, không tự suy đoán từ client.

Thỏa thuận riêng vẫn là nội dung hai bên phải đọc: kiểm tra này đảm bảo các trường cấu trúc khớp nguồn, không tự diễn giải mọi câu chữ tự do thành thay đổi nghiệp vụ.

## 3. Đồng thời, lịch sử và giao diện

- Frontend gửi `baseRevisionId` và đúng trường đang chỉnh; một tab cũ nhận lỗi 6072 thay vì ghi đè tab đã lưu trước.
- Backend khóa hồ sơ thuê rồi hợp đồng, đọc mới entity sau khi lấy khóa; tránh dùng revision cũ từ persistence context sau lúc chờ.
- Trước ghi yêu cầu SmartCA, so revision hiện hành với revision của PDF đã chuẩn bị; không so hai tham chiếu có thể cùng trỏ đến một entity vừa được refresh.
- Chặn sửa khi yêu cầu ký đang chạy, đã ký hoặc còn kết quả cần đối soát, kể cả nhãn hợp đồng bị đưa về DRAFT.
- Chỉ làm cũ PREVIEW/OFFICIAL khi tạo revision; không làm cũ file chữ ký lịch sử.
- Giữ `schemaVersion` của bản đang sửa.
- Giao diện nêu rõ trường lấy từ hồ sơ, đường nhận nhà sớm và đường phụ lục sau ký. Lưu thỏa thuận thất bại giữ lại nội dung đang sửa, không báo lưu thành công.
- Sửa thêm lỗi cùng phạm vi: duyệt nhận nhà sớm trước đây chỉ đổi ngày bắt đầu/kết thúc, để lại ngày bàn giao cũ. Luồng này nay dùng chung phép tính lịch và tiếp tục tạo revision mới, giữ bản cũ.

## 4. Trường hợp kiểm thử

| Nhóm | Trường hợp đã có test |
|---|---|
| Lịch thuê | Hồ sơ 3 tháng/bản nháp 12 tháng; đổi ngày đầu/cuối/bàn giao; sửa cả nhãn thời hạn; đối chiếu sau nhận sớm. |
| Tiền và danh tính | Sửa tiền thuê/cọc bằng số hoặc bằng chữ; CCCD/tài khoản mất số 0 đầu; người/tài sản không khớp hồ sơ; sửa chứng từ. |
| Dữ liệu cấu trúc | Thêm key lạ, null trường được bảo vệ, object thay scalar; sửa người/xe, phí, tài sản bàn giao, tiện ích, nội quy hoặc loại công tơ. |
| Tương thích | Form đầy đủ không đổi; số tiền tương đương; cập nhật riêng công tơ/thỏa thuận/phí chậm trả; giữ dữ liệu không gửi; cọc 0 không tạo giao dịch giả. |
| Công tơ | Số âm, chữ, NaN/Infinity, giá trị quá lớn, quá 3 chữ số thập phân; số 0 và mô tả nước theo người hợp lệ. |
| Dữ liệu cũ | Revision lệch nguồn bị chặn trước xuất/ký; lưu mới từ nguồn chuẩn không sửa tiền/listing; thiếu bản khởi tạo không fallback sang payload. |
| Chữ ký | Cả hai vai SmartCA kiểm tra lại; CREATED/PENDING/PROVIDER_SIGNED/EMBEDDING/SIGNED; lỗi chưa rõ/provider signature còn tồn tại không được sửa. |
| Ranh giới ký | PDF cũ bị từ chối sau refresh dù context và entity cùng tham chiếu; kết quả provider của revision cũ sai nguồn không tự kích hoạt thuê. |
| PostgreSQL thực | Lưu tạo revision và làm cũ preview; payload sai không đổi DB; lỗi ghi tài liệu rollback và retry; hai tab cùng lưu chỉ một tab thắng; giao dịch ký giữ khóa khiến sửa bị từ chối. |
| Luồng hiện có | HĐ cọc độc lập, chọn file/PDF/revision, nhận sớm/giữ xe, tạo nháp tự động và phục hồi lỗi mẫu/PDF, thanh toán, hóa đơn, phụ lục/gia hạn. |

Các unit test renderer hiện có mock bộ đối chiếu để giữ đúng phạm vi test; policy thật có suite riêng và được ghép với `ContractServiceImpl` thật ở test PostgreSQL. Các assertion cũ được giữ; fixture cũ được cập nhật cho dependency HĐ cọc, snapshot thực và entity manager.

## 5. Kết quả nghiệm thu

Lượt regression cuối: **BUILD SUCCESS**. Log backend: `%TEMP%/hs-phase01-core-regression.log`; frontend: `%TEMP%/hs-phase01-web.log`; typecheck: `%TEMP%/hs-phase01-types.log`.

| Kiểm tra | Kết quả cuối |
|---|---|
| Backend: listing, payment, contract và API liên quan | 554 ca được báo cáo: **553 đạt, 1 bỏ qua**, 0 failure/error |
| PostgreSQL thực, nằm trong tổng backend | **26/26 đạt**: 5 chấp thuận yêu cầu thuê, 3 kho HĐ cọc, 12 onboarding, 6 tính nhất quán bản nháp |
| Policy mới và ranh giới SmartCA | **39 + 2 ca đạt**, nằm trong tổng backend |
| Frontend | **56/56 đạt**, 0 bỏ qua |
| ESLint các file sửa | Đạt |
| TypeScript `--noEmit --incremental false` | Đạt |
| Kiểm tra diff | Đạt |

Tổng **609 ca đạt**, không cộng lặp các ca nằm trong bảng. Ca bỏ qua duy nhất là chẩn đoán SmartCA cần CCCD/chứng thư và dịch vụ thật; không bỏ qua một ca regression bị lỗi để lấy kết quả xanh. Số ca tham số hóa của suite chấp thuận thuê tăng từ một mục bị skip sang các trường hợp thực thi khi bật PostgreSQL; kết quả cuối dùng số thực chạy từ log Maven.

PostgreSQL chạy trong hai container tạm riêng trên localhost: `hs_phase01_test` cho listing/onboarding/draft và `hs_contract_test` cho kho HĐ cọc. Không dùng URL CSDL vận hành, không seed/drop dữ liệu vận hành và không gọi nhà cung cấp ký thật. Hai container được dọn sau kiểm thử.

Đây là kiểm thử tự động ở lớp service/DB/API và frontend handler/render, **chưa phải E2E trình duyệt với SmartCA thật**. Kết quả không thay cho nghiệm thu tích hợp nhà cung cấp ở giai đoạn cuối. Các sửa PDF viewer có sẵn từ lượt trước và sửa đường dẫn prompt ở giai đoạn 00 được giữ nguyên, không tính là thay đổi nghiệp vụ mới của giai đoạn 01.

## 6. Phạm vi dừng

Không thay cấu hình hạn, ngoại lệ cọc 0, công thức xử lý tranh chấp, khấu trừ cọc hoặc chính sách gửi lại gia hạn. F02–F04 và các tính năng giai đoạn 02–09 chưa triển khai.

Chỉ chuyển sang giai đoạn 02 sau khi bàn giao kết quả giai đoạn 01 và được chủ dự án đồng ý.
