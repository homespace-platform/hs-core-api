# Đồng bộ bài đăng đã duyệt vào hợp đồng — 05/11/2026

## Luồng sử dụng

1. Trong bản nháp và chế độ phụ lục, các trường nguồn Listing (địa chỉ, diện tích, đặc điểm, tiện ích, tài sản bàn giao, nội quy) chỉ đọc. API cũng chặn sửa tay các nhóm này.
2. Chủ nhà sửa bài gốc, gửi duyệt. Nội dung mới nằm riêng trong `pendingEditPayload`; nội dung đã duyệt và trạng thái vận hành tiếp tục áp dụng. Hàng chờ admin bao gồm bản sửa của tin PUBLISHED/RESERVED/RENTED/HIDDEN/EXPIRED.
3. Admin đối chiếu và duyệt đúng `pendingEditId`. Từ chối cần lý do. Khi duyệt, giữ **trạng thái tại thời điểm quyết định**, kể cả hồ sơ thuê đã chuyển trạng thái sau khi gửi sửa; lưu lịch sử và bản chụp nội dung được duyệt. Không khôi phục trạng thái giữ chỗ đã hết hạn, không đưa tin đang thuê ra công khai.
4. Bên cạnh Lưu/Lưu nháp có **Cập nhật dữ liệu từ bài đăng**. Xem chênh lệch rồi chọn đưa vào form. Bước này chưa ghi hợp đồng. Giá/cọc/phí/chu kỳ/cách tính điện nước được đối chiếu riêng và không tự ghi đè các khoản đã chốt.
5. Với hợp đồng gốc DRAFT, Lưu gửi mã bản Listing đã duyệt, kiểm tra lại revision hợp đồng + phiên bản Listing, ghi revision mới rồi kết xuất Word/PDF của đúng revision đó. Bản xem trước cũ bị đánh dấu hết giá trị. Chưa ký mới được sửa; các khóa chữ ký/đối soát cũ vẫn áp dụng.
6. Với hợp đồng ACTIVE, dùng bản Listing đã duyệt để soạn phụ lục. Snapshot nguồn được lưu ngay khi gửi đề xuất, nên Listing sửa lần nữa không đổi nội dung người thuê đang duyệt. Áp dụng sau đồng ý, đủ chữ ký và tới đầu kỳ hợp lệ theo luồng phụ lục hiện có.
7. Chênh lệch tiền xử lý qua **Soạn thỏa thuận tài chính** của phụ lục riêng. Không ghép giá/cọc/phí mới với yêu cầu đồng bộ Listing. Hóa đơn và giao dịch đã thu giữ lịch sử; điều chỉnh cọc dùng luồng thu/hoàn phần chênh lệch hiện có. Trước khi hợp đồng có hiệu lực, bảng đối chiếu chỉ thông tin chênh lệch, không tự đổi thỏa thuận thanh toán đã xác nhận.

## Sửa thông tin phòng đang giữ chỗ/cho thuê

- Sửa nội dung bài đăng, mã/tên phòng hoặc căn và liên kết chi nhánh vẫn phải qua admin. **Loại hình** cố định sau khi tạo; mã định danh Listing không thay đổi. Tin đang RESERVED/RENTED không bị khóa chỉnh sửa chỉ vì có hồ sơ thuê.
- Lỗi kiểm tra trường hoặc xung đột bản sửa (`409`) giữ nguyên form và dữ liệu vừa nhập để sửa rồi gửi lại. Chỉ mã lỗi `4012` (cho thuê ngoài hệ thống) mở màn hình khóa tương ứng.
- Dữ liệu mới chỉ áp dụng vào hợp đồng khi chủ nhà chọn **Cập nhật dữ liệu từ bài đăng** rồi Lưu; duyệt tin không tự sửa bản hợp đồng hoặc chỗ xe đang áp dụng.
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

- GET `/api/v1/contracts/{id}/listing-sync`: chủ nhà đối chiếu nguồn đã duyệt với revision đang áp dụng.
- PATCH revision bản nháp: `baseRevisionId` + `listingSourceVersion`. Client không gửi lại dữ liệu Listing để tin cậy.
- Payload phụ lục: `changes: {freeForm: true, listingSourceVersion: "…"}`; có thể kèm thông tin các bên/thỏa thuận riêng. Không kèm sửa tiền thuê/cọc/phí.
- PATCH `/api/v1/admin/listings/{listingId}/edits/{editId}`: `{approve: true}` hoặc `{approve: false, reason: "…"}`. Token cũ bị từ chối.
- Source snapshot được đóng băng tại lần duyệt, gồm cả địa chỉ/tiện ích từ chi nhánh. Không dựng lại từ dữ liệu chi nhánh có thể đã sửa sau đó.
- Bản cũ chưa có snapshot duyệt cần gửi lại bản sửa qua admin trước khi dùng nút đồng bộ. Không tự sửa hợp đồng cũ.

## Kiểm thử và vận hành

Các ca tự động bao gồm giữ trạng thái duyệt tin; token cũ; khóa field API; giữ snapshot khi đang chờ duyệt phụ lục; không sửa lịch sử tiền; 6 → 3 tháng/ngày cuối tháng; kỳ riêng dài hơn; hợp đồng đã ký; hết hạn mới; hóa đơn đã phát hành; người/xe vượt giới hạn; chỗ xe đã kích hoạt; policy theo mã danh mục; dùng số người/xe của phụ lục đang hiệu lực để tính chênh lệch.

Web có test component/logic và browser fixture thật trên Chrome desktop/mobile, dùng API giả lập; bao gồm Lưu tự kết xuất, quyền người thuê, khôi phục nháp, soạn tài chính riêng, phiên bản cũ và luồng duyệt/ký phụ lục hiện có. Không gọi SmartCA hoặc xác nhận tiền thật trong bộ này.

Kết quả lần triển khai: **239 test backend**, **34 test logic/component web**, **28 nhóm kiểm tra browser**, TypeScript web/admin và lint component xem bản sửa admin đều qua. Build backend dùng profile `isolated-build` để không thay lớp đang chạy trong IDE. Chưa chạy kiểm thử tích hợp trực tiếp với PostgreSQL/SmartCA và dịch vụ kết xuất đang vận hành; browser fixture không ghi vào hồ sơ thật.

Lần sửa lỗi chặn edit: **321 test backend** qua (57 Listing/chỗ xe + 264 hợp đồng/hóa đơn), gồm đổi mã phòng qua admin nhưng giữ trạng thái, chuyển chỗ xe giữa chi nhánh và giữ nguyên chi nhánh đích của phụ lục khi Listing đổi tiếp. Web bổ sung test lỗi `409` giữ form/thử lại và `4012` khóa đúng; browser desktop/mobile kiểm tra mã phòng mới đã duyệt, nguồn phụ lục và Lưu tự kết xuất bằng API giả lập.

Khởi tạo CSDL dev mới: dừng Core trước khi người vận hành reset CSDL, rồi khởi động Core với `JPA_DDL_AUTO=update` để Hibernate tạo schema theo entity hiện tại và bootstrap tài khoản/catalog. Sau khi Core khởi động thành công, chạy seed chính `hs-infra/postgres/seed/listings_seed_hcm.sql` theo README của seed. Không cần SQL vá cột riêng cho đồng bộ Listing; các trường mới đã được ánh xạ trong `Listing`, `ContractRevision`, `ContractAmendment`. `validate/none` không tạo schema trên CSDL trống. Việc reset CSDL và chạy seed do người vận hành thực hiện.
