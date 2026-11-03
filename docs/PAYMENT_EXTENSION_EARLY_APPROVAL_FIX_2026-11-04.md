# Sửa duyệt gia hạn thanh toán trước hạn

> Báo cáo lịch sử: thay đổi tiếp theo giảm trần phạt xuống **5 ngày**, bổ sung đơn phương chấm dứt với cửa sổ khiếu nại **24 giờ**. Xem [quy tắc hiện hành](OVERDUE_5_DAY_UNILATERAL_24H_2026-11-04.md); không dùng mức trần 10 ngày trong báo cáo cũ cho quyết định mới.


**Cập nhật tiếp cùng ngày:** cách xử lý phí sau hạn gia hạn đã được sửa riêng trong [báo cáo phí sau gia hạn](PAYMENT_EXTENSION_LATE_FEE_RESUMPTION_2026-11-04.md). Quyết định duyệt mới chỉ ngừng tăng phí trong hạn gia hạn; sau hạn mới tiếp tục cộng phí theo hợp đồng và trần chung của hóa đơn. Bằng chứng bên dưới là kết quả của bản sửa hạn trước đó, không phải kết quả kiểm thử cuối của thay đổi phí này. Quyết định miễn tăng phí cũ không bị truy thu.

Ngày: **04/11/2026**. Phạm vi: yêu cầu gia hạn thanh toán MonthlyInvoice trong ảnh người dùng, không phải gia hạn thời gian thuê hoặc mở thêm nghiệp vụ gia hạn khoản INITIAL trước ký.

## Nguyên nhân

Đồng hồ nghiệp vụ giả lập `2026-12-04T10:00:00` không có lỗi chuyển múi giờ trong ca này. Chuỗi không có offset được `BillingTime` hiểu theo giờ Việt Nam. Hạn phản hồi trong ảnh là 06/12/2026 10:00, khớp 48 giờ sau lúc gửi ngày 04/12.

Code cũ tính `newDue = approvedAt + days`, rồi chặn nếu `newDue <= currentDue`. Với hóa đơn hạn 08/12/2026 23:59:59 và xin 1 ngày, duyệt lúc 04/12/2026 10:00 cho ra 05/12/2026 10:00, sớm hơn hạn cũ. Vì vậy guard chống rút ngắn hạn từ chối. Màn hình lại cho gửi yêu cầu và hiển thị nút đồng ý; người dùng phải đợi/chọn số ngày lớn hơn thay vì được gia hạn đúng nghĩa.

## Hành vi sau sửa

**Hạn mới = max(hạn thanh toán hiện tại, thời điểm duyệt) + số ngày được duyệt.**

| Trường hợp | Kết quả |
|---|---|
| Duyệt 04/12/2026 10:00, hạn hiện tại 08/12/2026 23:59:59, thêm 1 ngày | Hạn mới **09/12/2026 23:59:59** |
| Duyệt 10/12/2026 10:00, hạn hiện tại 08/12/2026 23:59:59, thêm 1 ngày | Hạn mới **11/12/2026 10:00** |
| Duyệt đúng lúc hết hạn | Cộng số ngày vào mốc đó; không rút ngắn thời gian trả tiền |
| Hóa đơn phát hành sau thời điểm hiện tại do lùi ngày giả lập | Chặn gửi/duyệt/dời trực tiếp và giải thích cần kiểm tra thời gian; không thay lịch sử tương lai |

Đã cập nhật hướng dẫn trên PaymentExtensionPanel, tài liệu MONTHLY_BILLING/SIMULATED_TIME, các mốc UC07/UC08/UC25 và nguồn RAG 07. Nguồn RAG mới ở trạng thái draft trên đĩa, chưa ingest runtime.

Giữ nguyên: khoảng 1–30 ngày, quyền tenant/landlord, hạn phản hồi 48 giờ, khóa contract → invoice → payment, chặn khi báo chuyển/đối soát/đã nhận tiền, chống duyệt lặp, tiền/phí đã chốt, cách tính khi đã quá hạn, hạn gốc và lịch sử. Chỉ lượt duyệt mới dùng quy tắc mới; không tính lại các yêu cầu đã APPROVED hoặc sửa giao dịch đã thanh toán.

