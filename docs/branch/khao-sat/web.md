# Khảo sát web (`hs-web-app`) cho giám sát chi nhánh và sơ đồ phòng

Ngày 2026-11-26. Chỉ đọc code, không chạy server, không xem trên trình duyệt.
**XM** = XÁC MINH (thấy trong code). **SĐ** = SUY ĐOÁN (suy luận hoặc đề xuất, chưa kiểm).
Đường dẫn tính từ `hs-web-app/`, trừ khi ghi `core:` (`hs-core-api/`).

Viết tắt: `br/page` = `app/dashboard/branches/page.tsx`; `br/[id]` = `app/dashboard/branches/[id]/page.tsx`; `CDV` = `components/contract/ContractDetailView.tsx`; `MIS` = `components/contract/detail/MonthlyInvoicesSection.tsx`; `LE` = `app/dashboard/properties/new/ListingEditor.tsx`.

## 1. Trang chi nhánh hiện tại

### Cấu trúc `br/page` (1505 dòng)

| Dòng | Nội dung | Mức |
|---|---|---|
| 45-86 | `getChargeDisplay`, `getChargeMeta` export từ file page; `br/[id]:35` và `MonthlyExpensesSection.tsx:30` import lại | XM |
| 90-157 | `BranchesPage`: state danh sách và khoảng 35 state của form | XM |
| 175-304 | mở form tạo (reset), mở form sửa (đổ dữ liệu) | XM |
| 306-355 | ảnh bìa, phí tự thêm | XM |
| 357-557 | upload ảnh, dựng `defaultCharges`, create/update, validate bằng `alert` | XM |
| 559-575 | xóa: chặn khi `totalUnits > 0`, dùng `confirm()` | XM |
| 581-778 | header, loading, empty, danh sách thẻ (`div onClick`, không focus được bằng phím, dòng 633-636) | XM |
| 780-1446 | modal tự viết: cơ bản + địa chỉ, ảnh bìa, biểu phí/bãi xe, nội quy. Không có `role="dialog"`, Esc, khóa focus | XM |
| 1448-1501 | modal xác nhận đồng bộ phí khi `activeListingsCount > 0` (551-554) | XM |

- Form không dùng react-hook-form/zod, toàn `useState` (XM).
- Service `services/branch.service.ts:67-97`: list, get, create, update, delete. List không phân trang (68-71). Type `PropertyBranch` (19-41) không có sơ đồ, phòng, số việc (XM).
- `loadBranches` nuốt lỗi (146-156), nên lỗi mạng hiện thành "Chưa có chi nhánh" (XM).
- `br/[id]` (368 dòng): banner, lưới phí, danh sách `ListingItemCard`; không có tab, không sửa chi nhánh tại đây. Nút "Thêm phòng mới" (204-209) thực chất là tạo tin (XM).

### Chỗ đặt bước "sơ đồ"

| Phương án (SĐ) | Ghi chú |
|---|---|
| **Nên chọn:** tạo xong thì chuyển sang `/dashboard/branches/{id}?tab=map&setup=1` | `createBranch` đã trả về branch (`branch.service.ts:78-84`) nhưng `br/page:511` đang bỏ qua (XM). Bỏ qua bước = rời tab. Sửa sau dùng cùng màn. Không phải nhét thêm vào modal. `?tab=map` hiện **chưa tồn tại** |
| Thêm bước vào modal | Phải xử lý trạng thái "chi nhánh đã lưu, sơ đồ lưu lỗi", file càng lớn |

Trong `br/[id]`, hai chế độ "Sơ đồ" / "Cần chú ý" có thể đặt dưới banner (sau dòng 244), dùng `DetailTabs`. Không dùng được nguyên `useDetailTab` vì hook chỉ nhận id trong `DETAIL_TABS` của hợp đồng (`hooks/useDetailTab.ts:17`) (XM).

### Cách tách nhỏ và an toàn nhất (SĐ)

