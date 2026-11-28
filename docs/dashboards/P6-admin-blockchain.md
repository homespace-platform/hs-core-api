# P6 · Blockchain Explorer (admin) — `hs-admin-portal` `/blockchain`

File hiện tại: `src/pages/blockchain/BlockchainExplorerPage.tsx` (7 dòng, trang trắng). Route `<Route path="blockchain" element={<BlockchainExplorerPage />} />` (`router/index.tsx`), chưa gắn quyền; sidebar "Blockchain Explorer" (`layouts/Sidebar.tsx:83`). Trạng thái: **THIẾT KẾ (bản sau kiểm kê 28/11/2026), chờ duyệt**. Nền tảng nghiệp vụ: `docs/BLOCKCHAIN_INTEGRATION.md` (§1 luồng, §5 trạng thái).

## 0. Thay đổi sau kiểm kê
- Quyền: dùng danh sách duy nhất ở **P4 §6** (`BLOCKCHAIN_VIEW`, `BLOCKCHAIN_MANAGE`; thêm `CONTRACT_VIEW` có sẵn cho `integrity`); P6 không định nghĩa quyền riêng.
- `summary` thêm: `integrity` (tài liệu `MISMATCH/MISSING/UNAVAILABLE`), `retrying` + `byErrorCode`, `openIncidents` (thành phần `BLOCKCHAIN`), `senderLeaseHeldUntil`, `ethUnsettled`. Cùng dịch vụ truy vấn được P4 §4.1.5 dùng (một nguồn sự thật, P4 chỉ hiện một dòng + liên kết).
- Tab ETH thêm bộ lọc "Chưa settle" (có `transaction_hash` mà đơn chưa `PAID`) — đối soát tiền on-chain đã gửi nhưng chưa cấp quyền.
- Điều kiện sửa lỗi liên quan: sự cố `BLOCKCHAIN` và job `BlockchainProofScheduler` sẽ ghi lần chạy (P4 §5.4) → trang hiện "lần chạy cuối của worker".
- Thêm kiểm thử và mục "Không đưa vào (lý do)" ở §9.

## 1. Mục đích & người xem
- Người xem: quản trị có `BLOCKCHAIN_VIEW` (mới). Quyết định trong 10 giây: "Có hợp đồng nào ký xong mà chưa được ghi lên blockchain, hoặc ghi thất bại, cần đối soát không? Một hợp đồng cụ thể đã được ghi chưa và hash có khớp on-chain không?"
- Đây là trang vận hành/đối soát, **không** là trình duyệt blockchain chung: chỉ proof của hợp đồng HomeSpace (bảng `contract_document_proofs`) và thanh toán hội viên ETH. Không hiển thị nội dung PDF, không hiển thị thông tin các bên (chỉ số hợp đồng).

## 2. Bố cục (desktop)
```
+--------------------------------------------------------------------------+
| Blockchain Explorer                      Mạng: Sepolia (11155111)  [Tải lại]|
| Registry: 0x12ab…89ef [Etherscan ↗]      Ghi nhận: BẬT/TẮT               |
+--------------------------------------------------------------------------+
| [Chờ 3] [Đang xác nhận 2] [Đã xác nhận 148] [Thất bại 1 ●đỏ]  (bấm = lọc)  |
| Cảnh báo: 2 proof chờ > 15 phút                                           |
+--------------------------------------------------------------------------+
| Tab: [Proof tài liệu hợp đồng] [Thanh toán hội viên ETH]                  |
| Lọc: Trạng thái [Tất cả v]  Số HĐ [____]  Từ ngày..Đến ngày               |
+--------------------------------------------------------------------------+
| Số HĐ        | Loại      | Trạng thái | Tx hash     | Block   | Tạo  | Xác nhận|
| HS-2611-0042 | Thuê gốc  | CONFIRMED  | 0x9f3a…c1 ↗ | 7312045 | 27/11| 27/11   |
| HS-2611-0043 | Cọc       | FAILED     | 0x77b0…de ↗ | —       | 28/11| —       |
|  ... phân trang 20/trang ...                                              |
+--------------------------------------------------------------------------+
Drawer chi tiết (bên phải, mở khi bấm một dòng):
 [x] HS-2611-0042 · Hợp đồng thuê gốc · CONFIRMED
 - Hash tài liệu (SHA-256) / ID on-chain / Registry / Chain 11155111
 - Tx hash ↗ | Block ↗ | Block hash | Registered at | Confirmed at
 - Xác minh on-chain: [Kiểm tra ngay] -> "Khớp: hash lưu = hash on-chain" 
 - Kiểm tra tệp đã lưu: [Kiểm tra tệp] -> MATCH/MISMATCH/... (không tải PDF)
 - Lỗi: mã + mô tả (nếu FAILED)   [Thử lại] (chỉ hiện khi đủ điều kiện, §4)
```
Màn nhỏ: bốn thẻ trạng thái thành lưới 2x2; bảng chuyển thành danh sách thẻ (số HĐ, trạng thái, tx rút gọn); drawer thành toàn màn hình; hash luôn rút gọn `0x1234…abcd` kèm nút sao chép.

