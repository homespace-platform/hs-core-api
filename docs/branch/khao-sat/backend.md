# Khảo sát backend: giám sát chi nhánh + sơ đồ phòng

Ngày 2026-11-26. Chỉ đọc code; **chưa chạy test / service nào**. Dòng được tìm trên mốc hiện tại.
Ký hiệu: **[XM]** = XÁC MINH (thấy trong code) · **[SĐ]** = SUY ĐOÁN.
Viết tắt: `L/` = `hs-listing-service/src/main/java/com/hs/listing/`, `C/` = `hs-contract-service/src/main/java/com/hs/contract/`, `P/` = `hs-payment-service/src/main/java/com/hs/payment/`.
Tên file: CSI = `C/service/impl/ContractServiceImpl`, CLS = `C/service/ContractLifecycleService`, MBS = `C/service/MonthlyBillingService`, CAS = `C/service/ContractAmendmentService`, PRS = `L/service/ParkingReservationService`.

## 0. Ba phát hiện ảnh hưởng thiết kế

1. **[XM] HĐ thuê chưa tồn tại trước ngày vào ở**: chặn `today < moveInDate` (CSI:235). "Giữ chỗ" / "Sắp vào" phải lấy từ `RentalRequest ACCEPTED` (+ HĐ cọc), không lấy từ HĐ thuê. Liên kết phòng chỉ đặt trên `Contract` thì không phủ được giai đoạn này.
2. **[XM] Chưa có bản nháp kỳ điện nước trước ngày ghi**: nháp chỉ sinh khi `today >= periodEndExclusive-1` (MBS:1037). "Báo trước 3 ngày" phải tính từ `moveInDate + n tháng`.
3. **[XM, đọc code, chưa tái hiện] Có lỗi chỗ xe** do `contracts.branch_id` null (mục 2.4).

## 1. Chi nhánh hiện có

| Mục | Nội dung [XM] | Bằng chứng |
|---|---|---|
| `PropertyBranch` | ownerId, name, code, category, address 1-1, totalUnits, sức chứa xe, cover, `active`; index `owner_id`, `(owner_id,active)`. `code` không có unique ở entity | L/model/PropertyBranch:16-87 |
| `BranchCharge` | phí mặc định, N-1 branch; update = xóa hết rồi gắn lại | L/model/BranchCharge; PropertyBranchService:116-179 |
| Liên kết tin | `Listing.branchId` (cột `branch_id`, nullable) + quan hệ chỉ đọc; không có index khai báo | L/model/Listing:15-35 |
| Gán/gỡ | Lưu tin: kiểm branch active + đúng owner; request không có branchId thì **set null** | L/service/ListingService:323-329 |
| API | `/branches` POST, GET, GET/{id}, PUT/{id}, DELETE/{id}; ghi cần `LISTING_POST`; GET không có `@PreAuthorize`, lọc theo owner. GET list không phân trang | hs-api-service `PropertyBranchController`:21-70; PropertyBranchService:102-113 |
| Lọc tin theo CN | `GET` tin của tôi có `branchId` | ListingController:57; ListingQueryService:60-61 |
| Xóa | Chặn nếu còn tin `active=true` (mọi status, kể cả DRAFT/EXPIRED); xóa mềm `active=false`. Không xét HĐ | PropertyBranchService:182-192 |
| Số phòng | `count` = số tin active của CN; `totalUnits = count>0 ? count : totalUnits lưu` (tạo mới = 0). `activeListingsCount = count`. **Đếm tin, không đếm phòng vật lý** | PropertyBranchService:266-267; ListingRepository:27-28 |

## 2. Hợp đồng

### 2.1 Mọi nơi tạo `Contract` (grep `Contract.builder()`/`new Contract` trong `src/main`: 5 nơi) [XM]

| Loại | Nơi | parentContractId | agreementKind | branch_id lúc tạo |
|---|---|---|---|---|
| **HĐ thuê gốc** (tay `createContract` và tự động `createDraftAutomatically` CSI:193) | CSI:266-278 | null | null | **không set** |
| HĐ cọc | `C/deposit/DepositAgreementService`:200 | **null** | `DEPOSIT` | không set |
| Phụ lục | CAS:252-257 | gốc | `AMENDMENT` | `targetBranch(c,terms)` |
| Gia hạn | CLS:101-106 | gốc | null | copy gốc |
| Văn bản workflow (chấm dứt sớm) | `C/service/WorkflowDocumentService`:50 | gốc | `KIND` | copy gốc |

