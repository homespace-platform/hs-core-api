package com.hs.contract.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @RequiredArgsConstructor
public class AdminDashboardIndexMigration {
    private final JdbcTemplate jdbc;
    @EventListener(ApplicationReadyEvent.class) @Transactional
    public void migrate(){
        // Share the P5 index names when the same access path is already present.
        index("idx_stats_users_created","users","created_at");
        index("idx_stats_payments_confirmed","payment_requests","status,confirmed_at");
        index("idx_stats_membership_paid","membership_orders","status,paid_at");
        index("idx_admin_membership_expiry","membership_orders","status,expires_at");
        index("idx_admin_signature_age","signature_requests","status,initiated_at");
        index("idx_stats_kyc_created","kyc_verifications","created_at");
        index("idx_admin_kyc_current","kyc_verifications","user_id,status,verified_at desc,created_at desc");
        index("idx_admin_kyc_steps_created","kyc_provider_steps","provider,created_at");
        index("idx_admin_renewal_response","contract_renewals","status,respond_by");
        index("idx_admin_invoices_due","monthly_invoices","status,due_at");
    }
    private void index(String name,String table,String columns){if(Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null",Boolean.class,table)))jdbc.execute("create index if not exists "+name+" on "+table+"("+columns+")");}
}
