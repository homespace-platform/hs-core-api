# Đồng bộ bài đăng đã duyệt vào hợp đồng — 05/11/2026

## Luồng sử dụng

1. Trong bản nháp và chế độ phụ lục, các trường nguồn Listing (địa chỉ, diện tích, đặc điểm, tiện ích, tài sản bàn giao, nội quy) chỉ đọc. API cũng chặn sửa tay các nhóm này.
2. Chủ nhà sửa bài gốc, gửi duyệt → `status=PENDING_REVIEW`, xuất hiện trong tab Chờ duyệt của chủ tin/admin và ngừng xuất hiện trong tìm kiếm công khai. Nội dung mới nằm riêng trong `pendingEditPayload`; nội dung đã duyệt vẫn phục vụ hồ sơ thuê hiện tại. `reviewOperationalStatus` giữ tình trạng vận hành, `reviewOriginStatus` giữ nguồn gửi duyệt; không hủy tiền/chữ ký/hồ sơ thuê chỉ vì gửi chỉnh sửa. Hàng chờ bao gồm bản sửa của tin PUBLISHED/RESERVED/RENTED/HIDDEN/EXPIRED.
3. Admin xem nội dung đã gửi ngay trong trang chi tiết thông thường và dùng nút Duyệt/Từ chối cùng hộp xác nhận như tin mới. Không có khối so sánh hoặc nút duyệt bản sửa riêng. API chi tiết admin dựng bản xem từ nội dung đang chờ, không ghi vào Listing thật; địa chỉ, ảnh, tiện ích, nội thất, phí và nội quy đều lấy từ nội dung này. Client gửi đúng `pendingEditId`; token cũ bị chặn nếu chủ nhà gửi lại. Duyệt nội dung RESERVED/RENTED trả về **trạng thái vận hành tại lúc quyết định**, không khôi phục mù trạng thái trước khi sửa. Duyệt tin PUBLISHED/EXPIRED → PUBLISHED với thời hạn hiển thị mới; từ chối → REJECTED. Tin HIDDEN sửa nội dung vẫn HIDDEN. Tin nháp/bị từ chối/hết hạn gửi đăng lại theo luồng thông thường → admin duyệt thành PUBLISHED.
4. Nút **Cập nhật dữ liệu từ bài đăng** lấy ngay bản đã duyệt, không mở hộp so sánh hoặc yêu cầu chọn lại. Trên hợp đồng gốc DRAFT, một lần bấm sẽ lưu dữ liệu vào revision rồi tự kết xuất Word/PDF, kể cả không có chênh lệch nội dung. Dữ liệu liên hệ/chỉ số/thỏa thuận đang nhập được lưu cùng lần đó. Còn thiếu thông tin bắt buộc thì form chỉ rõ ô cần hoàn thiện, chưa tạo file thiếu dữ liệu.
5. Bản nháp gửi mã nguồn Listing đã duyệt và revision hợp đồng để chống lưu đè. Nhánh lấy/lưu dữ liệu trực tiếp không dựng bảng so sánh nội dung hoặc chênh lệch tiền. Giá/cọc/phí/chu kỳ/cách tính điện nước đã chốt vẫn theo nghiệp vụ tài chính hiện có. Ghi revision mới rồi kết xuất đúng revision đó; file cũ bị đánh dấu STALE. Kết xuất lỗi vẫn giữ bản nháp đã lưu, lần thử lại chỉ kết xuất. Chưa ký mới được sửa; các khóa chữ ký/đối soát cũ vẫn áp dụng.
6. Với hợp đồng ACTIVE, nút cập nhật đưa ngay dữ liệu vào phụ lục đang soạn, không mở đối chiếu Listing. Việc gửi đề xuất/đồng ý/ký vẫn theo luồng phụ lục; bảng thay đổi của đề xuất được giữ để các bên duyệt và lưu lịch sử. Snapshot nguồn đóng băng ngay khi gửi đề xuất, nên Listing sửa tiếp không đổi nội dung người thuê đang duyệt. Áp dụng sau đồng ý, đủ chữ ký và tới đầu kỳ hợp lệ; không ghi đè file hợp đồng đã ký.
7. Chênh lệch tiền xử lý qua **Soạn thỏa thuận tài chính** của phụ lục riêng. Không ghép giá/cọc/phí mới với yêu cầu đồng bộ Listing. Hóa đơn và giao dịch đã thu giữ lịch sử; điều chỉnh cọc dùng luồng thu/hoàn phần chênh lệch hiện có. Trước khi hợp đồng có hiệu lực, nút cập nhật dữ liệu không tự đổi thỏa thuận thanh toán đã xác nhận.

