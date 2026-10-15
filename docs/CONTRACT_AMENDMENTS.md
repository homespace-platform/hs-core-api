# Phụ lục điều chỉnh hợp đồng

Phụ lục là văn bản riêng (`Contract.agreementKind=AMENDMENT`, `parentContractId` là hợp đồng gốc), ký qua SmartCA. Hợp đồng gốc vẫn ACTIVE trong thời gian thương lượng/ký; `currentRevisionId` gốc, PDF gốc và giao dịch ban đầu không bị ghi đè. Thời hạn/chu kỳ thu/cọc thỏa thuận có thể điều chỉnh cho kỳ tương lai; giữ nguyên lịch sử đã phát hành và số cọc thực nhận. Văn bản gia hạn cũ có `agreementKind=null` và tiếp tục dùng luồng gia hạn; không suy diễn phụ lục thành gia hạn chỉ vì có `parentContractId`.

## API

Các endpoint có hai prefix `/api/v1/contracts/{id}/amendments` và `/contracts/{id}/amendments` cho Gateway/Core. `id` phải là hợp đồng gốc. Chỉ hai bên của hợp đồng được truy cập.

- `GET /`: đồng hồ máy chủ, điều khoản hiện tại, ngày đầu kỳ còn hợp lệ, quyền đề nghị, lý do bị chặn và lịch sử.
- `GET /options`: giữ để tương thích luồng cũ. Giao diện phụ lục nhập tay mới không gọi endpoint này và không bắt chọn theo Listing.
- `GET/PUT/DELETE /draft`: bản nháp riêng của chủ nhà, không đưa vào lịch sử hai bên và không ảnh hưởng hóa đơn. Backend từ chối token người thuê.
- `POST /tenant-requests`: chỉ người thuê, `{requestId,message,evidenceIds}`; yêu cầu bằng chữ, không có `changes` thực thi.
- `POST /tenant-requests/{requestId}/decision`: chỉ chủ nhà, `ACCEPT` để soạn phụ lục hoặc `REJECT` kèm lý do. Chấp thuận trao đổi không tạo PDF/phiên ký, không chấp thuận mức tiền trong đoạn chữ.
- `POST /preview`: chỉ chủ nhà, tính bảng đối chiếu và điều khoản dự kiến; không lưu đề nghị.
- `POST /`: chỉ chủ nhà, gửi đề xuất cụ thể. `requestId` UUID do client tạo giúp xử lý retry. Cùng UUID/nội dung trả lại cùng yêu cầu; UUID khác nội dung bị từ chối. Nếu xuất phát từ yêu cầu người thuê, thêm `tenantRequestId` của yêu cầu TENANT_ACCEPTED.
- `POST /{proposalId}/decision`: `{action, note}`. `APPROVE`, `REJECT`, `REQUEST_CHANGES` dành cho bên nhận; `WITHDRAW` dành cho bên đề nghị khi chưa ký đủ. Từ chối/chỉnh sửa cần lý do tối thiểu 10 ký tự.

Payload đề nghị:

```json
{
  "requestId": "UUID",
  "baseRevisionId": "id điều khoản đang áp dụng từ GET",
  "previousProposalId": null,
  "effectiveOn": "2026-11-14",
  "reason": "Hai bên thỏa thuận điều chỉnh giá thuê",
  "changes": { "rentAmount": "12000000" },
  "evidenceIds": []
}
```

`changes` hỗ trợ `rentAmount`, `occupantCount`, `motorbikeCount`, `carCount`, `charges`, `equipments`, `amenities`, `policies`, `additionalTerms`. Các bảng gửi danh sách đầy đủ sau điều chỉnh. Backend tính lại đơn giá mô tả và phí theo người/xe, không tin `estimatedMonthlyAmount` hoặc số tiền trong văn bản do client sửa. Điện/nước giữ nguyên phương pháp tính và chuỗi chỉ số; không xóa công tơ bằng phụ lục.