- **[XM] HĐ gốc dùng thu tiền**: `billable()` = không phải cọc, `parentContractId == null`, status `ACTIVE` hoặc `EXPIRED` (MBS:1462). Tra theo hồ sơ thuê: `ContractRepository.findByRentalRequestId` (đã loại con + cọc), trả tối đa 1.
- **[XM] Con → gốc**: `parentContractId` (1 cấp). **HĐ cọc cũng có parent null**: lọc `parent IS NULL` thôi là sai, phải thêm `agreementKind IS NULL OR <> 'DEPOSIT'` (mẫu `ContractAgreementSelection.lease`). Cọc nối gốc qua `rentalRequestId` / `DepositAgreement`.
- **[XM] Gia hạn không thành HĐ gốc mới**: ký xong chỉ tăng `parent.effectiveLeaseMonths` (CLS:135-156).

### 2.2 Chỗ tự gắn `management_branch_id`
Chỉ một chỗ: builder CSI:266 (cả hai đường tạo đều qua đây; HĐ đã có thì return sớm ở CSI:220 nên không gắn lại sau khi chủ nhà gỡ). [SĐ] Lấy `listing.getBranchId()` đang live, chỉ gán khi CN còn active và `branch.ownerId == contract.landlordId`; không gán cho cọc/con (đọc qua gốc). Do phát hiện 0.1, nên cân nhắc lưu liên kết phòng theo `rentalRequestId` (câu hỏi 1). Nhớ `WorkflowDocumentService`:55 dùng `BeanUtils.copyProperties` cho **revision**, không copy field của `Contract`.

### 2.3 `contracts.branch_id`: đúng là **không set khi tạo gốc**, nhưng có thể được set sau [XM]

| Loại | Vị trí | Việc |
|---|---|---|
| Khai báo + index | C/model/Contract:20, 42-43 | |
| **Ghi** | ContractListingSync:332-336 | lưu nháp **có đồng bộ tin** (`listingSourceVersion != null`, CSI:593): gán = branch của tin |
| **Ghi** | CAS:254 | child phụ lục = `targetBranch` |
| **Ghi** | CAS:364-368 | phụ lục đến hiệu lực: **gốc bị ghi đè** bằng branch của child |
| **Ghi** | CLS:102, WorkflowDocumentService:50 | copy sang child |
| Đọc | ContractDraftResidents:77 | pool chỗ xe nháp |
| Đọc | ContractListingSync:189, 332 | so sánh với tin (gốc null + tin có CN thì luôn báo "khác") |
| Đọc | CAS:274, 320, 548, 551-557 | `targetBranch`, `parkingChanged` |
| Đọc | CAS:301 | `validateSigning` phụ lục |
| Đọc | `C/service/AmendmentTermsBuilder`:37-47 | snapshot `listingBranchId` từ `revision.contract.branchId` |

Không thấy đọc/ghi trong `hs-api-service`, `hs-payment-service`. Kết luận của concept (không tái dùng cột này) là đúng.

### 2.4 Lỗi chỗ xe: **có thật theo code** [XM đường đi; chưa chạy tái hiện]

**Đường A: sửa người/xe trên nháp**
1. Tin thuộc CN X. Chủ nhà nhận yêu cầu → hold `HELD` có `branchId = X` (PRS:158-161).
2. Tạo HĐ nháp → `contract.branchId = null` (CSI:266).
3. `updateRevision` sửa `occupantCount`/`motorbikeCount`/`carCount`, request **không** có `listingSourceVersion` (CSI:576-600).
4. `ContractDraftResidents` gọi `replaceDraftHoldInBranch(rental, bikes, cars, null)` (ContractDraftResidents:77). Chỉ đổi **số người** cũng tới đây (chỉ return sớm khi cả 3 số không đổi, dòng 34-35).
5. Trong PRS:341-385, `branch=null`:
   - sức chứa = `listing.maxMotorbikeCount/maxCarCount` (dòng 360); nếu null → 0 → **báo hết chỗ sai** khi có xe;
   - chỉ tính reservation `branchId == null` (dòng 363) → bỏ qua toàn bộ pool CN;
   - hold cũ (X) thành `RELEASED`, hold mới ghi `branchId = null` → **pool CN X mất lượt này → có thể bán vượt chỗ**; kích hoạt HĐ giữ nguyên null.

**Đường B: phụ lục đổi xe/thời hạn, không kèm đồng bộ tin** (HĐ ACTIVE, `branch_id` null)
`targetBranch = null` → `validateAmendmentCapacity` **return ngay, không kiểm sức chứa** (PRS:405) → `holdInPool` chỉ coi reservation `branchId==null` là đã giữ (PRS:456) nên giữ lại toàn bộ số xe ở pool null → `activateInPool` cắt/nhả reservation ACTIVE của CN X và tạo ACTIVE `branchId=null`.

