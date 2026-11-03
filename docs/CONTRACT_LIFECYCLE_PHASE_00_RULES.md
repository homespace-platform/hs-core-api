# Giai đoạn 00 — Nguồn dữ liệu, quyền và chuyển trạng thái

Ngày rà soát: **03/11/2026**.

Cập nhật sau bàn giao: chủ dự án đã đồng ý tiếp giai đoạn 01. [Báo cáo triển khai D01/F01](D:/Workspace/homespace/hs-core-api/docs/CONTRACT_LIFECYCLE_PHASE_01_REPORT.md) ghi phạm vi thực hiện và kiểm thử; phần bên dưới giữ nội dung khảo sát của giai đoạn 00.

Phạm vi của lượt này: chuẩn bị quy tắc cho các bước sửa, xác minh baseline và sửa lỗi đường dẫn của test. **Chưa sửa hành vi hợp đồng/thanh toán; chưa bắt đầu giai đoạn 01–09.**

Tham chiếu:

- [Checklist triển khai](D:/Workspace/homespace/hs-core-api/docs/CONTRACT_LIFECYCLE_COMPLETION_CHECKLIST.md).
- [Các lỗi và khoảng trống đã review](D:/Workspace/homespace/hs-core-api/docs/CONTRACT_LIFECYCLE_REVIEW_2026-11-02.md).

## 1. Cách đọc tài liệu

- **Hiện có:** đối chiếu được với code; không đồng nghĩa đã chạy tích hợp DB/provider thật.
- **Quy tắc đề xuất:** dùng để chủ dự án duyệt trước khi triển khai phần liên quan; chưa phải kiểm tra đã được backend áp dụng.
- **Khoảng trống:** cần sửa ở giai đoạn ghi rõ, không đổi nghiệp vụ trong giai đoạn 00 để lấp tạm.

Không coi các lỗi F01–F04 đã được sửa chỉ vì baseline hiện tại đạt.

## 2. Bảng nguồn dữ liệu

