# Checklist hoàn thiện vòng đời hợp đồng HomeSpace

Ngày lập: **03/11/2026**. Trạng thái: **giai đoạn 01–02 đã được duyệt tiếp; giai đoạn 03 đã triển khai và đạt regression, chờ chủ dự án nghiệm thu; giai đoạn 04–09 chưa bắt đầu**.

Bàn giao giai đoạn 03: [Sổ cọc và gửi lại đề nghị gia hạn](D:/Workspace/homespace/hs-core-api/docs/CONTRACT_LIFECYCLE_PHASE_03_REPORT.md).

Bàn giao giai đoạn 02: [Giải quyết tranh chấp thanh toán](D:/Workspace/homespace/hs-core-api/docs/CONTRACT_LIFECYCLE_PHASE_02_REPORT.md).

Bàn giao giai đoạn 01: [Bản nháp khớp hồ sơ thuê](D:/Workspace/homespace/hs-core-api/docs/CONTRACT_LIFECYCLE_PHASE_01_REPORT.md).

Bàn giao giai đoạn 00: [Nguồn dữ liệu, quyền, chuyển trạng thái và kết quả kiểm thử](D:/Workspace/homespace/hs-core-api/docs/CONTRACT_LIFECYCLE_PHASE_00_RULES.md). Mục được đánh dấu hoàn thành chỉ xác nhận phần khảo sát/tài liệu đã làm; quy tắc đề xuất cần duyệt và lỗi nghiệp vụ vẫn được ghi rõ.

Căn cứ: [Review vòng đời hợp đồng ngày 02/11/2026](D:/Workspace/homespace/hs-core-api/docs/CONTRACT_LIFECYCLE_REVIEW_2026-11-02.md). Mã F01–F04 và G01–G04 bên dưới tham chiếu tài liệu này.

Mục tiêu: hoàn thiện cả luồng thuận lợi và luồng phát sinh từ yêu cầu thuê, ký HĐ cọc, ký HĐ thuê đến bàn giao, thanh toán, khiếu nại và kết thúc nghĩa vụ. Mỗi giai đoạn phải có API, giao diện cần thiết và kiểm thử; không đánh dấu hoàn thành chỉ vì đã thêm endpoint.

## Thứ tự thực hiện

| Thứ tự | Hạng mục | Ưu tiên | Phụ thuộc chính | Kết quả phải bàn giao |
|---|---|---|---|---|
| 00 | Chốt nguồn dữ liệu và quy tắc chuyển trạng thái | Nền tảng | Review hiện có | Ma trận điều khoản, quyền và hành động hợp lệ |
| 01 | Sửa điều khoản bản nháp lệch hồ sơ thuê — F01 | P1 | 00 | Văn bản, lịch thuê, tiền và xe khớp nhau |
| 02 | Mở đường giải quyết `DISPUTED` — F02; nền tảng khiếu nại | P1 | 00, 01 | Khoản tranh chấp có thể tiếp tục xử lý bằng API/UI đúng quyền |
| 03 | Hoàn thiện sổ cọc và gửi lại gia hạn — F03, F04 | P2 | 00, 01; sổ cọc đối chiếu với 02 | Số tiền đầy đủ; gia hạn không bị khóa bởi đề nghị cũ |
| 04 | Hoàn thiện khiếu nại người thuê → chủ nhà → admin — G01 | P1 | 02, 03 | Hồ sơ vụ việc, chứng cứ, chuyển cấp và quyết định có lịch sử |
| 05 | Gửi thông báo thật, nhắc hạn và cảnh báo hồ sơ kẹt — G02 | P2 | Sự kiện của 02, 04 | Thông báo có gửi, retry và chống trùng |
| 06 | Xử lý cọc đã nhận nhưng không hoàn tất ký/nhận nhà | P1 | 01–05 | Có lối kết thúc an toàn, không giữ phòng vô thời hạn |
| 07 | Biên bản nhận nhà và trạng thái nghĩa vụ HĐ cọc — G04 | P2 | 01, 03–06 | Bằng chứng bàn giao; phân biệt đã ký và đã nhận nhà |
| 08 | Chấm dứt sớm theo thỏa thuận, không cần có nợ — G03 | P2 | 02–07 | Kết thúc thuê, bàn giao và quyết toán đúng thỏa thuận |
| 09 | Nghiệm thu toàn luồng trên DB riêng và giao diện | Bắt buộc | 01–08 | Báo cáo test, các luồng không còn điểm kẹt đã biết |

