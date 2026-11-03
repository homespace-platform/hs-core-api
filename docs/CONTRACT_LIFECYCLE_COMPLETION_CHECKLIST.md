# Checklist hoàn thiện vòng đời hợp đồng HomeSpace

Ngày lập: **03/11/2026**. Trạng thái: **giai đoạn 01–04 và 06–08 đã triển khai; R01–R03 đã khắc phục, bổ sung PDF/ký riêng, tính theo ngày và bộ kiểm thử. Thông báo bỏ qua; nghiệm thu SmartCA thật chờ sandbox theo người dùng. Kết quả và giới hạn xem báo cáo hoàn thiện.**

Đối chiếu lại ngày 03/11/2026: [Báo cáo theo đủ 10 giai đoạn, phần còn mở và kiểm thử chạy lại](CONTRACT_LIFECYCLE_REAUDIT_2026-11-03.md). Sau khi chủ dự án chốt và triển khai [miễn ký HĐ cọc khi không cọc/cọc 0 đồng](CONTRACT_ZERO_DEPOSIT_IMPLEMENTATION_REPORT.md), file có **103 ô**, gồm 6 ô bổ sung so với mốc 97 ban đầu; bỏ 8 ô thông báo còn **95 ô: 93 đã đánh dấu, 2 còn mở**. Đây là số ô trong checklist, không phải tỷ lệ nghiệm thu production; cập nhật prompt/Word và giới hạn T26 được theo dõi riêng trong báo cáo.

Hậu kiểm và kết quả khắc phục: [R01–R03 và giới hạn kiểm thử](D:/Workspace/homespace/hs-core-api/docs/CONTRACT_LIFECYCLE_PHASE_06_08_REVIEW_GAPS.md).

Bàn giao ba hạng mục mới: [Cọc chưa ký, nhận nhà và chấm dứt sớm](D:/Workspace/homespace/hs-core-api/docs/CONTRACT_LIFECYCLE_PHASE_06_08_REPORT.md).

Bàn giao giai đoạn 04: [Khiếu nại hai bên và HomeSpace](D:/Workspace/homespace/hs-core-api/docs/CONTRACT_LIFECYCLE_PHASE_04_REPORT.md).

Bàn giao giai đoạn 03: [Sổ cọc và gửi lại đề nghị gia hạn](D:/Workspace/homespace/hs-core-api/docs/CONTRACT_LIFECYCLE_PHASE_03_REPORT.md).

Bàn giao giai đoạn 02: [Giải quyết tranh chấp thanh toán](D:/Workspace/homespace/hs-core-api/docs/CONTRACT_LIFECYCLE_PHASE_02_REPORT.md).

Bàn giao giai đoạn 01: [Bản nháp khớp hồ sơ thuê](D:/Workspace/homespace/hs-core-api/docs/CONTRACT_LIFECYCLE_PHASE_01_REPORT.md).

Bàn giao giai đoạn 00: [Nguồn dữ liệu, quyền, chuyển trạng thái và kết quả kiểm thử](D:/Workspace/homespace/hs-core-api/docs/CONTRACT_LIFECYCLE_PHASE_00_RULES.md). Mục được đánh dấu hoàn thành chỉ xác nhận phần khảo sát/tài liệu đã làm; quy tắc đề xuất cần duyệt và lỗi nghiệp vụ vẫn được ghi rõ.

Căn cứ: [Review vòng đời hợp đồng ngày 02/11/2026](D:/Workspace/homespace/hs-core-api/docs/CONTRACT_LIFECYCLE_REVIEW_2026-11-02.md). Mã F01–F04 và G01–G04 bên dưới tham chiếu tài liệu này.

Mục tiêu: hoàn thiện cả luồng thuận lợi và luồng phát sinh từ yêu cầu thuê, ký HĐ cọc, ký HĐ thuê đến bàn giao, thanh toán, khiếu nại và kết thúc nghĩa vụ. Mỗi giai đoạn phải có API, giao diện cần thiết và kiểm thử; không đánh dấu hoàn thành chỉ vì đã thêm endpoint.

Bàn giao lượt hoàn thiện: [Báo cáo](CONTRACT_LIFECYCLE_GAPS_COMPLETION_REPORT.md), [Hướng dẫn vận hành / kiểm thử lại](RENTAL_WORKFLOWS_OPERATIONS.md), [Ma trận T01–T40](CONTRACT_LIFECYCLE_ACCEPTANCE_MATRIX.md).

## Thứ tự thực hiện

