# Tạo AWS S3 lần đầu cho HomeSpace

Dùng tài khoản quản trị AWS để thực hiện. Bucket và IAM user bên dưới thuộc cùng AWS account. Nếu tên bucket đã có người dùng, đổi tên và thay đồng nhất trong mọi policy/cấu hình.

## 1. Tạo bucket

**S3 → General purpose buckets → Create bucket**:

| Thiết lập | Giá trị |
|---|---|
| Region | `ap-southeast-1` — Singapore |
| Bucket name | `homespace-production-files` |
| Object Ownership | ACLs disabled / Bucket owner enforced |
| Block Public Access | Bật cả bốn mục lúc tạo |
| Bucket Versioning | **Enable** |
| Default encryption | SSE-S3 |

Chọn **Create bucket**. Kiểm tra **Properties → Bucket Versioning = Enabled**. Chờ **15 phút** sau lần bật Versioning đầu tiên trước khi upload/xóa file; trong lúc chờ làm các bước tiếp theo.

## 2. Public read

**Bucket → Permissions → Block public access → Edit**. Bỏ chọn ô tổng để chỉnh:

| Thiết lập | Giá trị |
|---|---|
| BlockPublicAcls | Bật |
| IgnorePublicAcls | Bật |
| BlockPublicPolicy | Tắt |
| RestrictPublicBuckets | Tắt |

**Save changes**, xác nhận. Nếu cấp account/organization vẫn chặn public policy, nhờ quản trị viên kiểm tra trước khi tiếp tục.

**Permissions → Bucket policy → Edit**, dán và **Save changes**:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowPublicRead",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::homespace-production-files/public/*"
    },
    {
      "Sid": "DenyInsecureTransport",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:*",
      "Resource": [
        "arn:aws:s3:::homespace-production-files",
        "arn:aws:s3:::homespace-production-files/*"
      ],
      "Condition": { "Bool": { "aws:SecureTransport": "false" } }
    }
  ]
}
```

Chỉ mở `public/*`; không thêm public Allow toàn bucket hoặc Deny vô điều kiện cho `private/*`.

## 3. CORS

**Permissions → Cross-origin resource sharing (CORS) → Edit**, dán và **Save changes**:

```json
[
  {
    "AllowedHeaders": ["*"],
    "AllowedMethods": ["GET", "PUT", "HEAD"],
    "AllowedOrigins": [
      "http://localhost:3000",
      "http://localhost:5000",
      "http://127.0.0.1:3000",
      "http://127.0.0.1:5000"
    ],
    "ExposeHeaders": ["ETag", "x-amz-checksum-sha256", "x-amz-version-id"],
    "MaxAgeSeconds": 3600
  }
]
```

Khi có domain frontend, thêm đúng origin HTTPS vào danh sách; giữ các origin local còn sử dụng. Không thêm port backend hoặc origin `*`.

## 4. Tạo IAM policy

**IAM → Policies → Create policy → JSON**, dán:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ListBucket",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::homespace-production-files"
    },
    {
      "Sid": "ManageObjects",
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject",
        "s3:GetObjectVersion",
        "s3:DeleteObject"
      ],
      "Resource": [
        "arn:aws:s3:::homespace-production-files/public/*",
        "arn:aws:s3:::homespace-production-files/private/*"
      ]
    }
  ]
}
```

Chọn **Next** → đặt tên `HomeSpaceStorageProductionS3Access` → **Create policy**. Không cấp `AmazonS3FullAccess` hoặc `s3:DeleteObjectVersion`.

## 5. Tạo IAM user và Access Key

1. **IAM → Users → Create user**.
2. User name: `homespace-storage-production`. Không bật đăng nhập AWS Management Console.
3. **Next → Attach policies directly** → chọn `HomeSpaceStorageProductionS3Access`.
4. **Next → Create user**. Mở **Permissions**, kiểm tra policy đã được gắn.
5. **Security credentials → Access keys → Create access key**.
6. Chọn use case **Local code** hoặc **Application running outside AWS** tương ứng, xác nhận, rồi **Next → Create access key**.
7. Lưu **Access key ID** và **Secret access key** vào nơi quản lý bí mật; secret chỉ hiển thị đầy đủ một lần.
8. Chọn **Done**, kiểm tra key **Active**. Không dùng key root hoặc đưa key vào frontend/Git.

## 6. Cấu hình Core

Bổ sung vào `D:/Workspace/homespace/hs-core-api/.env`; giữ các biến ứng dụng hiện có:

```dotenv
AWS_REGION=ap-southeast-1
AWS_S3_BUCKET=homespace-production-files
AWS_ACCESS_KEY_ID=<access-key-id-o-buoc-5>
AWS_SECRET_ACCESS_KEY=<secret-access-key-o-buoc-5>
```

Chạy Core với working directory `D:/Workspace/homespace/hs-core-api` để nạp đúng `.env`. Nếu chạy bằng IDE/service, đặt working directory hoặc cấp đủ biến môi trường cho tiến trình. Restart Core và tạo upload URL mới.

## 7. Kiểm tra hoàn tất

- Upload ảnh qua ứng dụng local port `3000` hoặc `5000`; complete thành công, DB có S3 Version ID cụ thể.
- Mở URL ảnh `https://homespace-production-files.s3.ap-southeast-1.amazonaws.com/public/...` trong cửa sổ ẩn danh: xem được.
- Mở raw URL của file có thật dưới `private/*`: **AccessDenied**. Người có quyền xem qua API/presigned URL: tải được đúng file/version.
- Dùng file thử riêng: upload hai lần cùng key trong Console, bật **Show versions**, tải từng Version ID và đối chiếu hai nội dung.

Giữ **Versioning Enabled**; không bật Lifecycle xóa noncurrent versions của hợp đồng. Giữ nguyên `bucket + objectKey + s3VersionId` đã lưu của hợp đồng; không tự đổi sang latest hoặc xóa bucket đang còn được tham chiếu.