## 3. Từng khối
Đồng hồ: thời gian hiển thị `vi-VN`, `Asia/Ho_Chi_Minh`; "chờ quá lâu" dùng giờ thật (`Instant.now()`), vì worker chạy theo giờ thật (`ContractDocumentProofWorker.nextAttempt`, `ContractDocumentProofWorker.java` cuối file). Không dùng BillingTime.

| # | Khối | Định nghĩa | Nguồn | Bấm vào |
|---|---|---|---|---|
| H | Đầu trang | Mạng/Chain ID lấy từ cấu hình `homespace.blockchain.chain-id=11155111` (`application.properties:196`), địa chỉ registry `homespace.blockchain.registry-address` (dòng 194), cờ `homespace.blockchain.enabled` (dòng 192). **Không trả** `rpc-url`, `private-key` (dòng 193, 195). Liên kết registry = `https://sepolia.etherscan.io/address/{registry}`. | NEW `summary` | Etherscan (tab mới, `rel="noopener noreferrer"`) |
| S | 4 thẻ đếm theo trạng thái | `COUNT(*) GROUP BY status` trên `contract_document_proofs` (`ContractDocumentProof.java:12-22,54-56`; enum `ProofStatus` PENDING, SUBMITTED, CONFIRMED, FAILED, `ProofStatus.java:3-8`). `NOT_AVAILABLE` (chưa có proof) **không đếm được ở backend** vì không có hàng — không hiển thị. Thẻ FAILED đỏ nếu > 0. Dòng cảnh báo = số proof `status IN (PENDING,SUBMITTED)` có `created_at < now() - 15 phút` (ngưỡng giả định). | NEW `summary` | lọc bảng theo trạng thái |
| T | Bảng proof | Mỗi hàng = một `contract_document_proofs`: `status`, `transaction_hash`, `block_number`, `created_at`, `confirmed_at`, `document_kind` (1 = Cọc, 2 = Thuê gốc, 3 = Phụ lục/văn bản bổ sung; `ContractDocumentProofEnqueuer.java:40-41`), `error_code`; **số hợp đồng** = `contracts.contract_number` qua `contract_id` (`Contract.java:34`, unique `idx_contract_number`). Sắp xếp `created_at desc`. Lọc: `status` (enum), `contractNumber` (khớp chính xác hoặc tiền tố, không `LIKE '%x%'`), khoảng `created_at`. | NEW `proofs` | mở drawer |
| D | Drawer chi tiết | Trường lấy từ `BlockchainProofResponse` (`BlockchainProofResponse.java:6-10`: `documentHash`, `onChainDocumentId`, `chainId`, `registryAddress`, `transactionHash`, `blockNumber`, `blockHash`, `registeredAt`, `confirmedAt`, `errorCode`, `caseId`, `documentKind`) cộng `contractNumber`, `attempts`, `submittedAt`. Liên kết: tx `https://sepolia.etherscan.io/tx/{transactionHash}`, block `…/block/{blockNumber}`, registry `…/address/{registryAddress}`. Chỉ tạo liên kết khi `chainId = 11155111` (bảng ánh xạ `chainId → baseUrl` trong frontend; chain lạ → hiện hash, không có link). Hash hợp lệ phải khớp `^0x[0-9a-f]{64}$` trước khi nối vào URL. | NEW `proofs/{documentId}` | Etherscan |
| V | Xác minh on-chain | Nút "Kiểm tra ngay": backend đọc registry (`RegistryClient.readDocument(chainId, registry, onChainDocumentId)`, `RegistryClient.java:20`) và so `ChainProof.documentHash` với `proof.documentHash` trong DB; trả `registered`, `matches`, `onChainHash`, `registeredAt`. Lỗi RPC → `UNAVAILABLE` (không kết luận "sai"), đúng quy tắc `ContractDocumentProofQueryService.verify` (`…QueryService.java:96-102`, không lan nguyên nhân gốc vì có thể chứa URL/khoá). Không tự gọi khi mở drawer (tốn RPC); chỉ khi bấm. | NEW `proofs/{id}/verify` | — |
| F | Kiểm tra tệp đã lưu | Nút "Kiểm tra tệp": backend tính SHA-256 của PDF theo `signed_s3_version_id` và so với baseline (`ContractDocumentIntegrityService.check`, `ContractDocumentIntegrityService.java:41`), trả `DocumentIntegrityResponse` (`status`, `documentHash`, `onChainDocumentHash`, `checkedAt`, `message`). **Không trả bytes PDF.** Hàm `check` hiện chỉ cho bên hợp đồng (`inspect(documentId,false)`), cần thêm `checkForAdmin` gọi `inspect(documentId, true)` giống `contentForAdmin` (dòng 50). Cần cả `BLOCKCHAIN_VIEW` và `CONTRACT_VIEW`. | NEW `proofs/{id}/integrity` | — |
| E | Tab thanh toán hội viên ETH | Hàng = `membership_eth_quotes` có `transaction_hash IS NOT NULL` (`EthPaymentQuote.java` ~16-58; unique `uk_membership_eth_quote_tx`) ⋈ `membership_orders` (qua `membership_order_id`, `provider = ETH`): hiển thị mã đơn, gói (`tier`/`kind`), `amount_vnd`, `amount_wei`, `transaction_hash` + Etherscan, `confirmed_at`, trạng thái đơn; bộ lọc **"Chưa settle"** (tx hash đã có nhưng đơn chưa `PAID`: tiền on-chain đã gửi mà quyền chưa cấp, tín hiệu A7 của kiểm kê). Chain mặc định Sepolia (cấu hình `homespace.eth-payment.*` không khai báo chain id; giả định cùng Sepolia, dòng 178 `# ETH payment (Sepolia)`; nên bổ sung cột/cấu hình chain id khi có mạng khác — Câu hỏi 4). **Không hiển thị** `payer_wallet`, `signature`, `owner_id`; địa chỉ nhận/hợp đồng thanh toán chỉ hiển thị dạng rút gọn nếu cần (thông tin công khai on-chain nhưng liên kết được tới một người dùng). | NEW `membership-eth-payments` | Etherscan tx |