## Sửa thông tin phòng đang giữ chỗ/cho thuê

- Trong lúc chờ duyệt, hết giữ chỗ/hủy giữ chỗ cập nhật trạng thái vận hành thành PUBLISHED hoặc EXPIRED theo thời hạn hiển thị còn lại. Ký xong HĐ thuê chuyển trạng thái vận hành thành RENTED. Nhận lại phòng đã được xác nhận hai bên chuyển thành PUBLISHED với thời hạn mới. `status` vẫn PENDING_REVIEW cho tới khi admin xử lý; duyệt sau đó không mở lại phòng hết hạn hoặc mất trạng thái đang thuê. Các thao tác vận hành này giữ lịch sử riêng.
- Chủ tin RENTED_EXTERNALLY còn thời hạn: dùng Phòng trống, hiển thị lại, giữ thời hạn còn lại. Hết hạn: có nút Gửi duyệt lại, admin duyệt → PUBLISHED và cấp thời hạn mới. Không mở khóa sửa nội dung khi tin vẫn đang cho thuê ngoài; chủ tin xác nhận phòng trống/gửi đăng lại trước.
- API quản trị không cho chuyển thủ công RESERVED/RENTED sang PUBLISHED/PENDING_REVIEW/HIDDEN/RENTED_EXTERNALLY. Kiểm tra thêm hồ sơ ACCEPTED và hợp đồng thuê gốc chưa CANCELLED/CLOSED, chưa xác nhận trả phòng; vì vậy không thể lách bằng cách đi qua trạng thái trung gian. HĐ cọc và phụ lục không tạo một lượt chiếm phòng riêng. Khóa VIOLATION vẫn được phép; mở công khai lại vẫn phải vượt qua kiểm tra hồ sơ thuê.
- Chỉ API duyệt đúng phiên bản được xử lý tin có `pendingEditId`; API đổi trạng thái chung không thay thế được thao tác này. Khi admin khóa VIOLATION, bản gửi duyệt bị hủy. Thống kê đếm đúng một lần theo `status`, không cộng thêm bản sửa đang chờ.

- Sửa nội dung bài đăng, mã/tên phòng hoặc căn và liên kết chi nhánh vẫn phải qua admin. **Loại hình** cố định sau khi tạo; mã định danh Listing không thay đổi. Tin đang RESERVED/RENTED không bị khóa chỉnh sửa chỉ vì có hồ sơ thuê.
- Lỗi kiểm tra trường hoặc xung đột bản sửa (`409`) giữ nguyên form và dữ liệu vừa nhập để sửa rồi gửi lại. Chỉ mã lỗi `4012` (cho thuê ngoài hệ thống) mở màn hình khóa tương ứng.
- Duyệt tin không tự sửa hợp đồng hoặc lịch chỗ xe. Chủ nhà bấm **Cập nhật dữ liệu từ bài đăng** trên bản nháp để hệ thống tự lưu và kết xuất; phụ lục theo quy trình duyệt/ký đang áp dụng.
- Khi đổi chi nhánh trên bản nháp, Lưu kiểm tra sức chứa tại chi nhánh mới và thay phần giữ chỗ trong cùng transaction. Không chuyển chỗ xe sang chi nhánh mới chỉ vì sửa số xe khi chưa đồng bộ nguồn Listing.
- Phụ lục đóng băng chi nhánh đích cùng bản nguồn đã duyệt. Chỗ xe ở chi nhánh mới được giữ cho toàn bộ số xe, không lấy chỗ ở chi nhánh cũ để bù. Ký đủ thay lịch xe từ ngày hiệu lực; phần lịch sử ở chi nhánh cũ giữ nguyên. Liên kết chi nhánh của hợp đồng gốc đổi khi phụ lục có hiệu lực. Listing đổi tiếp trong lúc chờ đồng ý/ký không thay chi nhánh đích này.
- `listingBranchId` là metadata liên kết trong bộ điều khoản/phụ lục, nằm ngoài `ListingSnapshotDto` và không thêm placeholder Word. Không ghi đè bản Word/PDF đã ký.

