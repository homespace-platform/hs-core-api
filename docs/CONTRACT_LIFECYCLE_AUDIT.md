# Rà soát vòng đời thuê, hóa đơn và cọc — 21/10/2026

## Thay đổi lần này

Trang Hóa đơn chỉ còn **Hóa đơn theo kỳ** và **Cọc & hoàn cọc**. Tháng 1 nằm trong danh sách hóa đơn, lấy từ khoản INITIAL gốc; không tạo thêm công nợ hay QR. Chỉ tính dòng RENT/SERVICE_FEE thực tế đã chốt, loại DEPOSIT. Khoản chuyển gộp vẫn hiển thị tổng chuyển gốc và giải thích cọc riêng.

Tháng đầu là read model `INITIAL_RENT`, ID ổn định `first-rent:<paymentId>`, `periodIndex=-1` chỉ trong DTO. Các kỳ chi phí DB vẫn bắt đầu từ 0; quyết toán không đếm thêm một tháng hoặc thu tháng đầu hai lần. API sửa hóa đơn định kỳ không nhận ID read model này. Hồ sơ thanh toán được xem từ giai đoạn xác nhận cọc, kể cả chưa ký/đã hủy; không làm contract thành ACTIVE. Tenant vẫn phải đủ quyền xem bản nháp.

Hạn ký của tenant có trường riêng `tenantSigningDueAt`, dùng đồng hồ nghiệp vụ, bắt đầu sau khi chủ nhà ký SmartCA thành công. Không dùng thời gian chủ nhà đang xử lý để kết luận tenant bỏ ký.

## Không trả tháng đầu / không hoàn tất ký

Lối thoát có thỏa thuận vẫn được giữ: tenant có thể đề nghị hủy và chủ nhà đồng ý hoàn các khoản thực nhận ngay cả khi chủ nhà đã ký nhưng tenant chưa ký. Chữ ký một bên được lưu nguyên trong lịch sử; không cho hủy khi có chữ ký tenant, đủ hai bên hoặc provider đang xử lý/chưa rõ kết quả. Phản đối giữ cọc không làm mất quyền hai bên thống nhất hủy/hoàn tiền.

Phạm vi đã được chủ dự án xác nhận: **cả hai trường hợp**. Không có scheduler tự tịch thu cọc.

1. Yêu cầu thuê mới bắt buộc tenant chủ động đồng ý `RESERVATION_DEFAULT_V1` khi gửi. UI có required, khóa nút và submit guard; API có AssertTrue và service guard, thiếu/false/null đều bị chặn. Lưu phiên bản và thời điểm trước thanh toán; không tích sẵn hay suy ra đồng ý từ bản nháp chưa ký. Hồ sơ cũ không có đồng ý không được giữ cọc hồi tố; không tự backfill.
2. Chỉ xét rental ACCEPTED, Listing RESERVED, sổ cọc HELD dương, chưa đủ chữ ký/chưa bàn giao. Chủ nhà phải có PDF đúng revision/fingerprint. Nhánh bỏ ký cần chữ ký SmartCA chủ nhà trên đúng revision và hết hạn riêng của tenant.
3. Chủ nhà gửi thông báo cuối cùng, mở thêm ít nhất 24 giờ để tenant **thực sự có thể thanh toán/ký** hoặc phản hồi. Cấu hình `rental.default-response-hours` không thể rút dưới 24 giờ. Thông báo và hạn được lưu nhật ký.
4. Báo chuyển, REJECTED, DISPUTED, phản hồi tenant, SmartCA đang chạy/FAILED/chưa rõ kết quả đều chặn giữ cọc. Tenant đã ký cũng chặn. Đổi revision, hạn hoặc trạng thái tiền làm thông báo cũ mất điều kiện áp dụng.
5. Hết hạn, chủ nhà xác nhận đã thông báo và chưa bàn giao nhà/chìa khóa. Backend khóa rental → contract → payment → deposit, kiểm tra lại và chỉ giữ **cọc thực nhận**. Tiền thuê đã nhận tạo nghĩa vụ hoàn riêng, kể cả INITIAL gộp cọc + thuê.
6. Cùng transaction: hủy contract chưa hoàn tất, kết thúc rental, hủy khoản chưa trả, đóng đề nghị ngày thuê còn treo, giải phóng xe, trả Listing về PUBLISHED nếu còn quyền hiển thị hoặc EXPIRED nếu hết hạn. Không ép mở tin bị ẩn/vi phạm. Giữ nguyên receipt/chứng từ/chữ ký và lịch sử.
7. Retry đã hoàn tất không tác động lượt giữ chỗ mới. Lỗi một bước phải rollback toàn bộ DB; nghĩa vụ hoàn không được coi là tiền đã chuyển.

