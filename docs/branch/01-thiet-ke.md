# 01 · Thiết kế giám sát chi nhánh

Nguồn chuẩn [00-concept.md](00-concept.md). Tên bảng, trường và API dưới đây là thiết kế cho Phase 1–2. Các mục `[CHỜ CHỐT Qn]` dùng phương án đề xuất để lập kế hoạch. Chốt câu trả lời trong [04-cau-hoi.md](04-cau-hoi.md) và cập nhật hợp đồng tương ứng trước khi làm phần bị chặn.

## 1. Ranh giới và vị trí

| Nơi | Trách nhiệm |
|---|---|
| `hs-listing-service` | Chi nhánh hiện có, sơ đồ, phòng, liên kết phòng–tin, cổng kiểm tra liên kết trước khi xóa. |
| `hs-contract-service` | Liên kết quản lý của HĐ thuê gốc, xếp phòng theo lịch thật, tổng quan theo lô. Cài cổng kiểm tra do listing khai báo, theo mẫu `RentalHoldProtectionChecker`. |
| `hs-payment-service` | Khi cần, cung cấp phép đọc payment theo lô ở Phase 3–5. |
| `hs-api-service` | Controller, lấy người dùng từ `CurrentUser`, phân quyền, nối các dịch vụ. |
| `hs-web-app` | Màn hình và điều hướng, dùng axios qua Gateway. Làm web trước với dữ liệu giả theo hợp đồng API của phase. |

POM xác nhận listing dùng common/user/storage, payment dùng listing, contract dùng listing/payment, API dùng tất cả. Cổng kiểm tra xóa gọi bằng interface của listing; kiểm thử cả wiring thật ở API. Giữ hợp đồng các endpoint cũ và cột `contracts.branch_id`. Dùng bảng mới do JPA tạo theo cấu hình môi trường đã được phép, không thêm SQL migration hay sửa cấu hình.

## 2. Phase 1 · Sơ đồ

| Dữ liệu | Trường và ràng buộc |
|---|---|
| `branch_layouts` | `branch_id` PK/FK, `revision` số nguyên tăng sau mỗi lần sửa cấu trúc. Chưa có row = chưa thiết lập. |
| `branch_floors` | `id`, `branch_id`, `label` (tầng/khu), `position`. |
| `branch_rows` | `id`, `floor_id`, `label`, `position`. Mỗi dãy là một cột. |
| `branch_slots` | `id`, `row_id`, `branch_id` (luôn khớp dãy/tầng), `position`, `room_code` nullable, `normalized_room_code` nullable. Có mã = phòng, id ô là roomId; null = lối đi/ô trống, không nhận liên kết. Lưu mã đã trim, chuẩn hóa hoa/thường độc lập locale; unique (`branch_id`, `normalized_room_code`). |

- Mỗi cấp là mảng có thứ tự, vị trí lưu từ chỉ số mảng. UUID ổn định khi đổi mã, sửa tên, đổi thứ tự hoặc chuyển phòng. Ô mới bỏ `id` trong request, server cấp id. Id lặp, id lạ hoặc id bên ngoài sơ đồ trả 400 chung. Kiểm mã rỗng, mã trùng sau chuẩn hóa, nhãn rỗng, dãy/tầng không hợp lệ và kiểu dữ liệu trước khi ghi. Khi có sơ đồ cần ít nhất một tầng, một dãy và một phòng. Cho phép tầng/dãy mới còn trống trong bản nháp trên web.
- Một lần lưu thay cả cây trong một transaction, giữ id của node còn tồn tại. Khóa chi nhánh, kiểm revision và diff trước khi ghi. Khi đổi chéo mã tránh va unique trung gian trong cùng transaction. Nếu stale thì giữ bản nháp ở client, báo tải lại rồi áp lại thay đổi. Hủy hoặc hoàn tác bản nháp tại chỗ, không yêu cầu lưu lịch sử.
- Chọn mẫu ở web, các mẫu đều cho sửa trước khi lưu. Một dãy = một tầng/khu, một cột; hai dãy đối diện = hai cột; nhiều tầng = chọn số tầng/số phòng mỗi tầng, mã 101, 102, 201…; tự thiết kế = thêm bằng nút **+** dưới từng cột. Nhà nguyên căn **tự tạo** sơ đồ một tầng, một dãy, một phòng, không cần chọn mẫu: web gửi PUT sơ đồ 1 ô ngay sau khi tạo chi nhánh loại HOUSE (chi nhánh HOUSE cũ chưa có sơ đồ: tạo khi chủ nhà mở tab sơ đồ lần đầu); GET không tự ghi. Nhân bản tầng tạo node mới và nhập mã mới để tránh trùng. Thêm nhiều, đặt lại mã, ô trống, menu di chuyển và hoàn tác phải có ngay trong Phase 1.
- Chi nhánh được tạo trước bằng API cũ. Bỏ qua thì chi nhánh dùng như hiện tại. Lỗi lưu sơ đồ giữ chi nhánh đã tạo và bản nháp để thử lại. Số phòng thật đếm ô có mã; `totalUnits` và `activeListingsCount` vẫn theo ngữ nghĩa cũ.

