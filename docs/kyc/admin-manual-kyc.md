# ADMIN nhập KYC thủ công (27/11/2026)

Yêu cầu chủ dự án: ADMIN nhập tay thông tin xác thực KYC và kết quả kiểm tra cho một user, để test dễ.

## Quy tắc
- Chỉ role ADMIN. Kiểm quyền ở backend, không chỉ ẩn nút.
- ADMIN **không xem** thông tin KYC của user: API cho admin chỉ trả trạng thái (đã/chưa xác minh, nhà cung cấp, thời điểm), không bao giờ trả CCCD, họ tên, ngày sinh, địa chỉ... (kể cả dạng che).
- Chưa xác minh: nhập đủ trường thì user thành đã xác minh. Đã xác minh: ghi đè toàn bộ giá trị cũ (form luôn trống, không nạp giá trị cũ).
- Nhà cung cấp hiển thị: **"Quản trị viên"** (mã lưu do code chọn, ví dụ `MANUAL_ADMIN`, theo cách đang lưu "VNPT eKYC").
- Ghi xuống đi qua đúng entity `UserKycDetails` nên được mã hoá bằng `HOMESPACE_ENCRYPTION_KEY` và cập nhật `cccd_hash`. CCCD đã thuộc user khác → từ chối với mã lỗi trùng CCCD đang có.
- Sau khi ghi, user thấy kết quả như KYC thật (thẻ "Thông tin xác thực KYC", kết quả kiểm tra) và các luồng cần KYC (đăng tin, ký HĐ, SmartCA...) coi như đã xác minh, giống hệt kết quả VNPT thành công.
- Ghi vết: log/audit ai (adminId) đặt KYC cho user nào lúc nào, KHÔNG ghi dữ liệu cá nhân.

## Trường nhập (đều bắt buộc, trừ ghi chú)
CCCD (12 số), Họ và tên trên CCCD, Ngày sinh, Giới tính (Nam/Nữ), Nơi thường trú, Ngày cấp, Ngày hết hạn, Nơi cấp, Độ khớp khuôn mặt (0–100, mặc định 100), và **toàn bộ** cờ kết quả kiểm tra đang có trên `UserKycDetails` (documentValid, frontBackIdentical, clearCorners, idQualityGood, issueDateQualityGood, unexpired, liveDocument, noWarnings, noOverlayImage, eyesOpen, faceNotBlurred, faceValid, faceNotCovered, và cờ khác nếu có) — form mặc định tất cả **Đạt** để "full 100%". Ngày theo đúng định dạng đang lưu từ VNPT (dd/MM/yyyy). Kiểm: ngày hợp lệ, ngày cấp ≤ hôm nay, ngày cấp < ngày hết hạn, ngày sinh < ngày cấp.

## API (core, qua gateway, theo tiền tố admin đang dùng)
- `GET  .../admin/users/{userId}/kyc` → `{ verified, provider, providerLabel, verifiedAt }`
- `PUT  .../admin/users/{userId}/kyc` body như trên → cùng dạng response. Idempotent theo nội dung; ghi đè nếu đã có.
- Lỗi: 400 dữ liệu sai (message tiếng Việt), 403 không phải ADMIN, 404 user không tồn tại, 409 CCCD đã dùng cho tài khoản khác.
Đường dẫn chính xác do agent core chốt theo controller admin hiện có và ghi lại vào mục dưới.

## Đường dẫn đã chốt
Tiền tố gateway: `/api/v1`. Controller: `AdminKycController` (`/admin/users/{userId}/kyc`).

### GET `/api/v1/admin/users/{userId}/kyc`
- Quyền: ADMIN có quyền `KYC_VIEW`. Không body.
- Response 200 (bọc `ApiResponse`): `{ "code": 1000, "result": { "verified": boolean, "provider": string|null, "providerLabel": string|null, "verifiedAt": ISO-8601 instant|null } }`
- Chưa xác minh: `verified=false`, ba trường còn lại `null`. Không bao giờ có CCCD/họ tên/ngày sinh/địa chỉ.

### PUT `/api/v1/admin/users/{userId}/kyc`
- Quyền: ADMIN có quyền `KYC_MANAGE` (quyền mới trong catalog, tự gán cho vai trò ADMIN khi khởi động). Idempotent; ghi đè toàn bộ nếu đã xác minh (form luôn trống).
- Request JSON (tất cả bắt buộc, kiểu string):
  - `cccd`: đúng 12 chữ số, ví dụ `"001234567890"`
  - `fullName`: họ và tên trên CCCD (văn bản thường, lưu đúng như nhập, đã trim)
  - `birthDate`, `issueDate`, `expirationDate`: **ISO `yyyy-MM-dd`** (từ date picker), ví dụ `"2000-01-31"`. Server đổi sang `dd/MM/yyyy` khi lưu (cùng kiểu dữ liệu VNPT nên thẻ KYC của user hiển thị giống nhau)
  - `gender`: `"Nam"` hoặc `"Nữ"`
  - `permanentAddress`, `issuePlace`: văn bản thường, lưu đúng như nhập (trim); không gọi VietMap hay chuẩn hoá địa chỉ
