# Thiết lập AWS S3 cho Storage Service

Tài liệu này hướng dẫn cấu hình AWS S3 cho môi trường development, bật Versioning cho tài liệu hợp đồng và lấy Access Key để chạy `hs-api-service` trên máy local. Các bước trên AWS Console do bạn thực hiện thủ công.

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
6. **Bucket Versioning**: chọn `Enable`.
7. **Default encryption**: giữ mã hóa mặc định `SSE-S3`.
8. Nhấn **Create bucket**.

Bucket phải để private. Storage Service cấp presigned URL tạm thời để upload, xem và tải file; không cần public bucket.

## 1A. Bật Versioning cho bucket đang sử dụng

Versioning giữ nhiều phiên bản của cùng một object key. Upload file mới cùng key tạo phiên bản mới; bản cũ vẫn còn nếu chưa bị xóa vĩnh viễn hoặc hết hạn theo Lifecycle. Versioning không tự kiểm tra chữ ký/hash và không thay thế blockchain.

### 1A.1. Thao tác trên AWS Console

1. Chọn thời điểm có thể tạm dừng các thao tác ghi file của HomeSpace: upload, tạo PDF, ký số, copy và xóa file. Không thử trên hợp đồng đang có lượt ký xử lý dở.
2. Mở **S3 → General purpose buckets → homespace-dev-files → Properties**.
3. Tại **Bucket Versioning**, nhấn **Edit**.
4. Chọn **Enable**, sau đó **Save changes**.
5. Quay lại **Properties**, kiểm tra **Bucket Versioning: Enabled**.
6. Với lần bật đầu tiên, đợi ít nhất **15 phút trước khi tiếp tục PUT/DELETE** theo khuyến nghị của AWS. Sau đó mới mở lại thao tác ghi file của ứng dụng.

Không cần đổi bucket, region, Access Key hoặc biến môi trường chỉ để bật Versioning. Cần bổ sung quyền IAM đọc phiên bản ở mục 3 trước khi backend bắt đầu dùng `versionId`.

### 1A.2. Kiểm tra bằng một file thử

Thực hiện trên key thử riêng, ví dụ `general/versioning-demo.txt`; không ghi đè tài liệu hợp đồng thật.

1. Tại tab **Objects**, upload file thử có nội dung `ban A`.
2. Bật **Show versions**, mở phiên bản vừa upload và ghi nhận **Version ID**.
3. Upload file nội dung `ban B` vào **đúng key đó**.
4. Bật **Show versions**: phải thấy hai phiên bản với Version ID khác nhau, trong đó bản B là mới nhất.
5. Mở/tải riêng từng phiên bản để xác nhận bản A vẫn chứa nội dung cũ.

Đây là kiểm tra S3, chưa chứng minh HomeSpace đã đọc đúng phiên bản. Không dùng API xóa của ứng dụng để dọn thử rồi cho rằng mọi phiên bản đã được xóa: xóa theo key thường chỉ tạo delete marker.

### 1A.3. Dữ liệu cũ và giới hạn hiện tại của backend

| Trường hợp | Hành vi |
|---|---|
| Object có từ trước lúc bật | Giữ nội dung hiện có; Version ID của phiên bản cũ là giá trị đặc biệt `null` của S3. Không tự có lịch sử trước khi bật. |
| Upload/copy sau lúc bật | S3 cấp Version ID mới cho phiên bản đích. |
| GET không truyền `versionId` | Lấy phiên bản hiện tại; có thể nhận file mới bị ghi đè cùng key. |
| GET truyền Version ID cụ thể | Lấy đúng phiên bản đó, cần quyền `s3:GetObjectVersion`. |
| Xóa theo key, không truyền Version ID | Thường tạo delete marker; các phiên bản cũ vẫn còn và vẫn tính phí lưu trữ. |

**Backend hiện tại chưa lưu S3 `versionId` và chưa truyền nó khi đọc/copy/cấp URL.** Bật Versioning giúp S3 giữ lịch sử ngay, nhưng chưa làm HomeSpace tự cố định PDF đã ký. Phần backend này cần triển khai riêng sau review. Không nhầm S3 Version ID với `revisionId`, `templateVersionId` hay `Version` trong JSON IAM policy.