### Hợp đồng API Phase 1

Mọi đường dẫn là đường dẫn core qua Gateway. Thành công 200, body `ApiResponse` với `code:1000`, `result` theo bảng; endpoint xóa không cần `result`. GET gọi `CurrentUser.requireUserId()` hoặc cơ chế tương đương hiện có rồi lọc owner/active. Ghi cần thêm `LISTING_POST`. 401 thiếu xác thực, 403 thiếu quyền ghi, 404 chi nhánh/đối tượng đích không thấy trong phạm vi chủ nhà, 400 request không hợp lệ. Lỗi qua `AppException`/handler hiện có. Các xung đột mới trả HTTP 409 với mã số riêng trong nhóm lỗi listing/contract và thông điệp theo từng trường hợp. Chọn mã số chưa dùng khi hiện thực, ghi vào tài liệu trước khi nối client; UI tải lại dữ liệu khi gặp 409. Lỗi của endpoint cũ giữ nguyên.

| API | Request | `result` / hành vi |
|---|---|---|
| GET `/branches/{branchId}/layout` | Không body | `{revision,floors:[{id,label,rows:[{id,label,slots:[{id,roomCode}]}]}]}`. Chưa có: `revision:0,floors:[]`. `roomCode:null` hoặc thiếu trong JSON đọc là ô trống. |
| PUT `/branches/{branchId}/layout` | `{expectedRevision,floors:[{id?,label,rows:[{id?,label,slots:[{id?,roomCode}]}]}]}`, ô trống gửi `roomCode:null` | Cây đã lưu theo shape GET. 409 nếu revision lệch hoặc (từ P2) ô còn liên kết HĐ. Gửi lại sau mất phản hồi: GET để kiểm lần lưu trước. |

## 3. Phase 2 · Liên kết và khay

| Dữ liệu | Trường và bất biến |
|---|---|
| `branch_room_listings` (listing) | `listing_id` unique/FK, `room_id` FK/index. Một phòng có thể giữ nhiều tin; một tin tối đa một phòng. Chỉ gắn khi tin active, cùng chủ, `listing.branchId` trùng branch thật của phòng. |
| `branch_contract_assignments` (contract) | `contract_id` PK/FK đến HĐ thuê gốc; `management_branch_id` FK/index; `room_id` nullable FK/index. Có row mà room null = **Chưa xếp phòng**; không row = chưa gắn chi nhánh, kể cả dữ liệu cũ. Bảng riêng để tránh ghi cả row HĐ khi người khác đang ký. |
| Phòng (listing) | Thêm `manualStatus`: `NONE`, `EXTERNAL_RENT`, `REPAIR`. Thuê ngoài chỉ ghi cờ. Gắn phòng có lượt thuê hiệu lực hoặc bật cờ nơi có lượt thuê hiệu lực → 409; tạo HĐ trong luồng cũ vẫn chạy, liên kết quản lý rơi vào khay. [CHỜ CHỐT Q5] Đề xuất cờ phòng là nguồn chính của thuê ngoài. |