- KHÔNG gửi độ khớp khuôn mặt hay cờ kiểm tra: server tự đặt `faceMatchPercent=100` và mọi cờ kết quả (documentValid, frontBackIdentical, clearCorners, idQualityGood, issueDateQualityGood, unexpired, liveDocument, noWarnings, noOverlayImage, eyesOpen, faceNotBlurred, faceValid, faceNotCovered) = đạt (true). Trường lạ trong body bị bỏ qua.
- Kiểm: CCCD 12 số; ngày hợp lệ; ngày cấp <= hôm nay (giờ VN); ngày cấp < ngày hết hạn; ngày sinh < ngày cấp; giới tính Nam/Nữ; các trường chữ không rỗng.
- Response 200: cùng dạng GET, `provider="ADMIN"`, `providerLabel="Quản trị viên"`, `verifiedAt` = thời điểm vừa ghi.

### Lỗi (body `{ "code": number, "message": string }`)
- 400, code `2510`: dữ liệu sai, `message` tiếng Việt nêu trường lỗi (ví dụ "CCCD phải gồm đúng 12 chữ số.", "Ngày cấp phải trước ngày hết hạn.").
- 403, code `1003`: không phải ADMIN (PUT thiếu `KYC_MANAGE`, GET thiếu `KYC_VIEW`).
- 401, code `1002`: chưa đăng nhập.
- 404, code `2002`: user không tồn tại.
- 409, code `2512`: CCCD đã dùng cho tài khoản khác.

### Sau khi ghi
User thấy kết quả như KYC thật qua endpoint chi tiết KYC hiện có (provider lưu `ADMIN` trong `kyc_verifications`); các luồng cần KYC (đăng tin, ký HĐ, SmartCA) coi là đã xác minh. Thông báo "KYC_VERIFIED" gửi cho user như VNPT thành công. Log audit: `adminId`, `userId`, thời điểm, không có dữ liệu cá nhân.

## Mở rộng nhiều nhà cung cấp (chủ dự án 27/11)
- Hệ thống đã có `kyc_verifications` với cột `provider` (enum `KycProvider`: DIDIT, VNPT, ADMIN). Nhập thủ công dùng **`KycProvider.ADMIN`**, nhãn hiển thị "Quản trị viên". Không tạo provider mới cho việc này.
- Bảng `vnpt_kyc_steps` đặt cứng tên VNPT → đổi thành bảng chung **`kyc_provider_steps`** có cột **`provider`** (varchar, NOT NULL). Dữ liệu cũ giữ nguyên, gán `provider='VNPT'`. Đổi tên bằng migration chạy trước Hibernate (theo mẫu `*SchemaMigration`), idempotent: chỉ rename khi bảng cũ còn và bảng mới chưa có; đổi luôn tên index `idx_vnpt_step_attempt` → `idx_kyc_step_attempt`.
- Entity/repo/store đổi tên trung tính (`KycProviderStep`, `KycProviderStepRepository`); truy vấn lọc theo `provider`. Code gọi VNPT (`VnptKycProxy`) giữ nguyên tên vì đúng là riêng VNPT.
- Nhãn nhà cung cấp hiển thị lấy từ một chỗ duy nhất (enum hoặc map trong code): VNPT → "VNPT eKYC", ADMIN → "Quản trị viên", DIDIT → "Didit". Thêm FPT.AI sau này = thêm giá trị enum + nhãn + client, không đổi bảng.
- Không dựng thêm interface/adapter cho nhà cung cấp tương lai (chưa có FPT.AI để tích hợp).

## Chỉ số khi ADMIN xác thực (chủ dự án 27/11)
- Mọi chỉ số phần trăm/điểm (độ khớp khuôn mặt và bất kỳ trường %/score/confidence nào khác trên `UserKycDetails`, `KycVerification` hay kết quả lưu) **tự đặt 100%**, và mọi cờ kết quả kiểm tra **tự đặt Đạt**. ADMIN không phải nhập các mục này; form chỉ hiện chúng ở dạng đã đạt (chỉ đọc). Server tự gán, không nhận giá trị khác từ request.

## Form nhập (chủ dự án 27/11)
- Nơi thường trú và nơi cấp: **ô text thường**, không gọi VietMap/không chọn tỉnh-huyện-xã; server lưu nguyên văn (trim), không resolve địa chỉ.
- Ngày sinh, ngày cấp, ngày hết hạn: **date picker** (theo component chọn ngày đang dùng trong hs-admin-portal). Client gửi đúng định dạng API đã chốt ở mục "Đường dẫn đã chốt".
