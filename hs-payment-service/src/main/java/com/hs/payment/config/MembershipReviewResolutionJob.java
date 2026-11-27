package com.hs.payment.config;

import com.hs.listing.membership.MembershipEventRepository;
import com.hs.listing.membership.MembershipService;
import com.hs.payment.membership.MembershipOrder;
import com.hs.payment.membership.MembershipOrderRepository;
import com.hs.payment.membership.MembershipPaymentApplier;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** One-time startup pass: legacy REVIEW membership orders are applied by the no-reconciliation rules. Idempotent. */
@Component
@RequiredArgsConstructor
public class MembershipReviewResolutionJob {
    private static final Logger log = LoggerFactory.getLogger(MembershipReviewResolutionJob.class);
    private static final int BATCH = 50;
    private final MembershipOrderRepository orders;
    private final MembershipEventRepository events;
    private final MembershipService memberships;
    private final MembershipPaymentApplier applier;
    private final TransactionTemplate tx;

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        run(BATCH);
    }

    /** Returns the number of orders resolved. */
    public int run(int batch) {
        int done = 0;
        while (true) {
            var ids = orders.findByStatusOrderById(MembershipOrder.Status.REVIEW, PageRequest.of(0, batch))
                    .map(MembershipOrder::getId).getContent();
            if (ids.isEmpty()) break;
            int resolved = 0;
            for (String id : ids) resolved += Boolean.TRUE.equals(tx.execute(s -> resolve(id))) ? 1 : 0;
            done += resolved;
            if (resolved == 0) break; // nothing progressed; avoid spinning on rows that stay REVIEW
        }
        if (done > 0) log.info("Resolved {} legacy REVIEW membership orders", done);
        return done;
    }

    private boolean resolve(String id) {
        var preview = orders.findById(id).orElse(null);
        if (preview == null) return false;
        memberships.lockOwner(preview.getOwnerId());
        var o = orders.lockById(id).orElse(null);
        if (o == null || o.getStatus() != MembershipOrder.Status.REVIEW) return false;
        applier.resolveLegacyReview(o, events.existsByOrderId(o.getId()));
        return true;
    }
}