Mọi phụ lục, kể cả nội quy/tài sản/tiện ích không tác động tiền, chỉ được chọn đầu kỳ thuê tương lai trong thời hạn thuê và chưa phát hành hóa đơn ảnh hưởng. Không còn lựa chọn áp dụng ngay; API preview/propose/draft cũng chặn applyImmediately=true. Ký đủ trước 00:00 ngày hiệu lực D theo giờ Việt Nam; nếu hóa đơn ảnh hưởng đã phát hành sớm ngày D−1 thì khóa ngay từ lúc phát hành, không sửa hồi tố. Ví dụ kỳ thuê bắt đầu 15/10: đầu kỳ tiếp theo là 15/11, không phải ngày 01/11.

### Chỉnh lại phụ lục đã ký còn chờ áp dụng

Chủ nhà dùng nút **Chỉnh lại phụ lục** trên hồ sơ SCHEDULED khi server trả canReplace=true. Nội dung bản đã ký được nạp vào chế độ sửa, giữ đúng effectiveOn cũ; bản gốc vẫn là baseRevisionId đang áp dụng. Đề xuất mới liên kết previousProposalId; không sửa file/revision cũ. Người thuê phải duyệt nội dung mới, chủ nhà ký trước rồi người thuê ký lại cùng PDF mới. Word có phần ghi rõ thay thế phụ lục đã ký nào, ngày hiệu lực và đối chiếu với nội dung đang chờ, kể cả khi sửa tất cả về điều khoản gốc.

Bản SCHEDULED cũ chỉ thành SUPERSEDED sau khi bản thay thế có hai SignatureRequest SIGNED đúng revision và PDF SIGNED_FINAL READY, trong cùng transaction/khóa hợp đồng gốc. Bản cũ chưa áp dụng không được ghi APPLIED, PDF/chữ ký cũ không bị xóa và hợp đồng con cũ không bị hủy giả. Trước bước đó, bản cũ vẫn giữ lịch áp dụng: bản mới bị từ chối/rút/hết hạn/thiếu chữ ký không làm mất bản cũ. Cho phép sửa tiếp bản bị yêu cầu chỉnh lại nhưng chỉ một đề xuất thay thế được chờ xử lý. Không đổi ngày hiệu lực để lách hóa đơn; không thay thế sau ngày áp dụng hoặc khi kỳ liên quan đã phát hành hóa đơn. Lease/parking của đoạn tương lai được dựng lại theo bản thay thế kể cả khi trở về giá trị gốc; cọc chỉ tạo chênh lệch cho bản thực sự APPLIED. Không có schema hoặc SQL mới.

## Trạng thái và ký

Yêu cầu người thuê: `TENANT_REQUESTED → TENANT_ACCEPTED → TENANT_FULFILLED` (đã gửi đề xuất cụ thể). `TENANT_REJECTED` là chủ nhà từ chối rõ ràng; `TENANT_EXPIRED` là không xử lý đúng thời hạn hoặc hợp đồng không còn cho phép. Yêu cầu chờ phản hồi và chờ soạn đều có hạn 48 giờ, không tự duyệt hoặc tự ký. Yêu cầu đã chuyển thành đề xuất vẫn giữ trong lịch sử và liên kết qua `tenantRequestId`.

`REQUESTED → APPROVED → SCHEDULED → APPLIED`. `REQUEST_CHANGES` chuyển sang `CHANGES_REQUESTED`; bên khởi tạo gửi một UUID mới với `previousProposalId` để giữ phiên bản cũ `SUPERSEDED`. Các nhánh kết thúc khác là `REJECTED`, `WITHDRAWN`, `EXPIRED`. Mỗi hợp đồng chỉ một yêu cầu đang xử lý, kể cả SCHEDULED; ngoại lệ duy nhất là một đề xuất thay thế liên kết đúng bản SCHEDULED đó. Bản đã ký chỉ chuyển SUPERSEDED khi bản thay thế ký đủ như quy tắc ở trên. Quá hạn không tự đồng ý.