**Tin và đăng tin.** Nút từ ô đi tới `/dashboard/properties/new?branchId={id}&roomId={id}`, đọc ô từ GET layout, prefill chi nhánh qua editor hiện có. [CHỜ CHỐT Q3] Đề xuất chỉ điền thông tin chi nhánh và giữ mã phòng để hiển thị vị trí được liên kết. Thêm `roomId?` vào payload **tạo tin mới** từ ô; validate và ghi link trong cùng transaction tạo tin. Request cũ bỏ trường này, nhánh nhân bản không mang link từ nguồn. Khi đổi chi nhánh trong editor, xóa `roomId` của lần tạo. Nếu ô bị xóa sau khi mở editor thì báo cần chọn lại trước khi tạo, không tạo tin rời rồi retry mù.

**Tin đã có.** [CHỜ CHỐT Q4] Đề xuất gắn/gỡ tức thời qua API link riêng khi tin đã thuộc đúng chi nhánh, kể cả đang chờ duyệt bản sửa; muốn đưa tin từ nơi khác về thì dùng chức năng đổi chi nhánh sẵn có trước. Khi `ListingService` thực sự áp thay đổi `branchId` (lưu trực tiếp hoặc duyệt bản sửa), xóa liên kết phòng cũ trong cùng transaction, không tự dời HĐ. Bản sửa nháp/chờ duyệt/bị từ chối không ảnh hưởng link. GET chỉ lấy tin có branchId thật trùng chi nhánh.

**HĐ và lượt giữ chỗ.** Chỉ chỉnh HĐ gốc thuê, loại HĐ cọc (`DEPOSIT`) và văn bản con. Phụ lục, gia hạn và workflow đọc qua gốc. `createDraftFor` dùng chung cho đường tay/tự động: lần tạo đầu đọc chi nhánh của tin hiện hành và link phòng hợp lệ, kiểm còn active/cùng chủ; không có chi nhánh hợp lệ thì bỏ link, có chi nhánh mà thiếu phòng/xung đột thì ghi link chi nhánh với phòng null. Return HĐ đã có không tự gắn lại sau gỡ. Việc gỡ hoặc gắn tay chỉ thay bảng quản lý. [CHỜ CHỐT Q1] Đề xuất trước khi có HĐ gốc, chiếu lượt `RentalRequest.ACCEPTED` còn hiệu lực qua tin/phòng hiện hành và dẫn tới hồ sơ thuê; không lập link tay hay khay riêng cho hồ sơ này. Khi có HĐ gốc, link HĐ là nguồn quản lý duy nhất.

**Chồng lịch.** Nguồn ngày bắt đầu là `moveInDate`; kết thúc exclusive theo lifecycle: `agreedEarlyEndExclusive`, nếu chưa có thì `moveInDate + (agreedEarlyBillingMonths ?? effectiveLeaseMonths ?? leaseMonths)` tháng. Hai khoảng chồng nếu `a.start < b.end && b.start < a.end`. Loại hủy/đã trả/đã chấm dứt khỏi chiếm dụng; vẫn giữ link để mở lịch sử hoặc gỡ. Đọc `ContractClosure.acceptedAt` để biết đã bàn giao kể cả đang chờ hoàn cọc. [CHỜ CHỐT Q2] Đề xuất: gắn tay trùng trả 409, tự gắn trùng đưa HĐ vào khay; HĐ hết hạn chưa bàn giao giữ trạng thái đang thuê, thời gian kế tiếp có thể xếp trước theo lịch dự kiến. Nếu gia hạn/phụ lục kéo dài đến lịch khác, hoặc lượt kế tiếp tới ngày vào mà chưa bàn giao, ưu tiên lượt đang chiếm phòng và đưa lượt xung đột vào khay. Nếu chỉ có lượt tương lai cạnh tranh, ưu tiên ngày bắt đầu sớm rồi id ổn định. Xử lý ở đường ghi hoặc job đồng bộ hiện có cho biến động theo thời gian; giữa hai lần đồng bộ, phép đọc ưu tiên lượt đang chiếm và chiếu lượt xung đột vào khay, tuyệt đối không ghi từ GET. Xác nhận chủ dự án trước khi triển khai chính sách này; chỉ được điều chỉnh link quản lý, HĐ hợp lệ vẫn tiếp tục ký/gia hạn.