| Nhóm | Nguồn hiện có cần đối chiếu | Quy tắc bảo toàn / đề xuất khi sửa |
|---|---|---|
| Người thuê, chủ nhà và quyền | `RentalRequest.ownerId/renterId`; `Contract.landlordId/tenantId`; phiên đăng nhập backend | Không nhận ID bên ký/quyền từ map snapshot để thay chủ thể hợp đồng. |
| Tên/CCCD/liên hệ/địa chỉ bên ký | `ContractDataBuilder.buildLandlord/buildTenant`: hồ sơ user, thông tin liên hệ request; snapshot revision | Danh tính phải khớp chủ thể đã xác thực. Hoàn thiện từ nguồn hợp lệ trước khi ký; không sửa tùy ý căn cước trên bản nháp rồi ký bằng chứng thư của người khác. Văn bản đã ký giữ snapshot cũ. |
| Tài khoản ngân hàng của giao dịch | `PaymentRequest` lưu snapshot tài khoản trả/nhận | Đổi ngân hàng ở profile không sửa giao dịch đã chốt. Thay nơi nhận khoản mới phải qua xác thực, không sửa lịch sử bằng payload hợp đồng. |
| Tài sản, địa chỉ, loại hình, nội thất/tiện ích ban đầu | `RentalRequest.listingSnapshot` được chụp lại khi chấp thuận; `ContractDataBuilder.resolveListingSnapshot` | Không trộn sửa đổi listing hiện tại vào bản chụp cũ. Snapshot tồn tại nhưng hỏng phải báo lỗi. Code có fallback live listing khi thiếu snapshot; cần xác định hồ sơ tương thích cũ thay vì tự cho dữ liệu thiếu quyền thay lịch sử mới. |
| Ngày vào ở/ngày neo kỳ thuê | `RentalRequest.moveInDate`; thay đổi ngày sớm qua `RentalOnboardingService` | Snapshot Word không được tự đổi lịch thuê. Luồng đổi ngày hiện có phải giữ, kiểm tra lại tiền/xe và lưu lịch sử. |
| Thời hạn thuê/ngày kết thúc | `RentalRequest.leaseMonths`; `Contract.effectiveLeaseMonths` khi gia hạn/phụ lục hợp lệ; `ContractLeaseCalc` | Không dùng `lease.durationMonths/endDateText` do client sửa làm nguồn độc lập. Văn bản phải hiển thị cùng thời hạn nghiệp vụ. |
| Tiền thuê ban đầu | `effectiveMonthlyRent`, nếu không có dùng `monthlyRentPrice`; kiểm tra số tiền bằng `ContractDraftFunding` | Không tự sửa giá đã chấp thuận/tiền kỳ đầu trong API lưu snapshot. Nếu sản phẩm cho thay sau thu tiền phải có xử lý chênh lệch, không sửa receipt. |
| Tiền thuê/phí theo thời điểm sau ký | `ContractTermsResolver`, revision đã ký có hiệu lực, snapshot nguồn của hóa đơn | Phụ lục tương lai chưa được áp dụng sớm; hóa đơn đã phát hành không đổi theo profile/listing/phụ lục mới. |
| Cọc cam kết và cọc thực nhận | `RentalRequest.depositAmount`, điều khoản cọc có hiệu lực; `DepositRecord` và khoản thanh toán đã xác nhận | Phân biệt tiền phải cọc với tiền đã nhận. Điều chỉnh cọc đi qua nghĩa vụ thu/hoàn riêng; F03 còn thiếu ghi nhận khấu trừ ở nhánh quyết toán. |
| Chứng từ và số tiền đã trả | `PaymentRequest`, `PaymentLineItem`, `PaymentEvidence`, `PaymentEvent` | Báo chuyển không đồng nghĩa thực nhận; `initialPayment` trong Word là bản thể hiện, không có quyền thay sổ tiền. |
| Người ở và số xe | `RentalRequest.occupantCount/motorbikeCount/carCount`; các reservation và phụ lục hợp lệ | Sửa con số trên văn bản không tự cấp/sửa chỗ xe hoặc tăng sức chứa. Thay đổi phải kiểm tra sức chứa và giữ chỗ trong cùng transaction. |
| Công tơ đầu kỳ | `initialMetersSnapshot`, loại phí đo đếm trong snapshot | Có thể hoàn thiện trước ký nếu hợp lệ; không âm/thừa độ chính xác, không ghi đè chỉ số đã ký/đã dùng để phát hành hóa đơn. Hồ sơ bàn giao thực tế bổ sung ở giai đoạn 07. |
| Nội quy/phí/nội thất/tiện ích đang thương lượng | Snapshot được chấp thuận, revision nháp và phụ lục sau ký | Đề xuất cho hoàn thiện có kiểm tra từng trường trước ký, hiển thị thay đổi cho bên ký; không cho một ghi chú che sự thay đổi ngày/tiền/danh tính được bảo vệ. |
| Hạn thanh toán, hạn ký, nhắc việc | `BillingTime`, cấu hình nghiệp vụ, `MonthlyBillingSchedule` | Hạn và label do backend tính; không nhận một chuỗi hiển thị ngày hạn làm quyền ghi đè deadline. Không đổi cấu hình trong giai đoạn 00. |
| Văn bản và chữ ký | `ContractRevision`, `ContractDocument`, `SignatureRequest`, snapshot cọc/hash | Chọn đúng revision/mục đích/file sẵn sàng. Bản đã ký không bị sửa/xóa để làm hồ sơ chạy tiếp. |

## 3. Chính sách chỉnh sửa đề xuất cho giai đoạn 01

Mục tiêu là chặn dữ liệu nghiệp vụ lệch nhau, đồng thời giữ khả năng hoàn thiện văn bản đang hoạt động. Đây là bộ quy tắc cần duyệt; chưa áp dụng vào UI/API ở lượt này.