1. Không bắt buộc tách để làm sơ đồ nếu chọn phương án chuyển trang: chỉ sửa dòng 508-512.
2. Nếu tách: chuyển 45-86 sang `lib/branch-charges.ts` và sửa 2 nơi import.
3. Sau đó chuyển nguyên khối state/handler của form + JSX 780-1501 sang `components/branch/BranchFormModal.tsx`, props `branch | null`, `onClose`, `onSaved(saved)`. Giữ `handleDelete`, danh sách ở page. Không đổi logic phí/bãi xe trong cùng lượt.

Đã tìm trong `tests/`: chỉ thấy test phí tin theo chi nhánh (`listing-branch-fee-independence.test.cjs`), không thấy test cho hai trang chi nhánh (XM theo tìm chuỗi "branch" trong `*.cjs`).

### Lỗi nghi ngờ: đã kiểm

| Nghi ngờ | Kết luận | Bằng chứng | Mức |
|---|---|---|---|
| Giới hạn 100 tin | **Đúng.** Gọi `size: 100`, không lật trang; server cũng ép tối đa 100 | `br/[id]:64-67,78`; core: `hs-listing-service/.../ListingQueryService.java:50` | XM |
| Khớp theo **tên** chi nhánh | **Đúng.** Khớp `branchId` HOẶC `branchName` | `br/[id]:69-75,81-86` | XM |
| Khi nào gây sai | Lượt 1 server đã lọc `branchId` (core dòng 60-61). Nếu rỗng, lượt 2 lấy 100 tin mới cập nhật nhất của mọi chi nhánh rồi so tên: hai chi nhánh trùng tên thì lẫn tin | `br/[id]:77-87` | XM (luồng); chưa kiểm server có cấm trùng tên không |
| Số phòng sai | Tiêu đề dùng `listings.length` (tối đa 100); số trên banner là `max(listings.length, totalUnits)` | `br/[id]:137,323` | XM |
| Gọi trùng, ghi đè | `loadBranchListings` phụ thuộc `branch` nên chạy lại khi branch tải xong; không chặn phản hồi cũ | `br/[id]:61-104` | XM (code); hậu quả đua là SĐ |
| "Phòng" = tin đăng | `totalUnits` = số tin `active=true` của chi nhánh (rơi về giá trị lưu nếu bằng 0). `activeListingsCount` là cùng con số đó, không lọc trạng thái, trong khi UI ghi "tin đăng đang hoạt động" | core: `PropertyBranchService.java:266-267,303-304`; `br/page:1460-1465` | XM (`active` nghĩa là chưa xóa mềm: SĐ) |

Sơ đồ không được dựng từ truy vấn này (concept: phòng là dữ liệu riêng).

## 2. Đích điều hướng

`{R}` = `/dashboard/landlord/contracts/{contractId}`. Tab hợp lệ: `overview|sign|payment|end|content|history` (`lib/rental-labels.ts:68-77`). Tab không có với hợp đồng đó thì rơi về `overview` (`ContractPage.tsx`, biến `active`). Hợp đồng con chỉ có `overview|sign|content` (`CDV:581`).

