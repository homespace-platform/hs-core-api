# Bàn giao: cọc chưa hoàn tất ký, nhận nhà, chấm dứt sớm

> Báo cáo này ghi lượt triển khai 06–08 ban đầu. Các giới hạn PDF, tính theo ngày và R01–R03 đã được xử lý trong [lượt hoàn thiện](CONTRACT_LIFECYCLE_GAPS_COMPLETION_REPORT.md); số test bên dưới là số lịch sử, không cộng vào lượt cuối.

Ngày: 03/11/2026. Phạm vi được giao: ba task tương ứng checklist 06–08. Chủ dự án yêu cầu **bỏ qua giai đoạn thông báo 05** và làm cả ba task trong lượt này. Không triển khai gửi thông báo, outbox hoặc worker; không coi log hay thời hạn là thông báo đã được giao.

Trạng thái sau hậu kiểm: **đã triển khai ba workflow và hồi quy hiện có đạt; còn R01–R03 cần xử lý trước khi chốt hoàn thiện**. Xem [các khoảng trống hậu kiểm](CONTRACT_LIFECYCLE_PHASE_06_08_REVIEW_GAPS.md). Bước 05 được bỏ qua; chưa thực hiện nghiệm thu E2E với nhà cung cấp thật của 09.

## 1. Cọc đã nhận nhưng chưa ký xong

Thêm đề nghị kết thúc trước thuê với đầy đủ lý do, bằng chứng, phiên bản và lịch sử. Cả hai bên có thể gửi; bên còn lại đồng ý hoặc từ chối, người đề nghị có thể rút. Đề nghị mới tạm dừng ký/tạo HĐ mới và đổi ngày thuê, giữ nguyên các chứng từ đã nhận.

Nhánh này dùng **hoàn toàn bộ khoản thực nhận**, bao gồm cọc và tiền thuê đã nhận, mỗi nguồn có nghĩa vụ hoàn riêng. Không áp dụng mất cọc khi chưa ký HĐ cọc; không tính việc chủ nhà chưa chuẩn bị văn bản thành lỗi của người thuê. Nhánh giữ cọc theo chính sách đã ký của hệ thống cũ được giữ nguyên.

Hạn phản hồi mặc định **48 giờ**, cấu hình `rental.resolution-response-hours=${RENTAL_RESOLUTION_RESPONSE_HOURS:48}`. Sau hạn, chủ nhà có thể kết thúc đề nghị do mình gửi bằng thao tác riêng, chỉ khi xác nhận đã liên hệ khách và phòng chưa giao/đã nhận lại đầy đủ, đồng thời hoàn toàn bộ tiền. Không có xử lý tiền tự động khi hết giờ. Khách đã phản đối thì không đi qua nhánh im lặng; dùng hồ sơ khiếu nại có sẵn.

Không cần giả định HĐ cọc đã được lập/ký để mở đường hoàn tiền. Trước khi kết thúc, hệ thống kiểm tra lại tiền thực nhận, sổ cọc, chữ ký HĐ cọc và HĐ thuê, lượt giữ phòng và phiên bản. Chữ ký đang xử lý, kết quả provider chưa rõ, chữ ký khách đã có hoặc chứng từ đang đối soát đều chặn việc giải phóng phòng. Kết quả ký không rõ cần đối soát qua hệ thống ký hiện có; không có nút bỏ qua chữ ký.

Kết thúc hợp lệ hủy HĐ thuê còn dang dở và các đề nghị đổi ngày chưa áp dụng, trả chỗ xe và đúng lượt giữ phòng. HĐ cọc đã ký và PDF/chữ ký một bên được giữ. Tạo nghĩa vụ hoàn không phải đã trả tiền; tenant vẫn phải xác nhận thực nhận qua thanh toán, gồm đường tranh chấp giai đoạn 02.

## 2. Biên bản nhận nhà

Biên bản gắn yêu cầu thuê, HĐ thuê và revision nguồn, gồm ngày giờ thực tế, số chìa khóa, hiện trạng phòng, từng tài sản/số lượng/tình trạng, ảnh/PDF và chỉ số điện/nước thực tế. Chỉ lập sau HĐ thuê đã ký đủ; không cho ghi một lần nhận nhà trong tương lai hoặc trước ngày thuê.

Chủ nhà gửi bản để đối chiếu. Hai bên xác nhận riêng đúng phiên bản; một xác nhận chưa hoàn tất nhận nhà. Chỉnh lại biên bản tạo lượt phiên bản mới và xóa xác nhận cũ của bản hiện tại, đồng thời giữ toàn bộ nội dung/xác nhận/bằng chứng trước đó trong lịch sử. Người thuê có thể báo không được giao nhà/sai hiện trạng và mở hồ sơ HANDOVER để tiếp tục xử lý với chủ nhà, sau đó chuyển HomeSpace theo luồng 04.

