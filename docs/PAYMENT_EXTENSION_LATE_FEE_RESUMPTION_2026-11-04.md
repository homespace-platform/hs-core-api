# Phạt tiếp khi trả trễ sau gia hạn thanh toán

> Báo cáo lịch sử: thay đổi tiếp theo giảm trần phạt xuống **5 ngày**, bổ sung đơn phương chấm dứt với cửa sổ khiếu nại **24 giờ**. Xem [quy tắc hiện hành](OVERDUE_5_DAY_UNILATERAL_24H_2026-11-04.md); không dùng mức trần 10 ngày trong báo cáo cũ cho quyết định mới.


Ngày: **04/11/2026**. Phạm vi: MonthlyInvoice và PaymentRequest tiền thuê định kỳ. Không mở gia hạn khoản INITIAL trước ký hoặc thay đổi hệ thống thông báo.

## Lỗi và kết quả sau sửa

Trước bản sửa, duyệt gia hạn đặt `lateFeeExempt=true` vĩnh viễn cho hóa đơn: kể cả bỏ qua hạn mới, tiền phạt không tăng. Quyết định duyệt mới chỉ tạm dừng tăng phí trong thời gian gia hạn; sau hạn mới tiếp tục tính phí theo điều khoản đã đóng băng khi phát hành hóa đơn.

| Tình huống | Hành vi |
|---|---|
| Đã quá hạn, được duyệt thêm 5 ngày | Hạn mới = thời điểm chủ nhà duyệt + 5 ngày |
| Chưa quá hạn, được duyệt thêm 5 ngày | Hạn mới = hạn hiện tại + 5 ngày; không rút ngắn hạn |
| Đang chờ chủ nhà duyệt | Tạm dừng tăng phí; bị từ chối/hết hạn thì tính bù theo hạn hiện tại |
| Được duyệt và vẫn trong hạn mới | Giữ khoản phạt đã chốt lúc gửi yêu cầu, không phạt các ngày được gia hạn |
| Đã phạt 300.000đ, thêm 5 ngày, rồi trễ tiếp 1 ngày ở mức 100.000đ/ngày | Tổng phạt 400.000đ; không quay lại tính toàn bộ quãng thời gian từ hạn gốc |
| Gia hạn nhiều lần | Mỗi lần giữ phí thực tế trước đó, chỉ cộng ngày mới sau hạn mới; trần không đặt lại |
| Phạt theo ngày | Tổng tối đa mức/ngày × 10 ngày cho một hóa đơn; giữ trần thấp hơn trong bản ký cũ |
| Phạt một lần / không phạt | Không thu lần thứ hai / không tạo phí |
| Báo chuyển khoản hoặc tranh chấp | Giữ phí để đối soát; chứng từ bị từ chối thì cập nhật theo hạn được duyệt và giới hạn cũ |
| Đã xác nhận tiền / đã chốt chuyển nợ | Không tiếp tục tăng phí cho hóa đơn đó |

Số ngày phạt vẫn tính theo ngày lịch Việt Nam của chính sách hiện có, không đổi thành làm tròn từng 24 giờ: hạn mới 17/12 lúc 10:00 thì 17/12 lúc 10:01 đã quá hạn nhưng chưa thêm ngày phạt; sang 18/12 cộng ngày đầu. Các bản ký cũ có ân hạn riêng tiếp tục đọc đúng snapshot cũ. Không có thêm 5 ngày miễn phạt sau hạn mới.

## Triển khai và tương thích

