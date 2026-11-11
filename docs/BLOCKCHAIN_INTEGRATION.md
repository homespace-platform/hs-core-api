# Ghi nhận PDF hợp đồng lên blockchain

Tính năng chạy trong `hs-contract-service`, được Core API nạp cùng luồng ký SmartCA. Phạm vi là **HĐ Cọc, HĐ Thuê gốc và phụ lục AMENDMENT đã ký đủ**. Khi deploy registry mới, cập nhật địa chỉ backend trước khi bật worker; dữ liệu proof trên registry cũ không tự chuyển sang registry mới.

## 1. Luồng xử lý

1. Chủ nhà ký SmartCA, tạo PDF `SIGNED_LANDLORD`: chưa gửi blockchain.
2. Người thuê ký tiếp; backend nhúng và xác minh đủ hai chữ ký, upload PDF riêng tư `SIGNED_FINAL`, cố định phiên bản S3 của file.
3. Backend lưu SHA-256 từ đúng bytes PDF đó kể cả khi blockchain tắt; khi tính năng bật và cấu hình hợp lệ, lưu yêu cầu proof cùng transaction hoàn tất ký. HĐ Cọc, HĐ Thuê và mỗi phụ lục có tài liệu/ID/proof riêng.
4. Worker đọc yêu cầu đã commit, đối chiếu bytes trong storage và gọi `registerDocumentWithLineage(bytes32 documentId, bytes32 documentHash, bytes32 caseId, uint8 documentKind)` bằng ví hệ thống.
5. Worker kiểm tra receipt, sự kiện `DocumentRegistered`, hash trong registry, block canonical và mốc `finalized`, rồi đánh dấu `CONFIRMED`.

RPC lỗi hoặc ví thiếu gas không đảo chữ ký, không đổi trạng thái nghiệp vụ của cọc/thuê. Blockchain có trạng thái riêng. Hệ thống không chỉnh sửa hoặc chèn mã proof vào PDF sau khi ký.

Worker dùng executor riêng, không giữ luồng scheduler đang poll/nhúng chữ ký SmartCA. Mỗi instance chỉ chạy một lượt xử lý blockchain tại một thời điểm; lease database phối hợp giữa các instance.

### Quy tắc lưu file và phụ lục

- Mỗi lần upload/render/ký tạo object key mới theo UUID. Không ghi đè PDF HĐ chính bằng phụ lục.
- Storage lưu `s3VersionId` trả về từ S3. Đọc nội bộ, copy nguồn và cấp presigned URL dùng đúng phiên bản đã lưu; copy đích có version riêng. URL public của ảnh thông thường vẫn theo key hiện tại; không dùng đường public đó cho PDF hợp đồng.
- HĐ chính, phụ lục và các lượt ký là các tài liệu độc lập. `revisionId` quản lý điều khoản; S3 Version ID quản lý nội dung lưu trữ. URL presigned không phải định danh tài liệu.
- Phụ lục tạo proof sau khi ký đủ, kể cả đang chờ ngày hiệu lực. Trạng thái `SCHEDULED/APPLIED` điều khiển nghiệp vụ, không sửa proof cũ.
- PDF cuối được phục vụ qua API xác thực nội dung, không cấp đường S3 trực tiếp để bỏ qua kiểm tra. Không xóa PDF đã ký qua API storage thông thường.

S3 phải bật Versioning và IAM backend có `s3:GetObjectVersion`; xem [hướng dẫn S3](../hs-storage-service/SETUP_S3.md). Nếu đổi bucket trong `.env`, các object cũ trong database vẫn trỏ bucket cũ; không tự sửa tên bucket trong DB khi chưa chuyển và đối chiếu file. Backend không tự backfill tài liệu cũ hoặc bật Versioning trên AWS.

## 2. Điền biến môi trường backend

File cần điền: **`hs-core-api/.env`**. Core tự đọc file này khi chạy từ thư mục `hs-core-api` qua `spring.config.import`; không phải file `.env` của dự án Foundry hay frontend.

Các giá trị kết nối/private key được để trống. Công tắc giữ `false` cho đến khi bạn cấu hình xong:

```dotenv
CONTRACT_DOCUMENT_REGISTRY_ENABLED=false
CONTRACT_DOCUMENT_REGISTRY_RPC_URL=
CONTRACT_DOCUMENT_REGISTRY_ADDRESS=
CONTRACT_DOCUMENT_REGISTRY_OWNER_PRIVATE_KEY=
```

| Biến | Bạn điền gì? |
|---|---|
| `CONTRACT_DOCUMENT_REGISTRY_ENABLED` | `false` để tắt; đổi `true` sau khi kiểm tra xong. Khi bật và có hợp đồng mới ký đủ, backend sẽ tự gửi transaction và dùng Sepolia ETH. |
| `CONTRACT_DOCUMENT_REGISTRY_RPC_URL` | URL Ethereum **Sepolia** của Alchemy/provider bạn đang dùng. |
| `CONTRACT_DOCUMENT_REGISTRY_ADDRESS` | Địa chỉ `ContractDocumentRegistry` đã deploy và test; lấy đúng địa chỉ hiện tại, không lấy địa chỉ ví hay transaction hash. |
| `CONTRACT_DOCUMENT_REGISTRY_OWNER_PRIVATE_KEY` | Private key của ví `owner()` hiện tại của registry: 64 ký tự hex, có thể có tiền tố `0x`. Không điền địa chỉ ví, seed phrase hoặc mật khẩu. |

Backend Java dùng Web3j `Credentials.create(...)` để ký trực tiếp bằng `CONTRACT_DOCUMENT_REGISTRY_OWNER_PRIVATE_KEY`; không cần file keystore hoặc mật khẩu. Foundry/Cast vẫn có thể dùng `ACCOUNT=sepoliaKey` để bạn deploy hoặc thao tác thủ công; cấu hình đó độc lập với backend.

Trong `application.properties`, `${CONTRACT_DOCUMENT_REGISTRY_OWNER_PRIVATE_KEY}` đọc khóa từ biến môi trường/file `.env`. Bạn tự điền khóa thật vào `.env` hoặc cấp biến môi trường khi chạy server/container. File `.env` đã được Git ignore; backend không trả khóa qua API hoặc ghi khóa vào log/database. Không đưa biến này vào frontend `NEXT_PUBLIC_*`.

Core đọc `.env` theo định dạng Java `.properties`: điền giá trị trên một dòng, không bọc dấu nháy. Test dùng khóa ngẫu nhiên tạo trong bộ nhớ, không đọc ví thật của bạn.

Trong `hs-api-service/src/main/resources/application.properties`, mạng mặc định là Sepolia `11155111`, chu kỳ worker 10 giây, hẹn đối soát lại 30 giây và timeout mỗi RPC 15 giây. Không cần Etherscan API key để gửi/đọc proof: API key đó chỉ phục vụ bước verify source khi deploy.

**Sau khi điền:** bảo đảm ví owner có Sepolia ETH, đổi `CONTRACT_DOCUMENT_REGISTRY_ENABLED=true`, rồi khởi động lại Core bằng cách bạn đang dùng. Cấu hình RPC/private key được cache trong tiến trình; thay đổi cấu hình cần restart. Không dùng đồng thời ví owner để gửi transaction thủ công khi worker đang xử lý hàng đợi.

## 3. Kiểm tra kết nối trước khi bật

Khối PowerShell dưới chỉ lấy RPC và địa chỉ registry từ `.env`, sau đó gọi chain ID và `owner()`; không sử dụng private key, không ký hoặc gửi giao dịch.

**Output dự kiến:** dòng đầu `11155111`; dòng sau là địa chỉ ví owner. Private key bạn cấu hình phải thuộc ví này.

```powershell
$blockchainConfig = @{}
foreach ($line in Get-Content -LiteralPath .env -ErrorAction Stop) {
    if ($line -match '^\s*(CONTRACT_DOCUMENT_REGISTRY_RPC_URL|CONTRACT_DOCUMENT_REGISTRY_ADDRESS)\s*=\s*(.*?)\s*$') {
        $blockchainConfig[$Matches[1]] = $Matches[2]
    }
}
cast chain-id --rpc-url $blockchainConfig.CONTRACT_DOCUMENT_REGISTRY_RPC_URL
if ($LASTEXITCODE -ne 0) { throw 'Không đọc được chain ID.' }
cast call $blockchainConfig.CONTRACT_DOCUMENT_REGISTRY_ADDRESS 'owner()(address)' --rpc-url $blockchainConfig.CONTRACT_DOCUMENT_REGISTRY_RPC_URL
if ($LASTEXITCODE -ne 0) { throw 'Không đọc được owner của registry.' }
```