**Xóa và đồng thời.** Xóa chi nhánh giữ chặn theo tin active và thêm chặn khi còn bất cứ link HĐ quản lý nào, kể cả ở khay/đã kết thúc. Ở UI gửi DELETE để nhận lý do thật, không lấy `totalUnits` hay danh sách tin đã tải làm điều kiện. Layout bỏ một phòng có HĐ hiệu lực (gồm giữ chỗ theo Q1/Q2 và lượt đã xếp còn hiệu lực) → từ chối cả lần lưu. Với phòng chỉ còn tin hoặc HĐ lịch sử: xóa link tin, đặt roomId HĐ lịch sử về null, rồi xóa ô trong cùng transaction; cảnh báo trước cho chủ nhà. Đổi phòng thành ô lối đi áp dụng cùng quy tắc. Xóa chi nhánh vẫn là xóa mềm.

**Khóa.** Dùng `PropertyBranchRepository.findByIdForUpdate` làm khóa chung cho ghi sơ đồ/link, thay đổi link khi tin đổi chi nhánh, và xóa. Khóa bản ghi nghiệp vụ hiện có trước khóa branch khi cần cả hai; tôn trọng thứ tự Listing → Rental của acceptance. Đường tạo/hiệu chỉnh HĐ đã cầm Rental/HĐ chỉ đọc Listing, không xin khóa ghi Listing sau Rental hoặc branch. Khi cần nhiều branch, khóa theo thứ tự id. Sau khóa branch, đọc lại branch/phòng/link và dữ liệu quyết định hoặc kiểm phiên bản Listing. Kiểm các thay đổi ngày đã thấy ở `ContractLifecycleService`, `ContractAmendmentService`, `MutualTerminationService`, `RentalOnboardingService`, và các điểm kích hoạt/bàn giao; đường đối soát không lấy khóa ngược từ branch về Rental/HĐ. Khóa và mềm xóa cùng transaction để tránh link mồ côi. Các tình huống dự kiến như phòng vừa bị xóa/trùng lịch phải xử lý trước khi ghi link để tránh rollback HĐ; lỗi hạ tầng theo cơ chế transaction hiện hành. Kiểm đua thật trên PostgreSQL trước khi mở cổng P2.

### Hợp đồng API Phase 2

Cùng envelope/quyền P1. Mỗi thao tác kiểm chi nhánh đang active, phòng thực thuộc chi nhánh và người gọi là chủ tin hoặc `landlordId` HĐ. Id khác chủ trả 404 chung; ô lối đi hoặc loại HĐ không hỗ trợ trong phạm vi chủ nhà trả 400. Các xung đột ngữ cảnh đã xác thực (branch khác, phòng bận, cờ xung đột) trả 409.

