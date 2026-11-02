# Bàn giao giai đoạn 03 — Sổ cọc và gửi lại đề nghị gia hạn

Ngày: **03/11/2026**. Phạm vi được duyệt: **giai đoạn 03 / F03, F04**. Trạng thái: **đã triển khai và đạt regression, dừng chờ nghiệm thu**; chưa triển khai giai đoạn 04.

## 1. Sổ cọc khi quyết toán

`acceptClosure` nay ghi `retainedAmount` theo phần khấu trừ thực sự lấy từ tiền cọc đang giữ. Khấu trừ một phần và toàn bộ đều lưu căn cứ, thời điểm, cùng lịch sử chấp thuận phiên bản quyết toán. Không sửa chứng từ cọc ban đầu và không coi nghĩa vụ mới tạo là khoản đã thu/đã hoàn.

| Cọc đang giữ | Tổng khấu trừ được đồng ý | Ghi giữ lại từ cọc | Nghĩa vụ hoàn | Khoản người thuê trả thêm |
|---|---|---|---|---|
| 20.000.000 đ | 0 đ | 0 đ | 20.000.000 đ | 0 đ |
| 20.000.000 đ | 100.000 đ | 100.000 đ | 19.900.000 đ | 0 đ |
| 20.000.000 đ | 20.000.000 đ | 20.000.000 đ | 0 đ | 0 đ |
| 20.000.000 đ | 21.000.000 đ | 20.000.000 đ | 0 đ | 1.000.000 đ |

Trước chấp thuận, hệ thống đối chiếu lại tổng các khoản khấu trừ, số tiền cọc đang giữ, tiền hoàn và khoản trả thêm với đúng phiên bản đề xuất. Nếu sổ cọc đã thay đổi thì yêu cầu lập lại đề xuất để hai bên xem; không âm thầm dùng phép tính cũ làm mất tiền. Khoản bị từ chối/tranh chấp tiếp tục qua luồng giai đoạn 02. Chỉ người nhận xác nhận thực nhận mới cập nhật số đã hoàn và hoàn tất nghĩa vụ tương ứng.

Giữ khóa hợp đồng gốc và đọc lại dữ liệu sau khi chờ khóa. Test PostgreSQL kiểm tra hai lần chấp thuận đồng thời, hồ sơ đã đọc bị cũ, ghi giữ cọc đúng một lần, lỗi sau khi tạo nghĩa vụ hoàn rollback cả sổ cọc/quyết toán/khoản hoàn, rồi retry thành công.

Lý do giữ cọc trước đây giới hạn 500 ký tự trong DB trong khi ghi chú quyết toán cho phép 2.000 ký tự. Mapping `DepositRecord.retainedReason` được mở rộng thành 2.000 để giữ đầy đủ căn cứ; test thực lưu hơn 500 ký tự và đọc lại nguyên nội dung. Không tạo script SQL riêng hoặc chạy sửa DB vận hành.

## 2. Cách đọc sổ cọc thống nhất

Thêm `DepositLedgerService` / `DepositLedgerView`, dùng tại tiến trình onboarding, API vòng đời hợp đồng và chi tiết hợp đồng admin. Web và admin dùng cùng định nghĩa hiển thị các giá trị:

- Cọc thực nhận đã được xác nhận: lấy phần cọc của khoản ban đầu đã xác nhận, cộng các khoản bổ sung cọc đã xác nhận. Khoản cọc + tháng đầu chuyển chung không bị tính toàn bộ thành cọc.
- Cọc còn đang giữ: số dư hiện tại trong sổ.
- Cọc đã chốt khấu trừ/giữ lại: số đã ghi, kèm thời điểm và căn cứ.
- Cọc có thể hoàn/chưa chốt hoặc cọc còn phải hoàn theo sổ: phân biệt theo trạng thái nghiệp vụ.
- Cọc đã hoàn có xác nhận của người nhận: cộng hoàn chênh lệch và khoản hoàn quyết toán đã xác nhận, không cộng khoản mới tạo/đã báo chuyển/đang tranh chấp.

Kiểm tra cân đối có cả bổ sung cọc, hoàn chênh lệch, khấu trừ rồi hoàn cuối kỳ. Ví dụ: nhận cọc 20 triệu, bổ sung 3 triệu, đã hoàn chênh lệch 2 triệu, khấu trừ 100 nghìn, hoàn cuối 20,9 triệu ⇒ thực nhận cọc 23 triệu = đã hoàn 22,9 triệu + đã giữ lại 100 nghìn.