Nếu chain ID khác `11155111`, dừng và sửa RPC trước khi bật. Worker cũng kiểm tra mạng, code tại địa chỉ contract và quyền owner trước khi chuẩn bị giao dịch. Đọc proof không cần private key.

## 4. Database và khả năng khôi phục

Hai bảng mới:

- `contract_document_proofs`: gắn proof vào `ContractDocument.id`, cố định revision/storage/hash/network/registry; lưu kết quả và thông tin để tiếp tục xử lý sau restart.
- `blockchain_sender_leases`: khóa hàng đợi gửi dùng chung giữa các instance Core, tránh cùng lúc cấp nonce/gửi giao dịch khác nhau.

Ở môi trường phát triển dùng `JPA_DDL_AUTO=update`, Hibernate tạo các bảng từ entity khi Core khởi động. Nếu môi trường dùng `validate`/`none`, cần áp dụng [SQL tạo bảng](sql/BLOCKCHAIN_PROOFS.sql) theo quy trình database của bạn trước. Không đổi sang `create`/`create-drop` trên database đang có dữ liệu.

Job được lưu bền vững, không phụ thuộc tab trình duyệt. Khi tắt tính năng, worker dừng và dữ liệu đã có vẫn được giữ. Khi bật lại, worker tiếp tục các job đang chờ; hợp đồng ký trong lúc tắt/chưa đủ cấu hình công khai không tự được ghi bù. Chưa có backfill hàng loạt hoặc proof cho gia hạn RENEWAL.

Với database mới, khởi động Core bằng cấu hình `ddl-auto=update` để Hibernate tạo cột/entity mới rồi mới seed theo quy trình hiện có. Không cần reset lại DB để triển khai tính năng. Môi trường `validate/none` phải cập nhật schema tương ứng trước khi chạy; SQL proof cũ chỉ mô tả bảng blockchain, không thay thế toàn bộ schema storage/tài liệu.

Các cột bổ sung: `storage_objects.s3_version_id`, `row_version` (default `0`); `contract_documents.signed_sha256`, `signed_s3_version_id`, `integrity_status`, `integrity_checked_at`, `integrity_message`. Hash/version lúc ký không được cập nhật lại qua luồng ứng dụng. Kết quả kiểm tra gần nhất lưu trên tài liệu; mỗi lần kiểm tra có audit log `CONTRACT_DOCUMENT_INTEGRITY`, không tạo bảng audit riêng. Seed cũ có thể bỏ qua các cột nullable/default này; không tự điền hash/version giả cho dữ liệu seed.

Worker lưu nonce, hash transaction và bytes giao dịch đã ký **trước khi broadcast**. Timeout không tạo ID/nonce mới mù quáng: worker đọc lại proof/receipt và có thể phát lại cùng bytes. Dữ liệu giao dịch này không được trả qua API hoặc ghi log; private key không được lưu vào bảng proof.

## 5. Hiển thị và đối chiếu trên web

Phần proof xuất hiện cạnh đúng PDF cuối ở HĐ Cọc, HĐ Thuê gốc và phụ lục:

| Trạng thái | Ý nghĩa |
|---|---|
| `NOT_AVAILABLE` | Chưa có bản ghi proof, ví dụ hợp đồng cũ hoặc ký lúc tính năng tắt. |
| `PENDING` | Đã lưu yêu cầu, chờ chuẩn bị/gửi hoặc đang hẹn thử lại. |
| `SUBMITTED` | Đã lưu thông tin giao dịch để gửi/đối soát; có thể đang chờ mined hoặc finalized. Có tx hash chưa đủ chứng minh thành công. |
| `CONFIRMED` | Receipt/event/mapping đã khớp và block đạt finalized. |
| `FAILED` | Cần đối soát, ví dụ hash nguồn thay đổi, hash on-chain xung đột hoặc receipt không hợp lệ. PDF đã ký vẫn giữ nguyên. |

