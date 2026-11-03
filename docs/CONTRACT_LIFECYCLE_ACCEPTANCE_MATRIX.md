# Ma trận nghiệm thu vòng đời hợp đồng T01–T40

Ngày 03/11/2026. Đây là bảng đối chiếu bộ test đã chạy và giới hạn kiểm chứng, không phải đánh dấu mọi luồng production đã hoàn tất. Các suite tham chiếu đều nằm trong lượt Maven cuối `hs-gaps-core-final.log` hoặc bộ web/admin/browser/AI có báo cáo riêng. Tên phương thức rút gọn là từ khóa tra cứu bằng `rg`.

Dữ liệu vào: PostgreSQL/container riêng với hồ sơ owner/tenant/tenant2/admin giả, tiền cọc 20 triệu, giá thuê 5 triệu, các kỳ thuê có ngày neo và các trạng thái thanh toán/chữ ký được fixture tạo rõ. Provider ký/ngân hàng/storage cách ly; hai mẫu PDF qua Gotenberg thật. Test API không lấy danh tính từ body.

| Mã | Tình huống từ review | Mức đã kiểm chứng | Bằng chứng tự động | Giới hạn / bước kiểm chứng thật |
|---|---|---|---|---|
| T01 | Hai khách cùng gửi, hai thao tác chấp thuận đồng thời | PG đạt | `RentalRequestApprovalPostgresTest: concurrentApprovals / submissionRacing / rollback` | Chưa thay provider thật |
| T02 | Hủy/yêu cầu hết hạn cùng lúc nhận tiền hoặc ký | Service + PG đạt | `OnboardingPersistencePostgresTest; RentalWorkflowPostgresTest: cancellation, unknownProviderOutcome, release rollback` | Race nhà cung cấp thật chờ staging |
| T03 | Hủy trước khi báo chuyển, cả chủ nhà và người thuê | Service + PG đạt | `OnboardingPersistencePostgresTest: immediateCancellationRollsBackThenReleasesOnlyOnce` | Không suy ra đã nhận tiền |
| T04 | Báo chuyển sát hạn, chủ nhà chưa xác nhận | Service + PG đạt | `PaymentDisputePostgresTest; OnboardingPersistencePostgresTest: legacyRejectedReview` | Provider ngân hàng cách ly |
| T05 | Không đặt cọc hoặc cọc cố định bằng 0 | Service + API + PG + web đạt | `RentalCostCalculatorTest; DepositAgreementServiceTest; RentalOnboardingServiceTest; DepositAgreementControllerTest; OnboardingPersistencePostgresTest.zeroDepositWaitsForActualRentThenCreatesOnlyOneLeaseWithoutDepositSigning; deposit-agreement/rental-payment-plan/rental-onboarding-default web tests` | Miễn HĐ cọc, vẫn cần thực nhận tiền thuê; kiểm thử SmartCA thật vẫn chờ sandbox. Chi tiết tại báo cáo miễn cọc. |
| T06 | Cọc hôm nay, nhận nhà tương lai; và cọc/thuê cùng ngày | Service + PG đạt | `OnboardingPersistencePostgresTest; DepositSignatureIsolationTest` | Hai lượt SmartCA thật chờ sandbox |
| T07 | Thiếu/đồng thời phát hành nhiều mẫu cọc hoặc thuê | Service + PG đạt | `OnboardingPersistencePostgresTest: absentTemplateThenPublishedTemplateAndFailedPdfRecover` | Converter thật có lượt PDF QA riêng |
| T08 | Đã cọc nhưng khách không ký HĐ cọc, không phản hồi | PG đạt | `RentalWorkflowPostgresTest: fullRefundWorksWithoutAnyDepositDocument, cancelAfterResponseBoundary` | Thông báo bỏ qua; chủ nhà xác nhận đã liên hệ ngoài hệ thống |
| T09 | Server tắt trước ngày vào, bật sau ngày vào | Service/PG và persistence restart đạt | `RentalOnboardingServiceTest; RentalWorkflowSchemaUpgradeTest` | Chưa mô phỏng cả VNPT/ngân hàng đang chạy qua một lần server tắt |
| T10 | Nhiều scheduler cùng tạo draft/PDF | PG đồng thời đạt | `OnboardingPersistencePostgresTest: concurrentAutomaticAttemptsPersistExactlyOneRootAndRevision` | Hai context/DB lock có test riêng; storage/provider cách ly |
| T11 | Nhấn ký nhiều lần, webhook/polling trùng | Service + PG đạt | `SmartCaDraftIntegrityTest; ContractLifecyclePhase03PostgresTest: repeatedVerifiedCallback; WorkflowSignatureIsolationTest` | Webhook/poll provider thật chờ sandbox |
| T12 | VNPT timeout nhưng phía provider đã ký | Biên provider giả lập đạt | `SmartCaClientTest; SmartCaDraftIntegrityTest; RentalWorkflowPostgresTest: unknownProviderOutcomeNeverReleasesRoom` | Chưa chạy VNPT thật; có kit đối soát request cũ |
| T13 | Đã ký một bên rồi đổi revision/tải file khác | Service + PG + web đạt | `SmartCaPreviewSourceTest; RentalWorkflowPostgresTest: documentIsSeparateVersionedAndOldPdfCannotSignNewProposal` | PDF cũ giữ để tra cứu |
| T14 | Ký trước hạn nhưng nhúng PDF hoàn tất sau hạn | Guard được hồi quy; nghiệm thu provider chờ | `SmartCaDraftIntegrityTest; WorkflowSignatureIsolationTest: lateProviderResultForReplacedSource` | Chưa chứng minh VNPT ký trước hạn/nhúng sau hạn bằng sandbox |
| T15 | CCCD/chứng thư sai bên, hết hạn hoặc thu hồi | Identity unit đạt | `SmartCaSignerIdentityTest; SmartCaClientTest` | Chứng thư hết hạn/thu hồi và app thật chờ sandbox |
| T16 | HĐ cọc đủ hai chữ ký | Service + PG đạt | `DepositSignatureIsolationTest; DepositRepositoryPostgresTest` | Không kích hoạt lease/listing từ chữ ký HĐ cọc |
| T17 | Sửa ngày/thời hạn bản nháp khác rental | Service + PG đạt | `ContractDraftIntegrityPostgresTest; ContractDraftIntegrityTest` | Chặn lệch ngày/tháng, giữ snapshot và lịch thực |
| T18 | Sửa tiền thuê/cọc sau khi tiền đầu kỳ đã nhận | Service + PG đạt | `ContractDraftIntegrityPostgresTest; ContractDraftIntegrityTest` | Không sửa receipt cũ |
| T19 | Giả mạo snapshot người ký/tài sản qua API sửa draft | API/service + PG đạt | `ContractDraftIntegrityTest; ContractDraftIntegrityPostgresTest` | Nguồn danh tính/tài sản từ hồ sơ xác thực |
| T20 | Mở PDF sau phụ lục có hiệu lực hoặc phụ lục tương lai | Service + web đạt | `AiContractQueryServiceTest; agreement-preview and contract document history web tests` | Giữ văn bản lịch sử độc lập |
| T21 | Hóa đơn đầu kỳ chuyển gộp cọc + thuê | Service/PG đạt | `FirstRentInvoiceTest; OnboardingPaymentCreationTest; OnboardingPersistencePostgresTest` | Không tính hai lần cọc/thuê |
| T22 | Tháng 28/29/30/31 ngày, nhận nhà giữa tháng | Service đạt | `MonthlyBillingSchedule tests; FinalRentCalculatorTest: monthEnd/leap/initialThreeMonth` | Web gửi quote lên Core, không tự tính tiền |
| T23 | Công tơ giảm/missing/chưa bàn giao | Service + PG + browser đạt | `MonthlyBillingServiceTest; RentalWorkflowPostgresTest: twoPartyMoveInConfirmationAndCorrectionDoNotRewriteSignedMeters` | Biên bản là căn cứ; không ghi đè công tơ ký |
| T24 | Mở dispute hóa đơn tháng rồi hai bên thống nhất | PG đạt | `PaymentDisputePostgresTest: bilateralReviewThenActualReceiptCompletesRealMonthlyInvoiceReconciliation` | Receipt thật vẫn phải đúng bên xác nhận |
| T25 | Mở dispute hoàn cọc/điều chỉnh cọc | PG đạt | `PaymentDisputePostgresTest: refundCaseThenTenantReceipt; depositDeltaIsAppliedExactlyOnce` | Giữ cọc/closure khớp nguồn thực nhận |
| T26 | Chuyển thiếu/thừa tiền hoặc chuyển ngoài hạn nhưng chưa báo | Giới hạn sản phẩm hiện có | `PaymentRequestService / PaymentDispute tests chỉ bảo vệ đối soát` | Chưa có thanh toán nhiều phần hoặc tự tách tiền dư. Cần thiết kế nghiệp vụ riêng; không giả vờ đã nhận đủ |
| T27 | Đề nghị gia hạn tiền, chuyển nợ, trả tiền đồng thời | Service đạt | `MonthlyPaymentExtensionTest; PaymentRequestServiceTest` | Chưa có ca ngân hàng thật race nhiều lần chuyển |
| T28 | Phụ lục đổi giá/cọc/xe có hiệu lực tương lai | Service + PG đạt | `ContractAmendmentServiceTest; ContractLifecyclePhase03PostgresTest` | Không sửa hóa đơn đã phát hành, không thu chênh cọc hai lần |
| T29 | Gia hạn hợp đồng bị từ chối rồi gửi thời hạn khác | PG đạt | `ContractLifecyclePhase03PostgresTest: rejectedThreeMonthProposalAllowsFreshOneMonthProposal` | Giữ lịch sử yêu cầu cũ |
| T30 | Đề nghị gia hạn hết hạn phản hồi nhưng chưa cutoff ký | PG đạt | `ContractLifecyclePhase03PostgresTest: responseExpiryAllowsRetryBeforeCutoff` | Chặn đúng cutoff ký |
| T31 | Đang ký gia hạn thì hợp đồng chấm dứt/hết cutoff | PG / provider boundary đạt | `ContractLifecyclePhase03PostgresTest: lateCallbackForOldExpiredRequestCannotApplyToReplacement` | Callback VNPT thật chờ sandbox |
| T32 | Hợp đồng hết thời hạn nhưng khách chưa trả phòng | Service + PG đạt | `ContractLifecyclePhase03PostgresTest; RentalWorkflowPostgresTest: cannotSettleUnreturnedRoom` | EXPIRED khác CLOSED; chưa trả nhà không giải phóng sai |
| T33 | Quyết toán không cọc, khấu trừ một phần/toàn bộ/vượt cọc | PG đạt | `ContractLifecyclePhase03PostgresTest: settlementBalancesActualDepositAndKeepsExcessDebtSeparate, noDepositClosureSkipsRefundButStillCollectsAnAgreedBalance, initiallyZeroDepositStillRefundsAnActuallyReceivedDepositAdjustment` | Không tạo hoàn cọc 0 đồng; vẫn thu khoản thiếu và hoàn cọc bổ sung thực nhận. retainedAmount/refund/balance đúng ledger. |
| T34 | Hai phiên bản đề xuất quyết toán, tenant xác nhận bản cũ | PG + web đạt | `ContractLifecyclePhase03PostgresTest: alreadyReadClosureCannotBeAcceptedAgainAfterConcurrentCommit` | Stale browser bị chặn, POST không tự retry |
| T35 | Có chi phí điện/nước bổ sung cuối kỳ | Service + PG đạt | `MonthlyBillingServiceTest; ContractLifecyclePhase03PostgresTest` | Chi phí cuối kỳ riêng, không tự xóa hoặc trừ cọc |
| T36 | Hoàn cọc quá hạn hoặc chỉ báo chuyển chưa xác nhận | PG + web đạt phần trạng thái | `PaymentDisputePostgresTest; RentalWorkflowPostgresTest: earlyViewReflectsActualFinancialCompletionAndPendingRefund` | Nhắc hạn/thông báo bỏ qua theo người dùng |
| T37 | Người thuê trả đúng hạn muốn chấm dứt sớm | PG + browser đạt | `RentalWorkflowPostgresTest: earlyExitDoesNotRequireDebt; dailyProration; browser final settlement` | Không cần tạo nợ/giữ toàn bộ cọc |
| T38 | Chủ nhà không bàn giao/phòng không như cam kết | PG + browser/admin đạt | `RentalWorkflowPostgresTest: sourceEvidenceFromOtherPartyReachesAdminAndDoesNotDrift; browser source case/admin` | Ghi căn cứ nguồn và xử lý qua case, không tự thay quyền/tiền |
| T39 | Chủ nhà không giải quyết hoặc hai bên không thống nhất | PG + browser/admin đạt | `ComplaintCasePostgresTest; browser admin reads source and claims case` | SLA48h có cấu hình; hệ thống thông báo riêng chưa xây |
| T40 | Tài khoản bên thứ ba đọc PDF/chứng cứ, retry quyết định, lỗi giữa transaction | API + PG + web + browser đạt | `ComplaintControllerTest; RentalWorkflowPostgresTest: evidence ACL, stale, rollback; browser tenant2 denied` | Không dùng kết quả cách ly để khẳng định S3/Keycloak/provider thật đã nghiệm thu |