`null` có nghĩa chưa đủ dữ liệu. Không đổi thành 0 hoặc tự suy ra đã hoàn. Nếu không có sổ cọc, API trả `deposit = null`, `heldDeposit = null`, không mở quyền lập quyết toán; giao diện nêu thiếu sổ để đối chiếu. Dữ liệu cũ ghi `REFUNDED` mà thiếu chứng từ khớp không được hiển thị số tiền hoàn giả. API admin hiện có tiếp tục kiểm tra quyền trước khi trả số liệu; không thêm endpoint sửa tay số dư.

Các hồ sơ **đã chấp thuận trước bản sửa** nhưng thiếu `retainedAmount` không được tự backfill bằng phỏng đoán. Giao diện để rõ chưa có dữ liệu. Việc sửa số liệu lịch sử cần đối chiếu hồ sơ/chứng từ cụ thể; giai đoạn này không chạy cập nhật dữ liệu cũ hàng loạt.

## 3. Gửi lại gia hạn đúng điều kiện

Điều kiện chặn cùng kỳ nay xét trạng thái đề nghị. `REJECTED` và `EXPIRED` cho phép gửi một đề nghị mới khi hợp đồng còn hiệu lực, vẫn trong cửa sổ gia hạn và chưa qua hạn ký. Đề nghị mới có ID mới; giữ nguyên thời hạn, nội dung và trạng thái của bản cũ.

Các chốt đang có vẫn giữ: chỉ người thuê gửi; không có phụ lục đang xử lý; không đang chấm dứt; không có đề nghị `REQUESTED`/`APPROVED` hoặc bản đã áp dụng cho cùng kỳ; giới hạn tháng thuê, cửa sổ 30 ngày và hạn ký theo lịch hiện có. Từ chối 3 tháng có thể gửi lại 1 tháng. Hết 48 giờ phản hồi có thể gửi lại nếu vẫn trước hạn ký; đến đúng hạn ký thì bị chặn.

Khóa hợp đồng gốc tiếp tục tuần tự hóa gửi, duyệt và áp dụng chữ ký; đọc lại cả đề nghị và hợp đồng trong persistence context để không dùng object cũ sau khi chờ khóa. Chỉ một đề nghị chờ/được duyệt và một văn bản được tạo bởi các thao tác đồng thời. Callback ký lặp chỉ áp dụng thời hạn/giữ xe một lần. Callback của đề nghị cũ đã hết hạn không áp dụng cho bản thay thế.

Gia hạn không thu lại cọc/tháng đầu, không sửa văn bản gốc, chưa kéo dài lịch thuê khi mới gửi hoặc duyệt. Các bước giữ/đổi xe theo gia hạn hiện có được gọi đúng lúc; không giải phóng chỗ xe của hợp đồng gốc vì một bản gia hạn hết hạn.

Web mở form theo `canRequestRenewal` từ backend, giải thích khả năng gửi lại, giữ danh sách các bản bị từ chối/hết hạn và nội dung/thời điểm chủ nhà đã phản hồi. Không tự sửa trạng thái cũ để dùng lại.

## 4. Phạm vi API và giao diện

Không đổi route thanh toán/gia hạn/quyết toán hiện có. Các thay đổi response:

| Nơi đọc | Dữ liệu thêm/chuẩn hóa |
|---|---|
| Onboarding | `deposit` dùng `DepositLedgerView`, giữ các tên trường sổ cọc trước đây và bổ sung số thực nhận/thực hoàn, thời điểm giữ cọc |
| Lifecycle | Thêm `deposit`; `heldDeposit` là null nếu thiếu sổ, quyền đề xuất/gia hạn khớp điều kiện backend |
| Admin: chi tiết hợp đồng | Thêm `depositLedger` qua API chi tiết đã bảo vệ bởi quyền admin |

`DepositLedgerSummary` được dùng cho web; admin dùng cùng cách trình bày. Khi mở chi tiết admin, dữ liệu sổ cọc lấy lại từ API chi tiết, không lấy số tiền cọc trên snapshot văn bản làm số dư. Kết quả của yêu cầu mở cũ không ghi đè hợp đồng vừa mở sau; tải thất bại không để lại chi tiết hợp đồng trước.

## 5. Kiểm thử

Lượt cuối backend: **BUILD SUCCESS**; 0 failure/error.