Trạng thái chung: đang tải = skeleton hàng bảng; trống = "Chưa có proof nào" (kèm gợi ý nếu `enabled=false`: "Ghi nhận blockchain đang tắt"); lỗi API = banner thử lại; lỗi RPC ở khối V/F là trạng thái `UNAVAILABLE` nhẹ nhàng (màu vàng), không phải lỗi trang. Tự làm mới `summary` + trang hiện tại mỗi 15 giây **chỉ khi** tab hiển thị và còn proof PENDING/SUBMITTED; dừng khi không còn (theo cách web tự cập nhật ở `docs/BLOCKCHAIN_INTEGRATION.md` §5).

## 4. API mới
Không có endpoint admin nào hiện có cho danh sách proof: `ContractBlockchainProofController` (`hs-api-service/.../controller/contract/ContractBlockchainProofController.java:15-32`) chỉ có `GET/POST /contracts/documents/{documentId}/blockchain-proof[/verify]` và dùng `authorizedDocument` → `contracts.getDocument` (`ContractDocumentProofQueryService.java:103-109`), tức quyền **bên hợp đồng**, admin không dùng được. `ContractDocumentAdminController` (`:27`) chỉ trả PDF. Vì vậy cần controller mới `AdminBlockchainController` (`@RequestMapping({"/admin/blockchain","/api/v1/admin/blockchain"})`).

Quyền (định nghĩa duy nhất ở **P4 §6**; thêm vào `Permissions.java` + `CATALOG`, tự gán ADMIN ở `PermissionDataInitializer.java:55`, phản chiếu `config/permissions.ts`): `BLOCKCHAIN_VIEW` "Xem proof blockchain và thanh toán ETH hội viên"; `BLOCKCHAIN_MANAGE` "Thử lại proof blockchain thất bại" (giai đoạn 2). Frontend: `ROUTE_PERMISSIONS["/blockchain"] = { permission: PERMISSIONS.BLOCKCHAIN_VIEW }` và `guard("/blockchain", <BlockchainExplorerPage />)` ở router; nút "Thử lại" bọc `<Can permission={PERMISSIONS.BLOCKCHAIN_MANAGE}>` (`components/common/Can.tsx`).