| Loại trường | Cách xử lý đề xuất | Khi thiếu/sai dữ liệu |
|---|---|---|
| ID chủ thể, liên kết rental/listing/contract, CCCD và danh tính bên ký | Đọc từ nguồn backend đã xác thực; chặn đổi qua payload snapshot | Hoàn thiện/xác minh nguồn rồi tạo revision phù hợp trước ký. Không thay người ký bằng sửa tên/CCCD trên Word. |
| Ngày bắt đầu, thời hạn, người ở/xe, tiền thuê/cọc đã chấp thuận | Đối chiếu nguồn nghiệp vụ; payload round-trip cùng giá trị vẫn được chấp nhận | Yêu cầu đi qua luồng thay đổi có thỏa thuận hoặc báo lỗi cụ thể. Không mở thương lượng ngày/tiền tùy ý ngay trong API sửa revision. |
| Trường tài sản xác định căn/phòng và địa chỉ | Đối chiếu bản chụp tài sản và đúng đối tượng thuê | Không tự lấy listing đã sửa để vá bản chụp cũ. Thiếu căn/phòng thật phải hoàn thiện có kiểm tra; không giữ placeholder làm dữ liệu xác minh. |
| Thanh toán, ngân hàng giao dịch, số tiền thực nhận, mã chuyển, thời gian xác nhận | Chỉ đọc; dựng lại từ sổ giao dịch khi cần thể hiện trên văn bản | Chuyển tới luồng thanh toán/đối soát, không cho sửa số liệu trong form hợp đồng. |
| Công tơ, nội thất/tiện ích, nội quy, phí/điều khoản bổ sung được phép | Chỉ được sửa ở trạng thái và phạm vi hợp lệ, có validation và revision; bảo toàn quyền xem lại trước ký | Không tự nới thành tùy ý thay điều khoản cọc đã ký hoặc nghĩa vụ đã chốt. Trường ảnh hưởng khoản đã nhận cần xử lý riêng. |
| Số tiền bằng chữ, ngày kết thúc, nhãn chu kỳ/hạn, số văn bản, trạng thái chữ ký | Backend suy ra từ nguồn cùng phiên bản; không nhận giá trị client mâu thuẫn | Chuẩn hóa giá trị tương đương về cùng biểu diễn; không coi khác format là đổi điều khoản. |

Yêu cầu tương thích khi làm 01:

- Payload lưu cả form nhưng không đổi các trường được bảo vệ vẫn phải lưu được; không chỉ cấm mọi key hiện diện.
- Số `3000000` và chuỗi số hợp lệ tương đương không bị coi là đổi tiền; CCCD/số tài khoản giữ nguyên số 0 đầu.
- Giữ những luồng đang ổn: xem PDF, chữ ký hai bước, tính phí theo kỳ, phụ lục, gia hạn, hủy có hoàn và quyền xem đúng bên.
- Trước preview và trước ký phải kiểm tra lại; dữ liệu do API cũ tạo không được vượt kiểm tra chỉ vì nút lưu mới đã an toàn.
- Từng bản nháp, HĐ cọc, HĐ thuê, gia hạn và phụ lục có loại nghiệp vụ khác nhau; không áp một validation của hợp đồng thuê gốc lên tất cả văn bản.

## 4. Ma trận quyền thao tác

Tất cả quyền tenant/landlord trong bảng chỉ áp dụng cho hồ sơ của chính người đó, lấy actor từ xác thực backend. `SYSTEM` là tác vụ nội bộ được cấp quyền, không phải role do client gửi lên.

