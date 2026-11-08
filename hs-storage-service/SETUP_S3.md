# Thiết lập AWS S3 cho Storage Service

Dùng cho `hs-api-service` chạy local. Bạn thực hiện các bước AWS Console thủ công.

| Thông tin | Giá trị dùng trong hướng dẫn |
|---|---|
| Region | `ap-southeast-1` — Singapore |
| Bucket | `homespace-dev-files` |
| IAM policy | `HomeSpaceStorageS3Access` |
| IAM user | `homespace-file-storage-dev` |

- **Thiết lập lần đầu:** làm lần lượt mục A.
- **Đã có bucket, user và Access Key:** làm mục B để bật Versioning, không tạo lại toàn bộ.
- **Cần avatar public:** làm thêm mục C. Mặc định bucket private.

## A. Thiết lập lần đầu

### 1. Tạo bucket và bật Versioning

Mở **AWS Console → S3 → General purpose buckets → Create bucket**:

| Thiết lập | Chọn |
|---|---|
| AWS Region | `Asia Pacific (Singapore) ap-southeast-1` |
| Bucket type | `General purpose` |
| Bucket name | `homespace-dev-files` |
| Object Ownership | `ACLs disabled` / `Bucket owner enforced` |
| Block Public Access | Bật toàn bộ |
| Bucket Versioning | **Enable** |
| Default encryption | `SSE-S3` |

Nhấn **Create bucket**. Nếu tên đã thuộc tài khoản khác, chọn tên mới và thay đồng nhất trong IAM policy cùng `.env` bên dưới. Nếu bucket đã có trong tài khoản của bạn, dùng mục B.

Với lần bật Versioning đầu tiên, AWS khuyến nghị chờ **15 phút trước khi ghi/xóa object**. Trong lúc chờ, tiếp tục cấu hình các bước 2–5.

### 2. Cấu hình CORS

Mở **S3 → homespace-dev-files → Permissions → Cross-origin resource sharing (CORS) → Edit**. Dán JSON và **Save changes**:

```json
[
  {
    "AllowedHeaders": ["*"],
    "AllowedMethods": ["GET", "PUT", "HEAD"],
    "AllowedOrigins": [
      "http://localhost:3000",
      "http://localhost:5173",
      "http://localhost:4000",
      "http://localhost:5000"
    ],
    "ExposeHeaders": ["ETag", "x-amz-checksum-sha256", "x-amz-version-id"],
    "MaxAgeSeconds": 3600
  }
]
```

CORS cho phép trình duyệt upload/đọc qua presigned URL; không làm bucket public. Khi deploy, thay các origin local bằng domain frontend thực tế, không dùng origin `*` cho production. `ExposeHeaders` chỉ cho JavaScript đọc header, không tự lưu Version ID vào backend.

### 3. Tạo IAM policy