Ngày nhận thực tế không sửa ngày thuê hay hóa đơn đã phát hành. Chỉ số thực tế chỉ ghi bằng chứng, **không ghi đè** công tơ trong văn bản đã ký hoặc đường tính hóa đơn. Nếu cần điều chỉnh nguồn thu, tiếp tục dùng quy trình điều chỉnh/quyết toán có lịch sử; không áp dụng âm thầm từ biên bản.

HĐ cọc hiển thị riêng nghĩa vụ giữ chỗ, chờ nhận nhà, chuyển tiếp sang HĐ thuê, hoàn/giữ lại/đối soát, cùng đường dẫn HĐ thuê liên kết. Chữ ký HĐ thuê không được dùng thay bằng chứng đã trao chìa khóa. Hồ sơ cũ không có biên bản được ghi nhận là chưa đủ xác nhận, không tự backfill đã nhận.

## 3. Chấm dứt sớm theo thỏa thuận

Tenant hoặc landlord gửi được đề nghị khi HĐ đang hiệu lực, **không cần có nợ quá hạn**. Nội dung có ngày kết thúc (exclusive), lý do, cách trả nhà/tài sản, chính sách kỳ cuối và danh sách hoàn tiền thuê nếu có. Bên kia có thể đồng ý, từ chối hoặc gửi bản thay thế; người đề nghị được rút khi chưa đồng ý. Mỗi thao tác đối chiếu phiên bản đã xem.

Chính sách được ghi rõ và hai bên chấp thuận: **tính trọn kỳ thuê và phí cố định đã bắt đầu**, không tự chia theo ngày. Nếu thỏa thuận hoàn tiền thuê, chọn khoản thực nhận và số tiền riêng cho từng nguồn. Backend chỉ chấp nhận nguồn INITIAL/MONTHLY_RENT đúng hồ sơ đã CONFIRMED, không vượt phần RENT, không trùng hoặc đã có nghĩa vụ hoàn. Tiền thuê và cọc không trộn vào nhau.

Khi đồng ý, lưu ngày kết thúc và số kỳ tính phí riêng trên nguồn vận hành; không sửa ngày/thời hạn của HĐ đã ký hay receipt cũ. Hóa đơn cuối đang nháp được chốt đến ngày mới; không sinh kỳ sau ngày đã thống nhất. Không cắt/sửa hóa đơn đã phát hành: ngày đề nghị không được cắt qua hóa đơn đó. Hóa đơn/nợ hiện có vẫn phải thanh toán và xác nhận, không xóa nợ bằng việc đồng ý chấm dứt.

Phụ lục/gia hạn đang chờ (kể cả phụ lục đã ký chưa áp dụng) và chênh lệch cọc chưa đối soát phải xử lý trước. Đề nghị chấm dứt đang mở chặn tạo/ký gia hạn và phụ lục mới; không hủy các văn bản đó một phía. Giữ nguyên luồng chấm dứt do nợ đã có, không ép dữ liệu nợ để chạy nhánh mới.

Đến ngày trả nhà, hai bên xác nhận thực tế riêng. Chưa đủ hai xác nhận hoặc đang có khiếu nại chấm dứt thì chưa được quyết toán cọc. Sau đó tiếp tục quy trình hóa đơn cuối → đề xuất khấu trừ có căn cứ → tenant đồng ý → nghĩa vụ hoàn/trả thêm → thực nhận của giai đoạn 02–04. **Cọc không tự mất toàn bộ.** Khoản hoàn tiền thuê theo thỏa thuận phải được nhận đủ trước khi chốt cọc.

Listing/xe được giải phóng ở bước chấp thuận quyết toán bàn giao hiện có, không giải phóng chỉ vì đồng ý ngày kết thúc hoặc chỉ một bên khai đã trả nhà. Tiền hoàn còn theo dõi độc lập, chưa đánh dấu hết nghĩa vụ chỉ vì đã trả nhà.

## 4. Tính toàn vẹn và giao diện

Mỗi loại workflow có một aggregate riêng theo yêu cầu thuê, khóa nguồn rental/contract theo thứ tự của signing/billing, refresh dữ liệu sau khi chờ khóa và kiểm tra revision. Lịch sử chụp nội dung tại mỗi lần thực hiện, không ghi đè bản đã được đối chiếu. Lỗi tạo nghĩa vụ, ghi lịch sử hoặc trả phòng làm transaction rollback.