Che lỗi: nếu trước đó đã lưu nháp có đồng bộ tin thì `branch_id` đã được gán. [SĐ] Web có luôn gửi `listingSourceVersion` hay không: cần khảo sát web. `ContractDraftResidentsTest` tồn tại nhưng grep không thấy set `branchId` trong đó. Đây là lỗi **ngoài phạm vi** tính năng; sửa riêng, không dùng `management_branch_id` để vá.

## 3. Vòng đời tin liên quan phòng [XM]

| Mục | Nội dung | Bằng chứng |
|---|---|---|
| Status | DRAFT, PENDING_REVIEW, PUBLISHED, RESERVED, RENTED, RENTED_EXTERNALLY, EXPIRED, REJECTED, HIDDEN, VIOLATION | L/model/constant/ListingStatus |
| Nhân bản | `duplicateSourceListingId` + không có id → **row mới**, id mới; branchId theo request. Không lưu quan hệ cha-con trên row mới (chỉ dùng kiểm nguồn/media) | ListingService:65-127, 641+ |
| Sửa tin đang PUBLISHED/RESERVED/… (`CONTENT_REVIEW`) | **không tạo row bản sao**: lưu `pendingEditId/Payload` trên cùng row; status = PENDING_REVIEW, trạng thái thật ở `getOperationalStatus()`. Duyệt thì áp lên cùng id (**branchId có thể đổi**) | ListingService:83-93, 246-270; Listing:51-56 |
| Cho thuê | PUBLISHED → RESERVED (chấp nhận yêu cầu) → RENTED (ký xong) | ListingStatusService:210, 229 |
| Sau HĐ | Cùng row quay lại PUBLISHED (cửa sổ đăng mới) khi: 2 bên chốt bàn giao quyết toán (CLS:263) hoặc chấm dứt cưỡng bức (MBS `finalizeTermination` ~748). HĐ chỉ hết hạn (EXPIRED) chưa quyết toán thì tin **chưa** được nhả | ListingStatusService:241 |
| Hết hạn đăng | job đặt EXPIRED (giờ thật) | ListingStatusService:165; ListingExpirationScheduler:18 |
| Xóa mềm | có cờ `active`; grep không thấy luồng nào `setActive(false)` cho Listing, không thấy `listings.delete` | |

- **Một phòng vật lý có thể có nhiều row tin** [XM: không có ràng buộc nào ngăn; nhân bản hoặc đăng lại tạo row mới]. 
- [SĐ] Phòng nên là entity riêng, có quan hệ **1 phòng – N tin** (lưu `roomId` ở bảng liên kết, listing_id unique), "tin hiện tại" suy ra theo `operationalStatus`. Không để phòng giữ đúng 1 `listingId`; không dùng `status` thô (dùng `getOperationalStatus()`).
- Không dùng `hasOccupiedRental` (ListingRepository:45-55) làm "đang ở": nó gộp cả yêu cầu ACCEPTED và mọi HĐ chưa CANCELLED/CLOSED chưa `accepted_at` (gồm cả DRAFT, TERMINATED không có closure).

## 4. Trạng thái và ngày thuê [XM]

- Status HĐ: DRAFT, PENDING_REVIEW, LANDLORD_SIGNATURE_PENDING, TENANT_SIGNATURE_PENDING, ACTIVE, EXPIRED, CLOSED, TERMINATED, CANCELLED.
- Bắt đầu = `RentalRequest.moveInDate` (HĐ không có cột ngày).
- Kết thúc (exclusive) = `agreedEarlyEndExclusive` ?? `moveInDate + (agreedEarlyBillingMonths ?? effectiveLeaseMonths ?? leaseMonths)` tháng (CLS:575-578; `ContractLeaseCalc`). `AiContractQueryService.summarize` (dòng 72+) và query `findLeaseRowsForUser` đã tính phase/sắp hết hạn: nên tái dùng cách tính.