Luồng phụ lục hiện tạo hồ sơ, revision và các file riêng; không ghi đè PDF hợp đồng gốc. Versioning cần bảo vệ từng object của hợp đồng/phụ lục, không dùng các phiên bản cùng key để thay cho phụ lục nghiệp vụ. Chấp thuận đề xuất chưa đồng nghĩa đã có PDF ký đủ hoặc phụ lục đã đến ngày hiệu lực.

Không tự gán phiên bản mới nhất cho hợp đồng cũ. Khi bổ sung định danh phiên bản cho PDF cũ, phải kiểm tra bytes với hash đã ghi nhận (nếu có) trước khi cố định. Trong SDK, không truyền tham số `versionId` khác với việc truyền chuỗi literal `"null"` để chỉ phiên bản cũ của S3.

### 1A.4. Lưu giữ và chi phí

- Chưa tạo rule Lifecycle xóa **noncurrent versions** cho prefix tài liệu hợp đồng trước khi chốt chính sách lưu giữ. Phiên bản được HomeSpace cố định có thể trở thành noncurrent sau một lần upload cùng key.
- Versioning giữ nhiều bản nên tăng dung lượng tính phí. Xóa mềm trong ứng dụng hoặc tạo delete marker không thu hồi toàn bộ dung lượng.
- Nhánh cleanup khi upload/copy lỗi hiện xóa theo key. Sau khi bật Versioning, các bản orphan có thể còn lại sau delete marker; cần sửa cleanup theo version trong đợt backend. Không cấp rộng quyền xóa version hoặc tạo Lifecycle xóa mọi bản cũ để xử lý tạm.
- Khi đã bật, bucket chỉ có thể chuyển sang **Suspended**, không quay lại trạng thái chưa từng bật. Không Suspend để thử khôi phục; việc đó không xóa lịch sử và không phải rollback dữ liệu.
- Object Lock là cấu hình riêng để bảo vệ phiên bản khỏi bị xóa trong thời hạn lưu giữ. Chưa bật Object Lock trong bước này; cần chốt chính sách trước. Object Lock vẫn cho phép tạo phiên bản mới cùng key, nên backend vẫn phải cố định Version ID.