| Thao tác | Tenant | Landlord | Admin | System | Tình trạng |
|---|---|---|---|---|---|
| Gửi yêu cầu thuê | Có | Không gửi yêu cầu cho chính listing của mình | Không duyệt thay bằng role admin | Kiểm tra điều kiện | Hiện có |
| Chấp thuận/từ chối yêu cầu | Không | Có, còn đúng bước | Không tự có quyền của chủ nhà | Từ chối contenders trong transaction chấp thuận | Hiện có |
| Hủy giữ chỗ chưa chuyển | Có | Có | Chưa có hành động xử lý vụ việc tương ứng | Quá hạn theo điều kiện giữ chỗ | Hiện có, có guard tiền/chữ ký |
| Báo chuyển tiền | Người trả | Người trả khi hoàn tiền | Không tự nhận là đã chuyển | Không tự xác nhận ngân hàng | Hiện có |
| Xác nhận nhận tiền | Người nhận khoản hoàn | Người nhận tiền thuê/cọc | Không tự ký xác nhận thay | Phản ánh sự kiện đã xác nhận | Hiện có |
| Sửa nháp HĐ thuê | Không | Có theo guard | Không sửa trực tiếp theo luồng của chủ nhà | Tạo snapshot/preview theo luồng | Hiện có; validation F01 còn thiếu |
| Xem nháp/PDF | Theo điều kiện quyền xem của hồ sơ | Theo quyền hồ sơ | Qua endpoint/quyền admin tương ứng | Phục vụ tác vụ đúng phạm vi | Hiện có; quyền xem không cấp quyền sửa/ký |
| Ký HĐ cọc/HĐ thuê | Ký phần mình sau chủ nhà | Ký phần mình | Không ký thay hai bên | Đối soát kết quả provider/nhúng file | Hiện có |
| Xin nhận nhà sớm | Gửi/rút đề nghị | Duyệt/từ chối | Không sửa ngày trực tiếp | Đóng đề nghị hết hạn | Hiện có |
| Hủy sau nhận tiền trước thuê | Gửi đề nghị | Quyết định theo điều kiện | Quy trình giải quyết cần xây | Tạo nghĩa vụ từ quyết định hợp lệ | Hiện có một phần; khách im lặng còn thiếu lối thoát |
| Thông báo vi phạm trước thuê | Phản đối | Gửi/chốt nếu đủ guard | Giải quyết phản đối cần xây | Nhắc/đánh dấu, không tự giữ cọc | Hiện có một phần |
| Phát hành hóa đơn | Xem/báo chuyển | Chuẩn bị/chốt đúng điều khoản | Không đổi số tiền hóa đơn tùy ý | Sinh kỳ/tác vụ đủ điều kiện | Hiện có |
| Phụ lục | Gửi thương lượng/đáp và ký phần mình | Soạn/đáp và ký phần mình | Không sửa bản đã ký | Áp dụng đúng hiệu lực | Hiện có |
| Gia hạn | Gửi yêu cầu, ký phần mình | Quyết định và ký phần mình | Không tự kéo dài thuê | Đóng hết hạn/áp dụng sau ký hợp lệ | Hiện có; F04 còn chặn gửi lại |
| Quyết toán cọc | Đồng ý/phản đối đúng phiên bản | Đề xuất và chứng minh khấu trừ | Chuyển cấp/giải quyết cần xây | Tạo nghĩa vụ và đối chiếu xác nhận | Hiện có một phần; F03 |
| Đóng khoản `DISPUTED` | Phản hồi theo vụ việc | Phản hồi theo vụ việc | Giải quyết có lịch sử, không giả receipt | Thực thi quyết định hợp lệ | Chưa đủ, giai đoạn 02/04 |
| Khiếu nại hiện trạng/bàn giao/sửa chữa | Gửi/đáp/chuyển cấp | Gửi/đáp/chuyển cấp | Nhận xử lý theo phân quyền | Nhắc hạn | Chưa đủ, giai đoạn 04/07 |
| Chấm dứt bình thường khi không nợ | Đề nghị/đáp | Đề nghị/đáp | Xử lý vụ việc nếu cần | Cập nhật khi đủ điều kiện | Chưa có workflow riêng, giai đoạn 08 |

## 5. Ma trận chuyển trạng thái và tác động

Các dòng “thiếu” là yêu cầu thiết kế tiếp, không phải quyền đã được cấp ở giai đoạn 00.