## Bằng chứng kiểm thử

| Bộ kiểm tra | Kết quả cuối |
|---|---|
| Maven: BillingTime, PaymentRequestService, MonthlyBillingService, MonthlyBillingSchedule, ContractLifecycleService và PostgreSQL lifecycle | **165/165 đạt**, 0 failure/error/skipped, BUILD SUCCESS |
| PostgreSQL trong tổng Maven | **23/23 đạt**, gồm 2 ca mới duyệt trước hạn/quá hạn với MonthlyBillingService và PaymentRequestService thật |
| Frontend: payment-extension, contract-lifecycle, payment-dispute | **38/38 đạt** |
| TypeScript và ESLint PaymentExtensionPanel | Đạt |

Unit tái hiện đúng số tiền 3.665.000đ, mốc giả lập 04/12/2026 10:00, hạn gốc 08/12, thêm 1 ngày; kiểm tra hạn phản hồi, hạn mới, phí 0 và không tăng tổng tiền. Có thêm ca trước/đúng/sau hạn, duyệt 30 ngày khi đã quá hạn, chặn lùi thời gian trước phát hành, từ chối/hết hạn/tính bù phí, giữ hạn đã duyệt khi scheduler chạy bù.

Hai ca PG mới kiểm tra hóa đơn và PaymentRequest cùng lưu hạn mới, giữ số tiền/QR hiện có, ghi lịch sử APPROVED và chặn duyệt lại cùng request. Các ca cũ về quyết toán, sổ cọc, gia hạn thuê và race transaction tiếp tục đạt. Một assertion cũ kiểm tra scheduler bảo toàn hạn đã duyệt được cập nhật theo mốc mới của nhánh duyệt trước hạn; không đổi logic scheduler để làm test đạt.

Log ở `%TEMP%`:

- `hs-payment-extension-early-core-final-20261104.log` — Maven cuối.
- `hs-payment-extension-early-web-20261104.log` — 38 test frontend.
- `hs-payment-extension-early-types-20261104.log`, `hs-payment-extension-early-lint-20261104.log` — kiểm tra kiểu/lint.

Lệnh Maven cuối:

```powershell
mvn -Pisolated-build -pl hs-api-service -am '-Dtest=MonthlyBillingServiceTest,ContractLifecyclePhase03PostgresTest,MonthlyBillingScheduleTest,BillingTimeTest,PaymentRequestServiceTest,ContractLifecycleServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

PG dùng container riêng `hs-payment-extension-test-20261104`, loopback:55448, DB `hs_phase03_test`, theo guard opt-in của suite. Container/volume thử được dọn theo đúng ID/nhãn đã xác minh. Không thay CSDL vận hành, không restart dịch vụ người dùng hoặc ingest RAG. Các chỉnh sửa `.env.example` và `application.properties` có sẵn từ đầu lượt được giữ nguyên.

## Thử lại trên môi trường đang chạy

Build/restart Core bằng cách khởi động thường dùng để nạp code mới; bản build kiểm thử dùng profile isolated-build nên không thay lớp ứng dụng đang chạy trong IDE. Với ngày giả lập vẫn là 04/12/2026 10:00, có thể bấm đồng ý lại **yêu cầu PENDING hiện tại nếu còn hạn phản hồi**; không cần xóa/sửa DB hoặc tạo lại hồ sơ. Hạn mới cần hiện 09/12/2026 23:59:59 trên cả hai phía.

Nếu thời gian đã tới hoặc vượt 06/12/2026 10:00, yêu cầu trong ảnh đã hết hạn phản hồi; bản sửa không hồi sinh yêu cầu đã EXPIRED hoặc đã được xử lý. Kiểm thử trên hồ sơ thử khác hoặc dùng thao tác nghiệp vụ còn hợp lệ trên giao diện.
