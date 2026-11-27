package com.hs.payment.config;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** DB guard behind the owner lock: one PENDING membership order per owner. Idempotent; skipped if old rows collide. */
@Component
@RequiredArgsConstructor
public class MembershipPendingOrderIndex {
    private static final Logger log = LoggerFactory.getLogger(MembershipPendingOrderIndex.class);
    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void create() {
        try {
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_membership_orders_owner_pending "
                    + "ON membership_orders (owner_id) WHERE status = 'PENDING'");
        } catch (RuntimeException e) {
            log.warn("Could not create one-pending-order index (existing duplicate PENDING rows?): {}", e.getMessage());
        }
    }
}