| Thứ tự | Hạng mục | Ưu tiên | Phụ thuộc chính | Kết quả phải bàn giao |
|---|---|---|---|---|
| 00 | Chốt nguồn dữ liệu và quy tắc chuyển trạng thái | Nền tảng | Review hiện có | Ma trận điều khoản, quyền và hành động hợp lệ |
| 01 | Sửa điều khoản bản nháp lệch hồ sơ thuê — F01 | P1 | 00 | Văn bản, lịch thuê, tiền và xe khớp nhau |
| 02 | Mở đường giải quyết `DISPUTED` — F02; nền tảng khiếu nại | P1 | 00, 01 | Khoản tranh chấp có thể tiếp tục xử lý bằng API/UI đúng quyền |
| 03 | Hoàn thiện sổ cọc và gửi lại gia hạn — F03, F04 | P2 | 00, 01; sổ cọc đối chiếu với 02 | Số tiền đầy đủ; gia hạn không bị khóa bởi đề nghị cũ |
| 04 | Hoàn thiện khiếu nại người thuê → chủ nhà → admin — G01 | P1 | 02, 03 | Hồ sơ vụ việc, chứng cứ, chuyển cấp và quyết định có lịch sử |
| 05 | Thông báo/nhắc hạn — **bỏ qua theo yêu cầu chủ dự án** | Hoãn | Không phải phụ thuộc của 06–08 | Không triển khai trong lượt này |
| 06 | Xử lý cọc đã nhận nhưng không hoàn tất ký/nhận nhà | P1 | 01–04; bỏ phụ thuộc 05 theo yêu cầu | Có lối kết thúc an toàn, không giữ phòng vô thời hạn |
| 07 | Biên bản nhận nhà và trạng thái nghĩa vụ HĐ cọc — G04 | P2 | 01, 03–06 | Bằng chứng bàn giao; phân biệt đã ký và đã nhận nhà |
| 08 | Chấm dứt sớm theo thỏa thuận, không cần có nợ — G03 | P2 | 02–07 | Kết thúc thuê, bàn giao và quyết toán đúng thỏa thuận |
| 09 | Nghiệm thu toàn luồng trên DB riêng và giao diện | Bắt buộc | 01–08 | Báo cáo test, các luồng không còn điểm kẹt đã biết |

**Phạm vi được duyệt:** các giai đoạn trước triển khai lần lượt. Sau 06–08, chủ dự án yêu cầu hoàn thành các điểm còn thiếu, tiếp tục bỏ qua thông báo và chuẩn bị code/bộ kiểm thử SmartCA để chạy sau khi có sandbox. Lượt hoàn thiện gồm R01–R03, PDF/ký riêng, tính thuê theo ngày, bộ kiểm thử riêng và tài liệu; không triển khai thông báo, không deploy hoặc thay đổi CSDL vận hành.

## 00 — Chốt quy tắc chung trước khi sửa

- [x] Lập bảng nguồn dữ liệu cho từng trường: danh tính hai bên, tài sản, ngày bắt đầu, thời hạn, tiền thuê, tiền cọc, phí, xe, công tơ và chính sách.
- [x] Phân biệt dữ liệu yêu cầu ban đầu, điều khoản đã được hai bên thống nhất, snapshot văn bản và sổ tiền thực nhận. Không xem một map client gửi lên là nguồn có quyền thay đổi tất cả dữ liệu.
- [x] Chốt trường được chỉnh trực tiếp khi còn nháp và trường phải đi qua đề nghị thay đổi có thỏa thuận. Sau khi ký phải dùng văn bản điều chỉnh thích hợp, giữ nguyên bản đã ký. Phạm vi triển khai cụ thể ghi tại báo cáo 01; chủ dự án đã duyệt tiếp bằng yêu cầu “tiếp”.
- [x] Giữ yêu cầu đã thống nhất: HĐ cọc và HĐ thuê là hai văn bản, hai lượt ký riêng; cọc và nhận nhà cùng ngày vẫn không gộp thành một lượt ký.
- [x] Chủ dự án đã chốt: bài đăng không đặt cọc hoặc số cọc đã thống nhất bằng 0 thì bỏ qua ký HĐ cọc, không bổ sung văn bản giữ chỗ 0 đồng. Đã đồng bộ API/UI và hồi quy từ nhận tiền thuê tới tạo HĐ thuê; không dùng việc miễn cọc thay xác nhận thực nhận tiền thuê. Bản ghi miễn cọc 0 đồng không phải chứng từ chuyển tiền. Chi tiết tại báo cáo miễn cọc.
- [x] Lập ma trận quyền tenant/landlord/admin/system cho mọi thao tác có thay đổi trạng thái hoặc tiền.
- [x] Tách trạng thái chữ ký, quyền thuê/giữ chỗ, bàn giao và nghĩa vụ tài chính trong ma trận; không dùng một nhãn `ACTIVE`/`CLOSED` để suy ra mọi nghĩa vụ đã xong. Các model mới sẽ chỉ triển khai tại giai đoạn được duyệt tương ứng.
- [x] Các chính sách này đã được cụ thể hóa qua các giai đoạn được duyệt: SLA khiếu nại 48 giờ có cấu hình, quá hạn không tự đồng ý; hủy trước thuê mới hoàn toàn bộ thực nhận theo điều kiện và giữ nhánh chính sách cũ đã ký; gia hạn được gửi lại sau từ chối/hết hạn trong cửa sổ; chấm dứt thỏa thuận không mặc định mất cọc, quyết toán dựa trên phiên bản đồng ý và thực nhận. Xem báo cáo 03, 04, 06–08 và báo cáo hoàn thiện. Ngoại lệ văn bản cọc 0 đồng đã được chốt ở ô riêng.