**Cách làm theo yêu cầu chủ dự án:** chỉ triển khai một giai đoạn trong mỗi lượt được duyệt. Hoàn thành phần đã được giao, chạy regression liên quan, báo cáo thay đổi/kết quả/giới hạn rồi **dừng lại chờ đồng ý** trước khi bắt đầu giai đoạn tiếp theo. Không tự triển khai hoặc chuẩn bị thay đổi code của các giai đoạn sau. Kiểm thử từng phần bắt đầu ngay từ bước 01; bước 09 là nghiệm thu liên module.

## 00 — Chốt quy tắc chung trước khi sửa

- [x] Lập bảng nguồn dữ liệu cho từng trường: danh tính hai bên, tài sản, ngày bắt đầu, thời hạn, tiền thuê, tiền cọc, phí, xe, công tơ và chính sách.
- [x] Phân biệt dữ liệu yêu cầu ban đầu, điều khoản đã được hai bên thống nhất, snapshot văn bản và sổ tiền thực nhận. Không xem một map client gửi lên là nguồn có quyền thay đổi tất cả dữ liệu.
- [x] Chốt trường được chỉnh trực tiếp khi còn nháp và trường phải đi qua đề nghị thay đổi có thỏa thuận. Sau khi ký phải dùng văn bản điều chỉnh thích hợp, giữ nguyên bản đã ký. Phạm vi triển khai cụ thể ghi tại báo cáo 01; chủ dự án đã duyệt tiếp bằng yêu cầu “tiếp”.
- [x] Giữ yêu cầu đã thống nhất: HĐ cọc và HĐ thuê là hai văn bản, hai lượt ký riêng; cọc và nhận nhà cùng ngày vẫn không gộp thành một lượt ký.
- [ ] Chốt ngoại lệ cọc 0 đồng: hiện code miễn HĐ cọc. Quyết định có cần văn bản giữ chỗ 0 đồng ký riêng hay tiếp tục miễn; không tạo khoản chuyển/chứng từ nhận tiền giả để đủ luồng.
- [x] Lập ma trận quyền tenant/landlord/admin/system cho mọi thao tác có thay đổi trạng thái hoặc tiền.
- [x] Tách trạng thái chữ ký, quyền thuê/giữ chỗ, bàn giao và nghĩa vụ tài chính trong ma trận; không dùng một nhãn `ACTIVE`/`CLOSED` để suy ra mọi nghĩa vụ đã xong. Các model mới sẽ chỉ triển khai tại giai đoạn được duyệt tương ứng.
- [ ] Chốt các chính sách còn thiếu: SLA phản hồi/chuyển admin, điều kiện hủy sau khi cọc, gửi lại gia hạn, xử lý cọc khi chấm dứt bình thường. Ghi rõ phần nào là đề xuất chưa được duyệt.

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
- [x] Phân biệt tiền đang giữ, khoản sẽ hoàn và khoản đã hoàn; chỉ xác nhận tiền đã hoàn sau đúng bước thực nhận.
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

Giai đoạn 02 đã có trang admin đối soát thanh toán tối thiểu. Các mục dưới vẫn chưa hoàn thành cho khiếu nại tổng quát, SLA và nghĩa vụ ngoài thanh toán.