| Đích | URL hiện có | Mở đúng đến đâu | Còn thiếu / bổ sung nhỏ nhất (SĐ) | Mức |
|---|---|---|---|---|
| Chi tiết hợp đồng và tab | `{R}?tab=<tab>` | Đúng tab; thiếu `tab` thì `overview`, hoặc tab bước kế nếu trước hiệu lực (`CDV:121`) | Đủ | XM |
| Không muốn tự chọn phía | `/dashboard/contracts/{id}?...` | Xác định phía theo tài khoản, giữ query (`app/dashboard/contracts/[id]/page.tsx:25-55`). Văn bản `WORKFLOW` không redirect theo query mà hiện panel/link gốc (34-50) | Đủ | XM |
| Hóa đơn tháng cụ thể | `{R}?tab=payment&section=monthly&invoice={invoiceId}`; dựng bằng `paymentQuery` (`lib/payment-module.ts:25-29`) | `PaymentTab.tsx:31-33` đọc `invoice` thành `requestedInvoiceId`; `MIS:297-298` chọn đúng; id lạ hiện "không tìm thấy", không chọn hóa đơn khác. Tháng 1 dùng id `first-rent:<paymentId>` (`payment-module.ts:14-22`). **Trước hiệu lực (`preActive`) thì `invoice` bị bỏ qua**, hiện panel onboarding (`PaymentTab.tsx:38-39`) | Chọn được hóa đơn nhưng không tự cuộn tới; thêm `#monthly-invoices` (`MIS:170-174`, chỉ cuộn lúc mount, lúc section có thể đang tải: hiệu quả SĐ) | XM |
| Nhập chỉ số | Như hóa đơn, với hóa đơn `DRAFT` (người thuê không thấy nháp, `MIS` biến `shown`) | Mở đúng hóa đơn nháp | Chưa có hóa đơn/chưa có id thì chỉ mở được tab | XM |
| Xác nhận đã nhận tiền | Như hóa đơn; nút ở `MIS:469-470`. Cọc/khoản đầu trước hiệu lực: `?tab=payment` của HĐ cọc hoặc HĐ đang chờ | Đúng hóa đơn | Không có anchor tới nút. Chấp nhận được | XM |
| Xin gia hạn thanh toán | Như hóa đơn; `PaymentExtensionPanel` nằm trong chi tiết hóa đơn (`MIS:439`) | Đúng hóa đơn | Không có anchor panel; tùy chọn thêm `id` + hash | XM |
| Quá hạn | Như hóa đơn (`OverdueActionsPanel`, `MIS:440`) | Đúng hóa đơn | Đủ | XM |
| Ký (HĐ thuê, HĐ cọc) | `{R}?tab=sign` | Đúng tab | Đủ | XM |
| Ký phụ lục / gia hạn (văn bản con) | `/dashboard/contracts/{agreementId}?tab=sign` | Hợp đồng con: tab ký. `WORKFLOW`: panel của gốc, không phải `?tab=sign` | Đủ | XM |
| Phụ lục chờ phản hồi | `/dashboard/landlord/contracts/{rootId}?tab=end` | `AmendmentSlot` trong tab `end`; khi đang soạn thì chuyển sang `content` (`EndTab.tsx`, `ContractPage.tsx`) | **Không có deep link tới đề nghị.** `#contract-amendment-actions` có `id` (`ContractAmendmentPanel.tsx:119`) nhưng không đọc hash lúc tải. Thêm effect cuộn theo hash sau khi dữ liệu về | XM |
| Chấm dứt sớm chờ phản hồi | `{rootId}?tab=end`. Có `agreementId` văn bản workflow: `/dashboard/contracts/{agreementId}` mở panel và focus | `EndTab.tsx:70` không truyền `focusRequest` nên panel thu gọn | **Thiếu** với `?tab=end`: thêm `?focus=early-exit` rồi truyền `focusRequest` (panel đã hỗ trợ, `RentalWorkflowPanel.tsx:24,115-120,192`) | XM |
| Đề nghị gia hạn thuê | `{rootId}?tab=end` | `ContractLifecyclePanel mode="renewal"` | Không có anchor | XM |
| Hoàn/bù cọc, quyết toán | `/dashboard/contracts/{leaseId}?tab=payment&section=deposit` | Landing chuyển sang HĐ cọc `?tab=payment`; không có HĐ cọc thì `{lease}?tab=end` (`payment-module.ts:43-55`) | Không chọn được khoản cụ thể | XM |
| Một khoản chuyển/hoàn cụ thể | `/dashboard/payments?paymentId={id}` | Cuộn và focus đúng thẻ (`payments/page.tsx:134,263-272`). `INITIAL_PAYMENT_REFUND` còn phải bấm "Mở thao tác hoàn tiền" (421-425). Link tiếp theo theo loại: `paymentLink` (105-125), **không kèm invoice id** cho hóa đơn | Đủ để định vị; hóa đơn nên đi thẳng URL hóa đơn | XM |
| Yêu cầu thuê | `/dashboard/rental-requests?requestId={uuid}` | Tải theo id, mở modal, kiểm đúng phía (`useRentalRequestDeepLink.ts`; `RentalRequestsManagement.tsx:67`) | Đủ | XM |
| Lịch xem nhà | `/dashboard/viewing-schedules?appointmentId={uuid}&status={STATUS}` (`status` tùy chọn) | Mở modal, đổi tab trạng thái (`useAppointmentDeepLink.ts`; `viewing-schedules/page.tsx:59-63`) | Đủ | XM |
| Xem tin (quản lý) | `/dashboard/properties/view?id={listingId}` | Chỉ chủ tin (`properties/view/page.tsx:27-60`) | Đủ. Lưu ý thẻ tin hiện mở trang công khai `/rent/...` (`ListingItemCard.tsx:85`) | XM |
| Sửa tin | `/dashboard/properties/{id}/edit` | Editor; tin `RENTED_EXTERNALLY` bị khóa (`LE`, `setRentedLockListing`) | Đủ | XM |
| Tạo tin cho chi nhánh | `/dashboard/properties/new?branchId={id}` | Prefill (`LE:605-606` gọi `handleSelectBranch` từ dòng 302): loại hình, đơn vị giá, địa chỉ, biểu phí | Đủ cho chi nhánh | XM |
| Tạo tin cho **phòng** | **Chưa có** | | Xem dưới | XM |
| Danh sách tin lọc theo trạng thái/chi nhánh | **Chưa có** query: `app/dashboard/properties/page.tsx` không dùng `useSearchParams` | | Mỗi việc nên link từng tin theo id nên có thể chưa cần | XM |
| Danh sách hợp đồng | `/dashboard/landlord/contracts?kind=LEASE\|DEPOSIT` | Chỉ lọc `kind`; nhóm trạng thái là state (`ContractListView.tsx:115-123`) | Không cần cho sơ đồ | XM |
| Quay lại sơ đồ từ hợp đồng | **Chưa có**; header hợp đồng quay về danh sách hợp đồng (`CDV:615`) | | Dựa vào Back trình duyệt; vì vậy tầng/lớp/tháng nên nằm trên URL | XM (hiện trạng) |

