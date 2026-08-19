# Thiết lập AWS S3 cho Storage Service

Tài liệu này hướng dẫn thành viên dự án cấu hình AWS S3 cho môi trường development và lấy Access Key để chạy `hs-api-service` trên máy local.

## Thông tin dùng chung

| Cấu hình | Giá trị |
|---|---|
| AWS Region | `ap-southeast-1` (Singapore) |
| S3 bucket | `homespace-dev-files` |
| IAM user đề xuất | `homespace-file-storage-dev` |

> Không đưa `AWS_ACCESS_KEY_ID` hoặc `AWS_SECRET_ACCESS_KEY` lên Git, README, ảnh chụp màn hình hoặc nhóm chat.

## 1. Kiểm tra hoặc tạo bucket

Mở **AWS Console → S3 → General purpose buckets**.

Nếu đã thấy bucket `homespace-dev-files` ở region `ap-southeast-1`, bỏ qua việc tạo mới và chuyển sang bước 2.

Nếu chưa có:

1. Nhấn **Create bucket**.
2. **Bucket name**: nhập `homespace-dev-files`.
3. **AWS Region**: chọn `Asia Pacific (Singapore) ap-southeast-1`.
4. **Object Ownership**: giữ `ACLs disabled (recommended)`.
5. **Block Public Access**: giữ bật toàn bộ.
6. **Bucket Versioning**: có thể để `Disable` cho development.
7. **Default encryption**: giữ mã hóa mặc định `SSE-S3`.
8. Nhấn **Create bucket**.

Bucket phải để private. Storage Service cấp presigned URL tạm thời để upload, xem và tải file; không cần public bucket.

## 2. Cấu hình CORS cho frontend

CORS cần thiết khi frontend chạy trên một origin, ví dụ `http://localhost:3000`, nhưng upload hoặc đọc file trực tiếp từ domain S3 bằng presigned URL.

- Upload từ Postman hoặc backend sang S3: không cần CORS.
- Upload trực tiếp từ trình duyệt sang S3: cần CORS.
- `hs-web-app` sử dụng presigned URL trực tiếp: cần cấu hình bước này.

Mở **S3 → homespace-dev-files → Permissions → Cross-origin resource sharing (CORS) → Edit** và nhập:

```json
[
  {
    "AllowedHeaders": [
      "*"
    ],
    "AllowedMethods": [
      "GET",
      "PUT",
      "HEAD"
    ],
    "AllowedOrigins": [
      "http://localhost:3000",
      "http://localhost:5173",
      "http://localhost:4000",
      "http://localhost:5000"
    ],
    "ExposeHeaders": [
      "ETag",
      "x-amz-checksum-sha256"
    ],
    "MaxAgeSeconds": 3600
  }
]
```

Sau đó nhấn **Save changes**.

Giải thích:

| Cấu hình | Ý nghĩa |
|---|---|
| `AllowedOrigins` | Các địa chỉ frontend được phép gọi trực tiếp S3 |
| `PUT` | Upload file bằng presigned upload URL |
| `GET` | Xem hoặc tải file bằng presigned URL |
| `HEAD` | Đọc metadata của object khi cần |
| `AllowedHeaders: ["*"]` | Cho phép các header mà trình duyệt/AWS cần gửi khi upload |
| `ExposeHeaders` | Cho phép JavaScript đọc `ETag` và checksum từ response S3 |
| `MaxAgeSeconds` | Trình duyệt cache kết quả preflight trong 1 giờ |

Không thêm `"*"` vào `AllowedOrigins` cho production. Khi deploy, thêm chính xác domain frontend, ví dụ:

```json
"AllowedOrigins": [
  "https://homespace.example.com"
]
```

CORS không làm bucket thành public và không thay thế IAM policy. Request vẫn phải có presigned URL hợp lệ.

## 3. Tạo IAM policy giới hạn cho bucket

Không nên cấp `AmazonS3FullAccess`, vì quyền này cho phép truy cập mọi bucket trong tài khoản.