| Endpoint | Quyền | Mô tả |
|---|---|---|
| `GET /api/v1/admin/blockchain/summary` | ADMIN + `BLOCKCHAIN_VIEW` | `{ "network":{"chainId":11155111,"name":"Sepolia","registryAddress":"0x…","enabled":true,"explorerBaseUrl":"https://sepolia.etherscan.io"}, "counts":{"PENDING":3,"SUBMITTED":2,"CONFIRMED":148,"FAILED":1}, "stuck":2, "oldestOpenAgeSeconds":1320, "retrying":1, "byErrorCode":{"TRANSACTION_REVERTED":1}, "openIncidents":0, "senderLeaseHeldUntil":"2026-11-28T14:06:00+07:00", "integrity":{"MISMATCH":0,"MISSING":0,"UNAVAILABLE":1,"note":"chỉ phản ánh lần kiểm gần nhất"}, "ethUnsettled":0, "workerLastRunAgeSeconds":8 }`. `retrying` = proof `attempts>0` chưa `CONFIRMED/FAILED`; `byErrorCode` chỉ gồm mã đã biết (mã lạ → `OTHER`, không trả `error_message` thô); `openIncidents` = sự cố `BLOCKCHAIN` mở (đếm, không `sourceKey`); `senderLeaseHeldUntil` từ `blockchain_sender_leases` (lease hết hạn mà còn proof chờ = worker không chạy); `integrity` = `COUNT(*) GROUP BY integrity_status` trên `contract_documents` (`ContractDocumentIntegrityService.java:161`; **chỉ mới khi có người mở/kiểm**, nên UI ghi "có thể cũ"; job kiểm định kỳ có giới hạn tốc độ là giai đoạn 2); `ethUnsettled` = số `membership_eth_quotes` có `transaction_hash` mà đơn chưa `PAID`; `workerLastRunAgeSeconds` từ `scheduled_job_runs` (P4 §5.4), `null` nếu chưa có bảng. `explorerBaseUrl` do **backend** quyết định từ chain id (bảng cố định `11155111 → https://sepolia.etherscan.io`), frontend chỉ kiểm tra chuỗi bắt đầu bằng `https://` đã biết. Query: `select status, count(*) from contract_document_proofs group by status` + `min(created_at) where status in (PENDING,SUBMITTED)`. |
| `GET /api/v1/admin/blockchain/proofs?status=&contractNumber=&from=&to=&page=1&size=20` | `BLOCKCHAIN_VIEW` | Trả `PageResponse` (cùng kiểu `PageResponse` của `ContractAdminController.java:197`); `size` ≤ 50. Mỗi phần tử: `documentId`, `contractNumber`, `documentKind`, `status`, `transactionHash`, `blockNumber`, `createdAt`, `confirmedAt`, `errorCode`. JPQL/native: `from contract_document_proofs p join contracts c on c.id = p.contract_id where (:status is null or p.status = :status) …` — chỉ `select` cột phía trên, **không** nạp entity có `signed_raw_transaction`, `nonce`, `signer_address`. Index mới: `(status, created_at desc)` (hiện có `idx_document_proof_due(status,next_attempt_at,created_at)` và `idx_document_proof_contract(contract_id)`, `ContractDocumentProof.java:13-14`). |
| `GET /api/v1/admin/blockchain/proofs/{documentId}` | `BLOCKCHAIN_VIEW` | Chi tiết như khối D (`BlockchainProofResponse` + `contractNumber`, `attempts`, `submittedAt`). 404 nếu không có; không tự tạo proof (khác `ContractDocumentProofQueryService.get` vốn tự enqueue — admin chỉ đọc). |
| `POST /api/v1/admin/blockchain/proofs/{documentId}/verify` | `BLOCKCHAIN_VIEW` | Khối V. Không có body (hash lấy từ DB, không nhận hash từ client). Timeout RPC theo `homespace.blockchain.rpc-timeout-seconds=15` (`application.properties:198`); giới hạn tần suất 10 lần/phút/admin để không đốt quota RPC (giả định cần thêm bộ giới hạn). |
| `POST /api/v1/admin/blockchain/proofs/{documentId}/integrity` | `BLOCKCHAIN_VIEW` + `CONTRACT_VIEW` | Khối F, qua `checkForAdmin`. Chỉ trả `DocumentIntegrityResponse`. |
| `GET /api/v1/admin/blockchain/membership-eth-payments?unsettled=&page=&size=` | `BLOCKCHAIN_VIEW` | Khối E; tổng hợp 2 bảng, cột giới hạn như trên; `unsettled=true` chỉ trả hàng có `transaction_hash` mà đơn chưa `PAID` (kèm tuổi tính từ `confirmed_at`/`created_at` của quote). Index có sẵn: unique `transaction_hash`, `idx_membership_eth_quote_order`. |
| **Thử lại FAILED** `POST /api/v1/admin/blockchain/proofs/{documentId}/retry` | ADMIN + `BLOCKCHAIN_MANAGE` — **chưa tồn tại; chỉ là đề xuất, giai đoạn 2** | Không có hành động admin nào hiện nay: `ContractDocumentProofWorker.mutate` bỏ qua mọi proof đã `CONFIRMED/FAILED` (`ContractDocumentProofWorker.java:188`), tức FAILED là trạng thái cuối. Các mã lỗi terminal: `DOCUMENT_HASH_CONFLICT`, `DOCUMENT_LINEAGE_CONFLICT`, `SOURCE_PDF_CHANGED`, `TRANSACTION_MISMATCH`, `TRANSACTION_REVERTED` (`ContractDocumentProofWorker.java:78,104,132`; `Web3jRegistryClient.java:132,136,160`). Chỉ **`TRANSACTION_REVERTED`** được phép thử lại (có thể do gas/ví/RPC); bốn mã còn lại là dấu hiệu toàn vẹn/xung đột, **cấm** thử lại bằng nút, phải điều tra thủ công (đúng nguyên tắc "không tự tạo ID mới", `docs/BLOCKCHAIN_INTEGRATION.md` §4). Điều kiện server kiểm trong cùng transaction: `findByIdForUpdate` (`ContractDocumentProofRepository.java:19-21`); `status=FAILED` và `error_code=TRANSACTION_REVERTED`; tính năng đang bật; `checkForAdmin` trả `MATCH` hoặc `PENDING` (tệp còn khớp hash lúc ký); `readDocument` on-chain chưa đăng ký **hoặc** đã đăng ký đúng hash (nếu đã đúng hash thì chuyển worker đối soát, không gửi lại). Hành động: đặt `status=PENDING`, xoá `nonce`, `signed_raw_transaction`, `transaction_hash`, `block_number`, `block_hash`, `submitted_at`, `error_code/message`, `attempts=0`, `next_attempt_at=now` (nonce phải xoá vì có unique `uq_document_proof_nonce`, `ContractDocumentProof.java:17`); không đụng `document_hash`, `on_chain_document_id`, `case_id`. Worker lo phần on-chain dưới lease sẵn có (`blockchain_sender_leases`); sự cố `proof:{id}` (`OperationalIncidentService.observe`, mở bởi dòng 194) **giữ mở** cho tới khi worker xác nhận thành công (worker tự đóng). Ghi audit log (dùng cùng cơ chế audit của `CONTRACT_DOCUMENT_INTEGRITY`, giả định) gồm admin id, documentId, mã lỗi cũ. Idempotent: gọi lại khi đã `PENDING` → 409. Giao diện: hộp xác nhận nêu rõ "sẽ gửi giao dịch mới, tốn Sepolia ETH của ví hệ thống". |