Sau APPROVED, mở `agreementContractId` để dùng endpoint kết xuất/chữ ký hiện có. Chủ nhà kết xuất PDF phụ lục và ký trước; người thuê xem và ký cùng PDF. Cần đủ hai request SIGNED đúng revision và PDF SIGNED_FINAL/READY. Chỉ callback đã xác minh mới áp dụng; không có endpoint đánh dấu ký thành công công khai. Rút/yêu cầu sửa trước ký đủ dừng phiên ký, giữ tài liệu và chữ ký đã có; bản mới phải ký lại. Không rút đơn phương phụ lục đã ký đủ.

Khóa hợp đồng gốc tuần tự hóa đề nghị, duyệt, ký, hóa đơn và vòng đời. Phụ lục đang xử lý chặn yêu cầu gia hạn mới và đề nghị chấm dứt mới; yêu cầu gia hạn/chấm dứt đang xử lý cũng chặn phụ lục. Khi duyệt gia hạn sau một phụ lục đã áp dụng, văn bản gia hạn lấy điều khoản hiện hành.

## Tra cứu văn bản và tệp cũ

Trang chi tiết có một mục **Hợp đồng & các lần điều chỉnh**, không dùng tab hoặc danh sách tệp kỹ thuật làm giao diện chính. Hợp đồng gốc và từng điều chỉnh ký đủ hiển thị thành thẻ riêng; số lần điều chỉnh theo thứ tự tạo hồ sơ (audit), không lấy schemaVersion/revisionNumber hoặc đồng hồ giả lập. Đề xuất chưa ký không được gán số phụ lục đã ký. Đã ký đủ và đã áp dụng là hai trạng thái khác nhau; mốc áp dụng dùng serverNow của nghiệp vụ. Hợp đồng gốc không bị coi là hết hiệu lực chỉ vì có phụ lục.

Mỗi thẻ ưu tiên PDF READY/SIGNED_FINAL đúng phiên bản, không chọn theo tên tệp hoặc lần tạo mới nhất bất kỳ. Word, PDF trước ký, PDF chủ nhà ký và bản cũ nằm trong **Lịch sử tệp** mặc định đóng, vẫn xem/tải được sau kiểm tra quyền. STALE chỉ có nghĩa bản xem trước đã cũ: cho cấp URL đọc/tải, không đổi lại READY và không cho dùng để ký hiện tại. URL ngắn hạn được cấp lại khi người dùng bấm mở/tải, không dùng lại link cũ đã hết hạn. Mốc ký từng bên chỉ lấy SignatureRequest SIGNED đúng revision, không coi PROVIDER_SIGNED là hoàn tất. API phụ lục trả thêm decidedAt để hiển thị mốc duyệt/phản hồi thật. Không sửa file đã ký, không xóa tệp và không thay nghiệp vụ hóa đơn.

## Nguồn điều khoản của hóa đơn và chỗ xe

Đối chiếu phí theo từng khoản thực sự đổi (loại/cách tính, đơn giá, trạng thái bao gồm, tổng dự kiến hoặc ghi chú), không đưa cả bảng điện/nước vào chỉ vì đổi số xe/người. Số tiền tương đương như 150000 và 150000.00 không tạo thay đổi; chuỗi mô tả được kết xuất lại không phải thay đổi nghiệp vụ. Phí gửi xe đã bao gồm trong tiền thuê được giữ nguyên khi đổi số xe. Phí thu theo người/xe vẫn tính lại theo số lượng mới và hiện đúng khoản bị ảnh hưởng. Các bảng phí đầy đủ vẫn được lưu trong snapshot mới; chỉ phần so sánh được thu gọn. Không viết lại bảng đối chiếu hay PDF của hồ sơ lịch sử đã gửi/ký.

`ContractTermsResolver` chọn snapshot đã ký theo ngày bắt đầu kỳ của từng nghĩa vụ. Hóa đơn lấy chi phí theo `periodStart`, tiền phòng kỳ tiếp theo theo `periodEndExclusive`. `costTermsRevisionId` và `rentTermsRevisionId` lưu nguồn đã chốt; hóa đơn đã phát hành và công nợ không bị tính lại. Không thu tiền phòng ngoài thời hạn nếu không gia hạn.