### Prefill phòng cần gì

- Đề xuất `?branchId=…&roomId=…` (SĐ). `LE:123-124` hiện chỉ đọc `duplicateFrom`, `branchId`.
- Cần: API đọc phòng; đọc `roomId` sau `handleSelectBranch`; đổ mã phòng và tầng; gửi liên kết khi lưu. `CreateListingRequest` web có `branchId`, chưa có `roomId` (`types/listing.type.ts:305-340`, XM).
- Chỗ nhận dữ liệu khác nhau theo loại: căn hộ có `unitNumber`/`floor` gửi thành `unitCode`/`floorNumber` (`buildListingPayload.ts:194-195`); phòng trọ chỉ thấy `roomFloor` gửi `floorNumber` (`:234`), không thấy trường mã phòng trong phần này (XM). Có đưa mã phòng vào tiêu đề không là quyết định sản phẩm.
- `roomDetails.roomFloor` mặc định `"2"` (`LE:218`), phải ghi đè khi prefill (XM).
- `duplicateFrom` thắng prefill chi nhánh (`LE:539,605`) (XM).

### Ảnh hưởng của `dashboard-role`

| Điểm | Mức |
|---|---|
| Role lưu `localStorage` `hs.dashboard.role`, tự suy từ path (`lib/dashboard-role.ts:3,38-62`; `DashboardRoleContext.tsx:44-51`) | XM |
| `/dashboard/branches` **không** có trong `inferRoleFromPath`: mở link chi nhánh khi đang lưu role tenant thì role/sidebar vẫn là tenant. Bổ sung: thêm prefix này vào nhánh landlord | XM |
| `/properties`, `/rental-requests`, `/viewing-schedules`, `/landlord/*` tự chuyển sang landlord; đuôi `my-requests`, `my-bookings` là tenant | XM |
| `/dashboard/payments`, `/invoices`, `/contracts/{id}` trung tính; link hợp đồng và payment xác định phía theo tài khoản | XM |
| Trang `{R}` kiểm đúng phía bằng id hồ sơ (`CDV:105-112,465-466`) | XM |
| Gate UI: chi nhánh dùng `LISTING_POST` (`br/page:91`, `br/[id]:38`), không thấy quyền riêng cho chi nhánh trong `lib/permissions.ts:3-15`. Link "Thêm phòng" không bị gate | XM |