| Sự kiện | Điều kiện chính | Tác động đúng | Khi lỗi/retry | Phạm vi hiện tại |
|---|---|---|---|---|
| Chấp thuận một yêu cầu | Đúng chủ nhà, request chờ, listing còn cho thuê, đủ xe | Request ACCEPTED, listing RESERVED, giữ xe, tạo nghĩa vụ đầu vào, từ chối PENDING khác cùng listing | Cùng transaction; lỗi phải để contenders không bị từ chối riêng lẻ | Hiện có |
| Hết hạn/hủy giữ chỗ chưa chuyển | Không có tiền/chứng từ/chữ ký cần đối soát | Hủy nghĩa vụ chưa trả, giải phóng đúng lượt giữ/xe; không mở lại listing đang bị ẩn/vi phạm | Không được giải phóng lượt giữ mới khi gọi lặp | Hiện có guard; cần DB race nghiệm thu |
| Báo chuyển | Đúng người trả, đúng khoản/hạn/chứng từ | TRANSFER_REPORTED; chưa coi là đã nhận | Không tạo receipt hoặc trừ/hoàn tiền lần nữa | Hiện có |
| Xác nhận cọc | Đúng người nhận, đúng trạng thái | CONFIRMED, sổ cọc HELD; thử tạo HĐ cọc sau commit | Lỗi mẫu/PDF không đảo xác nhận nhận tiền; retry không tạo bản cọc trùng | Hiện có |
| Ký đủ HĐ cọc | Hai chữ ký xác minh, đúng revision, ledger/hồ sơ hợp lệ | Văn bản cọc đã ký; chưa COMPLETED rental, chưa RENTED listing | Callback trùng không kích hoạt thuê hoặc đổi tiền | Hiện có |
| Đến ngày nhận nhà dự kiến | Hồ sơ còn ACCEPTED, đủ điều kiện mở khoản đầu kỳ | Mở đúng nghĩa vụ tiền thuê nếu chưa có, giữ ngày/hạn gốc khi restart | Không sinh thêm cọc; không tự dời ngày thuê | Hiện có |
| Xác nhận đủ tiền tháng đầu | Đúng khoản/bên/số tiền; chưa kết thúc hồ sơ | Có thể tạo nháp thuê theo các guard ngày/mẫu, chưa coi là đã ký | Receipt độc lập với lỗi tạo nháp/preview | Hiện có |
| Sửa/preview bản nháp | Đúng chủ nhà/trạng thái/phạm vi, điều khoản nhất quán | Revision và preview đúng nguồn | Không dùng preview cũ sau đổi; không sửa văn bản đang ký | F01 cần bổ sung ở 01 |
| Ký đủ HĐ thuê | Đủ điều kiện HĐ cọc hoặc miễn, tiền/ngày/hạn/chủ thể/chữ ký đúng | HĐ thuê ACTIVE, rental COMPLETED, listing RENTED, xe ACTIVE | Atomic/idempotent, chữ ký/PDF chưa rõ phải đối soát | Hiện có guard; nhận nhà thực tế chưa thành hồ sơ độc lập |
| Hóa đơn kỳ thuê | Kỳ neo và revision có hiệu lực đúng | Một hóa đơn/nghĩa vụ đúng kỳ; không thu lại tiền đầu kỳ/cọc | Retry và scheduler nhiều instance không tạo trùng | Hiện có test; DB/integration nghiệm thu riêng |
| Phụ lục/gia hạn | Hai bên thống nhất, đúng hạn/phiên bản, đã ký đủ | Chỉ đổi phần có hiệu lực; không sửa chữ ký gốc hoặc tiền đã chốt | Callback đề nghị cũ không hồi sinh trạng thái đã hết hiệu lực | Hiện có, F04 ảnh hưởng gửi lại |
| Tự nhiên hết thời hạn | Đến ngày kết thúc nguồn nghiệp vụ | EXPIRED; vẫn theo dõi phòng/xe/công nợ cần bàn giao | Không tự hoàn/giữ cọc hay CLOSED | Hiện có |
| Chấp thuận quyết toán | Bản đề xuất mới nhất, đủ điều kiện hóa đơn, đồng ý bàn giao/khấu trừ | Ghi đúng sổ cọc; tạo nghĩa vụ hoàn/trả thêm; giải phóng theo xác nhận bàn giao hợp lệ | Không xóa nghĩa vụ tiền khi đã giải phóng phòng; F03 phải sửa | Hiện có một phần |
| Người nhận xác nhận khoản hoàn | Đúng người nhận khoản ngược, chứng từ/trạng thái hợp lệ | Cập nhật thực hoàn, đóng phần nghĩa vụ đủ điều kiện | Báo chuyển/timeout không thay thế xác nhận này | Hiện có; DISPUTED có thể chặn tiếp |
| Mở tranh chấp | Đúng bên, đúng đối tượng, có lý do | Ghi vụ việc, dừng đúng thao tác có nguy cơ, giữ chứng cứ | Không tự đánh dấu đã chuyển/đã nhận hoặc khóa vô hạn không có lối ra | Thiếu đường xử lý ở 02/04 |
| Khách không ký sau cọc | Phân biệt lỗi khách, chủ nhà, mẫu/provider và kết quả chưa rõ | Thông báo → phản hồi/đối soát → thỏa thuận hoặc chuyển xử lý → xử lý tiền/giữ chỗ | Không tự giữ cọc chỉ do hết timer, không giải phóng lượt mới | Thiếu nhánh, giai đoạn 06 |
| Nhận/trả nhà thực tế | Đúng hai bên, tài sản, biên bản/phiên bản | Lưu hiện trạng/chỉ số/chìa khóa và phản đối; không suy từ nút ký | Sửa sau xác nhận có lịch sử; không tự sửa tiền kỳ cũ | Hoàn thiện ở 07 |
| Chấm dứt bình thường | Thỏa thuận hợp lệ, ngày và nghĩa vụ rõ, bàn giao đúng điều kiện | Kết thúc quyền thuê; nghĩa vụ tiền còn lại tiếp tục được theo dõi | Không tạo nợ giả/giữ toàn bộ cọc để dùng nhánh quá hạn | Thiếu, giai đoạn 08 |