Phí chậm trả dùng chính sách trong `costTermsRevisionId` đã chốt của hóa đơn, không đọc cách tính mới nhất theo ngày chạy scheduler. Ví dụ phụ lục đổi Mỗi ngày → Một lần hiệu lực 15/11: hóa đơn quyết toán chi phí 15/10–14/11 giữ chính sách cũ; hóa đơn quyết toán kỳ bắt đầu 15/11 dùng Một lần. Đây là cùng quy tắc chọn nguồn chi phí ở trên, không đổi phí giữa chừng hoặc hồi tố hóa đơn. Một lần 100.000đ chỉ có một dòng 100.000đ dù trễ 1, 3 hay 10 ngày; theo ngày 100.000đ thì tương ứng 100.000đ, 300.000đ, tối đa 1.000.000đ; Không phạt không tạo dòng phí. Đề xuất/đồng ý nhưng chưa ký đủ không được đổi chính sách đang áp dụng. UI chọn cách tính bằng dropdown đúng ba giá trị NONE / FIXED_ONCE / FIXED_PER_DAY; các nội dung Listing khác vẫn nhập tay.

Đổi số xe qua phụ lục: **không chặn bằng giới hạn xe của Listing** (kể cả giới hạn sao chép trong snapshot gốc). Listing không liên kết chi nhánh: chủ nhà được đề xuất số xe máy/ô tô khác bài đăng, vẫn phải là số nguyên 0–100 mỗi loại. Listing liên kết chi nhánh: kiểm tra riêng sức chứa xe máy và ô tô của chi nhánh trên toàn khoảng ngày áp dụng, tính cả HELD/ACTIVE của các hồ sơ khác; trừ phần chỗ của chính hợp đồng sẽ được thay thế để không tính trùng. Chi nhánh không có chỗ ô tô thì không thể thỏa thuận thêm ô tô dù còn chỗ xe máy. Kiểm tra ngay khi xem lại/gửi, kiểm tra lại khi duyệt và ký dưới khóa branch/listing; đề xuất chưa giữ chỗ nên chỗ còn có thể thay đổi trước khi người thuê duyệt. Không sửa Listing hoặc tăng sức chứa chi nhánh.

Khi duyệt chỉ giữ phần tăng thêm theo từng đoạn thời gian. Nếu kéo dài thời hạn vượt chỗ đã đặt của hợp đồng gốc, đoạn vượt phải giữ toàn bộ số xe mới, không chỉ phần tăng tại ngày bắt đầu. Khi ký đủ, chuyển sang đăng ký mới từ ngày áp dụng và giữ nguyên đoạn lịch sử; chỗ HELD của chính phụ lục không bị tính trùng khi kiểm tra ký. Thay phụ lục chờ hiệu lực vẫn giữ chỗ cũ đến khi bản mới ký đủ; từ chối/rút/hết hạn chỉ giải phóng HELD của bản mới. Trường “Số xe tối đa” kế thừa không tự bị ghi đè; nếu chủ nhà chủ động đề xuất sửa chính trường này, số xe đăng ký phải phù hợp với mức mới do hai bên thỏa thuận. Giới hạn người ở và quy tắc yêu cầu thuê ban đầu không thay đổi.

Minh chứng dùng Storage GENERAL/PRIVATE, `referenceType=CONTRACT_AMENDMENT_EVIDENCE`, `referenceId=id HĐ gốc`. Tối đa 5 ảnh/PDF READY, mỗi tệp 15 MB, đúng người gửi và HĐ. Bên còn lại chỉ đọc file đã gắn vào đề nghị; file trong lịch sử không được xóa qua API thông thường.

## Cấu hình và triển khai local

`homespace.amendment.response-hours=48`, `homespace.amendment.signing-hours=48` nằm trực tiếp trong application.properties; không thêm biến .env. Restart Core để Hibernate cập nhật bảng `contract_amendments`, cột `contracts.agreement_kind` và hai cột nguồn điều khoản trong `monthly_invoices` khi local dùng `ddl-auto=update`. Không cần drop CSDL. Môi trường dùng validate/none cần triển khai schema tương ứng trước khi khởi động.

