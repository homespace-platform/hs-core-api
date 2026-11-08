# Ghi nhận PDF hợp đồng lên blockchain

Tính năng chạy trong `hs-contract-service`, được Core API nạp cùng luồng ký SmartCA. Phạm vi đầu tiên là **HĐ Cọc và HĐ Thuê gốc**. Registry đã deploy có thể dùng tiếp; không cần deploy lại Solidity cho tính năng này.

## 1. Luồng xử lý

1. Chủ nhà ký SmartCA, tạo PDF `SIGNED_LANDLORD`: chưa gửi blockchain.
2. Người thuê ký tiếp; backend nhúng và xác minh đủ hai chữ ký, upload PDF riêng tư `SIGNED_FINAL`.
3. Backend tính SHA-256 từ đúng bytes PDF đó, lưu yêu cầu proof cùng transaction hoàn tất ký. HĐ Cọc và HĐ Thuê có ID/proof riêng.
4. Worker đọc yêu cầu đã commit, đối chiếu bytes trong storage và gọi `registerDocument(bytes32 documentId, bytes32 documentHash)` bằng ví hệ thống.
5. Worker kiểm tra receipt, sự kiện `DocumentRegistered`, hash trong registry, block canonical và mốc `finalized`, rồi đánh dấu `CONFIRMED`.

RPC lỗi hoặc ví thiếu gas không đảo chữ ký, không đổi trạng thái nghiệp vụ của cọc/thuê. Blockchain có trạng thái riêng. Hệ thống không chỉnh sửa hoặc chèn mã proof vào PDF sau khi ký.

Worker dùng executor riêng, không giữ luồng scheduler đang poll/nhúng chữ ký SmartCA. Mỗi instance chỉ chạy một lượt xử lý blockchain tại một thời điểm; lease database phối hợp giữa các instance.

## 2. Điền biến môi trường backend

File cần điền: **`hs-core-api/.env`**. Core tự đọc file này khi chạy từ thư mục `hs-core-api` qua `spring.config.import`; không phải file `.env` của dự án Foundry hay frontend.

Các giá trị kết nối/private key được để trống. Công tắc giữ `false` cho đến khi bạn cấu hình xong:

```dotenv
BLOCKCHAIN_ENABLED=false
BLOCKCHAIN_RPC_URL=
BLOCKCHAIN_REGISTRY_ADDRESS=
BLOCKCHAIN_PRIVATE_KEY=
```

| Biến | Bạn điền gì? |
|---|---|
| `BLOCKCHAIN_ENABLED` | `false` để tắt; đổi `true` sau khi kiểm tra xong. Khi bật và có hợp đồng mới ký đủ, backend sẽ tự gửi transaction và dùng Sepolia ETH. |
| `BLOCKCHAIN_RPC_URL` | URL Ethereum **Sepolia** của Alchemy/provider bạn đang dùng. |
| `BLOCKCHAIN_REGISTRY_ADDRESS` | Địa chỉ `ContractDocumentRegistry` đã deploy và test; lấy đúng địa chỉ hiện tại, không lấy địa chỉ ví hay transaction hash. |
| `BLOCKCHAIN_PRIVATE_KEY` | Private key của ví `owner()` hiện tại của registry: 64 ký tự hex, có thể có tiền tố `0x`. Không điền địa chỉ ví, seed phrase hoặc mật khẩu. |

Backend Java dùng Web3j `Credentials.create(...)` để ký trực tiếp bằng `BLOCKCHAIN_PRIVATE_KEY`; không cần file keystore hoặc mật khẩu. Hai biến `BLOCKCHAIN_KEYSTORE_PATH` và `BLOCKCHAIN_KEYSTORE_PASSWORD` cũ không còn được dùng. Foundry/Cast vẫn có thể dùng `ACCOUNT=sepoliaKey` để bạn deploy hoặc thao tác thủ công; cấu hình đó độc lập với backend.

Trong `application.properties`, `${BLOCKCHAIN_PRIVATE_KEY:}` đọc khóa từ biến môi trường/file `.env`; mặc định trống. Bạn tự điền khóa thật vào `.env` hoặc cấp biến môi trường khi chạy server/container. File `.env` đã được Git ignore; backend không trả khóa qua API hoặc ghi khóa vào log/database. Không đưa biến này vào frontend `NEXT_PUBLIC_*`.