Từ sơ đồ (chỉ chủ nhà) nên dùng thẳng URL `landlord` (SĐ).

## 3. Thành phần và mẫu tái dùng

| Nhu cầu | Có sẵn | Ghi chú | Mức |
|---|---|---|---|
| Card, tiêu đề, pill | `components/contract/page/ui.tsx` (`Card`, `SectionTitle`, `Pill`, `TabBody`, skeleton mẫu); `components/ui/card.tsx`, `badge.tsx`, `button.tsx` | `Pill` dùng `toneClasses` | XM |
| Tab | `components/rental/DetailTabs.tsx` | Có phím mũi tên/Home/End, ARIA; `aria-label` cố định "Các mục của hồ sơ" | XM |
| Modal / bottom sheet | `components/rental-request/ModalShell.tsx` | Đáy trên mobile, giữa trên desktop, Esc, khóa cuộn, trả focus. Không bẫy Tab | XM |
| Xác nhận | `components/common/ActionDialog.tsx` `useActionDialog` | Thay `alert/confirm`. Không bẫy Tab | XM |
| Dialog bẫy focus | `@radix-ui/react-dialog` (vd `ContractAmendmentPanel.tsx`) | Bẫy focus là hành vi của thư viện (SĐ) | XM (đã dùng) |
| Side panel/drawer | Không thấy component chung trong `components/ui`, `components/common` | Dùng `ModalShell`, hoặc Radix Dialog đặt bên phải (SĐ) | XM |
| Menu "di chuyển phòng" | Mẫu menu riêng: `ListingStatusActionMenu.tsx` | Không thấy menu chung; chưa đọc chi tiết a11y | SĐ |
| Phân trang | `lib/pagination.ts` `getVisiblePages` | Chỉ tính số trang, UI mỗi trang tự viết | XM |
| Loading / empty / error | Skeleton `ContractPageSkeleton`; lỗi `role="alert"` + "Thử lại" (`ContractPage.tsx`, `DepositSettlementPanel.tsx`) | Trang chi nhánh hiện chỉ spinner + `alert()` | XM |
| Refresh an toàn | `usePolledRead.ts`; mẫu gộp request và hoãn khi tab ẩn (`MIS:95-165`) | | XM |
| Đếm giờ | `hooks/useNowMs.ts` | Chưa đọc nội dung; hạn phải lấy từ server | SĐ |
| Thư viện grid/floor-map/kéo thả | **Không có** trong `package.json`; tìm `package-lock.json` theo dnd, drag, grid-layout, gridstack, konva, fabric, reactflow, xyflow, moveable, sortable, panzoom, zoom-pan không ra | CSS Grid đủ; concept loại kéo thả. Embla, Radix slider không phải lưới sơ đồ | XM |

### Màu và theme

- Token ở `app/globals.css:15-125`, dark theo class `.dark` (dòng 5, 103). Có `primary`, `destructive`, `muted`, `card`; **không thấy token success/warning**, code dùng thẳng `emerald-*`, `amber-*` kèm `dark:` (XM).
- `toneClasses` (`rental-labels.ts:60-66`): neutral, info, warning, success, danger. Không có tím (XM).

### `lib/invoice-status.ts`

