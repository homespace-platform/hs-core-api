# Chính sách Listing và bản chụp hợp đồng

## Dữ liệu

`listing_policy_definitions` có code, nhãn, loại PERMISSION/INTEGER/TEXT, mô tả, giới hạn, đơn vị, thứ tự, required và active. `listing_policy_definition_categories` áp dụng HOUSE/APARTMENT/ROOM, unique(policy_code,category). `listing_policy_values` lưu giá trị + ghi chú của chủ nhà, FK Listing và definition, unique(listing_id,policy_code).

Bootstrap tạo definition thiếu, không ghi đè catalog đã có. Frontend lấy metadata qua endpoint listing options hiện có, không hardcode danh sách field riêng. Nháp được thiếu field; gửi duyệt phải có đủ required đúng category. API không nhận code/permission giả, số ngày lẻ/ngoài giới hạn hay field phí phạt.

Nhóm chung: thú cưng, hút thuốc, cho thuê lại, khách qua đêm, thông báo kiểm tra, hạn báo trước và hạn hoàn cọc. HOUSE có cải tạo/lắp đặt; APARTMENT có nội quy tòa nhà và tiện ích chung; ROOM có không gian chung và giờ yên tĩnh. Permission gồm ALLOWED, NOT_ALLOWED, APPROVAL_REQUIRED; cần chấp thuận không đồng nghĩa đã được phép.

## Listing đến hợp đồng

Detail công khai và preview trả `policies` có displayValue. Snapshot Listing của RentalRequest đóng băng giá trị, ghi chú, nhãn và cấu trúc. ContractDataBuilder dùng snapshot đó, không ghi đè từ Listing đổi về sau. Policy snapshot có `listingPolicies` và key tương thích như petPolicy, petsAllowed, depositRefundDays. APPROVAL_REQUIRED có flag null và mô tả chính xác, không bị rút thành true/false. Lifecycle hoàn cọc đọc deadline đã chốt trong revision.

PETS_ALLOWED không còn được seed/đưa ra catalog tiện ích. Không xóa lịch sử hay PDF đã ký. Hợp đồng mới không sinh tiện ích thú cưng; quyền này nằm trong bảng chính sách. Bản cũ không có `listingPolicies` vẫn đọc snapshot legacy, không tự chuyển điều khoản cũ.

Phạt đóng trễ nhập lúc soạn hợp đồng, cùng quy trình chốt công tơ ban đầu; không thuộc Listing. Phạt sinh qua contract.specialTerms, áp dụng hóa đơn theo điều khoản đã ký. Phụ lục nhập tay cho chỉnh nội dung chính sách hoặc thêm/xóa dòng, không bắt chọn theo Listing; thời hạn hoàn cọc/báo trước vẫn phải là số ngày hợp lệ và không xóa nghĩa vụ. Thay phí phạt/hạn thanh toán chỉ áp dụng từ kỳ tương lai sau đủ chữ ký; lịch sử thanh toán và chỉ số gốc giữ nguyên.

## Word và prompt

Backend thêm `{{#policiesTable}}`, hai cột Chính sách | Quy định áp dụng; catalog có 64 mã. CONTRACT_PROMPT.md ở hs-infra/prompts là nguồn hướng dẫn duy nhất để tạo mới hoàn toàn ba DOCX, kiểm tra và upload/activate phiên bản template mới. Không cần mẫu Word cũ hoặc file prompt phụ. Các DOCX hiện có chưa được sửa/xóa trực tiếp trong tác vụ này.

Template cũ chưa có marker: in policy trong khối thỏa thuận để không mất dữ liệu, trả warning completeness. Template mới có marker: không lặp đoạn fallback. Cần sửa mẫu để bỏ câu mặc định trái Listing, nhất là thú cưng dẫn về Bảng tiện ích hoặc giờ cố định. Không sửa PDF đã ký. Chỉ kiểm tra XML/placeholder, chưa xác minh bố cục bằng ảnh do runtime thiếu bundled LibreOffice.

## Reset và kiểm thử

Người dùng tự reset DB; tác vụ không drop DB. Sau reset, khởi động Core để JPA tạo bảng/initializer tạo catalog rồi chạy hs-infra/postgres/seed/listings_seed_hcm.sql. Seed chọn policy theo catalog/category, sinh giá trị hợp lệ đa dạng; không có phạt. Không seed trước bootstrap Core.

Kiểm tra tạo/sửa/nhân bản cả ba loại, đổi category không gửi field loại khác, thiếu required bị chặn. Xem detail/preview, gửi yêu cầu thuê, đối chiếu snapshot khi Listing đổi về sau. Trên DRAFT nhập phạt và công tơ; Listing không có phạt. Kiểm tra Word/cảnh báo template cũ. Khi ACTIVE, chỉnh policy qua phụ lục, đối chiếu trước/sau và đủ hai chữ ký; dữ liệu gốc giữ nguyên.