**Nghiệm thu:** có bảng chuyển trạng thái ghi rõ ai được làm, điều kiện, tác động đến tiền/listing/xe/văn bản, cách retry và cách xử lý lỗi. Không có hành động “admin sửa tay trạng thái cho chạy tiếp”.

## 01 — Sửa F01: điều khoản bản nháp phải khớp hồ sơ nghiệp vụ

**Điểm bắt đầu:** `ContractServiceImpl.updateRevision`, kiểm tra trước xuất PDF/ký; đối chiếu `RentalRequest`, `ContractLeaseCalc`, thanh toán và giữ chỗ xe.

- [x] Thay việc chấp nhận các map tùy ý bằng danh sách trường được sửa và xác thực kiểu dữ liệu/giá trị ở backend.
- [x] Danh tính và liên kết tài sản lấy từ nguồn hệ thống; chặn thay người ký/tài sản bằng payload sửa snapshot.
- [x] Ngày bắt đầu, thời hạn, tiền thuê, cọc và số xe: nếu khác hồ sơ đã chấp thuận thì yêu cầu đúng luồng thay đổi hoặc từ chối với lý do rõ ràng. Bản đầu không mở thêm khả năng thương lượng ngày/tiền ngay trong API sửa snapshot.
- [x] Khi một thay đổi nghiệp vụ được chấp thuận: cập nhật nguồn điều khoản có hiệu lực và các dữ liệu vận hành liên quan trong cùng transaction; giữ lại yêu cầu và phiên bản trước đó làm lịch sử. Giai đoạn 01 giữ luồng nhận sớm hiện có, đồng bộ thêm ngày bàn giao và kiểm tra rollback; không mở API sửa tiền trước ký.
- [x] Khoản đã nhận không bị sửa số tiền/lịch sử. Payload nháp không được đổi nghĩa vụ đã chốt; các luồng thu chênh lệch/hoàn hiện có được giữ, không tạo thêm luồng thay đổi sau thu tiền trong giai đoạn này.
- [x] Kiểm tra nhất quán lại trước xuất PDF và bắt đầu ký; chặn đổi điều khoản khi chữ ký đang xử lý/chưa rõ kết quả.
- [x] Bản nháp đổi hợp lệ phải làm preview cũ mất hiệu lực; văn bản đã ký vẫn nguyên vẹn.
- [x] Frontend chỉ mở những trường backend cho sửa; giải thích đường đi cho yêu cầu thay đổi thay vì cho sửa rồi báo lỗi chung.
- [x] Thêm regression tái hiện hồ sơ 3 tháng nhưng snapshot 12 tháng; sửa ngày khác, tiền cọc khác, giả mạo danh tính và cập nhật đồng thời lúc ký.

**Nghiệm thu:** không thể ký một hợp đồng mà ngày/thời hạn/số tiền trên văn bản khác nguồn vận hành. Đường thay đổi được phép cũng phải có test rollback toàn bộ khi cập nhật tiền hoặc xe thất bại.

## 02 — Sửa F02: giải quyết khoản thanh toán `DISPUTED`

**Điểm bắt đầu:** `PaymentRequestService`, các nhánh đối soát onboarding và API thanh toán. Xây phần dữ liệu vụ việc tối thiểu dùng lại ở bước 04.