| Cần xác định | Quy tắc suy ra [SĐ trên dữ kiện XM] |
|---|---|
| Đang thuê | gốc ACTIVE, `start <= today < end` |
| Hết hạn, chưa trả phòng | gốc EXPIRED (CLS:432-434 tạo closure `WAITING_HANDOVER`); vẫn billable; tin vẫn RENTED. **Chưa trống** |
| Đã quyết toán/trả | CLOSED hoặc TERMINATED. Nhưng chốt closure → tin được nhả **ngay cả khi tiền hoàn còn nợ** (`REFUND_PENDING`, CLS:245-263): phòng trống, HĐ vẫn EXPIRED. Xét `closure.acceptedAt` |
| Giữ chỗ / sắp vào | `RentalRequest ACCEPTED`, chưa có gốc ACTIVE; sắp vào nếu `moveInDate > today` |
| Đang ký | gốc ở DRAFT…TENANT_SIGNATURE_PENDING (đã tới ngày vào) |
| Chấm dứt sớm | thỏa thuận 2 bên: `mutualTerminationWorkflowId`, ký xong set `agreedEarlyEndExclusive` (MutualTerminationService, case `APPLY_SIGNED`), status vẫn ACTIVE. Đơn phương/quá hạn: → TERMINATED (MBS:353, 769) |
| Gia hạn | `ContractRenewal` REQUESTED → APPROVED → APPLIED / REJECTED / EXPIRED; chỉ APPLIED mới đổi ngày kết thúc. Mở 30 ngày trước hết hạn, hạn ký = đầu ngày thuê cuối |
| Chồng lịch | Luồng tin chỉ cho 1 lượt RESERVED/RENTED mỗi **row tin**. Qua nhiều row tin cùng phòng thì **không có gì ngăn trùng**: ràng buộc "không trùng thời gian" phải kiểm khi gắn phòng, và với auto-gắn phải có lối "vào khay" thay vì fail tạo HĐ |

## 5. Nguồn dữ liệu cho "Cần chú ý" và lớp màu

Đồng hồ: **B** = `BillingTime` (giả lập được ở dev/test; `common/time/BillingTime`), **R** = giờ thật. Payment: B nếu `PaymentTimePolicy.usesBillingTime` (INITIAL, RESERVATION_DEPOSIT, INITIAL_PAYMENT_REFUND, MONTHLY_RENT, DEPOSIT_REFUND, DEPOSIT_ADJUSTMENT, và OTHER_CHARGE có contractId), ngược lại R. "Thiếu query" = chưa có query theo owner/lô; đa số repo chỉ tra theo 1 id.

### 5.1 Điện nước, tiền (đều [XM] trừ chỗ ghi [SĐ])

| Việc | Nguồn / điều kiện | Ai làm | Hạn / đồng hồ | Query |
|---|---|---|---|---|
| Sắp ghi (trước 3 ngày) | **Không có row.** [SĐ] tính ngày cuối kỳ = `moveIn+(n+1) tháng -1`; chỉ HĐ có phí `PER_KWH`/`PER_M3` không gồm trong giá (charges của revision hiệu lực, MBS:1203-1219) | chủ nhà | B | thiếu |
| Phải ghi hôm nay | invoice DRAFT, `today = periodEndExclusive-1`; `workflowState=METER_REQUIRED` (MBS:1584-1595). Đã `prepare` = `READY_FOR_ISSUE` (`draftExtraChargesSnapshot != null`) | chủ nhà | `meterDeadline` = 00:00 `periodEndExclusive` (MBS:1344). `prepare` bị chặn sau hạn (MBS:797-799); `issue` tay không chặn trên (MBS:821-845) | thiếu |
| Tự điền chỉ số | `electricityAutoFilled`/`waterAutoFilled` (MBS:1348-1360): điền **số cuối = số đầu**. Không có cờ "đã xem" nên việc này không tự biến mất | chủ nhà (xem lại) | không hạn / B | thiếu |
| Quá ngày vẫn nháp | DRAFT và `now >= meterDeadline` (`METER_DEADLINE_MISSED`). Xảy ra khi `autoReady=false`: kỳ trước còn nháp, `STATE_WATER_RATE`, thiếu số (MBS:1176-1219). Có thể đã có cờ autoFilled | chủ nhà | B | thiếu |
| Lớp Ghi chỉ số "đã ghi" | invoice đã phát hành, hoặc DRAFT có `draftExtraChargesSnapshot` | | | |
| Chờ xác nhận (tím) | `PaymentRequest.status=TRANSFER_REPORTED`, payee = chủ nhà; invoice vẫn UNPAID/OVERDUE | bên nhận | `confirmationDueAt` (= báo + 24h, P/service/PaymentRequestService:533). Tự xác nhận chỉ khi đã gửi cảnh báo trước và không bị khiếu nại chặn (P/service/PaymentRequestService:640-663): đếm ngược là **sớm nhất** | có `findByInvoiceId`; thiếu theo lô |
| Chờ xác nhận tiền đầu kỳ/cọc | payment INITIAL / RESERVATION_DEPOSIT (không có MonthlyInvoice) | chủ nhà | như trên / B | thiếu theo owner |
| Quá hạn (đỏ) | invoice OVERDUE (job set, MBS:~1126-1135) hoặc UNPAID có `dueAt<now`; `OVERDUE_ACTION_REQUIRED` sau 5 ngày; tự cộng dồn sau 10 ngày | người thuê trả; chủ nhà chọn phương án | `dueAt`, `MonthlyBillingSchedule` / B | thiếu |
| Xin gia hạn | **JSON** `extensionRequestsSnapshot`, status PENDING (MBS:375). Không lọc SQL được | chủ nhà (MBS:96) | `respondBy` trong JSON (+48h) / B | phải nạp invoice rồi parse |
| Đang gia hạn (cam) | `resolutionMode='PAYMENT_EXTENSION'` và `now <= dueAt` (MBS:383) | người thuê | `dueAt` / B | |
| Yêu cầu xem xét chấm dứt do quá hạn | **JSON** `Contract.unilateralTerminationSnapshot`, `REVIEW_REQUESTED` (MBS:222-240) | chủ nhà | 24h / B | parse |
| Chủ nhà phải hoàn | `PaymentRequest` payerId=chủ nhà, `direction=LANDLORD_TO_TENANT` (DEPOSIT_REFUND, INITIAL_PAYMENT_REFUND, DEPOSIT_ADJUSTMENT), AWAITING_TRANSFER/REJECTED/OVERDUE. TRANSFER_REPORTED → chờ người thuê. `contractId` có thể null (hoàn trước thuê) → nối qua `rentalRequestId` | chủ nhà | `dueAt`; refund tự xác nhận theo cửa sổ riêng / B | có index `payer_id,status`; thiếu method |
| Chờ quyết toán | `ContractClosure.status`: WAITING_HANDOVER (chủ nhà đề xuất; **không có hạn**), PROPOSED (người thuê), DISPUTED (chủ nhà), REFUND_PENDING / BALANCE_PENDING | theo status | `respondBy`, `refundDueAt` / B | thiếu theo lô |