Giữ các mẫu HĐ gốc đã phát hành. Phụ lục mới điền toàn bộ snapshot đã thống nhất vào đúng phiên bản template Word của hợp đồng gốc, rồi nối bản đối chiếu/ngày hiệu lực. Chuyển PDF và bổ sung đúng một trang chữ ký bằng pipeline Gotenberg/SmartCA hiện có; không sửa file Word/PDF đã ký. Completeness kiểm tra template thật, không mặc nhiên coi mọi phụ lục đã đủ dữ liệu. Chỉ cho đổi cách tính/mức phí phạt cho kỳ tương lai; deadline, ân hạn và giới hạn phạt là quy tắc hệ thống chỉ đọc, API không cho sửa; không thay ID người ký, ID Listing, hồ sơ tài khoản hoặc lịch sử công tơ. Các trường họ tên/CCCD/ngân hàng trên văn bản được nhập tay; xác thực SmartCA và QR vẫn gắn tài khoản đã đăng ký. Không chia tiền thuê giữa tháng.

Test thủ công độc lập: UC58–UC79 trong `SIMULATED_TIME.md`; test tự động gồm đề nghị/phản hồi, yêu cầu bằng chữ, nháp riêng, hết hạn, ký và PDF, điều khoản theo kỳ, quyền minh chứng, tiền thuê/chi phí và reservation xe.

## Giao diện chỉnh trực tiếp

Nút đầu trang **Xem hợp đồng đang áp dụng / Tải PDF đang áp dụng** đọc lại hợp đồng gốc và GET `/api/v1/contracts/{rootId}/amendments` mỗi lần bấm để lấy `serverNow` và `currentTermsRevisionId` do server chọn bằng `ContractTermsResolver`/`BillingTime.today()` (giờ Việt Nam, có hỗ trợ ngày giả lập). Sau đó chỉ mở PDF READY/SIGNED_FINAL có đúng contractId và đúng revisionId này, làm mới URL bằng API tài liệu đã có. Không phụ thuộc route mới `/effective-document`, nên vẫn hoạt động khi Core đang chạy phiên bản chưa nạp route đó (tránh 404 do lệch phiên bản frontend/backend). API `/effective-document` vẫn có, và luôn quy về hợp đồng gốc kể cả khi gọi bằng ID phụ lục.

Ví dụ gốc 15/10, PL1 áp dụng 15/11, PL2 đã ký nhưng áp dụng 15/12: từ 15/11 đến trước 15/12, cả chủ nhà/người thuê đều mở PL1 qua nút “đang áp dụng”, kể cả đang ở URL hồ sơ PL2. Hồ sơ phụ lục đã ký có nút riêng **Xem riêng phụ lục này** để mở PL2 đối chiếu và nhãn giải thích ký đủ chưa đồng nghĩa có hiệu lực. Đến 00:00 ngày 15/12 mới chuyển sang PL2. Bản SCHEDULED đã ký đủ vẫn được chọn khi tới ngày dù scheduler chưa đổi nhãn APPLIED; bản tương lai, chưa ký đủ, bị rút/từ chối/thay thế không được chọn. Không chọn theo tên tệp, thứ tự kết xuất, hoặc giờ máy người dùng. Nếu PDF hiệu lực bị thiếu/chưa tải được thì báo lỗi, không âm thầm mở bản cũ hoặc bản nháp. File gốc và từng file phụ lục vẫn mở riêng trong lịch sử; không ghi đè revision gốc, không kết xuất/ký lại khi tới ngày áp dụng.

Rà soát field theo CONTRACT_PROMPT.md ngày 15/10/2026: dàn ý HOUSE có 56 mã duy nhất, APARTMENT/ROOM 57; catalog chung 64, bắt buộc 27/28/28. Cả hai bên hiển thị đủ bankName/bankAccountNumber/bankAccountHolder từ snapshot hiện hành ở chế độ đọc, nạp đúng vào phụ lục và giữ số 0 đầu tài khoản. Nhóm thanh toán ban đầu hiển thị bảng và đủ 9 mã chi tiết đã lưu, không lấy giá/cọc đề xuất để tính lại lịch sử. Thông tin hệ thống nằm ở mục mở rộng, ngày hoàn tất ký chỉ lấy signedAt; không đoán ngày lập in trên PDF từ ngày bắt đầu thuê. Có 44 marker nội dung/bảng được sửa qua phụ lục, 2 marker bằng chữ tự tính và 18 marker hệ thống/lịch sử chỉ đọc. Một marker bảng đại diện nhiều dòng/cột, không phải một input. Không thay cấu trúc CSDL hoặc PDF đã ký.