- `invoiceStatusPresentation(invoice, paymentStatus, {paymentLoadFailed})` (65-96) trả `kind`: cancelled, draft, rolled, paid, awaiting_confirm, rejected, overdue, unpaid, unknown. Có `invoicePeriodLabel`, `formatInvoiceDate`, `formatInvoiceMoney`. Chỉ thấy `MIS` dùng hàm trạng thái trong `components/`, `app/` (XM).
- `invoiceNeedsAction` ở `lib/invoice-list.ts:28`; `chooseInvoice` ở `payment-module.ts:33` (XM).
- **Lệch với bảng màu concept** (XM): hiện chờ xác nhận = xanh dương, chưa trả = vàng amber; concept: chờ xác nhận = tím, đã gửi chưa đến hạn = xanh dương, còn ≤ 3 ngày/đang gia hạn = cam. Hàm cũng không tính "≤ 3 ngày" hay gia hạn.
- Đề xuất: tái dùng `kind`, thêm lớp ánh xạ màu riêng cho sơ đồ, không đổi `styles` (SĐ). "≤ 3 ngày", mức khẩn nhất, đếm ngược nên do backend trả (SĐ, theo AGENTS: không tự đặt quy tắc ở client).
- Dữ liệu có sẵn trong `MonthlyInvoice`: `dueAt`, `originalDueAt`, `serverNow`, `meterDeadlineAt`, `workflowState`, `electricityAutoFilled`, `waterAutoFilled`, `paymentExtensions`, `activeResolution` (`types/monthly-invoice.type.ts:36-90`). Type này không có hạn tự xác nhận (XM; chưa kiểm `PaymentRequest`).

### Responsive

Mẫu đang dùng: một cột trên mobile, `sm:`/`lg:` mở rộng; nút `min-h-11`; modal đáy màn hình; tab cuộn ngang `no-scrollbar`; `MIS:321` đổi danh sách bên thành `<select>` dưới `lg` (XM). Sơ đồ nhiều dãy nên cuộn ngang **trong** vùng sơ đồ, cả trang không cuộn ngang (SĐ).

## 4. Nơi tạo/sửa tin và hợp đồng

| Luồng | Vị trí | Cần cho chọn phòng / tự gắn | Mức |
|---|---|---|---|
| Tạo, sửa, nhân bản tin | `LE` (create/edit; routes `new`, `[id]/edit`) | Select chi nhánh ở `BasicInfoSection.tsx:146-178`: chỗ đặt select phòng | XM / vị trí là SĐ |
| Đổi/bỏ chi nhánh | `LE:302-325` | Phải xóa `roomId`. Sửa tin không cho chọn chi nhánh khác loại hình (327-330) | XM / SĐ |
| Payload tin | `buildListingPayload.ts:474` gửi `branchId` | Thêm trường phòng ở đây, `mapListingDetailToFormState.ts:289-302`, `types.ts:31`, `listing.type.ts` | XM / SĐ |
| Sửa tin đã duyệt | Có bản sửa chờ duyệt (`pendingEditId`, `LE` sau `upsert`) | Cần quyết liên kết phòng áp ngay hay chờ duyệt (câu 3). Concept nói gắn tin có sẵn từ ô, nên có thể dùng API liên kết riêng, không qua upsert (SĐ) | XM / SĐ |
| Tạo hợp đồng tự động | Backend: core `AutomaticContractDrafts.java:44` → `createDraftAutomatically` | **Tự gắn phải làm ở backend**; web không đi qua luồng này | XM |
| Tạo hợp đồng dự phòng (web) | `RentalRequestsManagement.tsx:92-126` → `contractService.createDraft({rentalRequestId})` (`contract.service.ts:98`) | Payload chỉ có `rentalRequestId`; backend dùng chung `createDraftFor` nên không cần selector | XM / SĐ |
| Sửa hợp đồng nháp | `CDV` tab `content`, listing-sync | Concept: link quản lý tách khỏi điều khoản, không đưa vào form revision/phụ lục | SĐ |
| Gắn/gỡ hợp đồng | **Chưa có.** `ContractResponse` có `listingId`, không thấy trường chi nhánh (`types/contract.type.ts`) | Đặt trên sơ đồ ("Chưa xếp phòng", bấm ô trống). `listContracts` phân trang, không có filter chi nhánh/chưa xếp (`contract.service.ts:111-123`) nên cần API mới | XM / SĐ |