| API dưới `/branches/{branchId}` | Request | `result` / lỗi riêng |
|---|---|---|
| GET `/assignments?page=0&size=20` | Phân trang khay, size 1–100 | `{rooms:[{roomId,manualStatus,listings:[{id,title,operationalStatus}],contracts:[{id,status,startDate,endDateExclusive,tenantName}],holds:[{rentalRequestId,moveInDate,tenantName}]}],unplaced:{items:[{kind,id,label}],page,size,totalElements}}`. `kind=LISTING\|CONTRACT`; sort khay theo kind/id; `holds` theo Q1. Tên chỉ trả cho chủ nhà. Khay lọc theo id thật. |
| GET `/assignment-candidates?kind=LISTING\|CONTRACT&page=0&size=20` | Trang ứng viên, size 1–100 | `{items:[{id,label,roomId}],page,size,totalElements}`. Tin active cùng branch; HĐ gốc của chủ nhà chưa gắn CN hoặc đang gắn CN này. Sort id ổn định. |
| PUT/DELETE `/rooms/{roomId}/listings/{listingId}` | Không body | PUT `{listingId,roomId}`, DELETE chỉ gỡ link đúng cặp. Gắn vào phòng khác yêu cầu gỡ trước (409); gắn lại đúng cặp/gỡ lại là idempotent. |
| PUT/DELETE `/contracts/{contractId}` | PUT `{roomId:uuid\|null}`; trường bắt buộc | PUT `{contractId,managementBranchId,roomId}`. null đưa vào khay; room khác trong cùng CN là di chuyển. Link sang CN khác yêu cầu gỡ trước (409). DELETE chỉ gỡ link của đúng CN. |
| PUT `/rooms/{roomId}/manual-status` | `{status:NONE\|EXTERNAL_RENT\|REPAIR}` | `{roomId,manualStatus}`, 409 nếu mâu thuẫn lượt thuê. |

GET cấu trúc/liên kết/ứng viên chỉ đọc. Bấm phòng mở menu thao tác hoặc trang gốc; khay cho chọn phòng hoặc gỡ. Dùng API gắn HĐ với roomId null để đưa HĐ cũ vào chi nhánh dù chưa biết phòng.

## 4. Web và nguồn dữ liệu Phase 3–5

| Màn hình | URL và hành vi |
|---|---|
| Danh sách chi nhánh | `/dashboard/branches`; sau POST dùng id trả về để tới bước sơ đồ. Từ P4 thẻ hiển thị số việc, sắp gấp trước. |
| Chi tiết chi nhánh | `/dashboard/branches/{id}?tab=map&setup=1` để cài sơ đồ, bỏ qua về chi tiết cũ. Xem/sửa sau bằng `tab=map`; P2 khay ở đây; P3 lưu `floor`, `layer=rooms\|payments`, `month=YYYY-MM` trong URL để Back giữ vị trí; P4 thêm `tab=attention`, P5 thêm `layer=meters\|contracts`. |
| Điều khiển | CSS Grid cuộn trong vùng sơ đồ. Nút/phím Tab theo dãy, chữ + biểu tượng có tên truy cập, focus sau khi di chuyển, dialog giữ/trả focus; chú giải có tổng để làm nổi, tìm mã/tên. Tách empty/loading/error. Sửa nhận diện prefix chi nhánh ở `dashboard-role` cho sidebar landlord; quyền thực kiểm server. |

**Khung đọc.** P3 `GET /branches/{id}/overview?month=YYYY-MM` trả `billingNow`, `realNow`, trạng thái phòng/lớp tiền, số khoản và đích có kiểu + id + hạn; P4 thêm nhóm việc và `GET /branches/attention-summary` cho toàn bộ thẻ theo owner; P5 bổ sung lớp và digest. Chốt shape chi tiết trước mỗi phase. Query theo lô, lọc chủ từ đầu, chỉ lấy trường cần; không gọi API hóa đơn từng HĐ, `MonthlyBillingService.list`/sync, `ContractLifecycleService.get` hay `RentalOnboardingService.refresh`. Xử lý ở trang gốc; đọc lại sau khi quay về để việc tự rơi khỏi danh sách. BillingTime cho hợp đồng/hóa đơn và payment theo `PaymentTimePolicy`; lịch xem/hết hạn tin dùng giờ thật. Ngày/tháng quy về múi giờ nghiệp vụ tương ứng.