**Lớp Thu tiền** [XM nguồn, SĐ ánh xạ]: PAID → xanh lá; payment TRANSFER_REPORTED → tím (ưu tiên trước đỏ); UNPAID chưa tới hạn → xanh dương; còn ≤3 ngày hoặc đang gia hạn → cam (`workflowState=PAYMENT_REMINDER` dùng mốc từ ngày hạn−2, MBS:1600-1604); quá hạn → đỏ; DRAFT/chưa có → xám. ROLLED_OVER, CANCELLED: bỏ để không tính đôi.
- **DRAFT có `dueAt=null`**, nên "tháng N = hạn trong tháng N" không xếp được ô "chưa phát hành" (câu hỏi 3).
- **Tiền tháng đầu không phải row `monthly_invoices`**: là payment INITIAL, hiển thị dạng ảo `first-rent:{paymentId}`, periodIndex −1 (`C/service/FirstRentInvoice`; MBS:449). Phải gộp vào lớp.
- HĐ TERMINATED không còn billable; nợ cũ của nó cần quyết (câu hỏi 4).
- Hóa đơn PAID do job đồng bộ sau (≤60s) khi payment CONFIRMED: nên đọc cả payment.

### 5.2 Hợp đồng

| Việc | Nguồn | Ai làm | Hạn / đồng hồ |
|---|---|---|---|
| Chờ chủ nhà ký (thuê, cọc) | [XM] `RentalOnboardingService.nextAction` (dòng 241-275): SIGN_LEASE_LANDLORD, SIGN_DEPOSIT_LANDLORD, SIGNING_OVERDUE; repo `findIdsAwaitingLandlordLeaseSignatureDue` (chỉ cửa sổ sắp hạn, toàn hệ thống). `LANDLORD_SIGNATURE_PENDING` = đã gửi SmartCA chờ xác nhận; DRAFT/PENDING_REVIEW cũng có thể đang chờ chủ nhà | chủ nhà | `PaymentRequest.contractDueAt` / B; `tenantSigningDueAt` cho bên thuê |
| Chờ ký phụ lục / gia hạn / VB chấm dứt | [XM] HĐ **con**; xem `SignatureRequest` theo role + status HĐ con. [SĐ] cần cả chữ ký chủ nhà; thiếu query | theo vai | `signBy` / B |
| Phụ lục | [XM] `ContractAmendment.status` (CAS:30): REQUESTED → **người nhận** (`recipientId`, thường là người thuê, CAS:120-128, 193-236); TENANT_REQUESTED, TENANT_ACCEPTED, CHANGES_REQUESTED → chủ nhà; APPROVED → chờ ký; SCHEDULED → không cần làm | theo `recipientId`/status | `respondBy`, `signBy` / B. Có `findByContractIdInOrderByCreatedAtDesc` |
| Yêu cầu gia hạn | [XM] `ContractRenewal` REQUESTED | chủ nhà | `respondBy` / B; thiếu query theo lô |
| Chấm dứt sớm | [XM] `RentalWorkflowRecord` id `{rentalId}:EARLY_EXIT`: PROPOSED → chủ nhà; APPROVED → ký; AGREED → xác nhận trả nhà (`ownerConfirmedAt`) | theo status | `respondBy` / B |
| Hủy trước thuê | [XM] `RentalRequest.cancellationRequestedAt` → DECIDE_CANCELLATION; `resolutionWorkflowId` + workflow record | chủ nhà | B |
| Đổi ngày bắt đầu | [XM] `RentalStartChange` REQUESTED (concept không liệt kê) | chủ nhà | `respondBy` / B |
| Khiếu nại | [XM] `DisputeCase` OPEN, `responseDueFor`, `respondBy` (concept không liệt kê) | theo `responseDueFor` | B (ComplaintCaseService:321) |
| Sắp hết hạn 30/60 | [XM] tính theo mục 4, chỉ ACTIVE. Không có cột ngày kết thúc → không lọc SQL. [SĐ] loại hoặc đánh dấu HĐ đã gia hạn APPLIED (ngày tự dời) / đang REQUESTED, APPROVED | chủ nhà | B |