**Lưu ý inventory:** `releaseReserved` hiện phục hồi PUBLISHED/EXPIRED theo hạn đăng; `releaseRentedAfterTermination` có hành vi tái đăng và đặt lại thời hạn riêng. Không gộp hai hàm thành một quy tắc “luôn PUBLISHED”. Cần giữ nguyên khác biệt này khi test các thay đổi sau; việc thay chính sách tái đăng không thuộc giai đoạn 00/01.

## 6. Bất biến bắt buộc cho các đợt sửa sau

1. Văn bản và vận hành dùng cùng điều khoản có hiệu lực, đồng thời giữ lịch sử yêu cầu/phiên bản đã ký.
2. Thay listing/profile không viết lại snapshot đã chốt; thay snapshot Word không được đổi chủ thể, tiền hoặc lịch ở nơi khác một cách độc lập.
3. Mỗi chứng từ và nghĩa vụ tiền có nguồn, bên trả/nhận, số tiền và lịch sử; không coi enum hay kết luận admin là bằng chứng giao dịch ngân hàng.
4. HĐ cọc đã ký không tự làm phát sinh quyền “đã nhận nhà”; HĐ thuê ACTIVE và công nợ đã tất toán là hai trục riêng.
5. Hết hạn không tự đồng nghĩa mất cọc, chấm dứt, đã bàn giao hoặc nhận tiền hoàn.
6. Sau giải phóng chỗ ở, nghĩa vụ hoàn/trả thêm vẫn tồn tại đúng bên; retry không tác động lượt giữ mới.
7. Sổ cọc cân đối theo tiền đã thực nhận và chênh lệch đã xác nhận; giữ lại từ cọc và nợ vượt cọc là hai khoản khác nhau.
8. Bên thứ ba không đọc/sửa/ký hồ sơ hoặc chứng cứ; endpoint admin không tự cấp quyền ký hay xác nhận nhận tiền của tenant/landlord.
9. Mỗi transaction đổi tiền/listing/xe phải có thứ tự khóa và hành vi rollback được kiểm chứng trên DB. Ma trận này chưa tuyên bố toàn bộ thứ tự khóa hiện có đã được chứng minh không deadlock.
10. Không bỏ guard đang ổn để chữa một trạng thái bị kẹt. Thêm đường xử lý có quyền, chứng cứ và audit.

## 7. Các quyết định và thời điểm cần duyệt

| Mã | Đề xuất | Hiện trạng / thời điểm cần quyết định |
|---|---|---|
| D01 | Duyệt chính sách trường được sửa tại mục 3: bảo vệ danh tính, lịch/tiền/xe và receipt; vẫn hoàn thiện điều khoản được phép khi chưa ký | Cần duyệt để thực hiện 01; F01 chưa sửa |
| D02 | Giữ luồng hai HĐ ký riêng đối với cọc lớn hơn 0, kể cả cùng ngày; cọc 0/không cọc bỏ qua HĐ cọc | Chủ dự án đã chốt ngày 03/11/2026: bỏ qua HĐ cọc khi không cọc hoặc cọc 0 đồng, không cần văn bản giữ chỗ 0 đồng ký riêng. Phù hợp `ReservationPolicy.V2` hiện có; không thay văn bản đồng ý/hash cũ hoặc tạo receipt chuyển tiền giả. Đã triển khai API/UI và kiểm thử tại báo cáo miễn cọc. |
| D03 | Cho gửi đề nghị gia hạn mới sau từ chối/hết hạn phản hồi nếu còn cửa sổ và không có đề nghị hiệu lực trùng | Cần chốt trước 03B; không mở lại bản cũ |
| D04 | Khiếu nại xử lý ở hai bên trước, được chuyển admin nếu không thống nhất/quá hạn; không coi im lặng là nhận tiền/đồng ý khấu trừ | Cần chốt SLA, quyền đóng/rút/mở lại và trường hợp khẩn cấp trước 04; chưa có cấu hình SLA khiếu nại tổng quát được duyệt |
| D05 | Cọc đã nhận nhưng không ký/không nhận nhà phải có đường xử lý dựa trên nguyên nhân, chứng cứ và kết quả đối soát | Cần chốt điều kiện trước 06; không sửa điều khoản giữ cọc cũ hồi tố |
| D06 | Chấm dứt bình thường không yêu cầu phải có nợ, không mặc định giữ toàn bộ cọc | Cần chốt cách tính kỳ cuối, khoản được khấu trừ/hoàn, bàn giao trước 08 |