- [x] Tạo hồ sơ tranh chấp liên kết đúng nghĩa vụ tiền, yêu cầu thuê/hợp đồng và hai bên; lưu người mở, lý do, bằng chứng và lịch sử. Chứng cứ được chụp theo hồ sơ, không làm đổi căn cứ quyết định cũ khi có tranh chấp mới.
- [x] Dùng cùng nền tảng cho tiền cọc, tháng đầu, hóa đơn định kỳ, hoàn tiền và chênh lệch cọc; xử lý hành động hợp lệ theo loại khoản tiền, không tạo cơ chế giải quyết riêng rời rạc cho từng màn hình.
- [x] Lưu trạng thái trước tranh chấp và phiên bản hiện tại để quyết định có căn cứ; không luôn mở lại về `AWAITING_TRANSFER`. Dữ liệu cũ thiếu trạng thái nguồn được ghi là chưa biết.
- [x] Định nghĩa hành động: nộp thêm chứng cứ, phản hồi, thống nhất xử lý, mở lại bước báo chuyển/xác nhận, chuyển admin. Hai bên không đồng thuận thì không cho một bên tự đóng vụ việc bất lợi cho bên kia.
- [x] Cho người nhận xác nhận thực nhận qua thao tác riêng khi đủ điều kiện. Quyết định của admin không tự đồng nghĩa tiền đã được chuyển/đã nhận.
- [x] Giữ receipt, số tiền và nghĩa vụ nguồn; chặn quyết định sửa/xóa nợ trực tiếp. Điều chỉnh/hủy, tạo chênh lệch/hoàn tiếp tục dùng nghiệp vụ tài chính có căn cứ và lịch sử hiện có; giai đoạn 02 không mở trình sửa nợ cho admin.
- [x] Khóa bản ghi và kiểm tra phiên bản khi giải quyết; chống bấm lặp, hai admin xử lý cùng lúc và nhận tiền đồng thời. Test DB kiểm tra cả object đã đọc nhưng bị cũ trong persistence context.
- [x] Áp dụng hạn chế theo đúng phạm vi vụ việc. Giữ các chốt kết thúc/giữ cọc/thu hồi phòng hiện có; không làm sống lại hồ sơ đã hủy hoặc listing thuộc lượt thuê khác. Nghĩa vụ hoàn của hồ sơ đã kết thúc vẫn xử lý độc lập.
- [x] Web hiển thị lý do đang đối soát, ai cần phản hồi, lịch sử và thao tác hợp lệ. Admin có hàng đợi đối soát thanh toán tối thiểu, tiếp nhận và quyết định đúng quyền.
- [x] Thêm test đầy đủ: mở dispute → phản hồi → thống nhất/chuyển admin → quyết định → báo chuyển/xác nhận hợp lệ → hoàn tất hóa đơn/hoàn tiền. Regression cuối: 608 ca backend đạt/1 chẩn đoán SmartCA bỏ qua, 163 web và 6 admin đạt; gồm 42 ca PostgreSQL thực trong tổng backend.

**Nghiệm thu:** `MONTHLY_RENT`, `DEPOSIT_REFUND`, khoản hoàn trước thuê và điều chỉnh cọc đều có đường xử lý tiếp bằng API/UI. Không cho phép chỉ cần đổi enum là vượt qua chứng từ hoặc quyền xác nhận tiền.

## 03 — Sửa F03 và F04

### 03A. Sổ cọc đầy đủ khi quyết toán

- [x] `acceptClosure` ghi `retainedAmount`, lý do và thời điểm cho cả khấu trừ một phần/toàn bộ, phù hợp với hai bên đã chấp thuận. Mở rộng cột căn cứ lên 2.000 ký tự theo giới hạn quyết toán.
- [x] Phân biệt tiền khấu trừ từ cọc với khoản phải trả thêm vượt cọc; không ghi khoản chưa thu thành cọc đã giữ lại. Đối chiếu lại số dư và phép tính trước chấp thuận phiên bản.
- [x] Phân biệt tiền đang giữ, khoản sẽ hoàn và khoản đã hoàn; chỉ xác nhận tiền đã hoàn sau đúng bước thực nhận. Không cọc/cọc thực giữ bằng 0 thì không tạo giao dịch hoàn cọc, không đặt hạn hoàn nếu không còn khoản tiền phải chuyển, không ghi giữ lại cọc 0 đồng; vẫn cần hóa đơn cuối, trả nhà và quyết toán khoản phải trả nếu có. Cọc bổ sung đã thực nhận sau đó vẫn quyết toán theo số dư thật.
- [x] Kiểm tra cân đối số tiền từ cọc thực nhận cộng/trừ các điều chỉnh đã xác nhận. Trạng thái `REFUNDED` không xóa dấu vết khoản đã khấu trừ trước đó.
- [x] Thống nhất API và cách hiển thị sổ cọc giữa onboarding, hợp đồng, quyết toán và admin; không tự suy diễn null là đã hoàn/không khấu trừ. Hồ sơ cũ thiếu số giữ lại được ghi rõ chưa có dữ liệu, không tự backfill khi chưa đối chiếu.
- [x] Test khấu trừ 0, một phần, toàn bộ, vượt cọc; hoàn bị từ chối/tranh chấp; xác nhận trùng; lỗi tạo nghĩa vụ hoàn phải rollback. Có test chấp thuận đồng thời và object đã đọc bị cũ.

**Nghiệm thu:** cọc 20 triệu, khấu trừ 100 nghìn phải ghi đủ khoản giữ lại 100 nghìn và nghĩa vụ hoàn 19,9 triệu; khi hoàn xong vẫn truy vết đủ cả hai.

### 03B. Gia hạn được gửi lại đúng điều kiện