Web tự cập nhật khi proof còn chờ và tab đang hiển thị. Người dùng không cần MetaMask hoặc nạp ETH vào tài khoản HomeSpace.

### Tự kiểm tra PDF đang lưu

Khi mở tài liệu cuối, web tự yêu cầu backend đọc file thực tế theo version đã cố định, tính SHA-256 và đối chiếu. Hiển thị riêng trạng thái ghi nhận blockchain và kết quả kiểm tra file cùng thời điểm kiểm tra. Không tải lại toàn bộ file mỗi lần render hoặc mỗi lần poll trạng thái proof; khi proof chuyển sang `CONFIRMED` sẽ kiểm tra lại.

| Kết quả | Ý nghĩa |
|---|---|
| `MATCH` | Bytes file khớp baseline và hash đọc trực tiếp từ blockchain. |
| `MISMATCH` | Nội dung hoặc liên kết bằng chứng không khớp; chặn phục vụ file. |
| `PENDING` | File khớp hash lúc ký nhưng chưa có xác nhận on-chain; vẫn đọc được với nhãn tương ứng. |
| `NOT_AVAILABLE` | Tài liệu cũ chưa có hash/bằng chứng để xác thực; vẫn đọc theo luồng tương thích, không gắn nhãn đã xác thực. |
| `UNAVAILABLE` | Tạm thời không kiểm tra được do RPC/storage; không coi là file sai, cần thử lại. |
| `MISSING` | Không còn đọc được file/phiên bản cần dùng; cần đối soát lưu trữ. |

Khi bấm **Xem/Tải**, backend kiểm tra lại và chỉ trả chính bytes đã kiểm tra, trước khi gửi nội dung PDF. `MISMATCH/MISSING/UNAVAILABLE` bị chặn; trạng thái ký/nghiệp vụ và proof blockchain không bị đổi. Dữ liệu phiên bản cũ không được tự gán latest hoặc tự gửi lại blockchain.

### Đối chiếu một bản bên ngoài

Phần mở rộng **Đối chiếu PDF từ bên ngoài** dùng cho file nhận qua email/Zalo hoặc lưu trên máy: chọn file tối đa 20 MB → trình duyệt tính SHA-256 → gửi **hash** cho backend đọc registry. File không được upload. Đây là công cụ bổ sung; người dùng không phải tải và chọn lại PDF để xem hợp đồng hằng ngày. Hash khớp không tự chứng minh tính pháp lý hoặc nội dung nghiệp vụ của hợp đồng.

## 6. API

Cả `/contracts/...` và `/api/v1/contracts/...` đều được hỗ trợ, dùng authentication và quyền đọc tài liệu hiện có. Response giữ envelope `ApiResponse.result`, không cache.

### Đọc trạng thái

`GET /api/v1/contracts/documents/{documentId}/blockchain-proof`

`documentId` là UUID tài liệu PDF trong HomeSpace, không phải `onChainDocumentId` bytes32. Kết quả chứa trạng thái, hash, chain/registry, ID on-chain và các trường transaction/block/thời điểm nếu đã có. Không trả private key, nonce hoặc raw transaction.

### Đối chiếu hash

`POST /api/v1/contracts/documents/{documentId}/blockchain-proof/verify`

```json
{ "documentHash": "0x<64 ký tự hex SHA-256 của file cần đối chiếu>" }
```

Kết quả gồm `registered`, `matches`, `documentHash` đọc từ chain và thông tin registry. Chưa có proof trả `409`; không đủ quyền trả `403`; hash sai định dạng trả `400`; RPC chưa khả dụng trả `503`. `registered=false` là chưa thấy commitment tại ID đó, khác với `registered=true, matches=false` là file không khớp. API không cho client tùy ý đăng ký tài liệu lên chain.

### Kiểm tra file lưu trên hệ thống

`POST /api/v1/contracts/documents/{documentId}/integrity-check` — không cần body.