Tham khảo AWS: [Bật Versioning và thời gian chờ](https://docs.aws.amazon.com/AmazonS3/latest/userguide/manage-versioning-examples.html), [đọc đúng phiên bản](https://docs.aws.amazon.com/AmazonS3/latest/userguide/RetrievingObjectVersions.html), [delete marker](https://docs.aws.amazon.com/AmazonS3/latest/userguide/DeleteMarker.html), [Object Lock](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html).

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
      "x-amz-checksum-sha256",
      "x-amz-version-id"
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
| `ExposeHeaders` | Cho phép JavaScript đọc `ETag`, checksum và Version ID khi S3 trả các header đó; không tự làm backend lưu phiên bản |
| `MaxAgeSeconds` | Trình duyệt cache kết quả preflight trong 1 giờ |

Không thêm `"*"` vào `AllowedOrigins` cho production. Khi deploy, thêm chính xác domain frontend, ví dụ:

```json
"AllowedOrigins": [
  "https://homespace.example.com"
]
```

CORS không làm bucket thành public và không thay thế IAM policy. Request vẫn phải có presigned URL hợp lệ.

## 2A. Chỉ public avatar nếu chưa dùng CloudFront

Mặc định giữ bucket private và bật toàn bộ Block Public Access. Nếu cần URL avatar công khai khi chưa dùng CloudFront, chỉ cho phép `user_avatar/*`; tài liệu hợp đồng, bản PDF chuẩn bị ký, giấy tờ định danh và các prefix còn lại phải private.

**Nếu từng áp dụng hướng dẫn cũ public toàn bucket trừ `user_contract/*`:** kiểm tra bucket policy, bỏ riêng statement `AllowPublicReadAllObjects` do hướng dẫn đó tạo và thay bằng allow avatar dưới đây. Bỏ riêng statement `DenyPublicReadUserContract` cũ: explicit deny với `Principal: "*"` chặn cả IAM/presigned GET, không chỉ người dùng ẩn danh. Giữ nguyên các statement khác chưa được review. Không dựa vào tên `user_contract/` để bảo vệ PDF: code hiện lưu tài liệu theo purpose, ví dụ `contract_document/` và `signature_prepared_document/`.

### 2A.1. Block Public Access nếu cần public avatar

Mở **S3 → homespace-dev-files → Permissions → Block public access → Edit**. Chỉ khi chọn phương án avatar public mới điều chỉnh:

| Thiết lập | Giá trị |
|---|---|
| Block public access granted through new ACLs | Bật |
| Block public access granted through any ACLs | Bật |
| Block public access granted through new public bucket or access point policies | Tắt |
| Block public and cross-account access through any public bucket or access point policies | Tắt |

Giữ **Object Ownership: Bucket owner enforced**, không dùng public ACL. Nếu account-level Block Public Access chặn policy, cần quản trị viên review ảnh hưởng các bucket khác trước khi thay đổi cấp account; có thể tiếp tục giữ toàn bucket private và dùng presigned URL cho avatar.

### 2A.2. Bucket policy chỉ cho avatar

Mở **Permissions → Bucket policy → Edit**. Nếu chưa có policy, dùng:

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

Nếu có policy, ghép statement allow avatar vào policy sau khi loại bỏ đúng rule public toàn bucket nói trên; không thay toàn bộ policy mà chưa review. Không cấp public `s3:GetObjectVersion`. Không thêm deny mọi principal cho prefix private chỉ để chặn anonymous: khi không có public allow, anonymous không được đọc; IAM backend vẫn cần đọc được theo policy mục 3.

### 2A.3. Kiểm tra quyền

Dùng key của object thực sự tồn tại, không dùng đường dẫn giả:

1. Mở URL avatar không có query presigned trong cửa sổ ẩn danh: chỉ hiển thị nếu đã chọn cấu hình avatar public.
2. Với PDF hợp đồng, PDF chuẩn bị ký và giấy tờ định danh: URL S3 không có query presigned phải bị từ chối. Kiểm tra cả phiên bản cụ thể nếu đã biết Version ID.
3. URL presigned do backend cấp cho người dùng có quyền vẫn phải hoạt động. Khi backend dùng phiên bản cụ thể, request cần có `versionId` và IAM cần `s3:GetObjectVersion`.

Không xem `AccessDenied` từ URL của key không tồn tại là bằng chứng policy đã bảo vệ đúng một tài liệu thật. Cấu hình bucket/policy chỉ được coi là đã kiểm tra sau khi bạn thực hiện các bước này trên AWS.

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
        "s3:GetObjectVersion",
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
| `s3:GetObjectVersion` | Đọc/HEAD hoặc cấp presigned URL cho một phiên bản cụ thể; cần khi backend cố định `versionId` |
| `s3:DeleteObject` | Xóa theo key; với bucket Versioning Enabled thường tạo delete marker |

Nếu policy đã tồn tại, sửa **policy đang gắn cho IAM user/role của backend** để thêm `s3:GetObjectVersion` vào nhóm quyền object; không tạo Access Key mới chỉ vì thêm quyền này. Quyền bật Versioning thuộc tài khoản quản trị thao tác Console, không cần cấp cho tiến trình backend.

Không cấp `s3:DeleteObjectVersion` cho backend trong cấu hình này. Đây là quyền xóa vĩnh viễn một phiên bản; chỉ quản trị viên có quy trình lưu giữ/khôi phục mới nên được cấp. `s3:ListBucketVersions` chỉ cần nếu công cụ quản trị thực sự liệt kê lịch sử; backend đọc theo Version ID đã biết không cần quyền này. Xem [quyền đọc object/version](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html).

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
hs-infra/postman/Storage Service - Gateway.postman_collection.json
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
- Chỉ `user_avatar/*` được phép public theo policy ở mục 2A khi chưa dùng được CloudFront. Mọi file còn lại phải dùng presigned URL.
- Nếu Access Key bị lộ, phải deactivate/delete key đó và tạo key mới ngay.
