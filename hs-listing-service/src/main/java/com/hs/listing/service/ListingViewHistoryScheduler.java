package com.hs.listing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ListingViewHistoryScheduler {

    private final ListingViewHistoryService viewHistoryService;

    /**
     * Tự động dọn dẹp các bản ghi lịch sử xem tin quá 30 ngày.
     * Mặc định chạy lúc 03:00 sáng hàng ngày (hoặc cấu hình qua listing.view-history-cleanup-cron).
     */
    @Scheduled(cron = "${listing.view-history-cleanup-cron}")
    public void purgeExpiredViewHistory() {
        try {
            int deleted = viewHistoryService.purgeExpiredHistory(30);
            if (deleted > 0) {
                log.info("Scheduled task: Purged {} expired view history record(s) older than 30 days", deleted);
            }
        } catch (Exception e) {
            log.error("Error during scheduled view history cleanup: {}", e.getMessage(), e);
        }
    }
}