Core đọc `.env` theo định dạng Java `.properties`: điền giá trị trên một dòng, không bọc dấu nháy. Test dùng khóa ngẫu nhiên tạo trong bộ nhớ, không đọc ví thật của bạn.

Trong `hs-api-service/src/main/resources/application.properties`, mạng mặc định là Sepolia `11155111`, chu kỳ worker 10 giây, hẹn đối soát lại 30 giây và timeout mỗi RPC 15 giây. Không cần Etherscan API key để gửi/đọc proof: API key đó chỉ phục vụ bước verify source khi deploy.

**Sau khi điền:** bảo đảm ví owner có Sepolia ETH, đổi `BLOCKCHAIN_ENABLED=true`, rồi khởi động lại Core bằng cách bạn đang dùng. Cấu hình RPC/private key được cache trong tiến trình; thay đổi cấu hình cần restart. Không dùng đồng thời ví owner để gửi transaction thủ công khi worker đang xử lý hàng đợi.

## 3. Kiểm tra kết nối trước khi bật

Khối PowerShell dưới chỉ lấy RPC và địa chỉ registry từ `.env`, sau đó gọi chain ID và `owner()`; không sử dụng private key, không ký hoặc gửi giao dịch.

**Output dự kiến:** dòng đầu `11155111`; dòng sau là địa chỉ ví owner. Private key bạn cấu hình phải thuộc ví này.

```powershell
$blockchainConfig = @{}
foreach ($line in Get-Content -LiteralPath .env -ErrorAction Stop) {
    if ($line -match '^\s*(BLOCKCHAIN_RPC_URL|BLOCKCHAIN_REGISTRY_ADDRESS)\s*=\s*(.*?)\s*$') {
        $blockchainConfig[$Matches[1]] = $Matches[2]
    }
}
cast chain-id --rpc-url $blockchainConfig.BLOCKCHAIN_RPC_URL
if ($LASTEXITCODE -ne 0) { throw 'Không đọc được chain ID.' }
cast call $blockchainConfig.BLOCKCHAIN_REGISTRY_ADDRESS 'owner()(address)' --rpc-url $blockchainConfig.BLOCKCHAIN_RPC_URL
if ($LASTEXITCODE -ne 0) { throw 'Không đọc được owner của registry.' }
```

Nếu chain ID khác `11155111`, dừng và sửa RPC trước khi bật. Worker cũng kiểm tra mạng, code tại địa chỉ contract và quyền owner trước khi chuẩn bị giao dịch. Đọc proof không cần private key.

## 4. Database và khả năng khôi phục

Hai bảng mới:

- `contract_document_proofs`: gắn proof vào `ContractDocument.id`, cố định revision/storage/hash/network/registry; lưu kết quả và thông tin để tiếp tục xử lý sau restart.
- `blockchain_sender_leases`: khóa hàng đợi gửi dùng chung giữa các instance Core, tránh cùng lúc cấp nonce/gửi giao dịch khác nhau.

Ở môi trường phát triển dùng `JPA_DDL_AUTO=update`, Hibernate tạo các bảng từ entity khi Core khởi động. Nếu môi trường dùng `validate`/`none`, cần áp dụng [SQL tạo bảng](sql/BLOCKCHAIN_PROOFS.sql) theo quy trình database của bạn trước. Không đổi sang `create`/`create-drop` trên database đang có dữ liệu.

Job được lưu bền vững, không phụ thuộc tab trình duyệt. Khi tắt tính năng, worker dừng và dữ liệu đã có vẫn được giữ. Khi bật lại, worker tiếp tục các job đang chờ; hợp đồng ký trong lúc tắt/chưa đủ cấu hình công khai không tự được ghi bù. Giai đoạn này chưa có backfill hàng loạt, phụ lục hoặc gia hạn.

Worker lưu nonce, hash transaction và bytes giao dịch đã ký **trước khi broadcast**. Timeout không tạo ID/nonce mới mù quáng: worker đọc lại proof/receipt và có thể phát lại cùng bytes. Dữ liệu giao dịch này không được trả qua API hoặc ghi log; private key không được lưu vào bảng proof.

## 5. Hiển thị và đối chiếu trên web

Phần proof xuất hiện cạnh đúng PDF cuối ở cả HĐ Cọc và HĐ Thuê gốc:

| Trạng thái | Ý nghĩa |
|---|---|
| `NOT_AVAILABLE` | Chưa có bản ghi proof, ví dụ hợp đồng cũ hoặc ký lúc tính năng tắt. |
| `PENDING` | Đã lưu yêu cầu, chờ chuẩn bị/gửi hoặc đang hẹn thử lại. |
| `SUBMITTED` | Đã lưu thông tin giao dịch để gửi/đối soát; có thể đang chờ mined hoặc finalized. Có tx hash chưa đủ chứng minh thành công. |
| `CONFIRMED` | Receipt/event/mapping đã khớp và block đạt finalized. |
| `FAILED` | Cần đối soát, ví dụ hash nguồn thay đổi, hash on-chain xung đột hoặc receipt không hợp lệ. PDF đã ký vẫn giữ nguyên. |

Web tự cập nhật khi proof còn chờ và tab đang hiển thị. Khi xác nhận xong có link transaction trên Sepolia Etherscan. Người dùng không cần MetaMask hoặc nạp ETH vào tài khoản HomeSpace.

Nút **Đối chiếu PDF**: chọn file local tối đa 20 MB → trình duyệt tính SHA-256 → gửi **hash** cho backend đọc registry. File không được upload trong thao tác này. `matches=true` nghĩa là bytes file khớp commitment on-chain. RPC lỗi được hiển thị là chưa kiểm tra được, không coi là file sai. Hash khớp không tự chứng minh tính pháp lý hoặc nội dung nghiệp vụ của hợp đồng.

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

## 7. Checklist bạn tự chạy với Sepolia

1. Điền cấu hình, kiểm tra chain/owner, nạp đủ Sepolia ETH và bật tính năng; restart Core.
2. Hoàn tất một HĐ Cọc mới: sau lượt chủ nhà chưa có proof; sau lượt người thuê có PDF cuối và proof đang chờ.
3. Chờ `CONFIRMED`, mở Etherscan: đúng registry, event ID/hash và giao dịch thành công. Đọc [giải thích receipt](../../hs-smart-contract-blockchain/POST_DEPLOY_TEST.md) nếu cần kiểm tra bằng Cast.
4. Chọn PDF vừa tải về để đối chiếu: phải khớp. Chọn một PDF có bytes khác: phải không khớp. Đổi tên file không thay đổi bytes nên vẫn khớp.
5. Hoàn tất HĐ Thuê của cùng hồ sơ: có proof/ID riêng; HĐ Cọc vẫn tra cứu được.
6. Tạm ngắt RPC hoặc restart khi đang chờ: chữ ký/PDF vẫn có, hàng đợi tiếp tục đối soát khi kết nối trở lại; không yêu cầu người dùng ký lại SmartCA.

Worker hiện ưu tiên đối soát giao dịch đang chờ trước khi gửi proof tiếp theo. Finalized có thể đến sau thời điểm Etherscan hiển thị Success, nên trạng thái chờ kéo dài không tự có nghĩa là lỗi. Chưa tự động tăng phí/thay thế giao dịch bị kẹt; RPC không hỗ trợ finalized hoặc giới hạn tra log lịch sử có thể khiến job tiếp tục chờ và cần quản trị đối soát. Không xóa bản ghi/đổi ID để thử lại giao dịch chưa rõ kết quả.

## 8. Kiểm thử không dùng ví thật

Các test tập trung bao phủ điểm enqueue, hash PDF, quyền API, đối chiếu hash, lỗi RPC/receipt, retry, nonce và private key fixture (đúng định dạng, khoảng giá trị secp256k1, suy ra đúng ví, ký offline). SDK dùng [Web3j 4.14.0](https://github.com/LFDT-web3j/web3j/releases/tag/v4.14.0).

Test PostgreSQL opt-in chỉ chạy với database thử riêng `hs_blockchain_test` trên `127.0.0.1`, cấu hình bằng `HS_BLOCKCHAIN_TEST_PG_URL`, `HS_BLOCKCHAIN_TEST_PG_USER`, `HS_BLOCKCHAIN_TEST_PG_PASSWORD`. Test dùng `create-drop`; không trỏ vào database HomeSpace thật. Không có các biến này thì test database được skip. Test unit/mocked RPC không chứng minh giao dịch thực trên Sepolia đã thành công; checklist ở trên do bạn chủ động thực hiện sau khi điền cấu hình.