### 5.3 Khách mới, phòng, dữ liệu

| Việc / lớp | Nguồn | Hạn / đồng hồ | Query |
|---|---|---|---|
| Yêu cầu thuê | [XM] `RentalRequest` PENDING | `respondBy` (+24h) / B (RentalRequestService:76, 262) | có `findActiveByOwnerAndStatus` |
| Lịch xem | [XM] `ViewingAppointment` PENDING; thêm `rescheduleRequested=true` (do người thuê, cả khi CONFIRMED; chủ nhà duyệt) | trước giờ hẹn / **R** | có index owner_status; thiếu list gọn |
| Trống chưa đăng tin | **Không có nguồn hiện tại** (chưa có entity phòng). Sau khi có: phòng không có HĐ/hồ sơ hiệu lực, không đánh dấu ngoài/sửa, không có tin operational PUBLISHED. [SĐ] DRAFT/HIDDEN/PENDING_REVIEW tính sao: câu hỏi 5 | | |
| Tin bị từ chối / hết hạn | [XM] status REJECTED / EXPIRED; từ chối **bản sửa** là `editRejectionReason` (tin vẫn live). Không có "đã xem": tin cũ bỏ hoang sẽ báo mãi | `expiresAt` / R | lọc được theo branchId |
| Chưa xếp phòng (HĐ/tin) | **Mới**: có `management_branch_id`, chưa có roomId. HĐ cũ toàn bộ chưa có CN, nên không xuất hiện trong khay CN nào cho tới khi gắn tay | | |
| Thuê ngoài | [XM] tin `RENTED_EXTERNALLY`; concept còn cho đánh dấu trên phòng (2 nguồn, câu hỏi 6) | | |
| **Sửa chữa** | **Không có nguồn nào**; cần trường mới trên phòng | | |

Mục **không có nguồn tin cậy**: sắp ghi điện nước (phải tính, không lưu); trống chưa đăng tin, sửa chữa (chờ entity phòng); "đã xem chỉ số tự điền"; "tên người thuê" cho thuê ngoài. Hạn cho chờ bàn giao quyết toán không có.

## 6. Thông báo và job [XM]

- Mỗi module ghi **outbox** trong transaction (`*NotificationWriter`), `*NotificationPublisher` đẩy Kafka topic `notification.events-topic`. Envelope: eventId, eventType, version, occurredAt, recipients, data **chỉ chứa id** (InvoiceNotificationWriter:21-24, 90-104). Consumer **nằm ngoài repo này** → event mới (digest) cần consumer hỗ trợ template [SĐ].
- Chống gửi trùng: `PaymentNotificationWriter.enqueueOnce(..., dedupeKey)` (dòng 59); hoặc cột "đã nhắc cho mốc X" (`PaymentDueReminderScheduler`, `AppointmentReminderScheduler`, `MembershipExpiryReminderScheduler`).
- Job: pool 4 thread (`api/config/SchedulingConfig`), Hikari mặc định 10. Cron theo giờ VN đã có mẫu: `ViewingAppointmentScheduler`:20. Billing/lifecycle/amendment sync mỗi 60s; onboarding 30s; auto-confirm 5 phút.
- Chưa có digest nào. [SĐ] Cách nhỏ nhất: 1 job trong `hs-contract-service`, chạy lặp (vd. mỗi giờ, sau giờ gửi), duyệt chủ nhà theo keyset, transaction riêng từng người, dedupe `(ownerId, billingTime.today())` có unique, chỉ gửi khi số việc > 0, tái dùng đúng service tổng hợp. Cron theo đồng hồ thật sẽ lệch khi BillingTime đứng yên: dùng fixedDelay + dedupe theo `today()`. Digest sẽ đi cùng các thông báo đơn lẻ đang có.

## 7. Đặt code, schema, quyền, test, hiệu năng

