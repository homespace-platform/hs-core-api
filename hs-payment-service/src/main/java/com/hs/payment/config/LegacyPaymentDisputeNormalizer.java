package com.hs.payment.config;

import com.hs.common.time.BillingTime;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.model.DisputeCaseEvent;
import com.hs.payment.model.PaymentEvent;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import com.hs.payment.service.PaymentRequestService;
import com.hs.payment.service.PaymentTimePolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * The payment-dispute feature was removed. Payments it left in DISPUTED are moved back to a workable status.
 * Idempotent: a no-op when nothing is DISPUTED. Complaint cases (other subject type) are never touched.
 */
@Component @Slf4j
public class LegacyPaymentDisputeNormalizer {
    static final String REASON = "Tính năng đối soát đã ngừng";
    private final PaymentRequestRepository payments;
    private final DisputeCaseRepository cases;
    private final DisputeCaseEventRepository caseEvents;
    private final PaymentEvidenceRepository evidence;
    private final PaymentEventRepository paymentEvents;
    private final DepositRecordRepository deposits;
    private final RentalRequestRepository rentals;
    private final BillingTime time;
    private final TransactionTemplate tx;
    private final long reopenHours;
    private final long confirmationHours;

    public LegacyPaymentDisputeNormalizer(PaymentRequestRepository payments, DisputeCaseRepository cases, DisputeCaseEventRepository caseEvents,
            PaymentEvidenceRepository evidence, PaymentEventRepository paymentEvents, DepositRecordRepository deposits,
            RentalRequestRepository rentals, BillingTime time, PlatformTransactionManager txManager,
            @Value("${rental.first-rent-window-hours:24}") long reopenHours,
            @Value("${homespace.payment.confirmation-window-hours:24}") long confirmationHours) {
        this.payments = payments; this.cases = cases; this.caseEvents = caseEvents; this.evidence = evidence;
        this.paymentEvents = paymentEvents; this.deposits = deposits; this.rentals = rentals; this.time = time;
        this.tx = new TransactionTemplate(txManager); this.reopenHours = reopenHours; this.confirmationHours = confirmationHours;
    }

    /** Target status for a payment leaving DISPUTED. Confirmed, final or DISPUTED prior states are never restored. */
    public static PaymentStatus decide(PaymentStatus previous, boolean hasTransferProof) {
        if (previous == PaymentStatus.AWAITING_TRANSFER || previous == PaymentStatus.TRANSFER_REPORTED
                || previous == PaymentStatus.REJECTED || previous == PaymentStatus.OVERDUE) return previous;
        return hasTransferProof ? PaymentStatus.TRANSFER_REPORTED : PaymentStatus.AWAITING_TRANSFER;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void normalize() {
        var ids = payments.findIdsByStatus(PaymentStatus.DISPUTED);
        if (ids.isEmpty()) return;
        int done = 0;
        for (var id : ids) {
            try { tx.executeWithoutResult(s -> normalizeOne(id)); done++; }
            catch (RuntimeException e) { log.error("Could not normalize legacy DISPUTED payment {}", id, e); }
        }
        log.info("Normalized {}/{} legacy DISPUTED payments", done, ids.size());
    }

    void normalizeOne(String id) {
        var p = payments.findByIdForUpdate(id).orElse(null);
        if (p == null || p.getStatus() != PaymentStatus.DISPUTED) return;
        var now = time.now();
        var open = cases.findByActiveKey("PAYMENT:" + id).orElse(null);
        boolean proof = !evidence.findByPaymentRequestIdOrderByCreatedAtDesc(id).isEmpty();
        var next = decide(open == null ? null : open.getPreviousPaymentStatus(), proof);
        if (next == PaymentStatus.AWAITING_TRANSFER || next == PaymentStatus.OVERDUE) {
            next = PaymentTimePolicy.usesBillingTime(p) && p.getDueAt() != null && !now.isBefore(p.getDueAt())
                    ? PaymentStatus.OVERDUE : PaymentStatus.AWAITING_TRANSFER;
            if (PaymentRequestService.isOnboarding(p) && p.getRentalRequestId() != null) {
                var rental = rentals.findById(p.getRentalRequestId()).orElse(null);
                if (rental == null || rental.getStatus() != RentalRequestStatus.ACCEPTED) {
                    // The hold is already closed: never reopen a transfer (or extend a hold) for a dead request.
                    next = PaymentStatus.CANCELLED; p.setCancelledAt(now); p.setCancelledReason(REASON);
                } else {
                    Instant opens = p.getOpensAt() != null && p.getOpensAt().isAfter(now) ? p.getOpensAt() : now;
                    p.setDueAt(opens.plus(Duration.ofHours(reopenHours))); next = PaymentStatus.AWAITING_TRANSFER;
                    rental.setHoldExpiresAt(p.getDueAt()); rentals.save(rental);
                }
            }
            p.setConfirmationDueAt(null);
        } else if (next == PaymentStatus.TRANSFER_REPORTED) {
            p.setConfirmationDueAt(now.plus(Duration.ofHours(confirmationHours)));
        }
        if (p.getType() == PaymentType.DEPOSIT_REFUND) {
            var target = next == PaymentStatus.TRANSFER_REPORTED ? DepositStatus.REFUND_REPORTED : DepositStatus.REFUND_DUE;
            deposits.findByRefundPaymentRequestId(id).filter(d -> d.getStatus() == DepositStatus.DISPUTED)
                    .ifPresent(d -> { d.setStatus(target); deposits.save(d); });
        }
        p.setStatus(next); payments.save(p);
        paymentEvents.save(PaymentEvent.builder().paymentRequestId(id).eventType(PaymentEventType.DISPUTE_ROUTED)
                .fromStatus(PaymentStatus.DISPUTED).toStatus(next).actorId("system").actorRole("SYSTEM")
                .reason(REASON).createdAt(now).build());
        if (open != null) {
            open.setStatus(DisputeCaseStatus.RESOLVED); open.setActiveKey(null); open.setResolvedAt(now);
            open.setResolvedBy("system"); open.setResolutionReason(REASON); open.setRevision(open.getRevision() + 1);
            cases.save(open);
            caseEvents.save(DisputeCaseEvent.builder().caseId(open.getId()).revision(open.getRevision()).action("RESOLVED")
                    .actorId("system").actorRole("SYSTEM").reason(REASON).occurredAt(now).build());
        }
    }
}