- [x] Sửa điều kiện chặn gia hạn để xét trạng thái đề nghị, không chặn mọi bản ghi cũ có cùng ngày bắt đầu.
- [x] Cho gửi đề nghị mới sau `REJECTED`/`EXPIRED` nếu vẫn trong cửa sổ và không có đề nghị khác đang xử lý/đã áp dụng cho kỳ đó. Giữ cutoff và các chốt phụ lục/chấm dứt hiện có.
- [x] Giữ nguyên bản bị từ chối/hết hạn; không sửa nó thành đề nghị mới.
- [x] Một kỳ không được có hai đề nghị còn hiệu lực đồng thời; kiểm tra race hai lần gửi/duyệt/ký, đọc lại dữ liệu sau khi chờ khóa hợp đồng gốc.
- [x] Gửi lại không thu cọc hoặc tiền thuê đầu kỳ lần nữa; giữ chỗ xe và thời hạn chỉ đổi ở bước hợp lệ.
- [x] Frontend mở lại nút theo quyền từ backend, hiển thị lịch sử, nội dung đề nghị và thời điểm chủ nhà phản hồi; giải thích gửi lại sau từ chối/hết hạn.
- [x] Test bị từ chối rồi đổi 3 tháng thành 1 tháng; hết hạn phản hồi nhưng còn hạn ký; hết cutoff; đang chấm dứt; callback ký muộn của đề nghị cũ.

**Nghiệm thu:** có thể gửi lại khi đủ điều kiện, đồng thời không kéo dài thuê/giữ xe hai lần hoặc làm sống lại đề nghị cũ.

## 04 — Hoàn thiện khiếu nại người thuê → chủ nhà → admin

**Phụ thuộc:** dùng lại nền tảng vụ việc và hành động giải quyết từ bước 02; không tạo thêm một bảng/cơ chế tranh chấp tiền tách biệt.

Giai đoạn 04 mở rộng trang admin thành hai tab khiếu nại hồ sơ thuê/đối soát chuyển tiền, dùng chung bảng hồ sơ và audit. SLA được chủ dự án chọn: 48 giờ, có cấu hình riêng; quá hạn không tự chấp thuận/xử lý tiền. Thông báo tự động vẫn ở giai đoạn 05.

- [x] Người thuê/chủ nhà mở được vụ việc đúng quyền. Tiền chuyển dùng luồng 02; khấu trừ/quyết toán cọc tự tạo hồ sơ từ phản đối; các nhóm bàn giao/hiện trạng/sửa chữa/điều khoản/chấm dứt có hồ sơ chung.
- [x] Có tiến trình chờ phản hồi → trao đổi/đề xuất → chuyển admin → tiếp nhận/bổ sung → kết luận/theo dõi → đóng; rút trước chuyển cấp và mở lại có lý do, không hoàn tác tiền.
- [x] Chủ nhà phản hồi, giữ lịch sử và chứng cứ hai bên; trao đổi cũ không thay thế lượt phản hồi khi mở lại.
- [x] Chuyển HomeSpace sau khi chưa thống nhất/quá SLA; khai báo khẩn chỉ dành cho an toàn/bàn giao có lý do.
- [x] Admin lọc trạng thái/nhóm/quá hạn, phân trang, tiếp nhận/chuyển người xử lý, đọc chứng cứ, yêu cầu đúng bên bổ sung, kết luận và theo dõi thực hiện.
- [x] Chứng cứ PRIVATE/READY, đúng người và yêu cầu thuê, được gắn vào hồ sơ thật mới chia sẻ; giữ bảo vệ xóa kể cả sau đóng.
- [x] Quyết định, đề xuất tài chính và nghĩa vụ phát sinh có căn cứ, người, phiên bản và thời điểm; giữ phản hồi cũ, nguồn và dấu đồng ý riêng.
- [x] Có rút/đóng/mở lại và khóa một hồ sơ đang mở cho mỗi nhóm vấn đề trên yêu cầu thuê. Người mở cũ không được rút thay bên mở lại.
- [x] Đóng vụ việc không tự xóa nợ/nhận tiền/trả listing. Đóng kết luận cũng không vượt sự đồng ý của chủ nhà với đề xuất tiền của admin.
- [x] Admin có hai tab thực tế; web có điểm vào tại yêu cầu thuê, hợp đồng, hóa đơn/quyết toán và modal thanh toán.
- [x] Test quyền, SLA, phiên bản cũ, hai quyết định đồng thời, chứng cứ và rollback. Lượt cuối: 670 backend đạt/1 SmartCA bỏ qua, gồm 92 PostgreSQL; 182 web và 18 admin đạt.

**Nghiệm thu:** chạy được một vụ “tenant phản đối khấu trừ cọc → chủ nhà không đồng ý → gửi admin → kết luận → tạo nghĩa vụ hợp lệ → xác nhận thực nhận”. Cả ba bên nhìn thấy tiến trình phù hợp quyền của mình.

## 05 — Thông báo và nhắc hạn thật — BỎ QUA

