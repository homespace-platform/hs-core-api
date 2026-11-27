# Nhật ký P5-BE-01 + P5-BE-02 (Sonnet)

Hợp đồng: 07 §1, §2, §4, §5, §7, §8. Không viết test (Codex). Không commit.

## Trạng thái: XONG (2026-11-27). Bước kế: Codex viết P5-TEST-01.
- [x] 1. Đọc tài liệu, code P3/P4, outbox, consumer.
- [x] 2. P5-BE-01: lớp meters/contracts trong overview.
- [x] 3. Digest Core.
- [x] 4. Consumer hs-notification-service.
- [x] 5. Unit test core + kiểm notification.
- [x] 6. PostgreSQL DB trống mới + probe tạm (đã gỡ).

## File
C = hs-contract-service/src/main/java/com/hs/contract
- Mới: C/service/BranchMonitoringService (phép thuần: periods, candidates, meter, metered, expiry); C/notification/{BranchDigestScheduler, BranchDigestOutboxWriter}.
- Sửa: C/service/BranchOverviewService (meterLayer, expiryLayer, revisionAt dùng chung với khoản xám), C/service/BranchAttentionService (dùng chung metered và bộ chọn sắp hết hạn), C/dto/branch/{BranchOverviewResponse (+Meter, Expiry, Room 2 khoá; giữ constructor 6 tham số), BranchInvoiceRow (+finalCostSupplement, draftPrepared; giữ constructor 10 tham số)}, C/repository/{MonthlyInvoiceRepository, BranchContractAssignmentRepository (+findRevisionCharges)}, hs-listing-service PropertyBranchRepository (+findActiveOwnerIds).
- Test cũ sửa: BranchOverviewApiTest, JSON strict thêm "meters":[],"contracts":[] (hợp đồng 07 §1 P5). Không đổi gì khác.
- hs-notification-service: src/modules/notification-events/notification-event.ts (BRANCH_DAILY_DIGEST).
- Không thêm biến môi trường, bảng, topic, publisher.

## Điểm vào cho test
- BranchMonitoringService.{periods, candidates, meter, metered, expiry}; BranchDigestScheduler.send(LocalDate) (package com.hs.contract.notification); BranchDigestOutboxWriter.write(ownerId, day, count) -> false nếu đã có bản của ngày.

## Lựa chọn nhỏ nhất ngoài chữ của 07
1. Có kỳ trong cửa sổ [hôm nay, +3] nhưng không kỳ nào đủ điều kiện (đã huỷ / không đo chỉ số): để trống, không lùi về kỳ cũ.
2. Không có kỳ trong cửa sổ: chỉ xét kỳ đã tới ngày ghi mới nhất; kỳ đó huỷ, chưa chuẩn bị hoặc không đo thì để trống.
3. Digest bật theo notification.outbox.enabled (cùng điều kiện publisher). Lỗi đọc: bỏ owner đó, ghi log (ownerId + tên lớp lỗi); lỗi ghi: thử lại đúng 1 lần.

## Thứ tự triển khai (bắt buộc)
1. hs-notification-service trước (mọi bản consumer đang chạy). 2. Sau đó hs-core-api. Web P5 trước hay sau đều được.
- Consumer cũ gặp BRANCH_DAILY_DIGEST: INVALID_EVENT, vào DLT đã che dữ liệu, commit offset, không chặn event khác; bản gộp đó không tự tới feed (row core đã sent, chỉ replay từ DLT/source). Rollback consumer thì rollback core trước.

## Kiểm
- `./mvnw -o -B -pl hs-api-service -am test` BUILD SUCCESS. run/fail/error/skip: common 21/0/0/0, user 136/0/0/22, storage 71/0/0/0, listing 579/0/0/94, payment 263/0/0/25, contract 1310/0/0/245, api 157/0/0/1. Lần chạy đầu api lỗi 1 (JSON strict cũ, xem trên). Skip không tính là đạt.
- notification: `tsc --noEmit` đạt; jest src/modules/notification-events 17 suite, 722/722.
- PostgreSQL hs-branch-test-pg, DB trống mới (_p5be): BranchOverviewPostgresTest 6/0/0/0; BranchAttentionPostgresTest 1/0/0/0; probe BranchDigestProbeTest 1/0/0/0 (lớp meters/contracts, TODAY -> RECORDED -> huỷ thì trống; 2 lượt đồng thời: 1 row/owner, count = summary; owner lỗi không chặn owner sau; 105 owner qua 2 trang keyset; chạy lại sau sent_at không thêm row). Probe đã gỡ khỏi repo và target; bản lưu ở scratchpad phiên e1a0a164.... DB thử còn trong container.

## Chưa kiểm / rủi ro
- Chưa chạy app, Kafka, consumer -> feed/web thật (07 §8 yêu cầu trước khi đóng); cron 08:00 chưa chạy thật. Chưa đo nhiều owner / 1500 phòng.
- Lượt digest chiếm 1/4 luồng scheduler chung, mỗi lúc tối đa 1 kết nối DB; nhiều instance cùng tính (chỉ 1 row được ghi).
- Digest đi chung outbox hợp đồng (FIFO theo id, 20 row/lượt poll): rất nhiều owner thì thông báo hợp đồng phát sinh sau 08:00 chờ sau hàng digest.
- App tắt lúc 08:00: không bù.
- docs/KAFKA_TESTING.md chưa thêm dòng event (docs do Codex).