API xác thực đúng bên:

- `POST /api/v1/rental-requests/{id}/onboarding/default-notice`: `{reason}`.
- `POST .../default-dispute`: tenant gửi `{reason}`, chặn chốt để đối soát.
- `POST .../default-completion`: `{reason,tenantNotified:true,vacantPossessionConfirmed:true}`.
- `POST .../refresh`: thời gian server, hạn, quyền thao tác và trạng thái cọc.

## Ma trận rà soát / bất biến

| Giai đoạn hoặc gián đoạn | Xử lý an toàn |
| --- | --- |
| Chủ nhà chưa duyệt quá hạn | Hết hạn yêu cầu, không phải mất cọc; không rollback nhầm tin chưa giữ |
| Chấp nhận; chưa báo chuyển trong 60 phút | Hủy nghĩa vụ chưa trả, giải phóng giữ chỗ; tính từ chấp nhận, không từ lúc gửi yêu cầu |
| Đã báo chuyển nhưng chủ nhà im lặng/từ chối | Giữ phòng để đối soát, không tự xác nhận nhận tiền/giữ cọc |
| Đã cọc, chưa tới ngày vào ở | Giữ phòng; chưa thu tháng đầu/ký, không thu lại cọc |
| Xin nhận sớm bị từ chối/hết hạn | Giữ ngày/tiền cũ; duyệt phải kiểm tra lại xe và bàn giao |
| Đổi ngày nhưng thiếu xe | Rollback ngày, revision và reservation |
| Server tắt qua ngày vào ở | Restart mở đúng nghĩa vụ duy nhất với deadline gốc, không tự dời ngày thuê |
| Cọc rồi chưa trả tháng đầu | Đối soát/mở lại hạn, hủy có hoàn theo thỏa thuận, hoặc thông báo vi phạm đủ điều kiện |
| Đã trả đủ, chủ nhà chưa ký | Không thể kết luận tenant bỏ ký để giữ cọc |
| SmartCA timeout/ký rồi chưa nhúng PDF | Đối soát/retry transaction gốc, không hủy khi kết quả chưa rõ |
| Hai bên ký đủ | ACTIVE, rental COMPLETED, Listing RENTED, xe ACTIVE; không dùng nhánh hủy trước thuê |
| Job cuối tháng lặp/nhiều instance | Khóa contract và unique(contract,period), một nghĩa vụ cho một hóa đơn |
| Phụ lục đổi giá | Chi phí theo đầu kỳ chi phí; thuê trả trước theo đầu kỳ thuê tiếp theo. Đóng băng revision nguồn khi phát hành, không lấy PL tương lai hoặc sửa bill cũ |
| Báo chuyển bill/tranh chấp | Chưa PAID; không đồng thời chuyển nợ/chấm dứt; tạm dừng tăng phí đúng chính sách |
| Quá hạn 5/10 ngày | Mở đúng phương án; chuyển nợ có điều kiện, không tính hai lần, không tự coi đã bàn giao |
| Gia hạn đang ký hết cutoff | Không tự kéo dài thuê; đóng đề nghị/hold tương ứng, giữ bản gốc và công nợ |
| Hết thời hạn chưa bàn giao | EXPIRED không phải CLOSED; cần đủ hóa đơn, bàn giao và xử lý cọc |
| Phản đối quyết toán/chưa xác nhận nhận hoàn | Không tự đồng ý/đã nhận, giữ lịch sử và nghĩa vụ sau quá hạn |
| Chấm dứt do nợ sau khi đã ký | Dùng termination của bill, điều khoản đã ký và bàn giao, không dùng default trước thuê |

## Kiểm chứng và giới hạn

Regression suite bao gồm onboarding, thanh toán/chứng từ, SmartCA, phụ lục/giá theo kỳ, gia hạn, late fee/rollover, chấm dứt và quyết toán. Test mới kiểm tra tháng 1 không lẫn cọc; reported/disputed/cancelled; deadline chính xác; đổi hạn/revision; provider chưa rõ; hoàn riêng tiền thuê; idempotency. PostgreSQL riêng kiểm tra persistence và rollback tài chính + xe khi giải phóng Listing lỗi. Không create-drop trên DB người dùng.