## 5. Biểu đồ
Không có biểu đồ (trang bảng/đối soát). Bốn thẻ đếm là phần tử số + màu trạng thái kèm chữ (không chỉ màu): xám=PENDING, xanh dương=SUBMITTED, xanh lá=CONFIRMED, đỏ=FAILED, dùng `Badge` có sẵn (`components/ui/badge.tsx`), bảng dùng `components/ui/table.tsx`. Drawer: `package.json` có `radix-ui` nhưng `components/ui/` chưa có `sheet/dialog` — dùng `Dialog` của `radix-ui` tạo kiểu panel phải (không thêm phụ thuộc). Dịch vụ frontend mới `src/services/admin-blockchain.service.ts` theo mẫu `admin-complaint.service.ts` (`axiosClient`, đường dẫn `/api/v1/admin/blockchain/...`).

## 6. Hiệu năng & bảo mật
- Chi phí ở 100k thanh toán / vài chục nghìn proof: `summary` là một `GROUP BY status` trên bảng nhỏ (cả đời hợp đồng, mỗi PDF cuối một hàng); `proofs` phân trang theo `(status, created_at desc)` + join `contracts` bằng khoá chính → O(size). Không N+1 (một câu join, không `getContract` từng dòng). Tìm theo số HĐ dùng index unique `idx_contract_number`.
- **Không bao giờ trả**: `signed_raw_transaction`, `nonce`, `signer_address` (nội bộ; đúng nhận xét "không đưa private key, nonce hay raw transaction ra API", `BlockchainProofResponse.java:5`), `storage_object_id`, `revision_id` (không cần), nội dung/bytes PDF, tên/CCCD/email/ID các bên (`landlord_id`, `tenant_id`), `payer_wallet`, `signature`. Chỉ `contract_number` làm định danh hợp đồng. Thông báo lỗi lấy từ `error_code` ánh xạ sang mô tả cố định phía server; không trả `error_message` thô nếu mã không thuộc danh sách đã biết (tránh rò chi tiết RPC/URL).
- Không có URL RPC/khoá ở bất kỳ phản hồi nào; `explorerBaseUrl` từ bảng cố định phía backend (không nhận từ DB hay client) để chống link độc; mọi liên kết ngoài `target="_blank" rel="noopener noreferrer"`. Kiểm `^0x[0-9a-fA-F]{64}$` (tx/hash) và `^0x[0-9a-fA-F]{40}$` (địa chỉ) trước khi nối vào URL.
- Xác minh on-chain tốn RPC → giới hạn tần suất; mọi hành động đọc/kiểm tra ghi log mức INFO không kèm hash đầy đủ nếu cần (không bắt buộc, hash là công khai).
- `retry` là DEEP (tiền Sepolia, trạng thái hợp đồng): theo `AGENTS.md` phải có kiểm thử đường từ chối và đua luồng (hai admin bấm cùng lúc), không làm ở giai đoạn 1.

## 7. Kiểm thử cần viết
Backend (`AdminBlockchainControllerTest`, mẫu `ContractBlockchainProofControllerTest`; query test theo `ContractDocumentProofPostgresTest`):
1. 401 chưa đăng nhập; 403 USER; 403 ADMIN thiếu `BLOCKCHAIN_VIEW` cho mọi endpoint GET/POST; `integrity` 403 khi chỉ có `BLOCKCHAIN_VIEW` mà thiếu `CONTRACT_VIEW`.
2. `proofs`: lọc `status`, lọc `contractNumber`, phân trang, `size=500` bị chặn còn 50; response JSON **không chứa** khoá `signedRawTransaction`, `nonce`, `signerAddress`, `landlordId`, `tenantId` (so khớp danh sách khoá cho phép).
3. `summary`: không có hàng → mọi `counts` bằng 0; không lộ `rpcUrl/privateKey`; `explorerBaseUrl` đúng với chain 11155111 và rỗng với chain lạ.
4. `verify`: on-chain khớp → `matches=true`; hash on-chain khác hash DB → `matches=false` + cờ xung đột; `RegistryClient` ném `RuntimeException` → `UNAVAILABLE`, thông điệp không chứa URL; `documentId` không tồn tại → 404; không nhận hash từ client.
5. Tab ETH: chỉ hàng có `transaction_hash`; không có `payer_wallet` trong JSON; `unsettled=true` chỉ trả hàng có tx hash mà đơn `PENDING/FAILED/EXPIRED`, không trả đơn `PAID`; `summary.ethUnsettled` khớp.
5b. `summary` mới: `integrity` đếm đúng `MISMATCH/MISSING` và có `note`; `byErrorCode` mã lạ gộp `OTHER`, không có `error_message`; `openIncidents` chỉ đếm sự cố `BLOCKCHAIN` mở, JSON không có `sourceKey`; `senderLeaseHeldUntil` null khi không có lease; ADMIN thiếu `BLOCKCHAIN_VIEW` vẫn 403 dù có `SYSTEM_HEALTH_VIEW` (P4 chỉ lấy số tổng, không qua endpoint này).
6. (Giai đoạn 2) `retry`: chỉ `FAILED` + `TRANSACTION_REVERTED` thành công; `SOURCE_PDF_CHANGED`/`DOCUMENT_HASH_CONFLICT` → 409; thiếu `BLOCKCHAIN_MANAGE` → 403; gọi hai lần song song chỉ một lần thành công (khoá `findByIdForUpdate`); sau retry `nonce`, `signed_raw_transaction` là null và `document_hash` không đổi; sự cố vẫn mở.
Frontend (`node --test`): `explorerLink(chainId, kind, value)` — chain 11155111 đúng URL, chain lạ trả `null`, giá trị không hợp lệ (có `/`, `?`, thiếu `0x`) trả `null`; `shortHash`; `statusBadge` có nhãn chữ; bảng không render khi thiếu `BLOCKCHAIN_VIEW`; nút "Thử lại" ẩn khi thiếu `BLOCKCHAIN_MANAGE` và khi `errorCode` khác `TRANSACTION_REVERTED`.