- `PaymentExtension` thêm trường Boolean có thể thiếu `lateFeeResumesAfterDue`; lượt duyệt mới ghi `true` trong lịch sử JSON. Constructor và việc đọc lịch sử cũ được giữ tương thích, không cần migration cột.
- MonthlyBillingService lấy lượt APPROVED cuối có cờ mới và đúng hạn hiện tại, cộng phí mới vào `frozenLateFee`, dùng điều khoản đã đóng băng của hóa đơn. Chỉ tăng khi PaymentRequest thật cho phép, cập nhật dòng LATE_FEE, tổng tiền, QR và audit trong cùng giao dịch. Refresh không cộng trùng.
- Các guard quyền, thời gian, khóa contract → invoice → payment, chống duyệt lặp và lịch chuyển nợ theo hạn mới được giữ. Yêu cầu lần hai bị từ chối/hết hạn chỉ tính bù từ hạn được duyệt gần nhất, không mất các ngày miễn trong lần gia hạn trước.
- Quyết định cũ đã miễn tăng phí (`lateFeeExempt=true`) không bị thay đổi hoặc truy thu. Không suy diễn khoản phạt từ lịch sử cũ thiếu metadata. Chỉ lượt đồng ý mới dùng chính sách tiếp tục phạt; không chạy backfill trên CSDL vận hành.
- Frontend phân biệt chính sách mới và miễn phí cũ, giải thích rõ thời gian gia hạn/trần phí. Hóa đơn INITIAL_RENT không bị hiển thị nhầm thành có quyết định gia hạn chỉ vì được miễn phí định kỳ.
- Đã cập nhật MONTHLY_BILLING, các ca UC08/UC09/UC10/UC22/UC23/UC34 trong SIMULATED_TIME và nguồn RAG 07 (draft 0.3.2). Chưa ingest RAG.

## Kiểm thử cuối

| Bộ kiểm tra | Kết quả |
|---|---|
| MonthlyBillingServiceTest | 87 đạt |
| ContractLifecyclePhase03PostgresTest | 25 đạt |
| ContractLifecycleServiceTest | 37 đạt |
| PaymentRequestServiceTest | 20 đạt |
| BillingTimeTest / MonthlyBillingScheduleTest | 6 / 3 đạt |
| Tổng backend | **178/178**, không failure/error/skipped, BUILD SUCCESS |
| Frontend payment-extension, contract-lifecycle, payment-dispute | **40/40** đạt |
| TypeScript và ESLint các TS/TSX thay đổi | Đạt |
| git diff --check core/web/infra | Đạt |

Ca mới bao gồm: trước/đúng/sau hạn mới; phí cũ + phí mới; nhiều lần gia hạn; trần 10 ngày và trần tiền thấp hơn; NONE/FIXED_ONCE; yêu cầu lần hai bị từ chối/hết hạn; báo chuyển/tranh chấp/từ chối chứng từ/đã nhận tiền; nhánh dời hạn trực tiếp; quyết định miễn phí cũ thiếu metadata; lịch tự chuyển nợ sau gia hạn. Hai ca PostgreSQL mới dùng MonthlyBillingService và PaymentRequestService thật, kiểm tra lịch sử lưu CSDL, đồng nhất phí/tổng/snapshot/hạn, QR đổi đúng số tiền, refresh không cộng trùng; một nhánh tiếp tục tới báo chuyển và xác nhận thật trong DB thử, một nhánh tới trần phí.

Lần PG đầu có 2 assertion của URL QR thử thất bại vì số lưu PostgreSQL có scale `.00`; đã sửa stub chuẩn hóa BigDecimal. Không sửa code tính tiền để né assertion; lần chạy cuối toàn bộ các suite trên đạt. QR, ngân hàng và chứng từ lưu trữ ngoài hệ thống được thay bằng mock; không thực hiện chuyển tiền hoặc ký SmartCA thật.

Log trong `%TEMP%`: `hs-extension-fee-core-final-20261104.log`, `hs-extension-fee-web-20261104.log`, `hs-extension-fee-types-20261104.log`, `hs-extension-fee-lint-20261104.log`.

```powershell
$env:HS_LIFECYCLE_TEST_PG_URL='jdbc:postgresql://127.0.0.1:55449/hs_phase03_test'
mvn -Pisolated-build -pl hs-api-service -am '-Dtest=MonthlyBillingServiceTest,ContractLifecyclePhase03PostgresTest,MonthlyBillingScheduleTest,BillingTimeTest,PaymentRequestServiceTest,ContractLifecycleServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

PG chỉ chạy với DB thử opt-in theo guard của suite. Container `hs-extension-fee-test-20261104`, cổng loopback 55449 và volume thử đã dọn sau khi xác minh đúng ID/nhãn/volume. Không thay CSDL hoặc restart ứng dụng vận hành. Giữ nguyên chỉnh sửa `.env.example` và `application.properties` có sẵn từ đầu lượt. Build/restart Core theo cách thường dùng để nạp code; kiểm tra trên lượt duyệt mới, không kỳ vọng hóa đơn đã được miễn phí từ quyết định cũ tự đổi chính sách.
