```
(Get-Item -LiteralPath "C:\duong-dan\android-chrome-192x192.png").Length
```

# Test Storage Service bằng Postman

Import collection:

```text
hs-infrastructure/postman/Storage Service - Gateway.postman_collection.json
```

File thật được upload trực tiếp từ Postman lên S3. Core API chỉ tạo URL tạm thời và quản lý metadata của file.

## Hiểu nhanh bằng một ví dụ

Giả sử bạn muốn upload ảnh `avatar.png` có dung lượng `245817` byte. Chỉ có **3 request upload chính**:

```text
Request 2: POST /storage/uploads
           "Tôi sắp upload avatar.png, hãy cấp quyền upload"
                         ↓
           Backend tạo metadata PENDING, trả storageId + uploadUrl

Request 3: PUT {uploadUrl}
           Postman gửi bytes của avatar.png thẳng lên S3
                         ↓
           S3 lưu file và trả 200 OK

Request 4: POST /storage/{storageId}/complete
           "Tôi upload xong rồi, backend hãy kiểm tra"
                         ↓
           Backend kiểm tra S3 và đổi PENDING thành READY
```

Các request còn lại chỉ phục vụ tra cứu:

```text
Request 5: lấy thông tin file
Request 6: lấy link để xem file
Request 7: lấy link để tải file
Request 8: lấy danh sách file
Request 9: xóa file
```

## Field nào do bạn nhập?

| Field | Ví dụ | Giải thích đơn giản |
|---|---|---|
| `fileName` | `avatar.png` | Tên gốc mà người dùng nhìn thấy |
| `contentType` | `image/png` | Định dạng thật: PNG=`image/png`, JPG=`image/jpeg`, PDF=`application/pdf` |
| `size` | `245817` | Kích thước chính xác theo byte, không phải KB/MB |
| `purpose` | `USER_AVATAR` | File dùng làm gì để backend kiểm tra loại và dung lượng |
| `visibility` | `PRIVATE` | Ai được phép xin link xem/tải file |
| `referenceType` | `PROPERTY` | Tùy chọn: loại bản ghi nghiệp vụ chứa file |
| `referenceId` | `property-123` | Tùy chọn: ID thật của bản ghi đó |

Ví dụ về reference:

```text
Ảnh bài đăng:  referenceType=PROPERTY, referenceId=property-123
File hợp đồng: referenceType=CONTRACT, referenceId=contract-456
File tin nhắn: referenceType=CHAT,     referenceId=message-789
```

Lần test đầu tiên có thể để cả `referenceType` và `referenceId` rỗng. Chúng chỉ dùng để nhóm và tìm file theo nghiệp vụ.

## Field nào do backend tạo?

| Field | Ý nghĩa |
|---|---|
| `storageId` | ID lâu dài của file, dùng để gọi các API storage về sau |
| `uploadUrl` | Link tạm thời chỉ dùng để PUT file lên S3 |
| `method` | Cho client biết phải dùng HTTP `PUT` |
| `objectKey` | Đường dẫn nội bộ của object trong S3; frontend thường không dùng |
| `expiresAt` | Thời điểm `uploadUrl` hết hiệu lực |

Điểm cần nhớ:

```text
storageId = danh tính lâu dài của file trong hệ thống
uploadUrl = vé tạm thời để upload lên S3
viewUrl/downloadUrl = vé tạm thời để đọc file từ S3
```

Chỉ nên lưu `storageId` vào database nghiệp vụ. Không lưu presigned URL vì URL sẽ hết hạn.

## Luồng tổng quát

```text
1. Lấy access token
2. Xin presigned upload URL từ Core API
3. PUT file trực tiếp lên S3
4. Báo Core API kiểm tra và hoàn tất upload
5. Lấy metadata, URL xem hoặc URL tải file
6. Xóa file khi không còn sử dụng
```

Hãy chạy các request trong collection theo đúng số thứ tự.

## Chuẩn bị biến Postman

Mở collection > **Variables** và điền:

| Variable | Ví dụ | Ý nghĩa |
|---|---|---|
| `username` | `homespace` | Tài khoản Keycloak; mặc định đã trỏ sẵn vào admin được seed |
| `password` | `Homespace@123` | Mật khẩu Keycloak |
| `storageFileName` | `avatar.png` | Tên file muốn upload |
| `storageContentType` | `image/png` | MIME type của file |
| `storageFileSize` | `245817` | Kích thước chính xác theo byte |
| `storagePurpose` | `USER_AVATAR` | Mục đích sử dụng file |
| `storageVisibility` | `PRIVATE` | Quyền truy cập file |
| `storageReferenceType` | để trống | Tùy chọn; ví dụ `PROPERTY`, `CONTRACT` |
| `storageReferenceId` | để trống | Tùy chọn; ID thật của property/contract tương ứng |

Để lấy kích thước file chính xác trên PowerShell:

```powershell
(Get-Item -LiteralPath 'C:\temp\avatar.png').Length
```

Không cần điền `accessToken`, `storageId`, `uploadUrl`, `viewUrl` hoặc `downloadUrl`; các test script sẽ tự lưu.

## 1. Get Token - Password Grant

Request:

```text
Auth / 1. Get Token - Password Grant
```

Request gửi username và password tới Keycloak để lấy access token.

Sau khi thành công, test script tự lưu:

```text
accessToken
```

Các request gọi Core API sau đó sẽ tự dùng token này.

## 2. Create Presigned Upload

Request:

```http
POST {{baseUrl}}/storage/uploads
```

Request này chưa upload file. Nó gửi metadata cho Core API:

```json
{
  "fileName": "avatar.png",
  "contentType": "image/png",
  "size": 245817,
  "purpose": "USER_AVATAR",
  "visibility": "PRIVATE",
  "referenceType": "USER",
  "referenceId": "PROFILE"
}
```

Core API kiểm tra loại file, dung lượng và tạo một URL S3 tạm thời.

Response:

```json
{
  "message": "Upload URL created",
  "result": {
    "storageId": "abc-123",
    "uploadUrl": "https://bucket.s3...",
    "method": "PUT",
    "objectKey": "user_avatar/user-id/abc-123.png",
    "expiresAt": "..."
  }
}
```

Postman tự lưu:

```text
storageId
uploadUrl
objectKey
```

Nếu `uploadUrl` hết hạn trước khi upload, chạy lại request này.

## 3. PUT File Directly To S3

Request:

```http
PUT {{uploadUrl}}
```

Đây là request gửi thẳng tới AWS S3, không đi qua Gateway/Core API.

Trong Postman:

1. Mở request `3. PUT File Directly To S3`.
2. Chọn **Body** > **binary/file**.
3. Nhấn **Select File**.
4. Chọn đúng file đã dùng để tính `storageFileSize`.
5. Kiểm tra `Content-Type` bằng `storageContentType`.
6. Kiểm tra Authorization là **No Auth**.
7. Nhấn **Send**.

Không gửi Bearer token cho request S3. S3 thành công thường trả:

```text
200 OK
```

và body rỗng.

## 4. Complete Upload

Chỉ chạy sau khi bước PUT S3 trả `200`.

Request:

```http
POST {{baseUrl}}/storage/{{storageId}}/complete
```

Body:

```json
{}
```

Core API kiểm tra file trên S3:

- File có tồn tại không.
- Dung lượng có bằng `storageFileSize` không.
- Content-Type có bằng `storageContentType` không.

Thành công trả:

```json
{
  "message": "Upload completed",
  "result": {
    "id": "abc-123",
    "status": "READY"
  }
}
```

Chỉ khi trạng thái là `READY` mới nên gọi API xem hoặc tải file.

## 5. Get Storage Metadata

Request:

```http
GET {{baseUrl}}/storage/{{storageId}}
```

API trả thông tin file, không trả nội dung binary:

```json
{
  "result": {
    "id": "abc-123",
    "originalName": "avatar.png",
    "contentType": "image/png",
    "sizeBytes": 245817,
    "ownerId": "user-id",
    "purpose": "USER_AVATAR",
    "visibility": "PRIVATE",
    "status": "READY"
  }
}
```

## 6. Create View URL

Request:

```http
GET {{baseUrl}}/storage/{{storageId}}/view-url
```

API trả URL S3 tạm thời để xem ảnh, PDF hoặc media trên trình duyệt:

```json
{
  "result": {
    "url": "https://bucket.s3...",
    "expiresAt": "..."
  }
}
```

Postman tự lưu URL vào biến `viewUrl`. Copy giá trị này sang trình duyệt để kiểm tra file.

URL có thời hạn; hết hạn thì gọi lại request này.

## 7. Create Download URL

Request:

```http
GET {{baseUrl}}/storage/{{storageId}}/download-url
```

API trả URL tạm thời để tải file về. Postman tự lưu URL vào `downloadUrl`.

Khác nhau:

```text
view-url     -> ưu tiên mở/xem file
download-url -> ưu tiên tải file về
```

## 8. List My Storage Objects

Request:

```http
GET {{baseUrl}}/storage?page=1&size=20&status=READY
```

Collection hiện gửi thêm:

```text
referenceType={{storageReferenceType}}
referenceId={{storageReferenceId}}
purpose=USER_AVATAR
status=READY
```

Query parameters:

| Param | Mặc định | Mô tả |
| --- | --- | --- |
| `referenceType` | không lọc | Loại tham chiếu nghiệp vụ, ví dụ `USER` |
| `referenceId` | không lọc | Id tham chiếu nghiệp vụ, ví dụ `PROFILE` |
| `purpose` | không lọc | Mục đích file, ví dụ `USER_AVATAR` |
| `status` | `READY` | Trạng thái file; chỉ file `READY` mới xem/tải được |
| `page` | `1` | Trang hiện tại |
| `size` | `20` | Số phần tử mỗi trang |

Ví dụ lấy lịch sử avatar đã upload xong:

```http
GET {{baseUrl}}/storage?referenceType=USER&referenceId=PROFILE&purpose=USER_AVATAR&status=READY&page=1&size=20
```

API chỉ trả các file của user hiện tại khớp bộ lọc. Mặc định không trả file `PENDING` vì file đó chưa upload hoàn tất.

Response phân trang:

```json
{
  "result": [],
  "page": 1,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0,
  "hasMore": false
}
```

Muốn lấy tất cả file của user, tắt hai query parameter `referenceType` và `referenceId` trong Postman.

## 9. Delete Storage Object

Chạy request này cuối cùng:

```http
DELETE {{baseUrl}}/storage/{{storageId}}
```

API xóa file trên S3 và đánh dấu metadata không còn active. Sau khi xóa:

- File không còn xuất hiện trong danh sách.
- Không thể lấy metadata mới.
- Không thể tạo view/download URL mới.

## Các lỗi thường gặp khi test

### S3 trả `403 SignatureDoesNotMatch`

- `Content-Type` bước 3 khác bước 2.
- Presigned URL đã hết hạn.
- Request PUT S3 đang gửi Bearer token.

Chạy lại bước 2, để bước 3 là **No Auth** và không sửa `uploadUrl`.

### `STORAGE_UPLOAD_NOT_FOUND`

Bạn gọi complete trước khi upload file hoặc request PUT S3 đã thất bại.

### `STORAGE_UPLOAD_MISMATCH`

- `storageFileSize` không đúng kích thước file.
- Chọn nhầm file ở bước 3.
- Content-Type không khớp.

Tạo presigned URL mới với metadata đúng rồi upload lại.

### `STORAGE_NOT_READY`

Bạn gọi view/download trước bước complete hoặc complete chưa thành công.

### `STORAGE_UNAUTHENTICATED`

Chưa chạy bước lấy token hoặc Gateway không truyền được thông tin user tới Core API.