Mở **IAM → Policies → Create policy → JSON**, dán:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ListHomeSpaceDevBucket",
      "Effect": "Allow",
      "Action": ["s3:ListBucket"],
      "Resource": "arn:aws:s3:::homespace-dev-files"
    },
    {
      "Sid": "ManageHomeSpaceDevObjects",
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject",
        "s3:GetObjectVersion",
        "s3:DeleteObject"
      ],
      "Resource": "arn:aws:s3:::homespace-dev-files/*"
    }
  ]
}
```

Nhấn **Next**, đặt tên `HomeSpaceStorageS3Access`, rồi **Create policy**.

Policy giới hạn trong một bucket. `GetObjectVersion` dùng để đọc phiên bản cụ thể; `DeleteObject` thường chỉ tạo delete marker khi Versioning đang Enabled. Chưa cấp `DeleteObjectVersion` vì quyền này xóa vĩnh viễn phiên bản. Không dùng `AmazonS3FullAccess`.

### 4. Tạo IAM user và Access Key

1. Mở **IAM → Users → Create user**, tên `homespace-file-storage-dev`.
2. Không bật **Provide user access to the AWS Management Console**.
3. Chọn **Attach policies directly** → `HomeSpaceStorageS3Access` → **Create user**.
4. Mở user vừa tạo → **Security credentials → Access keys → Create access key**.
5. Chọn **Local code** hoặc **Application running outside AWS**, xác nhận và tạo key.
6. Lưu **Access key ID** và **Secret access key** vào nơi quản lý bí mật. Secret chỉ hiển thị đầy đủ một lần.

Không dùng key của root account; không đưa credential lên Git, tài liệu hoặc ảnh chụp màn hình. Nếu user/key đã có và dùng được, giữ lại, chỉ cập nhật policy.

### 5. Cấu hình backend

Điền vào `hs-core-api/.env`:

```dotenv
AWS_REGION=ap-southeast-1
AWS_S3_BUCKET=homespace-dev-files
AWS_ACCESS_KEY_ID=<access-key-id-cua-ban>
AWS_SECRET_ACCESS_KEY=<secret-access-key-cua-ban>
```

Điền một dòng cho mỗi giá trị, không bọc dấu nháy. `.env` không được commit. Chạy Core từ thư mục `hs-core-api` để nạp đúng file; nếu dùng IDE với working directory khác, cấu hình đường dẫn hoặc cấp các biến qua Run/Debug Configuration. Khởi động lại Core sau khi sửa.

### 6. Kiểm tra upload từ ứng dụng

Sau thời gian chờ ở bước 1, import collection:

```text
hs-infra/postman/Storage Service - Gateway.postman_collection.json
```

Chạy theo thứ tự:

| Bước | Thao tác | Kết quả cần thấy |
|---|---|---|
| 1 | Get Token | Có token đăng nhập hợp lệ. |
| 2 | Create Presigned Upload | Có `uploadUrl` trỏ đúng bucket. |
| 3 | PUT File Directly To S3 | `200 OK`, body có thể trống. Request dùng `No Auth`, gửi file dạng binary và đúng `Content-Type`. |
| 4 | Complete Upload | Thành công, bản ghi file chuyển `READY`. |
| 5 | Lấy presigned view/download URL | Xem/tải đúng file vừa upload. |

Tiếp tục kiểm tra Versioning theo mục B.2. Một lần upload thành công chưa chứng minh ứng dụng đã đọc theo version cụ thể.

## B. Bật Versioning cho bucket đang sử dụng

### B.1. Cập nhật cấu hình hiện có

1. Tạm dừng upload, tạo PDF, ký số, copy và xóa file; chờ lượt ký đang xử lý hoàn tất trước khi đổi cấu hình.
2. Mở **S3 → homespace-dev-files → Properties → Bucket Versioning → Edit**.
3. Chọn **Enable → Save changes**, kiểm tra trạng thái **Enabled**.
4. Đợi ít nhất **15 phút sau lần bật đầu tiên** trước khi tiếp tục ghi/xóa file.
5. Mở **IAM → Policies → HomeSpaceStorageS3Access → Edit**, thêm `s3:GetObjectVersion` vào nhóm quyền object như mục A.3. Bảo đảm policy đang gắn cho user/role backend sử dụng.
6. Cập nhật CORS theo mục A.2 nếu thiếu `x-amz-version-id`, rồi kiểm tra theo B.2 trước khi mở lại thao tác ghi file.

Không cần đổi bucket, Access Key hoặc `.env` chỉ để bật Versioning. Backend không cần quyền bật/tắt Versioning; quản trị viên thực hiện trên Console. Công cụ quản trị liệt kê lịch sử mới cần `s3:ListBucketVersions`.

### B.2. Kiểm tra S3 giữ đúng hai phiên bản

Dùng key thử riêng `general/versioning-demo.txt`, không dùng hợp đồng thật:

1. Upload file nội dung `ban A` từ Console.
2. Bật **Show versions**, ghi nhận **Version ID** của file.
3. Upload file nội dung `ban B` vào đúng key cũ.
4. Bật **Show versions**: phải có hai Version ID khác nhau; bản B là mới nhất.
5. Mở/tải riêng từng phiên bản: bản A vẫn giữ nội dung cũ.

Đây là kiểm tra S3. Việc HomeSpace lấy đúng phiên bản còn phụ thuộc hỗ trợ backend tại mục D.

## C. Chỉ public avatar nếu cần

Bỏ qua mục này nếu dùng presigned URL cho toàn bộ file. Nếu cần URL avatar công khai khi chưa dùng CloudFront:

1. Mở **S3 → homespace-dev-files → Permissions → Block public access → Edit**.
2. Giữ bật hai mục chặn public qua **ACL**; tắt hai mục chặn public qua **bucket/access point policy**, rồi lưu. Giữ Object Ownership là **Bucket owner enforced**.
3. Tại **Bucket policy**, thêm statement chỉ cho `user_avatar/*`. Nếu chưa có policy, dùng JSON đầy đủ dưới đây:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowPublicReadAvatars",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::homespace-dev-files/user_avatar/*"
    }
  ]
}
```

Nếu account-level Block Public Access chặn policy, quản trị viên cần review ảnh hưởng các bucket khác trước khi thay đổi cấp account; có thể giữ private và dùng presigned URL.

**Nếu đã dùng hướng dẫn public toàn bucket cũ:** bỏ riêng rule `AllowPublicReadAllObjects`, thay bằng allow avatar. Bỏ rule `DenyPublicReadUserContract` cũ vì `Deny` với `Principal: "*"` chặn cả IAM/presigned GET. Giữ nguyên các rule khác chưa được review. Không cấp public `s3:GetObjectVersion`.

Kiểm tra bằng URL **không có query presigned** của file thực sự tồn tại trong cửa sổ ẩn danh:

- `user_avatar/*`: xem được nếu đã chọn public avatar.
- `contract_document/*`, `signature_prepared_document/*`, `identity_document/*` và các prefix còn lại: bị từ chối, kể cả khi chỉ định phiên bản cụ thể.
- Presigned URL do backend cấp cho người có quyền vẫn phải hoạt động.

Không dùng key không tồn tại để kết luận policy đã bảo vệ đúng file. PDF hợp đồng hiện dùng prefix theo purpose như `contract_document/`, không chỉ `user_contract/`.

## D. Versioning và giới hạn hiện tại của HomeSpace

| Nội dung | Cần hiểu đúng |
|---|---|
| File có trước lúc bật | Phiên bản cũ có Version ID đặc biệt `null`; không khôi phục được lịch sử đã mất trước khi bật. |
| Upload/copy sau lúc bật | S3 cấp Version ID mới cho phiên bản đích. |
| Đọc không truyền `versionId` | Lấy phiên bản hiện tại của key. |
| Đọc có `versionId` | Lấy đúng phiên bản, cần `s3:GetObjectVersion`. |
| Xóa theo key | Thường tạo delete marker; các bản cũ còn lại và vẫn tính phí. |
| Phụ lục | Có hồ sơ, revision và file riêng; không dùng S3 version thay cho phụ lục nghiệp vụ. |

**Backend hiện chưa lưu hoặc truyền S3 `versionId` khi đọc/copy/cấp URL.** Bật Versioning giúp giữ lịch sử phía S3, chưa tự cố định PDF đã ký và chưa tự phát hiện file bị thay. `revisionId`/`templateVersionId` là ID nghiệp vụ, khác S3 Version ID.

Khi triển khai backend:

- Lưu version trả về từ upload/copy; đọc và cấp URL theo đúng version đã lưu.
- Phân biệt DB trống (chưa xác định version) với chuỗi literal `"null"` (phiên bản cũ của S3 đã xác định).
- Không tự gán latest cho PDF cũ; cần đối chiếu nội dung với hash blockchain nếu đã có proof.
- Sửa cleanup upload/copy lỗi: xóa theo key hiện có thể để lại orphan sau delete marker. Không cấp rộng quyền xóa phiên bản để xử lý tạm.

Chưa tạo Lifecycle xóa **noncurrent versions** của tài liệu hợp đồng trước khi chốt chính sách lưu giữ: bản cần giữ có thể thành noncurrent sau một lần upload cùng key. Versioning tăng dung lượng tính phí; không thể chuyển bucket về trạng thái chưa từng bật, chỉ có thể **Suspend**.

Object Lock là bước riêng để bảo vệ phiên bản trong thời hạn lưu giữ, chưa thuộc cấu hình này. Nó vẫn cho phép tạo phiên bản mới cùng key; ứng dụng vẫn phải cố định Version ID. Versioning không thay thế kiểm tra chữ ký hoặc hash blockchain.

## E. Lỗi thường gặp

| Lỗi | Kiểm tra |
|---|---|
| `NoSuchBucket` | Tên bucket trong `.env`, region và bucket thực tế; restart Core, lấy URL mới. |
| `AccessDenied` khi upload | Policy đã gắn đúng IAM user/role, có `s3:PutObject` và đúng ARN bucket. |
| `AccessDenied` khi đọc version | Có `s3:GetObjectVersion`; không có explicit deny chặn request. |
| Trình duyệt báo CORS | Origin frontend đúng trong CORS; `Content-Type` đúng; xem lỗi S3 thực tế trong Network. |
| `SignatureDoesNotMatch` | Dùng nguyên URL backend trả về, đúng method/`Content-Type`, `No Auth`, file binary đúng size. Nếu đổi file phải tạo upload mới. |
| Không đọc được sau thao tác xóa | Kiểm tra delete marker trong **Show versions**; không xóa vĩnh viễn phiên bản để thử sửa. |

Xem kích thước file trên PowerShell nếu cần đối chiếu `size` lúc tạo upload:

```powershell
(Get-Item -LiteralPath 'C:\duong-dan\file.png').Length
```

Mỗi môi trường nên có bucket và IAM user/role riêng. Nếu Access Key bị lộ, deactivate/delete key cũ và tạo key mới.

## Tài liệu AWS

- [Bật Versioning và thời gian chờ](https://docs.aws.amazon.com/AmazonS3/latest/userguide/manage-versioning-examples.html)
- [Đọc phiên bản cụ thể](https://docs.aws.amazon.com/AmazonS3/latest/userguide/RetrievingObjectVersions.html)
- [Quyền GetObject/GetObjectVersion](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html)
- [Delete marker](https://docs.aws.amazon.com/AmazonS3/latest/userguide/DeleteMarker.html)
- [Object Lock](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html)