Chứng cứ PRIVATE/READY, ảnh JPEG/PNG/WebP hoặc PDF, tối đa 5 tệp, 15 MB/tệp, đúng người và yêu cầu thuê. Chỉ file đã gắn vào lịch sử thật mới được chia sẻ; không cho xóa bằng chứng đã dùng kể cả sau chỉnh/đóng. Lịch sử hỏng từ chối cấp quyền đọc và giữ bảo vệ xóa.

Web có các mục gấp/mở tại onboarding/yêu cầu thuê, HĐ và hồ sơ thanh toán. Nút lấy quyền từ backend; mọi xác nhận nhạy cảm mặc định chưa tích. Tải lại không xóa nội dung phản hồi, lỗi phiên bản không tự retry POST. Component tách scope theo tài khoản/yêu cầu/loại hồ sơ; phản hồi mạng muộn không xóa ghi chú của hồ sơ khác. Bảng tài sản và từng khoản hoàn được hiển thị có cấu trúc.

Không tạo loại tiền/chữ ký giả, không có thao tác admin nhận tiền thay người nhận, không sửa PDF/HĐ cũ. Các hồ sơ khiếu nại mở từ ba task dùng lại màn hình và quy trình 04.

## 5. API

Mọi route có bản `/api/v1` qua gateway và bản core không tiền tố, xác thực actor từ context, không nhận actorId của client.

| Route dưới `/rental-requests/{id}/workflow` | GET | POST đề xuất | POST hành động |
|---|---|---|---|
| `/cancellation` | Tiến trình trước thuê và các khoản hoàn | Lý do/chứng cứ/phiên bản | `/ACCEPT`, `/REJECT`, `/WITHDRAW`, `/FULL_REFUND_EXIT`, `/ESCALATE` |
| `/move-in` | Biên bản, nguồn, xác nhận, lịch sử | Ngày/hiện trạng/tài sản/công tơ/chứng cứ | `/CONFIRM`, `/REPORT_ISSUE` |
| `/early-exit` | Thỏa thuận, trả nhà, tiền thuê hoàn | Ngày/cách bàn giao/chính sách/nguồn tiền hoàn | `/ACCEPT`, `/REJECT`, `/WITHDRAW`, `/CONFIRM_RETURN`, `/REPORT_ISSUE` |

Hành động yêu cầu `expectedRevision`, lý do và xác nhận phù hợp. Đề xuất thay thế dùng revision mới nhất; chấp thuận cũ bị từ chối. Chi tiết thanh toán/đối soát/khiếu nại vẫn dùng API cũ.

## 6. Kiểm thử và giới hạn

| Kiểm tra cuối | Kết quả |
|---|---|
| Backend listing/payment/contract/API | **710 ca được báo cáo: 709 đạt, 1 bỏ qua**, không failure/error, BUILD SUCCESS |
| PostgreSQL thực, nằm trong tổng backend | **127/127 đạt**: 92 ca hồi quy cũ và **35 ca workflow mới** |
| API mới, nằm trong tổng backend | **4/4 đạt**: danh tính context, anonymous, validation/revision, dữ liệu biên bản/chính sách kỳ cuối |
| Web hợp đồng/cọc/thuê/hóa đơn/thanh toán/khiếu nại/preview | **195/195 đạt**, gồm **13 ca handler/render mới** |
| TypeScript web (`tsc --noEmit --incremental false`) | Đạt |
| ESLint toàn bộ file web sửa/thêm trong ba task | Đạt, không warning |
| `git diff --check` core và web | Đạt |

Ca bỏ qua duy nhất là chẩn đoán SmartCA cần CCCD/chứng thư thật. Không bỏ qua kiểm thử đang lỗi. Không cộng trùng các lượt chạy riêng vào tổng. Kiểm thử admin của 04 không bị sửa; các payload/queue khiếu nại admin giữ nguyên, không tự công bố đã chạy lại build/admin trong lượt này.

Lượt cuối backend chạy bằng Maven profile `isolated-build`, tránh đụng output IDE. Log trong `%TEMP%`: `hs-workflow-core-final.log`, `hs-workflow-web-regression.log`, `hs-workflow-web-new.log`, `hs-workflow-web-types.log`, `hs-workflow-web-lint.log`.