Chủ dự án chưa muốn triển khai. Các ô dưới giữ nguyên để theo dõi backlog; không tính là việc phải làm của lượt 06–08. Không có outbox, worker, gửi thông báo hoặc giả định đã giao thông báo.

- [ ] Liệt kê sự kiện cần gửi: duyệt/từ chối yêu cầu, xác nhận tiền, văn bản sẵn sàng, lượt ký, sắp/quá hạn, khiếu nại, quyết định, nghĩa vụ hoàn và bàn giao.
- [ ] Ghi sự kiện/outbox cùng transaction thay đổi nghiệp vụ; tái sử dụng hạ tầng thông báo hiện có.
- [ ] Worker có retry, chống gửi trùng, ghi trạng thái gửi và hàng đợi lỗi để theo dõi; tách “đã tạo thông báo” với “đã gửi thành công”.
- [ ] Chốt kênh bắt buộc cho bản đầu, đề xuất thông báo trong ứng dụng; email nếu dùng phải kiểm chứng việc gửi thực tế. Không mặc định log là thông báo đã giao.
- [ ] Thông báo dẫn đúng hồ sơ và kiểm tra quyền khi mở; không chứa file/chứng cứ hoặc thông tin nhạy cảm không cần thiết.
- [ ] Đồng bộ badge, danh sách đã/chưa đọc và nơi xem hồ sơ cần phản hồi.
- [ ] Nhắc hạn/cảnh báo hồ sơ kẹt chỉ tạo tác vụ hỗ trợ; không tự giữ cọc, xác nhận tiền hay coi im lặng là đồng ý.
- [ ] Test worker tắt/restart, nhà cung cấp gửi lỗi, giao trùng, nhiều instance và người dùng mở link sau khi hồ sơ đã chuyển bước.

**Nghiệm thu:** chứng minh thông báo đã xuất hiện/gửi tới đúng người; restart không làm mất hoặc nhân đôi thông báo nghiệp vụ.

## 06 — Cọc đã nhận nhưng khách không hoàn tất ký/nhận nhà

- [x] R01: chuyển căn cứ/chứng cứ của đề nghị trước thuê vào case khiếu nại để admin xem đúng nguồn.
- [x] R02: có hành động tiếp tục thuê/ký trực tiếp sau khi khiếu nại đã giải quyết, không phải tạo rồi rút đề nghị hủy khác.
- [x] Phân biệt chưa ký HĐ cọc, HĐ cọc đã ký một bên, provider chưa rõ kết quả, đã ký cọc nhưng chưa trả tháng đầu, và đã trả đủ nhưng chưa ký HĐ thuê.
- [x] Dùng đồng hồ nghiệp vụ và hạn tương ứng của từng bước; không tính thời gian chủ nhà chưa chuẩn bị văn bản thành lỗi của khách.
- [x] Chủ nhà có thể gửi yêu cầu xử lý kèm lý do/chứng cứ dù khách không chủ động gửi đề nghị hủy.
- [x] Ghi đề nghị/hạn phản hồi trên UI, cho khách phản hồi; luồng mở lại ký hiện có được giữ. Chữ ký/chứng từ chưa rõ phải đối soát trước quyết định cuối. Gửi thông báo tự động được bỏ qua ở 05; kết thúc một phía hoàn toàn bộ tiền yêu cầu chủ nhà xác nhận đã liên hệ khách ngoài hệ thống.
- [x] Hết hạn xử lý mà không thống nhất có đường chuyển admin. Không vô hiệu hóa các chốt an toàn hiện có chỉ để giải phóng phòng.
- [x] Nhánh mới hoàn toàn bộ cọc và tiền thuê thực nhận, theo dõi riêng từng khoản; không tự khấu trừ khi chưa ký. Nhánh giữ cọc do vi phạm chính sách đã ký hiện có được giữ nguyên, không mở rộng sang HĐ cọc chưa ký.
- [x] Kết thúc hồ sơ phải xử lý đúng nghĩa vụ chưa trả, ngày thuê đang đề nghị, xe và lượt giữ phòng hiện tại; nghĩa vụ hoàn còn tồn tại được theo dõi độc lập.
- [x] Hiển thị trạng thái nghĩa vụ giữ chỗ của HĐ cọc; giữ PDF/chữ ký và lịch sử kể cả khi không tiếp tục thuê.
- [x] Test khách mất liên lạc, chủ nhà không phản hồi, provider trả kết quả muộn, hoàn bị dispute, gửi lặp và listing đã có lượt giữ mới.

**Nghiệm thu:** mọi nhánh cọc đã nhận có người chịu trách nhiệm và đường xử lý tiếp; hệ thống không tự tịch thu tiền hoặc tự xác nhận đã hoàn.

## 07 — Bàn giao nhận nhà và kết thúc nghĩa vụ giữ chỗ