- [ ] Người thuê/chủ nhà mở được vụ việc gắn hồ sơ đúng quyền: thanh toán/cọc, bàn giao, hiện trạng, sửa chữa, điều khoản hoặc chấm dứt.
- [ ] Định nghĩa trạng thái và chuyển tiếp rõ: mở → chờ phản hồi → trao đổi/đề xuất giải quyết → chuyển admin → đang xử lý → đã giải quyết/đóng. Rút vụ việc và mở lại phải có điều kiện.
- [ ] Chủ nhà nhận và phản hồi vụ việc của người thuê; giữ nguyên ý kiến và chứng cứ của cả hai bên.
- [ ] Có nút chuyển HomeSpace khi không thống nhất hoặc quá SLA. Quy định rõ vụ việc khẩn cấp nào có thể chuyển trực tiếp.
- [ ] Admin có danh sách lọc theo trạng thái/loại/hạn, nhận phân công, đọc hồ sơ, yêu cầu bổ sung, ghi kết luận và theo dõi hành động phát sinh.
- [ ] File chứng cứ private, đúng người gửi và hồ sơ; không lộ file giữa các hợp đồng hoặc qua URL công khai.
- [ ] Quyết định và nghĩa vụ phát sinh được lưu nguyên nhân, người xử lý, phiên bản, thời điểm; không xóa phản hồi cũ.
- [ ] Có cơ chế rút/đóng/mở lại khiếu nại và ngăn nhiều vụ việc đang mở trùng cùng vấn đề.
- [ ] Đóng vụ việc không tự xóa nợ, xác nhận hoàn tiền hoặc trả listing nếu các điều kiện đó chưa đạt.
- [ ] Thay trang trống `ComplaintsPage` bằng giao diện thực tế; bổ sung điểm mở/xem khiếu nại tại yêu cầu thuê, hợp đồng và thanh toán trên web.
- [ ] Test bên thứ ba, admin không đủ quyền, hết hạn, hai quyết định đồng thời, quyết định cũ, chứng cứ sai hồ sơ và lỗi giữa transaction.

**Nghiệm thu:** chạy được một vụ “tenant phản đối khấu trừ cọc → chủ nhà không đồng ý → gửi admin → kết luận → tạo nghĩa vụ hợp lệ → xác nhận thực nhận”. Cả ba bên nhìn thấy tiến trình phù hợp quyền của mình.

## 05 — Thông báo và nhắc hạn thật

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

- [ ] Phân biệt chưa ký HĐ cọc, HĐ cọc đã ký một bên, provider chưa rõ kết quả, đã ký cọc nhưng chưa trả tháng đầu, và đã trả đủ nhưng chưa ký HĐ thuê.
- [ ] Dùng đồng hồ nghiệp vụ và hạn tương ứng của từng bước; không tính thời gian chủ nhà chưa chuẩn bị văn bản thành lỗi của khách.
- [ ] Chủ nhà có thể gửi yêu cầu xử lý kèm lý do/chứng cứ dù khách không chủ động gửi đề nghị hủy.
- [ ] Gửi thông báo, cho khách phản hồi và mở lại hạn khi phù hợp; tranh chấp/chữ ký chưa rõ phải được đối soát trước quyết định cuối.
- [ ] Hết hạn xử lý mà không thống nhất có đường chuyển admin. Không vô hiệu hóa các chốt an toàn hiện có chỉ để giải phóng phòng.
- [ ] Hoàn/khấu trừ dựa trên điều kiện đã thống nhất và kết quả xử lý; khoản thực nhận, tiền thuê đã nhận và tiền cọc được theo dõi riêng.
- [ ] Kết thúc hồ sơ phải xử lý đúng nghĩa vụ chưa trả, ngày thuê đang đề nghị, xe và lượt giữ phòng hiện tại; nghĩa vụ hoàn còn tồn tại được theo dõi độc lập.
- [ ] Hiển thị trạng thái nghĩa vụ giữ chỗ của HĐ cọc; giữ PDF/chữ ký và lịch sử kể cả khi không tiếp tục thuê.
- [ ] Test khách mất liên lạc, chủ nhà không phản hồi, provider trả kết quả muộn, hoàn bị dispute, gửi lặp và listing đã có lượt giữ mới.

**Nghiệm thu:** mọi nhánh cọc đã nhận có người chịu trách nhiệm và đường xử lý tiếp; hệ thống không tự tịch thu tiền hoặc tự xác nhận đã hoàn.

