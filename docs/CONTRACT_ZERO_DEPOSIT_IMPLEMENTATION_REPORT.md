# Không đặt cọc / cọc 0 đồng: bỏ qua HĐ cọc

Ngày: **03/11/2026**. Quyết định của chủ dự án: nếu bài đăng không đặt cọc hoặc số cọc đã thống nhất bằng 0 thì bỏ qua bước ký HĐ cọc; không bổ sung văn bản giữ chỗ 0 đồng ký riêng.

## Kết quả

| Tình huống | Hành vi |
|---|---|
| Bài đăng `NONE` | Calculator ghi số cọc 0 vào hồ sơ; khoản cọc do client tự gửi không thay được quy định này. |
| Bài đăng `FIXED_AMOUNT = 0` | Cùng nhánh miễn HĐ cọc như NONE. |
| Ngày vào ở là hôm nay | Chỉ thanh toán tiền thuê tháng đầu, chủ nhà xác nhận thực nhận rồi chuẩn bị/ký HĐ thuê. |
| Ngày vào ở trong tương lai | Không cần chuyển hoặc ký cọc; chờ ngày bắt đầu thuê để mở tiền thuê tháng đầu. Có thể đề nghị bắt đầu sớm theo điều kiện hiện có. |
| Thiếu bản ghi thanh toán cọc 0 | Tiến trình không bị kẹt ở chờ cọc hoặc chờ chữ ký cọc; mở tiền thuê theo ngày gốc. |
| Báo chuyển tiền thuê nhưng chưa xác nhận | Chưa được ký HĐ thuê. Miễn cọc không đồng nghĩa tiền thuê đã nhận. |
| Khoản cọc đang dispute/đối soát | Giữ kiểm tra và dữ liệu đối soát; không bỏ qua chỉ vì số cọc trên hồ sơ là 0. |
| Cọc dương đã thống nhất nhưng bài đăng được sửa sau đó | Vẫn căn cứ số cọc của hồ sơ thuê, không dùng bài đăng hiện tại để tự miễn nghĩa vụ cũ. |
| Chưa biết số cọc | Không tự coi null hoặc dữ liệu thiếu là được miễn cọc. |

## Điểm phát hiện và sửa

Code trước đó đã có `signedOrExempt` cho số cọc 0 nhưng chưa nhất quán trên toàn tiến trình:

- API overview có thể trả `required=false` cùng `signingAllowed=true`. Đã chặn ký/mở lại hạn ký cọc cho hồ sơ miễn cọc và trả lý do phù hợp.
- `RentalOnboardingService` phụ thuộc khoản cọc/ban đầu CONFIRMED để vượt bước chờ cọc. Đã ghi nhận miễn cọc bằng số tiền đã thống nhất, không phụ thuộc một receipt 0 đồng để mở tiền thuê.
- Bổ sung `depositAgreementRequired` trong DTO tiến trình để frontend nhận trạng thái từ backend, không suy ra từ sự tồn tại của sổ cọc.
- Giao diện dự toán, gửi/duyệt yêu cầu, tiến trình, chi tiết và đổi ngày nhận nhà đã phân biệt hồ sơ có cọc/miễn cọc. Hồ sơ miễn cọc không có nút tạo/ký HĐ cọc và không gọi trạng thái ký cọc khi tải hồ sơ miễn cọc.
- Bản ghi miễn thanh toán 0 đồng hiện có được giữ tương thích nội bộ; không hiển thị như một giao dịch ngân hàng đã nhận trong tiến trình. Bản ghi chưa đối soát vẫn hiển thị.
- Cập nhật nguồn RAG 06/08 trên đĩa và đóng quyết định D02 trong checklist. Chưa ingest nguồn vào runtime.

Không sửa `ReservationPolicy.V2` hoặc hash/văn bản đồng ý đã chụp. Không xóa/sửa văn bản cọc đã ký, chứng từ, khoản tiền thực nhận hoặc dữ liệu vận hành. Không đổi quy trình hai HĐ ký riêng cho hồ sơ có cọc dương.

## Kiểm thử thực chạy