## Phần bổ sung R01–R03 và tài liệu riêng

- R01: 3 nguồn CANCELLATION/MOVE_IN/EARLY_EXIT → snapshot bất biến → case → admin; chứng cứ bên còn lại, upload chưa gắn/sai rental bị chặn; PDF nguồn gắn đúng hash và không cho xóa.
- R02: case đã giải quyết → REQUEST_RESUME → bên kia RESUME; đúng phiên bản, kiểm tra chữ ký/chuyển tiền chưa rõ và lượt giữ phòng; không cần đề nghị hủy giả. Có PG và browser test.
- R03: RETURNED → hóa đơn cuối → closure → thực nhận hoàn/thu thêm → GET hiển thị COMPLETED. Có PG và browser test đến bước thực nhận.
- PDF: nguồn bất biến, current/stale, hai lượt ký riêng, lỗi converter rollback, chữ ký muộn sai nguồn không áp dụng, retry không nhân đôi nghĩa vụ. Có unit/PG và render/PNG QA; SmartCA thật được người dùng hoãn đến khi có sandbox.
- Tính thuê theo ngày: 28/29/30/31 ngày, ngày neo cuối tháng, nhiều tháng trả trước, nguồn chưa nhận hoặc không khớp phải đối soát; không sửa invoice/receipt.
- Schema: mô phỏng schema cũ, update giữ dữ liệu, hai application context khóa chung PostgreSQL, đóng/mở lại và đọc đúng kết quả.

Chi tiết chạy lại và kit SmartCA: [Hướng dẫn vận hành](RENTAL_WORKFLOWS_OPERATIONS.md). Kết quả cuối: [Báo cáo hoàn thiện](CONTRACT_LIFECYCLE_GAPS_COMPLETION_REPORT.md). Thông báo/nhắc hạn bỏ qua theo yêu cầu; các cột giới hạn được giữ rõ để không nhầm với kết quả mock.
