# HomeSpace Notification Service

Module này quản lý OTP cho email và SMS. Controller được đặt ở `hs-api-service`, còn toàn bộ nghiệp vụ, Redis repository và dịch vụ gửi OTP nằm trong module này.

## Luồng OTP

1. Client tạo challenge bằng `POST /notifications/otp/challenges`.
2. Backend sinh mã 6 chữ số bằng `SecureRandom`.
3. Backend chỉ lưu SHA-256 hash của OTP trong Redis, TTL mặc định 3 phút.
4. Profile `dev` ghi OTP ra application log. Profile `smtp` gửi OTP qua Google SMTP.
5. Client gửi `challengeId` và mã đến endpoint verify.
6. Challenge bị xóa ngay sau khi xác minh thành công, nên OTP không thể dùng lại.

Giới hạn mặc định:

- OTP hết hạn sau 3 phút.
- Phải chờ 60 giây mới được gửi lại.
- Tối đa 5 lần nhập sai.
- Tối đa 5 lần gửi cho cùng một địa chỉ trong một giờ.

## Chạy profile dev

`.env`:

```properties
SPRING_PROFILES_ACTIVE=dev
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=homespace123
OTP_HASH_SECRET=replace-with-at-least-32-random-characters
```

OTP xuất hiện trong log dạng:

```text
DEV OTP | destination=nv***@gmail.com purpose=LOGIN code=123456 expiresInMinutes=3
```

## Gửi email thật bằng Google SMTP

1. Bật 2-Step Verification cho tài khoản Google.
2. Tạo Google App Password tại `Google Account > Security > App passwords`.
3. Không dùng mật khẩu Gmail thông thường.
4. Cấu hình `.env`:

```properties
SPRING_PROFILES_ACTIVE=smtp
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=homespace.platform@gmail.com
SMTP_PASSWORD=your-16-character-google-app-password
SMTP_FROM=homespace.platform@gmail.com
SMTP_FROM_NAME=HomeSpace
```

Khởi động lại `hs-api-service` sau khi đổi profile hoặc biến môi trường.

## SMS

API và channel `SMS` đã được thiết kế. Ở profile `dev`, SMS OTP cũng được ghi ra log để test toàn bộ luồng. Ở profile khác, nhánh `sendSms(...)` trong `OtpDeliveryServiceImpl` hiện trả về `4007 - Notification provider is unavailable`. Sau này chỉ cần tích hợp SDK/API của nhà cung cấp tại đây; không phải sửa OTP service hoặc controller.

## API

### Tạo OTP

```http
POST /api/v1/notifications/otp/challenges
Content-Type: application/json

{
  "channel": "EMAIL",
  "purpose": "LOGIN",
  "destination": "user@example.com"
}
```

`channel`: `EMAIL` hoặc `SMS`.

`purpose`: `LOGIN`, `REGISTER`, `PASSWORD_RESET`, `VERIFY_EMAIL`, `VERIFY_PHONE`.

Response chỉ trả `challengeId`, địa chỉ đã che, thời điểm hết hạn và thời gian chờ gửi lại. Response không bao giờ trả OTP.

### Xác minh OTP

```http
POST /api/v1/notifications/otp/challenges/{challengeId}/verify
Content-Type: application/json

{
  "code": "123456"
}
```

### Gửi lại OTP

Chỉ gọi sau thời gian `resendAfterSeconds` trong response tạo challenge:

```http
POST /api/v1/notifications/otp/challenges/{challengeId}/resend
```

Endpoint trả một `challengeId` mới và vô hiệu hóa challenge cũ sau khi gửi thành công.

## Lưu ý production

Các endpoint hiện dùng được để kiểm thử theo security config hiện tại của dự án. Khi nối với Keycloak Authenticator, nên chuyển chúng thành API nội bộ và yêu cầu service credential/mTLS. Backend phải tự lấy email hoặc số điện thoại đã xác thực của user; không tin `destination` do browser gửi lên.