| Kiểm tra | Kết quả cuối |
|---|---|
| Backend: listing, payment, contract và API liên quan | **634 ca được báo cáo: 633 đạt, 1 bỏ qua** |
| PostgreSQL thực, nằm trong tổng backend | **59/59 đạt**: 5 chấp thuận thuê, 3 kho HĐ cọc, 6 bản nháp, 14 onboarding, 14 tranh chấp và 17 giai đoạn 03 |
| Giai đoạn 03 mới, nằm trong tổng backend | **25/25 đạt**: 17 PostgreSQL, 6 cách đọc sổ cọc, 1 response admin, 1 thiếu sổ không bằng cọc 0 |
| Web: hồi quy hợp đồng, hóa đơn, thanh toán, cọc và onboarding | **169/169 đạt** |
| Admin: đọc sổ cọc và hồi quy đối soát thanh toán | **9/9 đạt** |
| TypeScript web `--noEmit --incremental false` | Đạt |
| Build admin (gồm typecheck) | Đạt; còn cảnh báo kích thước chunk có sẵn |
| ESLint các file triển khai sửa/thêm của giai đoạn 03 | Đạt |
| Kiểm tra diff ba repo | Đạt |

Ca bỏ qua duy nhất là chẩn đoán SmartCA cần CCCD/chứng thư/dịch vụ thật. Không bỏ qua một ca hồi quy lỗi để lấy kết quả đạt. Các test hiện có chỉ bổ sung wiring dependency/component mới; giữ assertion cũ, có suite riêng kiểm tra hành vi mới.

Các bộ test bao gồm:

| Nhóm | Ca chính |
|---|---|
| Quyết toán | Không khấu trừ; một phần/toàn bộ; vượt cọc; tiền hoàn/trả thêm tách đúng; chấp thuận lặp hoặc đồng thời |
| Xác nhận tiền | Hoàn bị từ chối → tranh chấp → hai bên thống nhất → bên nhận xác nhận; không đóng quyết toán trước thực nhận |
| Cân đối tiền | Cọc ban đầu chuyển chung với tháng đầu; bổ sung/hoàn chênh lệch; giữ lại + số dư + thực hoàn; bỏ các khoản chưa xác nhận/không đúng nguồn |
| Dữ liệu cũ | Thiếu sổ không bằng cọc 0; thiếu số giữ lại không thành 0; nhãn hoàn nhưng thiếu chứng từ phải rõ chưa đủ dữ liệu |
| Transaction | Lỗi sau tạo khoản hoàn rollback; retry; object đã đọc bị cũ; hai lần chấp thuận chỉ một khoản hoàn |
| Gia hạn | Từ chối 3 tháng gửi lại 1 tháng; hết hạn phản hồi còn cửa sổ; chặn đúng cutoff; phụ lục/chấm dứt đang xử lý |
| Đồng thời/chữ ký | Hai yêu cầu, hai lần duyệt, hai callback ký; callback đề nghị cũ không áp dụng lên bản mới; không thu lại cọc/tháng đầu |
| Web/admin | Nội dung và số tiền đúng; backend điều khiển quyền gửi lại; lịch sử không mất; không giả số 0; admin không hiển thị nhầm sổ của hợp đồng trước |

PostgreSQL được chạy riêng cho từng nhóm: `hs_phase01_test`, `hs_contract_test`, `hs_phase02_test`, `hs_phase03_test` trên container tạm localhost. Không dùng URL hay thay đổi DB vận hành. Các test mới dùng service, bảng tiền/quyết toán/gia hạn và transaction thật; collaborator ngân hàng/QR, listing và parking được mock để tránh tác động bên ngoài và kiểm tra lỗi/đếm lượt gọi. Các suite listing/parking, chữ ký, hóa đơn và hợp đồng cọc hiện có vẫn được chạy hồi quy.

Kiểm thử này không phải E2E trình duyệt/ngân hàng/SmartCA thật. Không có cam kết mọi tình huống production đã được mô phỏng. Các giới hạn đã ghi ở giai đoạn 02 tiếp tục áp dụng.

Log cuối ở `%TEMP%`: `hs-phase03-core-final.log`, `hs-phase03-web.log`, `hs-phase03-web-types.log`, `hs-phase03-web-lint.log`, `hs-phase03-admin-tests.log`, `hs-phase03-admin-build.log`, `hs-phase03-admin-lint.log`. Các container DB kiểm thử được dọn sau khi chốt kết quả.

## 6. Điểm dừng

Chưa làm giai đoạn 04–09: khiếu nại tổng quát tenant → landlord → admin, SLA/thông báo, cọc đã nhận nhưng không ký/nhận nhà, biên bản bàn giao và chấm dứt sớm theo thỏa thuận. Không đổi chính sách ngoại lệ cọc 0 của giai đoạn 00.

Giữ các thay đổi giai đoạn 01, 02 và PDF viewer có sẵn. Không commit/push, deploy hay restart dịch vụ đang vận hành. **Dừng tại đây, chỉ làm giai đoạn 04 khi chủ dự án đồng ý.**