### 7.1 Vị trí
Phụ thuộc [XM] (AGENTS.md): `listing ← payment ← contract ← api`.
[SĐ] đề xuất: Tầng/Dãy/Phòng + trạng thái thủ công + liên kết tin → `hs-listing-service`; `managementBranchId` + liên kết phòng của HĐ/hồ sơ thuê, service tổng quan/cần chú ý, digest → `hs-contract-service`; controller → `hs-api-service`.
Chặn xóa CN/phòng còn HĐ: listing không được gọi contract. Dùng interface khai ở listing, cài ở contract (đã có mẫu `RentalHoldProtectionChecker` / `ContractRentalHoldProtectionChecker`); mẫu khác: native query sang bảng contracts (ListingRepository:45). Khi gắn cần khóa CN (`PropertyBranchRepository.findByIdForUpdate`) để tránh đua với xóa; **giữ thứ tự khóa** hiện có (comment PRS:344-347: acceptance khóa Listing trước Rental).
Gắn/gỡ: nên update chỉ cột mới trên `Contract`. `Contract` không thấy `@Version`; save toàn entity song song với luồng ký có thể ghi đè [SĐ] → dùng `findByIdForUpdate` hoặc `@Modifying` update. Phòng/liên kết để ở bảng riêng tránh đụng bảng contracts (chọn lúc plan).

### 7.2 Schema [XM]
`spring.jpa.hibernate.ddl-auto=${JPA_DDL_AUTO}` (application.properties:43); deploy đang dùng `update` (deploy/dokploy.md:49). Bảng/cột mới nullable do Hibernate tạo. `*SchemaMigration` chỉ làm mới CHECK constraint khi **thêm giá trị enum** (vd. ContractStatusSchemaMigration) → tránh thêm enum value vào cột cũ. `update` không tự thêm giá trị CHECK cho enum, kể cả bảng mới về sau. Concept: không viết SQL migrate.

### 7.3 Quyền [XM]
userId từ context đã xác thực (không nhận từ body/param); ghi `@PreAuthorize("hasAuthority('LISTING_POST')")`; đọc CN `findByIdAndOwnerIdAndActiveTrue` (không đúng chủ → 404); HĐ kiểm `landlordId/tenantId`. Phải kiểm cả 3: CN, phòng thuộc CN, HĐ/tin cùng owner. Lỗi qua `AppException` + `ApiResponse`. Tổng quan trả tên người thuê: chỉ cho chủ nhà; không log. Không gọi các hàm "GET" có ghi: `CLS.get` khóa row + `syncLocked` (CLS:77-79), `RentalOnboardingService.refresh` có sync; dùng bản chỉ đọc (`get` readOnly ở dòng 123).

### 7.4 Test [XM cấu trúc; lệnh **chưa chạy**]
- Unit JUnit5 + Mockito, không cần DB: vd. `PropertyBranchServiceTest`, `ParkingReservationServiceTest`, `ContractDraftResidentsTest`, `ContractListingSyncTest`, `ContractAmendmentServiceTest`, `ContractLifecycleServiceTest`, `MonthlyBillingServiceTest`.
- `*PostgresTest`: `@DataJpaTest`, opt-in bằng env (vd. `HS_LIFECYCLE_TEST_PG_URL` khớp `jdbc:postgresql://127.0.0.1:<port>/hs_phase03_test`), DB dùng bỏ, `create-drop`; không có env thì **bị skip**. HTTP: `RentalWorkflowHttpSystemTest` (`HS_WORKFLOW_HTTP_PG_URL`).
- Chạy tại `D:\Projects\homespace\hs-core-api` (PowerShell):

```
.\mvnw.cmd -pl hs-listing-service -am "-Dtest=PropertyBranchServiceTest,ParkingReservationServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl hs-contract-service -am "-Dtest=ContractDraftResidentsTest,ContractListingSyncTest,ContractAmendmentServiceTest,ContractLifecycleServiceTest,MonthlyBillingServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl hs-api-service -am compile
```
Xác nhận test thật sự chạy (không bị skip). Contract module mặc định mức L3 (`hs-contract-service/AGENTS.md`).

### 7.5 Hiệu năng