## 8. Câu hỏi mở
1. Có cho thử lại proof FAILED không? Mặc định: **giai đoạn 1 chỉ đọc**, không có nút; thêm `retry` (giai đoạn 2) chỉ cho `TRANSACTION_REVERTED` như §4 sau khi chủ dự án duyệt.
2. Quyền riêng `BLOCKCHAIN_VIEW`/`BLOCKCHAIN_MANAGE` hay dùng lại `CONTRACT_VIEW`? Mặc định: quyền riêng (tab ETH là dữ liệu tài chính, không thuộc quyền xem hợp đồng).
3. Ngưỡng "kẹt": 15 phút cho PENDING/SUBMITTED? Mặc định 15 phút, cấu hình `homespace.blockchain.stuck-minutes` (theo quy ước env: `${VAR}` trong properties, không default trong compose, đủ mọi file env).
4. Mạng của thanh toán ETH hội viên: hiện không có `chain-id` trong `homespace.eth-payment.*`. Mặc định: coi là Sepolia 11155111 và hiển thị nhãn "Sepolia"; thêm cấu hình `homespace.eth-payment.chain-id` khi thật sự đổi mạng.
5. Có cần xuất danh sách proof ra CSV để đối soát? Mặc định: không ở giai đoạn 1 (tránh thêm bề mặt dữ liệu); nếu cần, xuất ở client từ trang đang xem với cùng cột bị giới hạn.
6. `integrity` trong `summary` có thể cũ; có làm job kiểm định kỳ (giới hạn tốc độ, tốn S3 + RPC) không? Mặc định: không ở giai đoạn 1; chỉ ghi "có thể cũ".
7. Sự cố `BLOCKCHAIN` đang mở cho từng `proof:{id}`: có hiện danh sách ở P6 không? Mặc định: chỉ đếm; danh sách proof FAILED/kẹt nằm ở bảng (lọc theo trạng thái), `sourceKey` không bao giờ ra giao diện.

## 9. Không đưa vào (lý do)
| Tín hiệu (inventory) | Lý do |
|---|---|
| Hạn mức/độ trễ RPC Ethereum, lỗi RPC theo thời gian | Không có metric (chưa có registry Prometheus); chỉ có `byErrorCode` của proof hiện tại. Sức khoẻ cổng ngoài ở P4 §11. |
| Tự động kiểm toàn vẹn định kỳ mọi tài liệu | Tốn S3 + RPC; giai đoạn 2 (Câu hỏi 6). `integrity` hiện chỉ phản ánh lần kiểm gần nhất. |
| Thử lại proof FAILED | Giai đoạn 2, chỉ `TRANSACTION_REVERTED` (§4); bốn mã còn lại cấm thử lại bằng nút. |
| IPN SePay, giá ETH/VND, ví hệ thống hết Sepolia ETH | Không có dữ liệu ghi; ví hết ETH chỉ lộ qua proof kẹt/`FAILED`. |

## 10. API implemented (phase 2, 2026-11-28)

This section is the exact backend contract for frontend integration. Retry remains deferred;
no retry endpoint or blockchain/contract/payment state transition was added. The five P4
operations fixes, frontend/chart work, mobile and Excel are outside this change.

All six endpoints support both `/admin/blockchain` and `/api/v1/admin/blockchain` prefixes.
Every endpoint requires a verified Gateway user context, `ADMIN` role and `BLOCKCHAIN_VIEW`.
Integrity additionally requires `CONTRACT_VIEW`. Checks exist in method security and the service;
an authority naming ADMIN cannot promote a USER role. No permission catalog edit is included
here: P4 owns `Permissions.java` and activation of `BLOCKCHAIN_VIEW` in the shared catalog.
All successful responses set `Cache-Control: private, no-store`. Missing identity is 401,
insufficient permission is 403, invalid input is 400, and missing proof is 404 (code 6011).

| Method/path after prefix | Request DTO/validation | Response DTO/envelope |
|---|---|---|
| `GET /summary` | No parameters | `ApiResponse<AdminBlockchainResponse.Summary>` |
| `GET /proofs` | `AdminBlockchainController.ProofQuery`: optional `status=PENDING/SUBMITTED/CONFIRMED/FAILED`, `contractNumber` literal prefix (1–64 chars, no controls), `from`/`to` ISO instants, `page=1`, `size=20`; size clamped to 50 | `PageResponse<AdminBlockchainResponse.ProofRow>` |
| `GET /proofs/{documentId}` | No parameters/body | `ApiResponse<AdminBlockchainResponse.ProofDetail>` |
| `POST /proofs/{documentId}/verify` | No parameters/body; client hash input is rejected | `ApiResponse<AdminBlockchainResponse.Verification>` |
| `POST /proofs/{documentId}/integrity` | No parameters/body; both view permissions | `ApiResponse<DocumentIntegrityResponse>` |
| `GET /membership-eth-payments` | Optional `unsettled=true/false` (default false), `page=1`, `size=20`; size clamped to 50 | `PageResponse<AdminEthPaymentQueries.Payment>` |