Mở **AWS Console → IAM → Policies → Create policy → JSON**, sau đó dán:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ListHomeSpaceDevBucket",
      "Effect": "Allow",
      "Action": [
        "s3:ListBucket"
      ],
      "Resource": "arn:aws:s3:::homespace-dev-files"
    },
    {
      "Sid": "ManageHomeSpaceDevObjects",
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject",
        "s3:DeleteObject"
      ],
      "Resource": "arn:aws:s3:::homespace-dev-files/*"
    }
  ]
}
```

Tiếp tục:

1. Nhấn **Next**.
2. **Policy name**: nhập `HomeSpaceStorageS3Access`.
3. Có thể nhập mô tả: `Allow HomeSpace storage service to manage objects in homespace-dev-files`.
4. Nhấn **Create policy**.

Ý nghĩa quyền:

| Quyền | Mục đích |
|---|---|
| `s3:ListBucket` | Kiểm tra/list object trong bucket khi cần |
| `s3:PutObject` | Upload file bằng presigned URL |
| `s3:GetObject` | Xem, tải và kiểm tra object đã upload |
| `s3:DeleteObject` | Xóa file |

## 4. Tạo IAM user cho Storage Service

Mở **AWS Console → IAM → Users → Create user**.

1. **User name**: nhập `homespace-file-storage-dev`.
2. Không chọn **Provide user access to the AWS Management Console**.
3. Nhấn **Next**.
4. Ở bước **Set permissions**, chọn **Attach policies directly**.
5. Tìm và chọn policy `HomeSpaceStorageS3Access` vừa tạo.
6. Nhấn **Next → Create user**.

Nếu IAM user `homespace-file-storage-dev` đã tồn tại:

1. Mở user đó.
2. Chọn tab **Permissions**.
3. Chọn **Add permissions → Attach policies directly**.
4. Chọn `HomeSpaceStorageS3Access`.
5. Xác nhận thêm quyền.

## 5. Tạo Access Key cho local development

Mở **IAM → Users → homespace-file-storage-dev → Security credentials**.

1. Tìm phần **Access keys**.
2. Nhấn **Create access key**.
3. Chọn use case **Application running outside AWS** hoặc **Local code** nếu giao diện hiện tùy chọn này.
4. Xác nhận cảnh báo và nhấn **Next**.
5. Description tag có thể nhập `homespace-storage-local-dev`.
6. Nhấn **Create access key**.

AWS hiển thị hai giá trị:

```text
Access key ID
Secret access key
```

`Secret access key` chỉ hiển thị đầy đủ một lần. Hãy tải file `.csv` hoặc lưu ngay vào nơi quản lý bí mật an toàn. Không gửi hai giá trị này cho người khác.

## 6. Khai báo credential trên máy local

Mở file `hs-core-api/.env` và khai báo:

```env
AWS_REGION=ap-southeast-1
AWS_S3_BUCKET=homespace-dev-files
AWS_ACCESS_KEY_ID=<access-key-id-cua-ban>
AWS_SECRET_ACCESS_KEY=<secret-access-key-cua-ban>
```

Không thêm file `.env` vào Git.

Nếu chạy ứng dụng trực tiếp bằng VS Code hoặc IntelliJ, cần đảm bảo cấu hình chạy thực sự nạp file `.env`. Nếu IDE không nạp `.env`, hãy thêm bốn biến trên vào phần **Environment variables** của Run/Debug Configuration.

Sau khi thay đổi biến môi trường, phải dừng và khởi động lại hoàn toàn `hs-api-service`.

## 7. Kiểm tra nhanh bằng Postman

Import collection:

```text
hs-infrastructure/postman/Storage Service - Gateway.postman_collection.json
```

Chạy theo thứ tự:

```text
1. Get Token
2. Create Presigned Upload
3. PUT File Directly To S3
4. Complete Upload
```

Ở response bước 2, `uploadUrl` phải chứa đúng bucket:

```text
https://homespace-dev-files.s3.ap-southeast-1.amazonaws.com/...
```

Nếu bước 3 trả `200 OK` với body trống thì upload lên S3 đã thành công.

## 8. Lỗi thường gặp

### `NoSuchBucket`

Ví dụ:

```text
The specified bucket does not exist
```

Kiểm tra `AWS_S3_BUCKET` phải là:

```env
AWS_S3_BUCKET=homespace-dev-files
```

Restart service và tạo presigned URL mới.

### `AccessDenied` khi `s3:PutObject`

IAM user chưa được gắn policy `HomeSpaceStorageS3Access`, hoặc policy đang trỏ nhầm bucket. Kiểm tra resource phải là:

```text
arn:aws:s3:::homespace-dev-files
arn:aws:s3:::homespace-dev-files/*
```

### `SignatureDoesNotMatch`

Thông tin ở bước tạo presigned URL không khớp request PUT. Kiểm tra:

- `contentType` giống chính xác header `Content-Type` ở bước PUT.
- `size` đúng số byte của file.
- Sau khi đổi file, phải chạy lại bước tạo presigned URL.
- Request PUT phải dùng `No Auth` và không sửa URL được backend trả về.

Xem kích thước file chính xác trên PowerShell:

```powershell
(Get-Item -LiteralPath "C:\duong-dan\file.png").Length
```

## Nguyên tắc bảo mật

- Không dùng Access Key của root account.
- Mỗi môi trường nên có IAM user/role và bucket riêng.
- Development chỉ được truy cập bucket `homespace-dev-files`.
- Không cấp `AmazonS3FullAccess` nếu policy giới hạn phía trên đã đủ.
- Không public bucket để phục vụ download; hãy dùng presigned URL.
- Nếu Access Key bị lộ, phải deactivate/delete key đó và tạo key mới ngay.
