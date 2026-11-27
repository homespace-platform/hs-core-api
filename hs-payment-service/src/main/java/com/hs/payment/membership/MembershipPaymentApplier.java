package com.hs.payment.membership;

import com.hs.listing.membership.MembershipKind;
import com.hs.listing.membership.MembershipService;
import com.hs.payment.membership.notification.MembershipNotificationWriter;
import com.hs.payment.receipt.MembershipReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Applies a verified, correct-amount payment. A late payment activates; the same transaction is applied once; a second
 * distinct transaction on a PAID order, or a payment that cannot be activated, is recorded as an overpayment to refund.
 * Never parks an order for review. Caller holds the owner lock and the order lock.
 */
@Service
@RequiredArgsConstructor
public class MembershipPaymentApplier {
    private final MembershipOrderRepository orders;
    private final MembershipOverpaymentRepository overpayments;
    private final MembershipService memberships;
    private final MembershipNotificationWriter notifications;
    private final MembershipReceiptService receipts;

    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(MembershipOrder o, String tx) {
        if (tx.equals(o.getTransactionNo()) || orders.existsByProviderAndTransactionNo(o.getProvider(), tx)
                || overpayments.existsByProviderAndTransactionNo(o.getProvider(), tx)) return; // replay
        if (o.getStatus() == MembershipOrder.Status.PAID || o.getStatus() == MembershipOrder.Status.REVIEW) {
            overpay(o, tx);
            return;
        }
        fulfil(o, tx);
    }

    /** Legacy REVIEW row. {@code activated}: the original payment was already applied, so REVIEW marked a second one. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolveLegacyReview(MembershipOrder o, boolean activated) {
        if (o.getStatus() != MembershipOrder.Status.REVIEW) return;
        if (!activated) {
            fulfil(o, o.getTransactionNo());
            return;
        }
        o.setStatus(MembershipOrder.Status.PAID);
        orders.save(o);
        String tx = "rv-" + o.getId(); // the second transaction id was never stored
        if (!overpayments.existsByProviderAndTransactionNo(o.getProvider(), tx)) overpay(o, tx);
    }

    private void fulfil(MembershipOrder o, String tx) {
        MembershipOrder.Status before = o.getStatus();
        Instant now = Instant.now();
        if (!activate(o)) {
            o.setStatus(MembershipOrder.Status.FAILED); // the order cannot be fulfilled; the money is refunded
            orders.save(o);
            overpay(o, tx);
            return;
        }
        o.setTransactionNo(tx);
        o.setPaidAt(now);
        o.setStatus(MembershipOrder.Status.PAID);
        orders.save(o);
        receipts.capture(o);
        notifications.enqueueTransition(o, before);
    }

    private void overpay(MembershipOrder o, String tx) {
        var p = new MembershipOverpayment();
        p.setId(UUID.randomUUID().toString().replace("-", ""));
        p.setOrderId(o.getId());
        p.setOwnerId(o.getOwnerId());
        p.setProvider(o.getProvider());
        p.setTransactionNo(tx);
        p.setAmountVnd(o.getAmountVnd());
        p.setReceivedAt(Instant.now());
        overpayments.save(p);
        receipts.captureOverpayment(p);
        notifications.enqueueOverpayment(p);
    }

    private boolean activate(MembershipOrder o) {
        return o.getKind() == MembershipKind.ADDON
                ? memberships.activateAddon(o.getOwnerId(), o.getQuantity(), o.getId())
                : memberships.activate(o.getOwnerId(), o.getTier(), o.isUpgrade(), o.getId());
    }
}