Response `result` gồm `documentId`, `status`, `documentHash`, `onChainDocumentHash`, `checkedAt`, `message`. Chỉ kiểm tra PDF cuối đủ điều kiện, yêu cầu quyền đọc tài liệu; không nhận URL/storage key từ client.

### Xem/tải PDF cuối

`GET /api/v1/contracts/documents/{documentId}/content?download=false`

`download=false`: hiển thị inline; `download=true`: tải attachment. Cả hai trả PDF bytes có authentication và `Cache-Control: no-store`, thay vì redirect tới URL S3 chưa kiểm tra. Frontend dùng request có token và blob URL cục bộ, thu hồi blob khi đóng/thay tài liệu.

## 7. Checklist bạn tự chạy với Sepolia

1. Điền cấu hình, kiểm tra chain/owner, nạp đủ Sepolia ETH và bật tính năng; restart Core.
2. Hoàn tất một HĐ Cọc mới: sau lượt chủ nhà chưa có proof; sau lượt người thuê có PDF cuối và proof đang chờ.
3. Chờ `CONFIRMED`, mở Etherscan: đúng registry, event ID/hash và giao dịch thành công. Đọc [giải thích receipt](../../hs-smart-contract-blockchain/POST_DEPLOY_TEST.md) nếu cần kiểm tra bằng Cast.
4. Mở lại trang: kết quả tự kiểm tra phải `MATCH`. Bấm xem/tải nhận đúng PDF. Phần đối chiếu bên ngoài: chọn bản đúng phải khớp, bản có bytes khác phải không khớp; đổi tên file không đổi kết quả.
5. Hoàn tất HĐ Thuê của cùng hồ sơ: có proof/ID riêng; HĐ Cọc vẫn tra cứu được.
6. Tạm ngắt RPC hoặc restart khi đang chờ: chữ ký/PDF vẫn có, hàng đợi tiếp tục đối soát khi kết nối trở lại; không yêu cầu người dùng ký lại SmartCA.
7. Tạo và ký đủ một phụ lục: có file/proof riêng trước ngày hiệu lực; tới ngày áp dụng, tài liệu hiệu lực và lịch sử vẫn tra đúng proof.
8. Trên file thử, upload nội dung khác cùng key: S3 tạo version mới nhưng HomeSpace vẫn đọc version gốc đã cố định. Nếu phiên bản gốc không còn đọc được, báo lỗi và chặn phục vụ; không tự lấy latest.
9. API cấp URL/xóa storage không được cho bỏ qua kiểm tra hoặc xóa mềm PDF cuối. Không dùng hợp đồng thật để thử thao tác xóa phiên bản vĩnh viễn.

Worker hiện ưu tiên đối soát giao dịch đang chờ trước khi gửi proof tiếp theo. Finalized có thể đến sau thời điểm Etherscan hiển thị Success, nên trạng thái chờ kéo dài không tự có nghĩa là lỗi. Chưa tự động tăng phí/thay thế giao dịch bị kẹt; RPC không hỗ trợ finalized hoặc giới hạn tra log lịch sử có thể khiến job tiếp tục chờ và cần quản trị đối soát. Không xóa bản ghi/đổi ID để thử lại giao dịch chưa rõ kết quả.

## 8. Kiểm thử không dùng ví thật

Các test tập trung bao phủ điểm enqueue, hash PDF, quyền API, đối chiếu hash, lỗi RPC/receipt, retry, nonce và private key fixture (đúng định dạng, khoảng giá trị secp256k1, suy ra đúng ví, ký offline). SDK dùng [Web3j 4.14.0](https://github.com/LFDT-web3j/web3j/releases/tag/v4.14.0).

Test PostgreSQL opt-in chỉ chạy với database thử riêng `hs_blockchain_test` trên `127.0.0.1`, cấu hình bằng `HS_BLOCKCHAIN_TEST_PG_URL`, `HS_BLOCKCHAIN_TEST_PG_USER`, `HS_BLOCKCHAIN_TEST_PG_PASSWORD`. Test dùng `create-drop`; không trỏ vào database HomeSpace thật. Không có các biến này thì test database được skip. Test unit/mocked RPC không chứng minh giao dịch thực trên Sepolia đã thành công; checklist ở trên do bạn chủ động thực hiện sau khi điền cấu hình.
