package com.hs.payment.service;

import com.hs.payment.model.constant.PaymentDirection;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.repository.PaymentRequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;

/**
 * A reported refund that the tenant never confirms or rejects would keep the rental (and the listing) stuck forever.
 * After the window the system confirms it on the tenant's behalf. Each payment runs in its own transaction and the
 * service re-checks every condition under the row lock, so repeated runs are harmless.
 */
@Component @Slf4j
public class RefundAutoConfirmScheduler {
    private static final int PAGE = 200;
    private final PaymentRequestRepository payments;
    private final PaymentRequestService service;
    private final Duration window;
    private final Duration warningLead;

    public RefundAutoConfirmScheduler(PaymentRequestRepository payments, PaymentRequestService service,
                                      @Value("${rental.refund-auto-confirm-hours:72}") long hours,
                                      @Value("${rental.refund-auto-confirm-warning-hours:24}") long warningHours) {
        this.payments = payments; this.service = service; this.window = Duration.ofHours(hours); this.warningLead = Duration.ofHours(warningHours);
    }

    @Scheduled(fixedDelayString = "${rental.refund-auto-confirm-check-ms:300000}")
    public void confirmStaleRefunds() {
        // Only reports old enough to be warned (service re-checks with the billing clock; over-fetching is harmless).
        Instant now = Instant.now(), clock = service.businessNow().isAfter(now) ? service.businessNow() : now;
        Instant reportedBefore = clock.minus(window.minus(warningLead));
        String afterId = null;
        while (true) {
            var ids = payments.findReportedRefundIdsAfter(PaymentStatus.TRANSFER_REPORTED, PaymentRequestService.AUTO_CONFIRMABLE_REFUNDS,
                    PaymentDirection.LANDLORD_TO_TENANT, reportedBefore, afterId, PageRequest.of(0, PAGE));
            for (String id : ids) {
                try {
                    if (service.autoConfirmStaleRefund(id, window, warningLead)) log.info("Auto-confirmed stale refund {}", id);
                    else service.warnRefundAutoConfirmSoon(id, window, warningLead); // once per report; no state change
                }
                catch (RuntimeException failure) { log.warn("Refund auto-confirmation deferred for payment {}: {}", id, failure.toString()); }
            }
            if (ids.size() < PAGE) return;
            afterId = ids.get(ids.size() - 1);
        }
    }
}