| Lớp | Nguồn/quy tắc |
|---|---|
| Phòng (P3) | Ở hiện tại: HĐ gốc ACTIVE theo lịch; EXPIRED chưa bàn giao vẫn chiếm theo Q2. Giữ chỗ/sắp vào lấy từ hồ sơ ACCEPTED trước khi có gốc theo Q1; `moveInDate > today` là sắp vào; HĐ chưa ký sau mốc vào cũng hiện giữ chỗ nếu hồ sơ còn hiệu lực. Ưu tiên đang thuê, sau đó lượt giữ chỗ; lượt kế tiếp có thể hiện nhãn phụ. Sau đó xét cờ phòng theo Q5, tin `getOperationalStatus()=PUBLISHED`, cuối cùng trống. Tin RESERVED/RENTED tự nó chưa chứng minh đang ở. |
| Thu tiền (P3) | Hóa đơn tháng + khoản INITIAL dạng `first-rent:{paymentId}`. Khóa tháng theo `dueAt` hiện hành; nợ quá hạn tháng cũ vẫn hiện. Bỏ CANCELLED/ROLLED_OVER để tránh đếm đôi. [CHỜ CHỐT Q6] Đề xuất xếp nháp/chưa phát hành theo hạn dự kiến từ lịch billing; [CHỜ CHỐT Q7] Đề xuất giữ nợ còn thu được và hoàn còn phải làm khi HĐ kết thúc. |
| Màu Thu tiền (P3) | PAID/payment CONFIRMED xanh lá; TRANSFER_REPORTED tím (hạn từ `confirmationDueAt`, có thể còn điều kiện cảnh báo/khiếu nại); quá hạn đỏ; còn ≤3 ngày hoặc gia hạn đang hiệu lực cam; đã gửi chưa đến hạn xanh dương; chưa phát hành xám/trắng. Chọn một màu/khoản theo thứ tự kiểm trên; nhiều khoản đề xuất đỏ → tím → cam → xanh dương → xám → xanh lá, kèm số khoản và chữ/icon. Dùng mốc hạn thay vì đợi job cập nhật trạng thái. |
| Ghi chỉ số (P5) | Dùng lịch billing của hợp đồng và phí theo chỉ số, báo trước 3 ngày dù chưa có draft. Đến ngày đọc draft/workflow, đã ghi khi đã chuẩn bị hoặc phát hành; tự điền/quá ngày nháp thành việc theo P4/Q9. |
| Hợp đồng (P5) | HĐ thuê gốc hiệu lực theo BillingTime và ngày kết thúc thực; dùng ngưỡng ≤30 / 31–60 ngày, gia hạn đã áp dụng dời ngày theo nguồn thật. |

| Việc (P4) | Nguồn → đích có sẵn |
|---|---|
| Ghi điện nước, nháp quá ngày, tự điền | Lịch kỳ + draft/workflow và cờ auto-fill; [CHỜ CHỐT Q9] đề xuất chỉ kỳ gần nhất chưa PAID. → `/dashboard/landlord/contracts/{id}?tab=payment&section=monthly&invoice={invoiceId}`; chưa có id → bỏ `invoice`. |
| Hóa đơn: chờ xác nhận/quá hạn/xin gia hạn | Payment TRANSFER_REPORTED, invoice + hạn và yêu cầu gia hạn PENDING trong snapshot. → đúng URL hóa đơn trên; cọc/đầu kỳ trước hiệu lực dùng tab payment của HĐ phù hợp hoặc `/dashboard/payments?paymentId={id}`. |
| Cọc/tiền chủ nhà phải hoàn | Payment hướng LANDLORD_TO_TENANT do chủ nhà còn phải xử lý, nối qua rental nếu thiếu contractId. → `/dashboard/payments?paymentId={id}`. |
| Ký, phụ lục/chấm dứt, sắp hết hạn | Onboarding nextAction, chữ ký đúng vai, amendment/workflow có đến lượt chủ nhà, mốc 30/60. → tab `sign` HĐ đúng loại, `tab=end` HĐ gốc cho đề nghị/hết hạn. Phase 4 bổ sung mở đúng panel/phụ lục từ id trong URL nếu route cũ chỉ mở tab. |
| Yêu cầu thuê, lịch xem | RentalRequest PENDING; ViewingAppointment PENDING hoặc có yêu cầu đổi lịch chờ chủ nhà. → `/dashboard/rental-requests?requestId={id}`, `/dashboard/viewing-schedules?appointmentId={id}`. |
| Phòng trống, tin từ chối/hết hạn, chưa xếp | Room + operational status, link, khay. [CHỜ CHỐT Q8] đề xuất nháp/ẩn/chờ duyệt lần đầu vẫn báo chưa đăng, ẩn việc tin cũ nếu đã thay bằng tin live hoặc phòng đã thuê. → `/dashboard/properties/view?id={id}` hoặc sơ đồ/khay; tạo tin theo P2. |