## 5. Test, lint, typecheck

### Cách viết

- Không có script `npm test`; chỉ có `test:data` (`package.json`). Dùng `node:test` + `node:assert` (XM).
- Mẫu phổ biến: đọc file TS/TSX, `typescript.transpileModule`, chạy bằng `vm`, tự cấp mock import (`tests/contract-next-step.test.cjs:1-20`; helper `tests/helpers/`). Logic thuần đặt trong `lib/` hoặc file `.ts` riêng thì test dễ nhất (SĐ).
- `lib/*.test.ts` chạy thẳng bằng Node (đã chạy với v26.13.1) (XM).
- `tests/amendment-ui/`: Playwright, `node tests/amendment-ui/run.cjs [playwright-module] [browser]`; **không chạy** lần này (XM theo README).

### Lệnh và kết quả ngày 2026-11-26 (chạy trên cây làm việc hiện tại, không sửa gì)

| Lệnh | Kết quả | Mức |
|---|---|---|
| `node --test tests/*.test.cjs tests/*.test.mjs scripts/*.test.mjs` | 824 test: 739 đạt, **85 lỗi** | XM |
| `node --test lib/*.test.ts` | 32/32 đạt | XM |
| `npx tsc --noEmit --incremental false` | 1 lỗi: `.next/types/validator.ts(125,39)` thiếu `app/dashboard/customers/page.js` (nhiều khả năng `.next` cũ: SĐ). Lệnh trong AGENTS là `npx tsc --noEmit` | XM |
| `npm run lint` | 6080 vấn đề: 349 lỗi, 5731 cảnh báo. File có lỗi chỉ thuộc `public/vendor` và `tests/` | XM |
| `npx eslint app/dashboard/branches` | Không in lỗi | XM |
| `npx eslint <file đã sửa>`; `npm run build`; `npm run test:data` | Cách dùng: lint theo file; build chỉ khi cần. `build` và `test:data` không chạy riêng | |

### File test đang lỗi (số lỗi)

| File | Lỗi | File | Lỗi |
|---|---|---|---|
| invoice-notification.test.mjs | 15 | agreement-notification.test.mjs | 5 |
| listing-furnishings.test.cjs | 10 | rental-request-notification.test.mjs | 5 |
| listing-notification.test.mjs | 10 | administrative-filter.test.cjs | 4 |
| return-settlement-notification.test.mjs | 9 | listing-sync-financial.test.cjs | 3 |
| contract-initial-meters.test.cjs | 8 | backend-health.test.mjs | 1 |
| payment-notification.test.mjs | 8 | home-ai-chat.test.cjs | 1 |
| account-onboarding.test.mjs | 5 | notification-popup-preferences.test.mjs | 1 |

Thông báo hay gặp: `Cannot find module '@/...'` (PolicyDialog, rental-labels, useAgreementChangeSync, Breadcrumb...), `createThrottle is not a function`, `useStickyTop ... is not a function`, vài `deepStrictEqual` (XM). Nhiều khả năng mock/loader của test chưa theo kịp import mới (SĐ; chưa chẩn đoán từng lỗi). **Không sửa.**

## 6. Accessibility cho lưới phòng bấm được

Toàn bộ mục này là yêu cầu đề xuất (SĐ), trừ chỗ ghi XM.