## Mốc thời gian

- DRAFT: người thuê đã chọn đúng mức tối thiểu cũ 6 tháng; Listing được duyệt giảm còn 3 tháng → chọn đồng bộ, Lưu áp dụng ngay 3 tháng, không thêm bước người thuê duyệt.
- Nếu đã chọn kỳ riêng dài hơn mức tối thiểu cũ, không tự rút kỳ này. Mức tối thiểu mới cao hơn thỏa thuận chỉ hiển thị cảnh báo.
- Cập nhật cùng transaction: `rental.leaseMonths`, tổng ước tính kỳ thuê, snapshot thời hạn và ngày kết thúc, khoảng giữ chỗ xe. Giữ nguyên ngày bắt đầu, tiền cọc đã nhận, khoản ban đầu và snapshot yêu cầu ban đầu.
- Ngày kết thúc hiển thị là **ngày ở cuối cùng**; các scheduler/hóa đơn/chỗ xe dùng mốc kết thúc không bao gồm ngày đó. Ví dụ bắt đầu 31/01/2027, thuê 3 tháng: ngày cuối 29/04/2027, mốc end-exclusive 30/04/2027.
- Chặn tự rút kỳ khi đã có hóa đơn, chỗ xe ACTIVE, đang xử lý hủy/đối soát, thời hạn mới đã qua hoặc giới hạn người/xe mới thấp hơn đăng ký.
- ACTIVE: giảm tối thiểu của tin không tự rút thời hạn đã ký. Thay đổi thời hạn thuộc phụ lục có chữ ký và kiểm tra lịch hóa đơn/chỗ xe hiện có.

## API và dữ liệu

- GET `/api/v1/contracts/{id}/listing-sync`: lấy dữ liệu nguồn đã duyệt. Hợp đồng DRAFT dùng `ContractListingSync.source`, bỏ tính bảng chênh lệch; ACTIVE giữ dữ liệu so sánh phục vụ đề xuất phụ lục và lịch sử.
- PATCH revision bản nháp: `baseRevisionId` + `listingSourceVersion`. Client không gửi lại dữ liệu Listing để tin cậy.
- Payload phụ lục: `changes: {freeForm: true, listingSourceVersion: "…"}`; có thể kèm thông tin các bên/thỏa thuận riêng. Không kèm sửa tiền thuê/cọc/phí.
- PATCH `/api/v1/admin/listings/{listingId}/edits/{editId}`: `{approve: true}` hoặc `{approve: false, reason: "…"}`. Token cũ bị từ chối.
- GET chi tiết tin admin: `listing` hiển thị nội dung đã gửi duyệt (nếu có), `status=PENDING_REVIEW`, `operationalStatus` là tình trạng vận hành, `protectedRental` cho biết còn hồ sơ thuê được bảo vệ. Chỉ dùng bản chiếu để xem, không lưu Listing. Không dựng được nội dung thì trả `reviewError`, chặn Phê duyệt nhưng vẫn cho Từ chối. Chủ tin/người đã có yêu cầu thuê vẫn xem nội dung đã duyệt; khách không liên quan không đọc được tin đang chờ duyệt.
- Source snapshot được đóng băng tại lần duyệt, gồm cả địa chỉ/tiện ích từ chi nhánh. Không dựng lại từ dữ liệu chi nhánh có thể đã sửa sau đó.
- Bản cũ chưa có snapshot duyệt cần gửi lại bản sửa qua admin trước khi dùng nút đồng bộ. Không tự sửa hợp đồng cũ.

## Kiểm thử và vận hành

Các ca tự động bao gồm giữ trạng thái duyệt tin; token cũ; khóa field API; giữ snapshot khi đang chờ duyệt phụ lục; không sửa lịch sử tiền; 6 → 3 tháng/ngày cuối tháng; kỳ riêng dài hơn; hợp đồng đã ký; hết hạn mới; hóa đơn đã phát hành; người/xe vượt giới hạn; chỗ xe đã kích hoạt; policy theo mã danh mục; dùng số người/xe của phụ lục đang hiệu lực để tính chênh lệch.