Chỉ tính việc quy được về chi nhánh từ link quản lý hoặc chi nhánh thật của tin khi việc thuộc luồng tin/hồ sơ. Dữ liệu không có nguồn tin cậy thì báo chủ dự án. Dùng thứ tự gấp nhất theo hạn/độ khẩn, khóa thứ tự ổn định khi bằng nhau và cùng phép gom nhóm cho thẻ, trang và digest. [CHỜ CHỐT Q10] Digest P5 một lần/ngày nếu có việc, đề xuất giờ Việt Nam cố định và chốt giờ với chủ dự án; dedupe owner/ngày bằng khóa unique/outbox trong transaction riêng, chỉ gửi thông tin tổng quát; xác nhận consumer ngoài repo hỗ trợ trước khi phát hành.

## 5. Đối chiếu khảo sát và bất biến

| Kết luận | Bằng chứng trong code |
|---|---|
| **Hiệu chỉnh khảo sát web:** giữ điều kiện xóa cũ và thêm chặn HĐ. Chi tiết chi nhánh kiểm `max(listings.length,totalUnits)`, danh sách kiểm `totalUnits`; hai trang đều cần bỏ kiểm cục bộ khi nối chặn mới. | `PropertyBranchService.deleteBranch`, hai `app/dashboard/branches/**/page.tsx`. |
| **Hiệu chỉnh diễn đạt backend:** ngày vào ở chỉ là một điều kiện chặn tạo gốc trong luồng hiện tại. HĐ cọc có thể tồn tại trước đó, và đến ngày vào chưa bảo đảm có gốc. UI lưu nháp/phụ lục chỉ gửi `listingSourceVersion` khi có nguồn sync đã chọn. | `ContractServiceImpl.createDraftFor`, `DepositAgreementService`, `ContractDetailView`, `ContractAmendmentWorkspace`. |
| Tin chờ duyệt cùng id, đổi CN chỉ khi áp nội dung; trang cũ lấy tối đa 100 tin và fallback tên, không dùng làm nguồn sơ đồ. | `ListingService`, `Listing.getOperationalStatus`, `ListingQueryService`, web `branches/[id]/page.tsx`. |
| GET billing đồng bộ thật; invoice query bỏ qua lúc pre-active trong web. | `MonthlyInvoiceController`, `MonthlyBillingService.list/syncContract`, web `PaymentTab` và `MonthlyInvoicesSection`. |
| Lỗi chỗ xe có đường đi trong code, chưa tái hiện runtime. | `ContractDraftResidents` truyền `contract.getBranchId()`; `ParkingReservationService.replaceDraftHoldInBranch` chọn pool tin nếu null và có thể nhả hold chi nhánh; đường phụ lục dùng `targetBranch`, nhánh pool null bỏ kiểm sức chứa sau kiểm đầu vào. Xem task `EXT-BE-01`. |

Giữ nguyên cột chỗ xe, phí, tin đăng, quy trình ký, lịch ghi chỉ số, thanh toán, nhắc hiện có, PDF/snapshot và response cũ. Ghi từ tính năng mới chỉ cho sơ đồ/link (và outbox digest ở P5). Các màn hình xử lý hiện tại vẫn là nơi thực hiện nghiệp vụ. Không thêm màn nhập số, thao tác hàng loạt, xác nhận tiền tại sơ đồ, mobile hay bất kỳ việc ngoài concept.