Màn hình chi tiết mặc định là chế độ đọc. Chủ nhà bấm **Điều chỉnh hợp đồng**: ngay tại các mục Thông tin các bên, Tài sản, Bàn giao, Giá thuê, Người ở, Phí dịch vụ, Tiện ích, Nội thất và Nội quy, text chuyển thành input/textarea. Không có select hoặc checkbox chọn mục từ Listing; dữ liệu Listing chỉ là nguồn khởi tạo hợp đồng. Các bảng phí, tiện ích, nội thất và chính sách có nút +/− để thêm/xóa dòng nhập tay. Bảng đặc điểm tài sản giữ nguyên số hàng, thứ tự và tên đặc điểm; chỉ giá trị được chỉnh (API cũng kiểm tra). Tiền dùng input VND, bằng chữ và tổng phí do server tính lại. Chỉ cách tính/mức phí phạt được nhập; ân hạn 0 ngày, giới hạn 10 ngày và tổng tiền phạt tối đa tự tính là nhãn chỉ đọc, tự đưa vào Word qua contract.specialTerms. Đoạn quy trình quá hạn mặc định được tách khỏi textarea, giữ nguyên trong văn bản mới. Số ngày hoàn cọc/báo trước phải có giá trị nguyên; không xóa hai nghĩa vụ này. Số người/xe/số lượng/ngày hạn là input number; ngày thuê/bàn giao là input date. Đơn vị diện tích hiển thị “mét vuông”; dữ liệu diện tích gốc vẫn là số. Ô Số căn/phòng giữ “Toàn bộ căn” khi snapshot cũ trống, không tự tạo diff chỉ vì mở sửa. Hệ thống chặn thêm/xóa/chuyển kiểu công tơ gây đứt chuỗi chỉ số, nhưng cho chỉnh tên/đơn giá/ghi chú.

Thanh hành động cố định có Hủy/Lưu nháp/Xem lại thay đổi; modal đối chiếu phải được tích xác nhận trước khi gửi. Người thuê chỉ nhập textarea mong muốn; không có form chỉnh trực tiếp. Chủ nhà chấp thuận trao đổi và tự soạn nội dung, người thuê duyệt lại, sau đó chủ nhà ký trước và người thuê ký sau trên cùng PDF mới. Duyệt nội dung chưa áp dụng snapshot; cần đủ hai chữ ký đúng revision và SIGNED_FINAL READY, sau đó đến ngày áp dụng.

Payload mới dùng `changes.freeForm=true`: `fields` chứa phần thay đổi của landlord/tenant/property/lease/financial/policies; `rentAmount`, số người/xe và các bảng charges/equipments/amenities; `featureRows` có featureName/featureValue; `policyRows` có code/label/text; `specialTerms` thay toàn bộ thỏa thuận bổ sung. Backend có allowlist, giới hạn độ dài/số dòng, chống trùng, xác thực số tiền/cách tính và tính lại số tiền theo người/xe/diện tích. Không tin amountAndMethod/estimatedMonthlyAmount do client gửi. Không sửa ID Listing, actor, template, số tiền INITIAL, chỉ số gốc hoặc dữ liệu đã ký. Payload cũ vẫn được kiểm tra bằng luồng cũ để giữ khả năng đọc/tiếp tục hồ sơ đã có.

Các mục đọc lấy điều khoản hiện tại từ resolver, không lấy giá tin đăng thay thế thỏa thuận. Listing chỉ khởi tạo dữ liệu; không bắt chọn lại danh mục khi soạn phụ lục. Tiện ích cũ đã ký vẫn giữ được dù không còn trên Listing; tiện ích dùng chung không đổi thành quyền riêng; thiết bị khác được thêm theo thỏa thuận mà không sửa Listing. WiFi và quyền vật nuôi được đồng bộ với khoản phí/nội quy để tránh thể hiện mâu thuẫn.