| Yêu cầu | Cách đáp ứng |
|---|---|
| Ô bấm được | `<Link>` nếu chỉ điều hướng, `<button type="button">` nếu mở panel. Không dùng `div onClick` (lỗi đang có ở thẻ chi nhánh, `br/page:633-636`: XM). Không lồng nút trong nút |
| Tên truy cập | Gồm mã phòng, tầng/dãy, trạng thái, số khoản, hạn: "Phòng 101, quá hạn, 2 khoản" |
| Không chỉ dùng màu | Chữ + icon trên ô (concept §5); icon `aria-hidden` |
| Cấu trúc | Mỗi tầng một heading, mỗi dãy một danh sách có nhãn; thứ tự DOM theo dãy. Thứ tự Tab tự nhiên là đủ cho bản đầu; chỉ dùng `role="grid"` khi làm đủ phím mũi tên và roving tabindex. Tìm `role="grid"` trong `components/`, `app/` không ra (XM) |
| Ô trống/cầu thang | Không focus ở chế độ xem. Chế độ sửa: nút "+" có `aria-label` nêu dãy |
| Di chuyển phòng | Menu dùng được bằng phím (concept loại kéo thả); giữ focus ở ô vừa chuyển; có hoàn tác |
| Chọn lớp, lọc từ chú giải | `DetailTabs`/radio cho lớp; `aria-pressed` cho nút làm nổi (mẫu `ContractListView.tsx:184-189`: XM). Ô bị làm mờ vẫn đủ tương phản |
| Tìm kiếm | Có `<label>`; báo số kết quả bằng `aria-live="polite"` |
| Focus, kích thước | `focus-visible:ring-2 ring-ring`; ô tối thiểu 44px |
| Panel | `role="dialog"`, Esc, trả focus; cần bẫy Tab thì dùng Radix |
| Đếm ngược | Không đọc lại mỗi giây |
| Theme, zoom | Kiểm tương phản cả light/dark; zoom 200% không mất nội dung; tôn trọng `motion-safe` |

## Rủi ro

| Rủi ro | Mức |
|---|---|
| Web chỉ đọc hóa đơn theo từng hợp đồng (`monthlyInvoiceService.list(contractId)`), và comment `MIS:96-97` cho biết GET này chạy đồng bộ billing có khóa (có thể đổi phí trễ, tạo lại VietQR: `MIS:108-109`). Sơ đồ gọi N lần vừa chậm vừa **gây tác dụng ghi**, trái "phần mới chỉ đọc". Cần API tổng hợp chỉ đọc | XM (comment, lời gọi); hậu quả là SĐ |
| Tính màu/độ khẩn ở client sẽ lệch quy tắc backend (múi giờ, "Tháng N" theo `dueAt`, hóa đơn tháng 1 dạng tổng hợp, `paymentStatus` tách khỏi hóa đơn) | SĐ |
| Dựng phòng từ tin đăng sẽ kế thừa lỗi 100 tin và khớp tên | XM (lỗi) |
| Trước hiệu lực, `?invoice=` bị bỏ qua: việc về khoản đầu/cọc phải link theo tab, thường là HĐ cọc | XM |
| Phụ lục và chấm dứt sớm chưa cuộn/mở đúng chỗ | XM |
| Role tenant còn lưu làm sidebar sai ở `/dashboard/branches` | XM |
| Xóa chi nhánh đang chặn theo số tin (`totalUnits`); concept yêu cầu chặn theo hợp đồng đang gắn, backend và hai trang đều phải đổi | XM (hiện trạng) |
| Có sẵn 85 test lỗi và lint đỏ: phase mới phải so với mốc này, không dựa vào "toàn bộ xanh" | XM |
| AGENTS yêu cầu đọc tài liệu Next trong `node_modules/next/dist/docs/` trước khi viết code; khảo sát này chưa đọc vì không viết code | XM |

## Câu hỏi cần chủ dự án quyết

1. Màu: chỉ sơ đồ dùng bảng màu mới (tím = chờ xác nhận, xanh dương = chưa đến hạn, cam = ≤ 3 ngày/gia hạn), còn trong hợp đồng giữ màu cũ (xanh dương = chờ xác nhận, vàng = chưa trả); hay đổi cho thống nhất?
2. "Đăng tin cho phòng này": ngoài thông tin chi nhánh, có điền mã phòng vào tiêu đề không? Phòng trọ hiện không thấy trường mã phòng riêng để nhận.
3. Gắn phòng cho tin đã duyệt: có hiệu lực ngay (liên kết quản lý) hay phải qua duyệt sửa tin? Đề xuất: ngay.