Các quyết định tương lai D03–D06 đã được khoanh rõ; chưa cần triển khai để bảo vệ bản nháp ở 01. Không tự thêm SLA hoặc thay chính sách xử lý tiền trong lượt này.

Các mốc cấu hình đang có được giữ nguyên: giữ chỗ 60 phút; phản hồi request 24 giờ; đề nghị nhận sớm 24 giờ; cửa sổ tiền thuê đầu 24 giờ; xác nhận nhận tiền 24 giờ; hạn xử lý ký thuê 48 giờ; thông báo cuối 24 giờ; phản hồi gia hạn thanh toán/quyết toán/phụ lục 48 giờ. Mỗi mốc có điểm bắt đầu và guard riêng; không gộp thành một countdown chung. Số ngày trong `MonthlyBillingSchedule` là quy tắc lịch đang có, không phải SLA khiếu nại.

## 8. Kiểm chứng baseline và thay đổi thật trong lượt này

### Backend

Chạy Maven với profile `isolated-build`, các suite contract/deposit/onboarding/SmartCA/billing/rental/payment/parking liên quan:

- **505 ca được báo cáo: 485 đạt, 20 bỏ qua, 0 failure/error.**
- 20 ca bỏ qua thuộc các kiểm thử DB opt-in/diagnostic provider cần môi trường riêng; không tính là đã đạt.
- Biến bật DB test/provider thật không được cấp trong tiến trình test này. Không chạy seed, drop DB hoặc giao dịch ký/chuyển tiền thật.
- F01–F04 vẫn còn theo review; suite baseline chưa chứng minh chúng được sửa.

### Frontend

- Lượt đầu có 1 test file dừng do tham chiếu `hs-infra/prompts/CONTRACT_PROMPT.md` không còn tồn tại.
- Tìm thấy file được đổi tên thành `HOMESPACE_CONTRACT_PROMPT.md`; sửa duy nhất đường dẫn đó trong `tests/contract-fields.test.cjs`.
- Giữ toàn bộ assertion, không skip hoặc xóa kiểm tra. Chạy lại 6 test file: **51/51 ca đạt, 0 bỏ qua**.
- Các ca gồm chọn PDF đúng loại/revision, quyền/hành động HĐ cọc, lỗi preview, trường form, catalog và placeholder các mẫu. Đây không phải E2E trình duyệt hoặc chữ ký provider thật.

Tổng trong lượt này: **536 ca đã chạy đạt**, **20 ca bỏ qua** có nêu phạm vi. Thay đổi runtime hiện có ở hai file PDF viewer từ lượt sửa trước được giữ nguyên, không gộp thành thay đổi mới của giai đoạn 00.

Log: `%TEMP%/hs-lifecycle-phase00-core-20261103.log` và `%TEMP%/hs-lifecycle-phase00-web-20261103.log`.

## 9. Trạng thái bàn giao và điểm dừng

- Đã có bảng nguồn dữ liệu, phân loại chỉnh sửa đề xuất, ma trận quyền, chuyển trạng thái, tác động tiền/listing/xe/văn bản và nguyên tắc retry/rollback.
- Đã chạy baseline và sửa lỗi đường dẫn test; chưa có thay đổi nghiệp vụ/DB/API/UI trong giai đoạn 00.
- **Chờ chủ dự án duyệt D01 để nghiệm thu phần quy tắc cho giai đoạn 01.** D02 được ghi nhận đúng ngoại lệ hiện có; thay đổi ngoại lệ, nếu muốn, là quyết định riêng.
- Các quyết định D03–D06 còn mở được lưu rõ để duyệt ở giai đoạn phụ thuộc, không tự coi đã có sự đồng ý.
- **Dừng tại đây. Chỉ bắt đầu sửa F01/giai đoạn 01 sau khi chủ dự án đồng ý.**
