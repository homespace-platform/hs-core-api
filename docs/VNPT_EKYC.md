# VNPT eKYC trên web

Người chưa xác minh dùng VNPT eKYC tại `/settings/account-security`. FPT AI eKYC và Viettel AI eKYC chỉ hiển thị là chưa khả dụng. Hồ sơ VERIFIED đã lưu trước đây tiếp tục được công nhận tại profile, các KYC gate và SmartCA. SmartCA vẫn yêu cầu CCCD khớp chứng thư. Tích hợp Didit và webhook đã được gỡ; nhãn DIDIT trong dữ liệu cũ chỉ được giữ để đọc lịch sử.

## Cấu hình local

1. Đặt credential do VNPT cấp vào `.env` local đã ignore của `hs-core-api`:

```dotenv
VNPT_EKYC_ENABLED=true
VNPT_EKYC_BASE_URL=https://api.idg.vnpt.vn
VNPT_EKYC_CLIENT_ID=<credential-local>
VNPT_EKYC_CLIENT_SECRET=<credential-local>
```

Không dùng credential trong sample SDK; không đặt các giá trị này vào NEXT_PUBLIC hoặc frontend. Chỉ bật sau khi xác nhận tenant có quyền API Web/3D. Dùng endpoint môi trường do VNPT cấp; không tự coi sandbox là production. Access token cần được quản lý/gia hạn theo gói VNPT.
Core chỉ hỗ trợ bảo mật mức 2: gọi `POST /auth/oauth/token` với JSON `client_id`, `client_secret`, `grant_type=client_credentials`. Token được cache trong bộ nhớ theo `expires_in`, cấp lại trước hạn tối đa 60 giây và dùng chung giữa các request đồng thời trong cùng instance. Token lỗi/thiếu thời hạn không được cache. Khi gọi dịch vụ eKYC, Core gửi `Authorization: Bearer <access_token>`; không gửi Token-id/Token-key mức 1.

Nếu VNPT trả HTTP 401 cho một bước SDK, Core bỏ token bị từ chối, lấy token mới và thử lại bước đó tối đa một lần. Timeout, lỗi mạng và 5xx không tự replay bước eKYC. Token và Client Secret không được ghi log hoặc trả về frontend.

Không cần khai báo token tĩnh, chế độ xác thực hoặc Token-id/Token-key. Nếu nâng cấp từ cấu hình mức 1, xóa các field cũ khỏi `.env` rồi restart Core.

Thay đổi mức xác thực chỉ nằm ở Core; không đổi SDK, endpoint HomeSpace, policy KYC hoặc hồ sơ VERIFIED. Chưa tích hợp kiểm tra `dataSign`/`dataBase64`; việc xác minh chữ ký phản hồi là hạng mục riêng.

2. Chuẩn bị SDK từ thư mục `hs-web-app`:

```powershell
python scripts/setup-vnpt-sdk.py
# Hoặc chỉ rõ artifact ZIP đã được cấp quyền sử dụng:
python scripts/setup-vnpt-sdk.py D:/approved-artifacts/web-sdk-version-3.2.1.0.zip
```

Script kiểm checksum ZIP và main bundle; chỉ lấy các asset nằm trong allowlist, không lấy `index.html` chứa credential mẫu. Asset được đặt trong `public/vendor/vnpt/` đã ignore Git. Môi trường build/deploy cần cấp artifact được phép phân phối và chạy bước này trước khi đóng gói. Khi đổi version phải review contract rồi cập nhật checksum/asset list.

3. Restart Core API và Gateway. Web nhận code mới qua dev server; restart web nếu không có hot reload.
4. Schema: bổ sung `expires_at` và `verified_details` (TEXT) vào `kyc_verifications`, bảng `vnpt_kyc_steps`. Với DB cũ, migration Java cập nhật enum CHECK để nhận VNPT và giữ DIDIT; không cần drop dữ liệu. Cấu hình JPA local dùng `update` để thêm cột; môi trường quản lý schema thủ công cần thêm cột trước khi deploy.
5. Đăng nhập tài khoản chưa VERIFIED, mở Tài khoản và bảo mật → chọn VNPT eKYC → Xác minh ngay.

Khi chạy `next dev`, màn hình VNPT có thêm lựa chọn tải ảnh giấy tờ lên để thử nghiệm (`PHOTO_AND_UPLOAD` theo bundle SDK 3.2.1.0; tài liệu gọi lựa chọn này là `BOTH`). Bản build production chỉ hiện chụp trực tiếp. Ảnh JPEG/PNG tối đa 1 MB theo giới hạn backend hiện tại.

## Luồng và giới hạn