| Rủi ro | Ghi chú |
|---|---|
| [XM] `mapToResponse` mỗi CN: 1 count tin + 1 tra ảnh bìa + lazy charges/amenities/address | N+1 sẵn có ở list CN; đừng nhét số việc vào hàm này theo từng CN |
| [XM] `MBS.response()`/`workflowState` tra contract, payment theo từng invoice; `CLS.end()` tra rental từng HĐ | Không tái dùng DTO response cho tổng quan; viết query theo lô: HĐ gốc theo owner/CN join rental → invoices/amendments/renewals/payments `IN (ids)` → gom trong bộ nhớ |
| [XM] extension, unilateral là JSON | parse trong Java; chỉ nạp invoice UNPAID/OVERDUE |
| [XM] Index | Có: contracts `(landlord_id,status)`, `rental_request_id`; invoices `contract_id`, `(status,due_at)`; amendments `contract_id`; renewals `contract_id`; rental_requests `(owner_id,status)`; appointments `(owner_id,status)`; payments `(payee_id,status)`, `(payer_id,status)`, `contract_id`; parking 4 index. **Không khai báo**: `listings.branch_id`, `contracts.listing_id`, `contracts.parent_contract_id` |
| [SĐ] | Thẻ danh sách CN: 1 lần tổng hợp theo owner rồi group theo CN. Cần index cho `management_branch_id`. Thiếu repo method theo lô cho renewal, closure, workflow, start change, signature, invoice (`findByContractIdIn`) |

Trạng thái lưu có thể trễ so với hạn tới 1 chu kỳ job; suy ra "quá hạn" bằng so hạn với đúng đồng hồ.

## Rủi ro với luồng đang ổn định

1. Dùng/ghi `contracts.branch_id` sẽ đổi hành vi chỗ xe, đồng bộ tin, phụ lục (2.3). Giữ nguyên.
2. `management_branch_id` **không được vào** snapshot điều khoản / `AmendmentTermsBuilder` / PDF (ContractDataBuilder:44 cấm branchId trong dữ liệu HĐ).
3. Auto-gắn nằm trong transaction tạo HĐ (cả đường tự động sau khi tiền đã xác nhận): lỗi gắn/trùng phòng **không được làm hỏng tạo HĐ**; không gắn được thì để khay.
4. Đổi rule xóa CN: hiện CN còn tin là bị chặn; thêm chặn theo HĐ/hồ sơ đang gắn. Link cũ trỏ CN đã xóa mềm phải coi là không hợp lệ.
5. Tin đổi/gỡ branch (lưu tin, duyệt bản sửa) không được tự kéo liên kết HĐ; phòng có thể giữ tin đã sang CN khác → cần quy tắc hiển thị.
6. Gọi API đọc có side effect (7.3) từ tổng quan sẽ khóa row hàng loạt, tranh chấp với billing/ký.
7. Lỗi chỗ xe 2.4 có sẵn; nếu sửa, làm task riêng L3 (sửa fallback `branch_id` ảnh hưởng nhánh phụ lục) và rà reservation `branch_id` null đã sinh.
8. Thêm event thông báo mà consumer chưa hỗ trợ → thông báo lỗi/rơi [SĐ].

## Câu hỏi cần chủ dự án quyết

1. "Giữ chỗ"/"Sắp vào" chưa có HĐ thuê: đồng ý gắn phòng từ **hồ sơ thuê đã chấp nhận** (HĐ kế thừa), và bấm vào mở trang hồ sơ thuê? Khay "Chưa xếp phòng" và chặn xóa CN có tính hồ sơ này không?
2. Hai bên thuê trùng thời gian trên một phòng khi auto-gắn: đưa HĐ mới vào khay (đề xuất)? HĐ EXPIRED chưa bàn giao có còn chiếm phòng?
3. Lớp Thu tiền tháng N: ô "chưa phát hành" (không có hạn) xếp theo tháng nào: hạn dự kiến theo điều khoản, hay ngày chốt kỳ?
4. Nợ của HĐ đã TERMINATED/CLOSED có hiện cảnh báo trên chi nhánh không? Phòng khi đã bàn giao nhưng còn nợ hoàn cọc: hiện "trống" kèm việc hoàn tiền?
5. "Trống mà chưa đăng tin": phòng chỉ có tin DRAFT / HIDDEN / PENDING_REVIEW có báo không? Tin REJECTED/EXPIRED cũ của phòng đã có tin mới hoặc đã có người ở: ẩn?
6. "Thuê ngoài": tin `RENTED_EXTERNALLY` có tự tô phòng không, hay chỉ theo đánh dấu trên phòng?
7. Cảnh báo "tự điền chỉ số" tồn tại đến khi nào (vd. đến khi hóa đơn PAID, hay chỉ kỳ mới nhất), vì không có thao tác "đã xem"?
8. "Cần chú ý" có thêm 2 việc ngoài concept: đổi ngày bắt đầu thuê chờ duyệt, khiếu nại chờ chủ nhà phản hồi?
9. Việc không gắn được CN (yêu cầu thuê/lịch xem của tin không thuộc CN) có cần hiện ở đâu, hay chỉ ở trang gốc? (Với tin thuộc CN thì suy theo `listing.branchId`.)
10. Lỗi chỗ xe 2.4: sửa trước, sau, hay ngoài đợt này?