- [x] R01: chuyển đúng phiên bản biên bản và chứng cứ đã gắn sang hồ sơ khiếu nại bàn giao.
- [x] Có biên bản nhận nhà đầu kỳ liên kết HĐ thuê/revision: ngày thực tế, người giao/nhận, chìa khóa, tài sản, tình trạng, ảnh và chỉ số công tơ.
- [x] Hai bên được xem, phản hồi và xác nhận đúng bản biên bản; sửa sau khi đã xem/xác nhận phải có phiên bản và lý do.
- [x] Người thuê có thể báo không được giao nhà/phòng khác cam kết và mở vụ việc từ biên bản.
- [x] Chốt quan hệ giữa ngày hiệu lực đã ký và ngày nhận nhà thực tế; nhận muộn không tự sửa ngày thuê hoặc hóa đơn đã phát hành.
- [x] Chỉ số công tơ đã dùng để ký/phát hóa đơn không được ghi đè âm thầm; sai sót đi qua điều chỉnh có lịch sử.
- [x] Phân biệt đã ký HĐ thuê, đã nhận nhà và đã hoàn tất giữ chỗ trên UI. Quyền đưa listing trở lại thị trường phải theo bằng chứng bàn giao/quyết định hợp lệ.
- [x] HĐ cọc giữ nguyên văn bản, có liên kết HĐ thuê và tình trạng nghĩa vụ đã chuyển tiếp/hoàn tất/đang tranh chấp.
- [x] Test một bên xác nhận, sai phiên bản, ảnh sai hợp đồng, nhận thiếu tài sản, nhận nhà trễ, từ chối nhận và không bàn giao thực tế.

**Nghiệm thu:** có thể tra cứu ai xác nhận nhận nhà, vào lúc nào, trên hiện trạng/chỉ số nào; chữ ký HĐ thuê không bị dùng thay cho chứng cứ đã giao chìa khóa.

## 08 — Chấm dứt sớm theo thỏa thuận

- [x] R01: chuyển căn cứ thỏa thuận và chứng cứ vào case khiếu nại chấm dứt.
- [x] R03: trạng thái mục chấm dứt đồng bộ khi quyết toán/hoàn tiền đã thực sự hoàn tất; bổ sung test GET sau đóng HĐ.
- [x] Tạo luồng riêng cho hợp đồng đang hoạt động dù không có hóa đơn quá hạn; không tái sử dụng bằng cách ép điều kiện nợ vào dữ liệu.
- [x] Tenant hoặc landlord gửi đề nghị gồm lý do, ngày mong muốn, cách bàn giao và đề xuất xử lý các nghĩa vụ còn lại.
- [x] Bên kia có thể đồng ý, phản hồi/đề nghị lại hoặc từ chối; chưa đồng ý không tự kết thúc thuê.
- [x] Thỏa thuận có phiên bản ghi rõ ngày kết thúc, bàn giao, chọn trọn kỳ hoặc tính tiền thuê theo số ngày thực tế, ghi khoản hoàn theo từng nguồn thuê thực nhận. Các phí khác không tự chia theo ngày; không sửa hóa đơn đã phát hành. Hai bên phải xác nhận đúng bản.
- [x] Chặn đề nghị khi gia hạn/phụ lục (kể cả đã ký còn chờ áp dụng) chưa xử lý xong; không hủy văn bản/giữ xe âm thầm. Chốt số kỳ tính phí mới, hóa đơn cuối, nguồn hoàn tiền trả trước và giữ các khoản nợ thực tế.
- [x] Hai bên xác nhận trả nhà/tài sản và quyết toán; cọc không mặc định bị giữ toàn bộ chỉ vì chấm dứt sớm.
- [x] Nếu có tiền hoàn, trả thêm hoặc tranh chấp thì dùng lại bước 02–04 và sổ cọc đã sửa. Bàn giao và hoàn tất tài chính có thể ở hai thời điểm khác nhau.
- [x] Giữ chữ ký/văn bản gốc; biên bản hoặc văn bản chấm dứt có phiên bản và xác nhận phù hợp quy tắc sản phẩm đã chốt.
- [x] Bổ sung UI cho hai bên, lịch sử và điểm mở khiếu nại/chuyển admin qua giai đoạn 04. Thông báo tự động được bỏ qua theo yêu cầu.
- [x] Test không nợ, còn nợ, đã trả trước, cọc đã điều chỉnh, phụ lục tương lai, gia hạn đang ký, chưa trả phòng, hoàn chậm và quyết định đồng thời.

**Nghiệm thu:** khách trả đúng hạn vẫn đề nghị kết thúc được; chỉ trả listing/xe khi đủ điều kiện và không đánh dấu hết nghĩa vụ khi còn khoản tiền chưa xử lý.