- `POST /api/v1/users/me/kyc/vnpt/session` tạo/tiếp tục attempt sở hữu bởi user từ Gateway.
- `GET /api/v1/users/me/kyc/details` trả bản tóm tắt thông tin và kết quả kiểm tra VNPT của chính người đã xác minh. Chỉ lưu các trường hiển thị; không lưu ảnh/hash trong bản kết quả này. Hồ sơ VNPT cũ chưa có bản tóm tắt sẽ trả `null`.
- SDK nằm trong iframe cùng origin. Cầu nối kiểm origin/source/nonce và chỉ chuyển các bước định sẵn sang axios/Gateway. SDK không nhận JWT hay credential VNPT; callback không gửi raw ảnh/kết quả vào React để tự chốt KYC.
- `POST .../vnpt/sessions/{id}/sdk/upload` và các bước `card`, `ocr`, `ocr-front`, `face`, `compare`, `mask` trả envelope cần cho SDK. Credential upstream do Core chèn.
- Route SDK tại Gateway dùng circuit riêng 50s; Core giới hạn upstream 45s, web 55s. JWT, identity propagation và rate limit được giữ.
- `POST .../vnpt/sessions/{id}/finalize` chỉ dùng bằng chứng backend đã nhận. Không có request success/CCCD do frontend tự khai để được VERIFIED.
- Phiên 15 phút, tối đa 5 phiên mới/user/24 giờ, tối đa 30 bước/phiên; ảnh JPEG/PNG mặc định 1 MB. Các giới hạn backend nằm trong `VnptKycProperties` và có thể bind bằng cấu hình `vnpt.ekyc.*` nếu cần sau khi đối chiếu tenant.
- Lỗi provider/timeout không tự gọi lại tính phí. Bước giống hệt đã hoàn tất dùng cache; bước đang chạy hoặc thất bại không tự replay. Nếu thay ảnh khiến bộ bằng chứng không còn nhất quán, đóng/hủy rồi tạo phiên mới.
- Chính sách hiện tại: giấy căn cước 12 số, hai mặt OCR hợp lệ và khớp, không hết hạn/cảnh báo nghiêm trọng, card liveness hai mặt, face liveness 3D, face MATCH và không che mặt. Kết quả liveness phải gắn với đúng ảnh selfie đã upload. Chưa hỗ trợ hộ chiếu, CMND 9 số hoặc xác thực NFC/VNeID.
- Parser nhận schema có căn cứ trong tài liệu; trường bắt buộc thiếu hoặc schema khác sẽ không tự VERIFIED. Cần đối chiếu response tenant thật trước nghiệm thu live, đặc biệt card_type, OCR checking/match fields và liveness 3D.
- Ảnh chỉ được chuyển tiếp trong request; không lưu S3/raw base64. Lưu bằng chứng tối thiểu có TTL 24 giờ, được dọn mỗi giờ. CCCD gắn vào user khi đã đạt policy và claim duy nhất. Không ghi raw payload/credential vào log hay Kafka.
- Bước face 3D của SDK upload thêm blob quét dạng `text`; Core giới hạn kích thước, chỉ chấp nhận mã hash của blob này trong đúng phiên hiện tại và không tính nó là ảnh giấy tờ/khuôn mặt.
- Capture VNPT được triển khai trên web. Admin portal có thêm xác minh thủ công tại chi tiết tài khoản; mobile chưa triển khai.

## ADMIN xác minh thủ công

- `GET /api/v1/admin/users/{userId}/kyc` đọc trạng thái; `POST .../kyc/verify` nhận JSON `{ "cccd": "<12 chữ số>" }`. Chỉ ADMIN đang hoạt động được thao tác; danh tính người thực hiện lấy từ context đã xác thực.
- Core kiểm tra tài khoản đang hoạt động, chưa VERIFIED và CCCD chưa thuộc tài khoản khác. CCCD giữ dạng chuỗi để không mất số 0 đầu.
- Ghi CCCD và hồ sơ `provider=ADMIN`, `status=VERIFIED`, thời điểm xác minh và `createdBy` trong cùng transaction. Đóng các phiên đang chờ để kết quả VNPT đến muộn không ghi đè.
- Xác minh ADMIN và finalize VNPT được tuần tự hóa theo user; kiểm tra trùng CCCD dùng khóa advisory chung và unique index hiện có. Schema migration mở rộng CHECK provider, giữ lịch sử DIDIT/VNPT.
- Các KYC gate, profile và SmartCA công nhận hồ sơ ADMIN VERIFIED qua cùng policy; không giả lập kết quả sinh trắc học VNPT.

## Nghiệm thu

Kiểm thử local dùng mock provider và PostgreSQL test riêng, không gọi VNPT/SmartCA thật. Test PostgreSQL chỉ chạy khi `HS_KYC_TEST_PG_URL` khớp tên database riêng `core_kyc_test_*` cùng credential test; tuyệt đối không trỏ vào DB ứng dụng vì test dùng create-drop.

Đã kiểm tra: 31 test Core (gồm 4 test PostgreSQL), 11 test Gateway, 4 test web; lint/typecheck web và admin. SDK đã tải và hiển thị màn hình capture từ asset local; trường hợp camera bị từ chối dùng mock. Chưa kiểm tra capture camera thật, tenant VNPT, response live và toàn bộ luồng SmartCA sau VNPT.

Trước khi bật dùng thật: kiểm tra credential/quyền tenant, browser camera qua localhost/HTTPS, toàn bộ network qua Gateway, quota và response Web/3D; thử thành công, từ chối camera, hủy/refresh, ảnh lỗi, trùng CCCD, và SmartCA. Bản ZIP tự báo getVersion khác tên file nên cần VNPT xác nhận release đang dùng. Không đánh dấu live thành công chỉ dựa vào test mock.