Đã mô phỏng: không có HĐ cọc, HĐ cọc đã ký, tiền thuê/cọc riêng, ranh giới hạn phản hồi, yêu cầu liên hệ/phòng trống, người thứ ba/tự đồng ý/bản cũ, trạng thái ký provider chưa rõ, chứng từ đang xử lý, rollback trả phòng/audit, hai xác nhận nhận nhà và sửa lại, sai người upload, bằng chứng hỏng, ngày nhận không hợp lệ, chấm dứt không nợ, nguồn hoàn tiền thuê, hóa đơn vượt cutoff, khoản gia hạn chưa xử lý, hai xác nhận trả nhà, chặn quyết toán khiếu nại, thực nhận cọc và đóng HĐ, scheduler không sinh kỳ ngoài thỏa thuận, hai lần chấp thuận hủy đồng thời chỉ tạo một nghĩa vụ hoàn và trả phòng một lần.

Đã tạo sáu PostgreSQL tạm localhost cho `hs_workflow_test`, `hs_phase01_test`, `hs_contract_test`, `hs_phase02_test`, `hs_phase03_test`, `hs_phase04_test`; không đọc/sửa/seed/drop DB vận hành. Sau khi hoàn tất, đã kiểm tra đúng ID/tên và dọn cả sáu container cùng volume tạm. Không gọi ngân hàng, S3 hoặc SmartCA thật. Chưa phải E2E với hai tài khoản và nhà cung cấp thật của giai đoạn 09; không cam kết đã mô phỏng mọi tình huống production.

Schema mới gồm bảng `rental_workflow_records` và các cột nullable trên rental/contract; không backfill/sửa hàng loạt hồ sơ cũ. Cần áp dụng schema qua cấu hình JPA/quy trình triển khai của môi trường trước khi chạy API mới; lượt này không chạy DDL trên DB vận hành.

Không commit/push, deploy hoặc restart dịch vụ vận hành. Hoàn tất ba task được giao và bàn giao tại đây; không triển khai thông báo hay tự thực hiện các hạng mục ngoài phạm vi.

## Rà soát khoản hoàn trước thuê — 06/11/2026

- Ca không đặt cọc, đã xác nhận tiền thuê tháng đầu 1.000.000đ, hai bên đồng ý hủy: tổng cần hoàn 1.000.000đ là đúng. Hai dòng thuê/cọc 0đ trong modal là lỗi hiển thị: nghĩa vụ hoàn chưa lưu line items, còn UI dùng dữ liệu yêu cầu thuê không được truyền đầy đủ để dựng chi tiết.
- Nghĩa vụ hoàn mới đóng băng các dòng từ chứng từ đã nhận: hoàn thuê, hoàn cọc hoặc cả hai; tổng các dòng bằng tổng nghĩa vụ/QR, không lấy giá Listing hiện tại. Hoàn riêng tiền thuê không tác động cọc giữ lại. Không tạo nghĩa vụ/dòng hoàn 0đ. Khoản gốc và chứng từ đã xác nhận giữ nguyên; gọi lặp không tạo thêm khoản hoàn hoặc dòng tiền.
- Khoản hoàn lịch sử thiếu chi tiết được UI hiển thị một dòng theo tổng tiền của PaymentRequest và ghi rõ thiếu phân bổ; không tự dựng hai dòng 0đ hoặc đoán lại từ hồ sơ thuê. Nội dung và người nhận trên modal đi theo chiều chuyển: chủ nhà hoàn cho người thuê. Chỉ payer thực tế được mở/gửi báo chuyển; người thuê là bên xác nhận thực nhận khoản hoàn.
- Sổ cọc trước đây bỏ qua INITIAL_PAYMENT_REFUND nên có thể ghi REFUNDED nhưng số thực hoàn không xác định dù đã có xác nhận. Đã nhận diện khoản hoàn toàn bộ của đúng chứng từ nguồn, đúng bên gửi/nhận và đúng liên kết sổ; chỉ cộng phần cọc gốc, không cộng cả tiền thuê của khoản gộp. Chưa xác nhận, sai nguồn/bên nhận, khoản hoàn thuê riêng hoặc không liên kết không chứng minh đã hoàn cọc.
- Kiểm thử **101 ca backend**, **52 ca PostgreSQL thật** trên container tạm `hs_workflow_test` và **42 ca web** đều qua; TypeScript và ESLint các file thay đổi đạt. PostgreSQL kiểm tra ca không cọc 1.000.000đ, hoàn gộp tiền thuê/cọc, đối soát, giữ phòng đến khi người thuê xác nhận, chống gọi lặp và các luồng bàn giao/chấm dứt đang có. Test giao diện dùng API giả lập; không gọi ngân hàng, S3 hoặc SmartCA thật.
- Không sửa dữ liệu/tổng tiền/chứng từ vận hành, không cần reset/seed/backfill CSDL hoặc thêm schema mới. Khởi động lại Core và làm mới web để nhận sửa đổi; container kiểm thử đã được kiểm tra đúng ID/nhãn và dọn sau kiểm thử.