| Bộ kiểm tra | Kết quả |
|---|---|
| Maven listing/payment/contract/API chọn lọc | **215/215 đạt**, 0 failure/error/skipped, BUILD SUCCESS |
| PostgreSQL trong tổng Maven | **19/19 đạt**, gồm 16 onboarding (2 ca mới về miễn cọc) và 3 deposit repository |
| Web các màn và handler liên quan | **52/52 đạt**, gồm miễn ký cọc, không gọi ký cho hồ sơ miễn cọc, hiển thị tiền thuê/nhận tiền và giữ dữ liệu đối soát |
| TypeScript `tsc --noEmit` | Đạt |
| ESLint các file TypeScript/TSX thay đổi | Đạt |

Hai ca PG mới kiểm tra cả NONE không có bản ghi cọc và FIXED_AMOUNT=0 có bản ghi miễn cọc: trước ngày vào chưa mở tiền thuê/chưa ký, đến ngày gốc chỉ tạo tiền thuê một lần, chưa nhận tiền thì chưa tạo HĐ thuê, đã nhận tiền thì tạo đúng một HĐ thuê và không có HĐ cọc. Các suite còn lại hồi quy hồ sơ cọc dương, ký độc lập, gia hạn/quyết toán, vi phạm trước thuê và dữ liệu bản nháp.

PostgreSQL chạy trong container thử riêng `hs-zero-deposit-test-20261103` trên loopback:55446, hai DB thử `hs_zero_deposit_test` và `hs_contract_test`. Provider ký/ngân hàng/storage được giả lập trong các suite; chưa chạy SmartCA thật vì chưa có sandbox. Không dùng CSDL đang vận hành.

Log ở `%TEMP%`:

- `hs-zero-deposit-regression-final-20261103.log`: lượt backend cuối, 215 ca.
- `hs-zero-deposit-web-final-20261103.log`: lượt web cuối, 52 ca.
- `hs-zero-deposit-types-final-20261103.log`, `hs-zero-deposit-lint-final-20261103.log` và `hs-zero-deposit-contract-list-lint-20261103.log`: kiểm tra kiểu/lint.

Lệnh backend (biến môi trường chỉ trỏ DB thử nêu trên):