## 07 — Bàn giao nhận nhà và kết thúc nghĩa vụ giữ chỗ

- [ ] Có biên bản nhận nhà đầu kỳ liên kết HĐ thuê/revision: ngày thực tế, người giao/nhận, chìa khóa, tài sản, tình trạng, ảnh và chỉ số công tơ.
- [ ] Hai bên được xem, phản hồi và xác nhận đúng bản biên bản; sửa sau khi đã xem/xác nhận phải có phiên bản và lý do.
- [ ] Người thuê có thể báo không được giao nhà/phòng khác cam kết và mở vụ việc từ biên bản.
- [ ] Chốt quan hệ giữa ngày hiệu lực đã ký và ngày nhận nhà thực tế; nhận muộn không tự sửa ngày thuê hoặc hóa đơn đã phát hành.
- [ ] Chỉ số công tơ đã dùng để ký/phát hóa đơn không được ghi đè âm thầm; sai sót đi qua điều chỉnh có lịch sử.
- [ ] Phân biệt đã ký HĐ thuê, đã nhận nhà và đã hoàn tất giữ chỗ trên UI. Quyền đưa listing trở lại thị trường phải theo bằng chứng bàn giao/quyết định hợp lệ.
- [ ] HĐ cọc giữ nguyên văn bản, có liên kết HĐ thuê và tình trạng nghĩa vụ đã chuyển tiếp/hoàn tất/đang tranh chấp.
- [ ] Test một bên xác nhận, sai phiên bản, ảnh sai hợp đồng, nhận thiếu tài sản, nhận nhà trễ, từ chối nhận và không bàn giao thực tế.

**Nghiệm thu:** có thể tra cứu ai xác nhận nhận nhà, vào lúc nào, trên hiện trạng/chỉ số nào; chữ ký HĐ thuê không bị dùng thay cho chứng cứ đã giao chìa khóa.

## 08 — Chấm dứt sớm theo thỏa thuận

- [ ] Tạo luồng riêng cho hợp đồng đang hoạt động dù không có hóa đơn quá hạn; không tái sử dụng bằng cách ép điều kiện nợ vào dữ liệu.
- [ ] Tenant hoặc landlord gửi đề nghị gồm lý do, ngày mong muốn, cách bàn giao và đề xuất xử lý các nghĩa vụ còn lại.
- [ ] Bên kia có thể đồng ý, phản hồi/đề nghị lại hoặc từ chối; chưa đồng ý không tự kết thúc thuê.
- [ ] Chốt cách tính kỳ cuối theo thỏa thuận có thể biểu diễn trên văn bản; không tự suy ra hoàn tiền theo ngày nếu điều khoản chưa quy định.
- [ ] Liên kết phụ lục/gia hạn đang chờ, tiền trả trước, xe và các hóa đơn với quyết định; không bỏ quên nghĩa vụ hoặc thu lại tiền đã nhận.
- [ ] Hai bên xác nhận trả nhà/tài sản và quyết toán; cọc không mặc định bị giữ toàn bộ chỉ vì chấm dứt sớm.
- [ ] Nếu có tiền hoàn, trả thêm hoặc tranh chấp thì dùng lại bước 02–04 và sổ cọc đã sửa. Bàn giao và hoàn tất tài chính có thể ở hai thời điểm khác nhau.
- [ ] Giữ chữ ký/văn bản gốc; biên bản hoặc văn bản chấm dứt có phiên bản và xác nhận phù hợp quy tắc sản phẩm đã chốt.
- [ ] Bổ sung UI cho hai bên, thông báo và điểm chuyển admin nếu không giải quyết được.
- [ ] Test không nợ, còn nợ, đã trả trước, cọc đã điều chỉnh, phụ lục tương lai, gia hạn đang ký, chưa trả phòng, hoàn chậm và quyết định đồng thời.

**Nghiệm thu:** khách trả đúng hạn vẫn đề nghị kết thúc được; chỉ trả listing/xe khi đủ điều kiện và không đánh dấu hết nghĩa vụ khi còn khoản tiền chưa xử lý.

## 09 — Nghiệm thu toàn luồng

