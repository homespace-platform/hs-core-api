# Nhật ký P4-BE-01 (Sonnet)

Hợp đồng: 07 §1, §2, §4, §6, §8 + quyết định P4-R1. Chỉ đọc. Không viết test (Codex). Không commit.

## Trạng thái: XONG (2026-11-27). Bước kế: Codex viết P4-TEST-01.
- [x] 1. Đọc tài liệu, nguồn dữ liệu. Thiết kế: BranchAttentionService.collect(owner, branchIds, B, R) là hàm dựng việc dùng chung cho danh sách, tổng số, digest P5; tiền tái dùng phép suy của BranchOverviewService.
- [x] 2. P4-R1 DEPOSIT_ADJUSTMENT trong lớp tiền P3; mở phép suy P3 cho P4.
- [x] 3. Tách bước ký của chủ trong RentalOnboardingService.
- [x] 4. BranchAttentionReadRepository, DTO, service, controller.
- [x] 5. Unit test (`-pl hs-api-service -am test`) BUILD SUCCESS. run/fail/error/skip: common 21/0/0/0, user 136/0/0/22, storage 71/0/0/0, listing 579/0/0/94, payment 263/0/0/25, contract 1280/0/0/243, api 145/0/0/1.
- [x] 6. PostgreSQL hs-branch-test-pg, DB trống mới: BranchOverviewPostgresTest 5/0/0/0 (hs_branch_overview_test_p4be). Probe tạm 1/0/0/0 (hs_branch_attention_test_p4be), đã xoá khỏi repo và target; bản lưu ở scratchpad phiên e1a0a164...: BranchAttentionProbeTest.java.
  - Probe ra 17 WorkType (chưa seed METER_TODAY, LISTING_EXPIRED); chỉ SELECT, không FOR UPDATE/SHARE; snapshot mọi bảng không đổi; chủ khác bị chặn; count summary = totalElements.
  - Số query: attention 28, summary 28; thêm 25 phòng vẫn 28, thêm 3 chi nhánh vẫn 28 (chia lô 500 ID thì tăng theo lô).

## File
- Mới: api BranchAttentionController; contract service/BranchAttentionService, repository/BranchAttentionReadRepository, dto/branch/BranchAttentionResponse.
- Sửa: BranchOverviewService (tách addStays; các add* tiền thành public, month null = mọi tháng; addDepositAdjustments), BranchOverviewResponse (+kind DEPOSIT_ADJUSTMENT), RentalOnboardingService (+landlordSigningStep chỉ đọc, dùng chung unresolved/depositReady/hạn ký/cửa sổ ký với view), PaymentRequestRepository (+3 query), PropertyBranchRepository (+findActiveIds), 07 §1.

## Điểm vào cho test / P5
- GET /branches/{id}/attention -> attention(branchId, owner, page, size); GET /branches/attention-summary -> summary(owner). Digest P5: cộng count của summary(owner).
- Hàm thuần: BranchAttentionService.{cardOrder, priority}; RentalOnboardingService.landlordSigningStep.

## Lựa chọn nhỏ nhất ngoài chữ của 07
1. TERMINATION_RESPONSE từ workflow PROPOSED: bỏ khi gốc đã là lịch sử (không còn gì để trả lời).
2. OWNER_SIGN phụ lục/gia hạn: thêm điều kiện gốc ACTIVE như validateSigning. Không kiểm các chặn khác lúc ký (ví dụ gia hạn khi đang chấm dứt, KYC, tài liệu PDF sẵn sàng).
3. PAYMENT_CONFIRM của INITIAL/cọc không đòi dòng RENT hợp lệ và nhận cả hồ sơ chưa có HĐ đã huỷ nhưng khoản còn TRANSFER_REPORTED; PAYMENT_OVERDUE INITIAL dùng đúng tập của lớp tiền P3.
4. METER_AUTOFILLED: "kỳ thường mới nhất" tính cả kỳ đã huỷ/đã thu (kỳ đó mới nhất thì không báo, không lùi về kỳ cũ).
5. DEPOSIT_ADJUSTMENT chiều thuê trả: giống SETTLEMENT_BALANCE (đã CONFIRMED thì rời lớp).