```powershell
mvn -Pisolated-build -pl hs-api-service -am '-Dtest=RentalCostCalculatorTest,RentalRequestServiceTest,OnboardingPaymentCreationTest,RentalOnboardingServiceTest,DepositAgreementServiceTest,DepositAgreementControllerTest,ContractDraftIntegrityTest,ContractLifecycleServiceTest,RentalDefaultTest,DepositSignatureIsolationTest,OnboardingPersistencePostgresTest,DepositRepositoryPostgresTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

Container/volume thử được dọn sau nghiệm thu, chỉ theo đúng ID và nhãn task đã kiểm tra. Không deploy/restart backend/web vận hành, không ingest RAG hoặc xây hệ thống thông báo trong lượt này. Phần cập nhật prompt/sáu mẫu Word và nghiệm thu staging vẫn được theo dõi riêng theo báo cáo đối chiếu.

## Rà soát bổ sung: hết hạn/trả nhà khi không cọc

Sau yêu cầu rà thêm của chủ dự án, đã đối chiếu nhánh hết hạn tự nhiên, chấm dứt theo thỏa thuận dùng chung quyết toán và nhánh chấm dứt do hóa đơn quá hạn.

Điều kiện không tạo giao dịch hoàn tiền bằng 0 đã có trước lượt này. Tuy nhiên, vẫn còn các chỗ ghi nhận/hiển thị chưa đúng và đã sửa:

- Quyết toán không có tiền phải chuyển vẫn đặt hạn hoàn theo `depositRefundDays`; cấu hình hoàn cọc không cần dùng có thể chặn hồ sơ không cọc. Nay chỉ đặt hạn khi có khoản hoàn hoặc khoản phải trả thực tế.
- Nhánh không hoàn cọc luôn ghi `RETAINED_BY_LANDLORD`, kể cả không từng giữ đồng nào. Nay không ghi giữ lại/mốc giữ lại cọc 0 đồng. Bản ghi miễn cọc `HELD` với số dư 0 tiếp tục dùng như trước, không tạo trạng thái đã chuyển/đã hoàn tiền giả; hợp đồng và quyết toán có trạng thái CLOSED độc lập.
- Nhánh chấm dứt do nợ cũng ghi giữ cọc 0 và thời điểm/lý do giữ. Đã giữ các dấu này chỉ cho số tiền thực giữ; công nợ hóa đơn vẫn còn sau khi trả nhà.
- Giao diện không còn hướng dẫn “hoàn nguyên cọc”, tạo đề xuất hoàn cọc hoặc xác nhận đã nhận tiền hoàn cho hồ sơ thực giữ 0. Thay bằng xác nhận trả nhà và quyết toán; khoản phải trả thêm vẫn có giao diện/thao tác thanh toán.
- Sổ cọc miễn cọc được giải thích đúng, không hiển thị như đã giữ lại/hoàn tiền. Sổ đang tranh chấp hoặc có tiền cọc bổ sung thực nhận vẫn giữ dữ liệu đối soát và lịch sử.

### Các điều kiện vẫn giữ

1. EXPIRED không tự thành CLOSED. Cần hóa đơn kỳ cuối/bổ sung đã được xác nhận thanh toán, chủ nhà xác nhận đã nhận lại nhà và người thuê chấp thuận đúng phiên bản quyết toán.
2. Nếu không có cọc và không còn khoản phải chuyển, chấp thuận quyết toán sẽ đóng hợp đồng, không có yêu cầu DEPOSIT_REFUND, QR, hạn hoàn hoặc thao tác xác nhận nhận tiền hoàn.
3. Nếu không cọc nhưng có khoản bồi thường/quyết toán được hai bên đồng ý, chỉ tạo khoản người thuê trả thêm; chưa thực nhận thì chưa đóng nghĩa vụ tài chính.
4. Ban đầu cọc 0 nhưng có điều chỉnh cọc đã thực nhận sau đó thì hoàn theo số dư thực giữ. Không căn cứ `NONE` hoặc số cọc ban đầu để bỏ qua tiền đang giữ thật.
5. Hoàn tiền thuê trả trước trong chấm dứt sớm là nghĩa vụ riêng; miễn cọc không hủy khoản hoàn thuê. Thiếu ledger/dữ liệu tài chính không được tự suy thành 0 hoặc tự tạo chứng từ để đóng.

### Kiểm thử bổ sung

| Bộ chạy | Kết quả |
|---|---|
| Hồi quy backend: lifecycle, khiếu nại thanh toán, phụ lục, hóa đơn, tính kỳ cuối, onboarding và cọc | **244/244 đạt**, gồm **21 PostgreSQL**, 0 lỗi/bỏ qua; log `hs-zero-deposit-closure-core-20261103.log` |
| Sau sửa thêm ghi nhận cọc 0 ở nhánh chấm dứt do nợ: MonthlyBillingServiceTest + ContractLifecycleServiceTest | **108/108 đạt**, 0 lỗi/bỏ qua; log `hs-zero-deposit-closure-monthly-20261103.log` |
| Web: lifecycle, thanh toán tranh chấp, khiếu nại, workflow và onboarding | **71/71 đạt**; log `hs-zero-deposit-closure-web-final-20261103.log` |
| Kiểm tra kiểu và lint file thay đổi | Đạt; log `hs-zero-deposit-closure-types-final-20261103.log`, `hs-zero-deposit-closure-lint-final-20261103.log` |

Các lượt backend có suite trùng nhau, không cộng 244 và 108 thành số ca độc lập. Bốn ca PG mới bao gồm: không cọc/không nợ → đóng không hoàn; không cọc/còn khoản phải trả → báo chuyển chưa đóng, thực nhận mới đóng; hóa đơn chưa trả hoặc chưa nhận lại nhà → bị chặn; cọc ban đầu 0 nhưng đã thu bổ sung 3 triệu → vẫn tạo nghĩa vụ hoàn thật. Unit bổ sung kiểm tra không lập hạn hoàn không cần thiết và không ghi giữ cọc 0 ở cả quyết toán hết hạn lẫn chấm dứt do nợ.

PostgreSQL 16 chạy trên container riêng `hs-zero-deposit-closure-test-20261103`, loopback:55447, DB `hs_phase03_test`; container và đúng volume thử đã được kiểm tra ID/nhãn rồi dọn. Provider ngân hàng/storage/ký được cách ly; chưa nghiệm thu SmartCA thật, chưa deploy/restart hoặc sửa DB vận hành. Nguồn RAG 08 được cập nhật trên đĩa, chưa ingest runtime.