Unknown/repeated query keys are rejected. Page/size must be positive decimal integers (up to
7 digits). Proof dates use the half-open interval `[from,to)` and require `from < to` when both
exist. Prefix `%`, `_` and `!` are literal, never SQL wildcards. Proof ordering is
`created_at DESC, document_id DESC`; ETH ordering is `membership_orders.created_at DESC,
payment_id DESC`, so ties paginate consistently. `PageResponse` puts the row array directly in
`result`, with root `page`, `size`, `totalElements`, `totalPages`, `hasMore` (1-based page).

Response field inventory:

- `Summary`: `asOf`, `network`, `counts`, `stuck`, `oldestOpenAgeSeconds`, `retrying`,
  `byErrorCode`, `openIncidents`, `senderLeaseHeldUntil`, `integrity`, `ethUnsettled`,
  `workerLastRunAgeSeconds`, `sourceCapabilities`.
- `network`: `chainId`, `name` (Sepolia or UNKNOWN), `registryAddress`, `enabled`,
  `explorerBaseUrl`. Only chain 11155111 gets `https://sepolia.etherscan.io`; every other
  chain gets null. Malformed addresses/hashes become null. No RPC URL/private key is exposed.
- `counts`: PENDING/SUBMITTED/CONFIRMED/FAILED, including zero buckets on an empty database.
  `stuck` is PENDING/SUBMITTED with `created_at < asOf - 15 minutes` (strict boundary);
  `retrying` counts nonterminal rows with `attempts > 0`. Empty open queue age is null.
- `byErrorCode`: all currently stored non-null error-code counts, allowlisted and grouped into
  `OTHER` for unknown codes in SQL; no raw messages. It describes current proof state, not
  historical failures. `openIncidents` counts only open BLOCKCHAIN incidents. Lease expiry
  comes only from ID `contract-document-proof-sender`, null if absent; an expired recorded
  expiry stays visible, without returning token/lease-owner details.
- `integrity`: `{counts, note}`. Fixed buckets MATCH/MISMATCH/MISSING/UNAVAILABLE/PENDING/
  NOT_AVAILABLE/UNCHECKED; null legacy status maps to UNCHECKED, unknown values to OTHER.
  These count `contract_documents` and reflect only the latest explicit check, which can be
  stale; null history never becomes a healthy zero check. There is no periodic integrity job.
- `sourceCapabilities`: `workerLastRun=UNAVAILABLE_NO_PERSISTED_JOB_RUNS`,
  `integrity=LATEST_EXPLICIT_CHECK_ONLY`, `ethChain=ASSUMED_SEPOLIA_NO_STORED_CHAIN_ID`.
  `workerLastRunAgeSeconds` is null because job instrumentation is deferred.
- `ProofRow`: `documentId`, `contractNumber`, `documentKind`, `status`, `transactionHash`,
  `blockNumber`, `createdAt`, `confirmedAt`, `errorCode`.
- `ProofDetail`: row fields plus `documentHash`, `onChainDocumentId`, `chainId`,
  `registryAddress`, `blockHash`, `registeredAt`, `caseId`, `attempts`, `submittedAt`.
  It never loads a proof entity with a raw signed transaction and never creates/enqueues a
  missing proof. Existing party endpoints remain unchanged.
- `Verification`: `documentId`, `status=AVAILABLE/UNAVAILABLE`, nullable `registered`,
  `matches`, `conflict`, `onChainHash`, `registeredAt`. A registered different hash gives
  `matches=false, conflict=true`; a zero on-chain hash gives `registered=false, conflict=false`.
  RPC failure, unsupported chain or malformed provider data gives UNAVAILABLE with null
  result fields, never a false mismatch or raw exception. Input hash comes only from DB.
- `DocumentIntegrityResponse`: `documentId`, `status`, `documentHash`, `onChainDocumentHash`,
  `checkedAt`, `message`; it returns no PDF bytes. `checkForAdmin` reuses pinned-version
  inspection and only updates the existing integrity result fields. Signed SHA-256, S3
  version, proof hashes and lifecycle states remain immutable.
- `Payment`: `orderId`, `tier`, `kind`, `amountVnd` (exact decimal from quote), `amountWei`
  (integer string), `transactionHash`, `confirmedAt`, `status`, `orderCreatedAt`, `ageSeconds`,
  `chainId=11155111`, `chainSource=ASSUMED_SEPOLIA_NO_STORED_CHAIN_ID`. Includes only quotes
  with a non-null transaction hash joined to provider ETH orders. Unsettled means status is
  anything except PAID, including legacy REVIEW and CANCELLED. Age uses quote confirmed_at
  or order created_at because quotes have no created_at column. Neither age nor presence of
  a transaction hash independently proves on-chain payment finality. Wallets/signatures/
  owner identifiers never enter the projection.