Không đồng nghĩa đã chứng minh mọi tình huống production không lỗi. Chưa kiểm chứng ngân hàng/VNPT thật, SMTP/push hoặc tải nhiều instance thật trong đợt này. Thông báo lưu trong hồ sơ **chưa phải bằng chứng đã giao email/SMS**; chủ nhà phải xác nhận đã thông báo thực tế. Phản đối/provider chưa rõ cần quy trình hỗ trợ đối soát, không có nút cưỡng ép bỏ qua. Thanh toán một phần chưa tự động hỗ trợ. Lùi giờ giả lập không rollback dữ liệu đã phát hành.

Trước production: duyệt nội dung giữ cọc, bằng chứng thông báo/bàn giao; bổ sung delivery/outbox, cảnh báo đối soát và test tải/restart nhiều instance. Không suy luận hiệu lực pháp lý từ nút bấm. Schema dev mới tạo qua JPA theo lựa chọn chủ dự án; không cần SQL chuyển dữ liệu cũ, không có thao tác drop DB trong thay đổi này.

## Hủy ngay yêu cầu chưa chuyển tiền

Cả chủ nhà và người thuê có thể bấm **Hủy yêu cầu** ở danh sách hoặc chi tiết khi đang chờ duyệt, hoặc đã chấp nhận nhưng chưa báo chuyển/nhận tiền. Không cần đợi hết hạn giữ chỗ 60 phút. API lấy người thao tác từ phiên đăng nhập, khóa yêu cầu và kiểm tra lại khoản cọc, tiền thuê, sổ cọc và chữ ký; không tin quyền hiển thị nút ở client.

Hủy khoản chưa trả và bản nháp nếu có, rút đề nghị đổi ngày còn chờ, giải phóng xe, trả Listing RESERVED về trạng thái phù hợp hạn đăng trong cùng transaction. Hủy PENDING không được giải phóng Listing đang giữ cho người khác. Gửi lặp không giải phóng lần nữa; lưu riêng trạng thái chủ nhà/người thuê hủy và lịch sử. Check constraint enum hiện hữu được mở rộng khi Core khởi động, không xóa dữ liệu.

Có tiền đã nhận, đã báo chuyển (kể cả sau khi mở lại hạn), chứng từ bị từ chối/tranh chấp hoặc chữ ký chưa rõ kết quả thì không hủy nhanh. Cọc 0 đồng chỉ là miễn cọc, không ngăn hủy nếu không có khoản nào khác đã chuyển. Chuyển tiền ngoài hệ thống nhưng chưa báo chuyển không thể phát hiện tự động: hộp xác nhận cảnh báo không hủy trong trường hợp này; cần báo chuyển/đối soát. Nếu đã hủy rồi mới phát hiện tiền chuyển ngoài hệ thống, liên hệ hỗ trợ đối soát, không tự mở lại hold đã cấp cho giao dịch mới.

## Kết quả chạy thực tế

- Core `mvn -Pisolated-build -pl hs-api-service -am package`: thành công; 69 test suite, 689 ca, **688 đạt, 1 bỏ qua, 0 failure/error**. Ca bỏ qua là diagnostic gọi SmartCA thật, cần cấu hình credential và CCCD test chủ động; không gọi provider thật trong đợt này.
- PostgreSQL 16 tách biệt tại cổng 55439: các ca opt-in đã chạy, gồm 7 ca persistence/onboarding, rollback và nâng check constraint trạng thái hủy của chủ nhà. Container test đã dừng/xóa sau kiểm tra; DB đang dùng của người dùng không bị thay đổi.
- Web: **168/168** ca `node --test tests/*.test.cjs` đạt; gồm SSR card hai mục, tháng 1 chuyển gộp, quyền thông báo/giữ cọc/phản hồi, lịch sử kết thúc và quyền hủy ngay trên hai màn hình.
- TypeScript `--noEmit --incremental false`, ESLint các TS/TSX thay đổi, `npm run build` và `git diff --check`: thành công.
- JAR đã build: `hs-api-service/target/isolated/hs-api-service-0.0.1-SNAPSHOT.jar`. Cần restart Core/web sau khi chuẩn bị schema dev mới để nhận thay đổi. Tài liệu RAG 06/07/13 đã cập nhật dạng draft, chưa publish hoặc reindex.
