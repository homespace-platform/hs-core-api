package com.hs.payment.service;

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
 * Owner rule: a receiver who does not confirm or reject a reported transfer by its confirmation deadline is treated as
 * having received it. Refunds have their own longer window ({@link RefundAutoConfirmScheduler}). Each row runs in its
 * own transaction and the service re-checks everything under the row lock, so reruns and poison rows are harmless.
 */
@Component @Slf4j
public class ReceiptAutoConfirmScheduler {
    private static final int PAGE = 200;
    private final PaymentRequestRepository payments;
    private final PaymentRequestService service;
    private final Duration warningLead;

    public ReceiptAutoConfirmScheduler(PaymentRequestRepository payments, PaymentRequestService service,
                                       @Value("${rental.receipt-auto-confirm-warning-hours:12}") long warningHours) {
        this.payments = payments; this.service = service; this.warningLead = Duration.ofHours(warningHours);
    }

    @Scheduled(fixedDelayString = "${rental.receipt-auto-confirm-check-ms:300000}")
    public void confirmOverdueReceipts() {
        Instant now = Instant.now();
        // Billing-time payments compare against BillingTime (service re-checks); over-fetching here is harmless.
        Instant horizon = (service.businessNow().isAfter(now) ? service.businessNow() : now).plus(warningLead);
        String afterId = null;
        while (true) {
            var ids = payments.findReportedIdsDueByAfter(PaymentStatus.TRANSFER_REPORTED, PaymentRequestService.AUTO_CONFIRMABLE_REFUNDS,
                    horizon, afterId, PageRequest.of(0, PAGE));
            for (String id : ids) {
                try {
                    if (service.autoConfirmOverdueReceipt(id, now, warningLead)) log.info("Auto-confirmed overdue receipt {}", id);
                    else service.warnReceiptAutoConfirmSoon(id, now, warningLead); // once per report; no state change
                }
                catch (RuntimeException failure) { log.warn("Receipt auto-confirmation deferred for payment {}: {}", id, failure.toString()); }
            }
            if (ids.size() < PAGE) return;
            afterId = ids.get(ids.size() - 1);
        }
    }
}
