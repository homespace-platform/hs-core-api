package com.hs.contract.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read indexes for bounded admin reports, including existing databases. */
@Component
@RequiredArgsConstructor
public class AdminStatisticsIndexMigration {
    private final JdbcTemplate jdbc;
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void migrate() {
        index("idx_stats_users_created","users","created_at");
        index("idx_stats_listings_created","listings","created_at");
        index("idx_stats_listings_owner_created","listings","owner_id,created_at");
        index("idx_stats_contracts_created","contracts","created_at");
        index("idx_stats_contracts_signed","contracts","signed_at");
        index("idx_stats_payments_confirmed","payment_requests","status,confirmed_at");
        index("idx_stats_membership_paid","membership_orders","status,paid_at");
        index("idx_stats_membership_created","membership_orders","created_at");
        index("idx_stats_kyc_created","kyc_verifications","created_at");
        index("idx_stats_kyc_verified","kyc_verifications","verified_at");
        index("idx_stats_payment_events_created","payment_events","created_at,event_type");
        index("idx_stats_rentals_created","rental_requests","created_at");
        index("idx_stats_appointments_created","viewing_appointments","created_at");
        index("idx_stats_incidents_opened","operational_incidents","opened_at");
        index("idx_stats_moderation_created","listing_status_history","created_at");
        index("idx_stats_invoices_due","monthly_invoices","due_at");
    }
    private void index(String name,String table,String columns) {
        // An unavailable optional source must not prevent the application from starting.
        if(Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null",Boolean.class,table)))
            jdbc.execute("create index if not exists "+name+" on "+table+"("+columns+")");
    }
}