Operations use real `Instant.now()`; no contract/payment deadline is recalculated, so BillingTime
does not alter queue ages or ETH reconciliation ages. Summary/list paths make no RPC or S3
calls. Explicit verify uses the existing bounded RegistryClient; explicit integrity uses the
existing pinned PDF inspection (50 MiB maximum). Both actions share a rolling quota of 10
checks/minute/admin, HTTP 429/code 4290 when exceeded. The bounded in-memory limiter is per
instance; shared quota across replicas requires a shared limiter later. Existing RPC timeout
configuration remains unchanged. No new environment variables or `.env` reads were added.

SQL lives in contract and payment modules respectively; result sets are scalar aggregates or
bounded projections, with no per-row calls. New idempotent index migration creates
`idx_proof_status_created(status,created_at DESC,document_id DESC)` and
`idx_proof_created(created_at DESC,document_id DESC)` on the existing proof table. Normal
ApplicationReady migration follows repository conventions; deployments that apply DDL
separately should create these before serving large datasets. The initial index creation can
briefly lock writes and should run in the deployment maintenance window.

### Notification delivery health (exclusive companion change)

Implemented separately in `hs-notification-service`: `GET /admin/delivery-health`, through
Gateway `GET /api/v1/notifications/admin/delivery-health`, no input DTO/body. Requires
GatewayAuthenticationGuard (valid internal secret), AdminGuard and `SYSTEM_HEALTH_VIEW`
parsed from the verified context. Existing authentication guards are unchanged. Response
`ApiResponseDto<DeliveryHealthResponse>` has `asOf`, `windowStart`, `email`, `push`, `kafka`,
`loops`, `notes`; full fields and source semantics are documented in that repository's
`docs/ADMIN_DELIVERY_HEALTH.md`.

Email/push aggregates expose configured enabled flags, current pending/processing, current
failed/skipped/sent/accepted rows updated in `[asOf-24h,asOf)`, retry-due counts, oldest pending
age and a bounded safe error-code map. These are current-state counters, not transition history.
Each channel runs one projected Mongo aggregate with a 2.5-second server limit and 3-second
source deadline, cached/coalesced for 15 seconds. Errors produce null counts/source unavailable,
independently per channel. No recipients/payloads/tokens/provider-message IDs are returned.
Kafka lag/DLT counters, loop heartbeat timestamps and device deactivation history are unavailable
and explicitly null; no deferred instrumentation, live broker/provider query or invented zero/OK
was added. Gateway was inspected read-only: it strips client identity headers and forwards
verified authorities/internal secret on notification routes, and already restricts admin paths.
Deployed Gateway forwarding and real Mongo pipeline integration still need consumer/environment
validation. Notification local commit: `009928627233ca25dde871c58394040b1d3e4090`
(`Add permission-scoped admin delivery health aggregates`); no push.

### Validation actually run

- `mvnw.cmd -Pisolated-build -pl hs-api-service -am -DskipTests compile`: BUILD SUCCESS;
  all upstream modules and downstream API assembly compiled.
- With `HS_P6_TEST_PG_URL=jdbc:postgresql://127.0.0.1:51938/hs_p6_test`,
  `mvnw.cmd -Pisolated-build -pl hs-api-service -am "-Dtest=AdminBlockchain*Test,ContractBlockchainProofControllerTest,ContractDocumentIntegrityControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`:
  BUILD SUCCESS, 26 tests, zero failures/errors/skips (contract 13, API 13).
- `AdminBlockchainPostgresTest`: all six tests executed on disposable PostgreSQL 16,
  container `hs-p6-pg-1837b32d`, random loopback port 51938. Hibernate generated the actual
  entity schema with constraints, not an approximate hand-built schema. The worker verified
  the container ID/task label and removed that container and its own volume after testing.
  Tests cover empty
  counts/null age, strict stuck boundary, retries excluding terminal rows, safe unknown codes,
  literal prefix escaping/injection, half-open time range, deterministic tied pagination,
  legacy unchecked documents, incident scope, exact sender lease, paid/unpaid/cancelled ETH
  and no-tx/non-ETH exclusions, exact decimal/wei, totals before pagination, idempotent indexes.
- `ContractDocumentIntegrityServiceTest`: 17 passed in the earlier focused run, unchanged
  afterward, including new admin permission/baseline immutability coverage. That earlier run
  failed two new fixture tests (invalid GOLD tier and Mockito re-stubbing an exception); both
  fixtures were corrected and their affected suites passed in the final command above.
- HTTP tests use real UserContextFilter and method-security proxy, including absent/forged
  identity, USER carrying ADMIN in permission headers, narrow ADMIN, integrity without
  CONTRACT_VIEW, authorized routes, whitelisted list JSON, size cap, invalid/repeated inputs,
  rejected client hashes, 404 and unchanged party-controller envelopes.
- Notification initial focused Jest run (delivery health plus existing gateway/admin guard
  suites): 22 passed. After adding source timeout coverage, final endpoint suite:
  `npm test -- --runInBand src/modules/notification-delivery/delivery-health.spec.ts`: 11 passed;
  `npx tsc --noEmit --incremental false` and `npm run build`: passed on final source.

No `.env` files, existing databases/services or live providers were accessed. Tests do not
certify real Sepolia/S3/Firebase/email delivery, actual Mongo pipeline execution or deployed
Gateway/consumer integration. No full repository suite, frontend/mobile or Excel check ran.