Web có test component/logic và browser fixture thật trên Chrome desktop/mobile, dùng API giả lập; bao gồm Lưu tự kết xuất, quyền người thuê, khôi phục nháp, soạn tài chính riêng, phiên bản cũ và luồng duyệt/ký phụ lục hiện có. Không gọi SmartCA hoặc xác nhận tiền thật trong bộ này.

Kết quả lần triển khai: **239 test backend**, **34 test logic/component web**, **28 nhóm kiểm tra browser**, TypeScript web/admin và lint component xem bản sửa admin đều qua. Build backend dùng profile `isolated-build` để không thay lớp đang chạy trong IDE. Chưa chạy kiểm thử tích hợp trực tiếp với PostgreSQL/SmartCA và dịch vụ kết xuất đang vận hành; browser fixture không ghi vào hồ sơ thật.

Lần sửa lỗi chặn edit: **321 test backend** qua (57 Listing/chỗ xe + 264 hợp đồng/hóa đơn), gồm đổi mã phòng qua admin nhưng giữ trạng thái, chuyển chỗ xe giữa chi nhánh và giữ nguyên chi nhánh đích của phụ lục khi Listing đổi tiếp. Web bổ sung test lỗi `409` giữ form/thử lại và `4012` khóa đúng; browser desktop/mobile kiểm tra mã phòng mới đã duyệt, nguồn phụ lục và Lưu tự kết xuất bằng API giả lập.

Khởi tạo CSDL dev mới: dừng Core trước khi người vận hành reset CSDL, rồi khởi động Core với `JPA_DDL_AUTO=update` để Hibernate tạo schema theo entity hiện tại và bootstrap tài khoản/catalog. Sau khi Core khởi động thành công, chạy seed chính `hs-infra/postgres/seed/listings_seed_hcm.sql` theo README của seed. Không cần SQL vá cột riêng cho đồng bộ Listing; các trường mới đã được ánh xạ trong `Listing`, `ContractRevision`, `ContractAmendment`. `validate/none` không tạo schema trên CSDL trống. Việc reset CSDL và chạy seed do người vận hành thực hiện.

### Nghiệm thu trạng thái duyệt tin — 06/11/2026

- Lượt hồi quy mở rộng: **646 ca backend thực chạy qua**, gồm **12 ca PostgreSQL** với schema Hibernate thật trong container riêng `hs_listing_review_test`; 35 ca PostgreSQL thuộc các bộ khác không bật môi trường riêng nên được bỏ qua. Sau đó thêm 7 ca hồi quy về đọc tin chờ duyệt, thống kê, ký khi chờ duyệt, chặn đồng bộ trước duyệt, xét lại hạn tại lúc quyết định và hủy lịch hẹn sau từ chối. Tổng **653 ca khác nhau đã chạy qua**; các lượt chạy lại 94 và 138 ca cũng qua.
- Web **23 ca**, admin **21 ca**; TypeScript web/admin và ESLint các file giao diện thay đổi đều qua. Các test giao diện dùng API giả lập, không phải E2E với tài khoản/dịch vụ thật.
- PostgreSQL kiểm tra thực tế: ACCEPTED vẫn khóa phòng dù trạng thái tin bị đổi; hợp đồng thuê gốc đang ký/ACTIVE/EXPIRED/TERMINATED giữ khóa đến khi xác nhận trả phòng; CANCELLED/CLOSED không khóa; HĐ cọc/phụ lục không tạo khóa riêng; trạng thái vận hành và nguồn gửi duyệt được lưu/đọc lại đúng khi ký trong lúc chờ.
- Đã kiểm tra và dọn đúng container tạm sau khi chạy. Không đọc/sửa/reset CSDL vận hành, không gọi ngân hàng/S3/SmartCA thật, không sinh SQL vá cột riêng. Khởi động lại Core để nạp code và hai cột nullable `review_operational_status`, `review_origin_status` qua mapping Hibernate hiện có; làm mới web/admin trước khi thử luồng mới.