- [ ] Chuyển 40 ca T01–T40 trong tài liệu review thành bộ nghiệm thu có trạng thái thực hiện, dữ liệu đầu vào và bằng chứng kết quả; bổ sung ca mới của các bước trên.
- [ ] Unit test các điều kiện nghiệp vụ; API test quyền, validation và mã lỗi; PostgreSQL integration test khóa, unique, retry và rollback liên module.
- [ ] E2E bằng ít nhất hai tài khoản tenant, một landlord và một admin; kiểm tra cả quyền nhìn thấy hồ sơ và thao tác thực tế trên web/admin.
- [ ] Chạy cả cọc trước/nhận nhà sau và cọc/thuê cùng ngày, từ gửi yêu cầu đến hai văn bản ký riêng, hóa đơn, bàn giao và đóng hồ sơ.
- [ ] Chạy các nhánh không thuận lợi: tranh chấp tiền, khách/chủ nhà im lặng, không bàn giao, chấm dứt bình thường, hoàn cọc bị chậm và khiếu nại lên admin.
- [ ] Kiểm tra phụ lục/gia hạn và quyết toán không làm thay đổi PDF đã ký hoặc tính trùng tiền, xe, kỳ thuê.
- [ ] Chạy restart/nhiều instance: tạo hồ sơ, ký, xác nhận tiền, gửi thông báo và xử lý vụ việc; kiểm tra callback muộn và hai thao tác đồng thời.
- [ ] Kiểm thử VNPT sandbox, lỗi chuyển Word/PDF/storage và lỗi kênh thông báo; ghi rõ phần chưa kiểm thử thật, không thay bằng kết quả mock.
- [ ] Chuẩn bị dữ liệu mới theo entity/JPA và seed phù hợp cho môi trường dev tạo lại DB; không tạo SQL vá dữ liệu nếu không có nhu cầu. Kiểm thử DB dùng môi trường riêng, không drop DB đang sử dụng để chạy test.
- [ ] Cập nhật Postman, hướng dẫn tenant/landlord/admin, seed kịch bản và tài liệu/RAG AI. AI chỉ hướng dẫn/gọi hành động được cấp quyền, không tự suy ra trạng thái thanh toán hoặc kết luận tranh chấp.
- [ ] Báo cáo nghiệm thu ghi rõ test đã chạy, ca bỏ qua, giới hạn còn lại và cách xử lý hồ sơ lỗi qua API/admin.

**Điều kiện hoàn thành toàn kế hoạch:** bốn lỗi F01–F04 có regression đạt; không còn nhánh `DISPUTED`/cọc đã nhận không có lối xử lý đã biết; khiếu nại chuyển tới admin chạy được; mọi quyết định tiền có lịch sử và xác nhận đúng bên; đã chứng minh các luồng E2E trên môi trường riêng.

## Nguyên tắc thực hiện xuyên suốt

- Tái sử dụng module hợp đồng, thanh toán, thông báo và component xem văn bản hiện có; mở rộng theo trách nhiệm nghiệp vụ, không viết một service khổng lồ xử lý tất cả.
- Mỗi thay đổi trạng thái có tác động tiền/listing/xe phải có transaction, thứ tự khóa thống nhất, kiểm tra quyền và chống lặp. Lỗi giữa chừng không để dữ liệu nửa cũ nửa mới.
- Không sửa/xóa văn bản đã ký, chứng từ nhận tiền hoặc quyết định cũ. Giữ dữ liệu nguồn và hành động khắc phục tách bạch.
- Cấu hình thời hạn theo nghiệp vụ ở properties; khóa bí mật ở môi trường backend. Không tạo nhiều công tắc mới để che một nhánh chưa có cách xử lý.
- Giải thích cho người dùng bằng trạng thái và bước tiếp theo rõ ràng; không yêu cầu họ hiểu enum, JSON, revision nội bộ hoặc URL storage.
- Không tự thay quyết định sản phẩm còn bỏ ngỏ thành hành vi mặc định trong code. Ghi quyết định đó vào mục 00 trước khi triển khai phần phụ thuộc.