Bàn giao 06–08: **709 backend đạt/1 chẩn đoán SmartCA bỏ qua**, gồm **127 PostgreSQL thực** (35 ca mới); **195 web đạt** (13 ca mới); TypeScript/ESLint/diff đạt. Chi tiết, giới hạn và log tại [báo cáo 06–08](D:/Workspace/homespace/hs-core-api/docs/CONTRACT_LIFECYCLE_PHASE_06_08_REPORT.md). Không triển khai gửi thông báo, không tác động DB vận hành và chưa chạy E2E provider thật.

## 09 — Nghiệm thu và giới hạn đã ghi nhận

- [x] Chuyển T01–T40 thành [ma trận nghiệm thu](CONTRACT_LIFECYCLE_ACCEPTANCE_MATRIX.md) có bằng chứng tự động và cột giới hạn; không đánh dấu ca cần provider thật là đã chạy thật.
- [x] Hồi quy backend/API/validation/quyền và PostgreSQL: 754 đạt, 1 diagnostic SmartCA cần dịch vụ thật bỏ qua; trong tổng có 142 PostgreSQL/schema/restart. Thêm 1 HTTP system harness đạt, chạy cùng 5 kịch bản trình duyệt.
- [x] Kiểm thử web/admin bằng hai danh tính tenant, landlord, admin riêng: người ngoài không đọc/thao tác hồ sơ; stale version, bàn giao, nguồn case/admin, tiếp tục ký và kết quả quyết toán.
- [x] Hồi quy tạo hai văn bản cọc/thuê độc lập, tránh thu cọc/tháng đầu hai lần, cả nhận nhà tương lai và cùng ngày ở tầng service/PG. Các lượt ký VNPT thật vẫn chờ sandbox.
- [x] Kiểm tra tranh chấp tiền, im lặng/quá hạn, không bàn giao, hoàn cọc/chậm/đối soát và chuyển admin qua bộ regression có quyền và audit; gửi thông báo ngoài phạm vi.
- [x] Kiểm tra phụ lục/gia hạn, nguồn PDF, callback muộn ở biên giả lập provider, rollback và không tính tiền/xe hai lần.
- [x] Kiểm tra nâng cấp schema và giữ dữ liệu cũ, đóng/mở application context, hai context dùng DB locks; không gọi đây là kiểm thử cluster với VNPT/ngân hàng thật.
- [x] PDF qua Gotenberg thật và kiểm tra PNG trực quan; lỗi converter/storage/nguồn ký được mô phỏng để xác minh rollback.
- [x] Postman, hướng dẫn tenant/landlord/admin, dữ liệu thử có thể tạo lại, source RAG và tool AI chỉ đọc được bổ sung.
- [ ] Ký SmartCA thật/end-to-end với chứng thư, ngân hàng, storage, Keycloak của staging: **chưa nghiệm thu**. Người dùng chưa có sandbox và yêu cầu chuẩn bị code/bộ chạy sau. Có runbook, collection và script preflight/ký/đối soát riêng.
- [ ] Publish/ingest nguồn RAG mới và deploy schema/code lên môi trường vận hành: thao tác triển khai riêng, chưa thực hiện trong lượt này.
- [x] Báo cáo ghi rõ ca chạy, ca bỏ qua, giới hạn và cách xử lý hồ sơ qua API/admin. Không triển khai hệ thống thông báo.

**Kết luận phạm vi:** các lỗi/gap đã được giao có bản sửa và hồi quy; chưa tuyên bố toàn bộ sản phẩm đã nghiệm thu production. T26 (thanh toán nhiều phần) vẫn là phạm vi sản phẩm riêng trong review ban đầu, không được ghi thành tính năng đã có.

## Nguyên tắc thực hiện xuyên suốt

- Tái sử dụng module hợp đồng, thanh toán, thông báo và component xem văn bản hiện có; mở rộng theo trách nhiệm nghiệp vụ, không viết một service khổng lồ xử lý tất cả.
- Mỗi thay đổi trạng thái có tác động tiền/listing/xe phải có transaction, thứ tự khóa thống nhất, kiểm tra quyền và chống lặp. Lỗi giữa chừng không để dữ liệu nửa cũ nửa mới.
- Không sửa/xóa văn bản đã ký, chứng từ nhận tiền hoặc quyết định cũ. Giữ dữ liệu nguồn và hành động khắc phục tách bạch.
- Cấu hình thời hạn theo nghiệp vụ ở properties; khóa bí mật ở môi trường backend. Không tạo nhiều công tắc mới để che một nhánh chưa có cách xử lý.
- Giải thích cho người dùng bằng trạng thái và bước tiếp theo rõ ràng; không yêu cầu họ hiểu enum, JSON, revision nội bộ hoặc URL storage.
- Không tự thay quyết định sản phẩm còn bỏ ngỏ thành hành vi mặc định trong code. Ghi quyết định đó vào mục 00 trước khi triển khai phần phụ thuộc.