Schema mới: bảng `contract_amendment_drafts` và `contract_amendments.tenant_request_id`. Local `ddl-auto=update`: restart Core, không drop CSDL. Hồ sơ cũ và phụ lục đã ký được giữ nguyên. Các yêu cầu có cấu trúc do người thuê gửi ở bản cũ vẫn có thể được phản hồi/rút; bản mới chỉ chủ nhà được gửi có cấu trúc.

Thông tin bên cần hành động hiển thị trong hồ sơ hợp đồng và được tải lại mỗi 30 giây khi không chỉnh sửa. Chưa có thông báo email/push riêng cho phụ lục; không tự gửi thông báo qua nhà cung cấp bên ngoài.

## Thời hạn, chu kỳ và chênh lệch cọc (15/10/2026)

- Ngày và số tháng được kiểm tra chặt; ngày kết thúc phải khép đủ kỳ thuê tháng, tối đa 120 tháng từ kỳ đầu. Nếu đổi ngày bắt đầu thì ngày mới phải bằng ngày hiệu lực phụ lục, không dời kỳ đã thu. Chữ mô tả thời hạn được nhập riêng. Sau đủ chữ ký, cập nhật effectiveLeaseMonths để đồng bộ lịch phát hành, gia hạn và quyết toán; không sửa RentalRequest đã thanh toán. Chỉ xóa DRAFT ngoài thời hạn mới; cấm cắt vào kỳ hoặc tiền thuê đã phát hành. Chỗ xe phải giữ/kiểm tra lại cả khi chỉ đổi thời hạn.
- Kỳ hạn thanh toán nhận Hàng tháng, Hàng quý, Hàng năm hoặc Mỗi N tháng (1–12). Server chuẩn hóa paymentCycleMonths. Tiền thuê thu trước theo chu kỳ mới, giới hạn đến ngày hết hợp đồng; chi phí và công tơ vẫn chốt hàng tháng. Mỗi hóa đơn lưu rent_period_start/rent_period_end_exclusive; tháng đã thu trước không thu lại (COSTS_ONLY). Ngày áp dụng không được chồng vào tiền thuê đã phát hành. Hạn thanh toán vẫn theo mốc cố định của hệ thống (5 ngày lịch tính cả ngày chốt); không có ô nhập số ngày đến hạn.
- depositAmountValue là số cọc yêu cầu mới, số tiền bằng chữ được tính lại. Sau đủ chữ ký và đến ngày hiệu lực, tạo PaymentType.DEPOSIT_ADJUSTMENT riêng cho đúng phần chênh lệch; khóa idempotency bằng invoice_id = amendment ID. Tăng cọc: người thuê → chủ nhà; giảm cọc: chủ nhà → người thuê. originalAmount/giao dịch INITIAL không sửa; heldAmount chỉ cộng/trừ sau bên nhận xác nhận, không khi ký/báo chuyển. Khoản điều chỉnh chưa đối soát chặn phụ lục tiếp và quyết toán/chấm dứt cọc. Xem/chuyển/đối soát tại Hóa đơn → Cọc & hoàn cọc.
- Schema bổ sung: monthly_invoices.rent_period_start, monthly_invoices.rent_period_end_exclusive (date, nullable); deposit_records.retained_amount (numeric(15,2), nullable). Enum PaymentType thêm DEPOSIT_ADJUSTMENT. Toàn bộ SQL bổ sung đã gộp vào `hs-infrastructure/postgres/listings_seed_hcm.sql`, không có file migration riêng. Khi tạo lại CSDL, khởi động Core để tạo bảng/bootstrap ADMIN/catalog rồi chỉ chạy file seed chính này. File chính đồng bộ cả CHECK của enum (ddl-auto=update không luôn cập nhật CHECK cũ), chạy lại được và không ghi đè lịch sử hợp đồng/thanh toán. Kiểm thử seed chỉ dùng container tạm, không seed lại CSDL chính.