## Bỏ vì thiếu nguồn
Như 07 §4 "Bỏ do thiếu nguồn đáng tin". Không phát sinh loại nào khác bị bỏ.

## Chưa kiểm / rủi ro
- Chưa chạy app, HTTP/Gateway thật (400/401/404, Cache-Control, route attention-summary so với /branches/{id}), chưa đo khối lượng lớn: load mọi hoá đơn của gốc trong phạm vi mỗi lần gọi.
- Web chưa có nhãn cho kind DEPOSIT_ADJUSTMENT (đang rơi về "Khoản thu"); thêm vào services/branch-overview.service.ts của hs-web-app.
- DB thử còn trong container: hs_branch_overview_test_p4be, hs_branch_attention_test_p4be.

## P4-FIX · sửa rà P4-TEST-01 + P4-R2 (Sonnet, 27/11)
- [x] 5: `list()` bỏ phần tử null (snapshot gia hạn/đơn phương), dòng nợ quá hạn vẫn hiện.
- [x] 2: `findLeaseRows` chỉ join hồ sơ khi `r.ownerId = c.landlordId` (tên, ngày vào, số tháng đều null nếu lệch); `findHolds` thêm `r.ownerId = s.branch.ownerId`; `BranchRoomSchedule.overlaps` chịu được ngày vào null.
- [x] 3: OWNER_SIGN phụ lục kiểm thêm phần chỉ đọc của `validateSigning`: không áp ngay, không đang chấm dứt, gia hạn đang mở, khoản bù cọc chưa CONFIRMED, revision gốc, còn hạn thuê, ngày áp dụng là đầu kỳ tương lai chưa bị hoá đơn phát hành chạm tới, chuỗi thay thế. Gia hạn: không đang chấm dứt, `startsOn` khớp cuối kỳ. Không kiểm được (vẫn hiện việc): chế độ ký, sức chứa bãi xe, nguồn tin, KYC, PDF. Thiếu dữ kiện thì giữ việc. Query mới `findByContractIdInAndType`, `findOpenRenewals` (thay `findApprovedRenewals`).
- [x] 4: mọi khoản onboarding (nhận tiền, bước ký) lọc qua một quy tắc `isReceiptOf` (đúng chiều, payee = chủ, payer = người thuê, văn bản thuộc hồ sơ).
- [x] 1 + P4-R2: `PAYMENT_REVIEW` "Đối soát khoản chuyển bị từ chối" (thay PAYMENT_OVERDUE cùng khoản; gồm cả INITIAL bị từ chối vì cùng bước REVIEW_PAYMENT), `CANCELLATION_DECISION` "Chờ quyết định hủy trước khi thuê", `SIGNING_REOPEN` "Quá hạn ký, cần mở lại hạn". Đích hồ sơ thuê. Đã thêm vào 07 §4. Web cần thêm 3 chuỗi enum + nhãn (parser đang chỉ nhận loại đã biết). Không kiểm trước chữ ký đang xử lý (quyết định hủy) và KYC (mở lại hạn cọc).

### Kết quả P4-FIX (XONG; phát hiện 6 thuộc web)
- `-pl hs-api-service -am test` BUILD SUCCESS. run/fail/error/skip: common 21/0/0/0, user 136/0/0/22, storage 71/0/0/0, listing 579/0/0/94, payment 263/0/0/25, contract 1310/0/0/245, api 157/0/0/1.
- PostgreSQL hs-branch-test-pg, DB trống mới: BranchAttention 1/0/0/0, BranchOverview 6/0/0/0, BranchAssociations 21/0/0/0 (DB hậu tố _p4fix1), BranchLayout 4/0/0/0, ContractLifecyclePhase03 31/0/0/0, RentalWorkflowHttpSystem 1/0/0/0 (hai DB tên cố định đã drop/create lại; đổi mật khẩu role postgres của container giữa hai lần).
- Không sửa/làm yếu test cũ; thêm 2 ca (BranchAttentionTest, RentalOnboardingServiceTest). Không phát hiện test sai.
- Còn lại: OWNER_SIGN hợp đồng cọc chưa kiểm trạng thái sổ cọc HELD của `DepositAgreementService.validateSigning`.
