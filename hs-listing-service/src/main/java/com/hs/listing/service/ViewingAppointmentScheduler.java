package com.hs.listing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ViewingAppointmentScheduler {

    private final ViewingAppointmentService appointmentService;

    /**
     * Tự động quét các lịch hẹn PENDING đã quá giờ để chuyển thành EXPIRED,
     * và các lịch hẹn CONFIRMED đã qua giờ để chuyển thành COMPLETED.
     * Mặc định chạy mỗi 15 phút.
     */
    @Scheduled(cron = "${listing.appointment-cleanup-cron:0 */15 * * * ?}")
    public void processAppointmentLifecycle() {
        try {
            int expiredCount = appointmentService.autoExpirePendingAppointments();
            if (expiredCount > 0) {
                log.info("Viewing appointment scheduler: Expired {} pending appointment(s)", expiredCount);
            }

            int completedCount = appointmentService.autoCompleteConfirmedAppointments();
            if (completedCount > 0) {
                log.info("Viewing appointment scheduler: Marked {} confirmed appointment(s) as COMPLETED", completedCount);
            }
        } catch (Exception e) {
            log.error("Error in ViewingAppointmentScheduler: {}", e.getMessage(), e);
        }
    }
}
