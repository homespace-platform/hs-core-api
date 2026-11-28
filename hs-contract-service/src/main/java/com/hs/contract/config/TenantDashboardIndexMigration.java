package com.hs.contract.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Idempotent indexes for existing schemas as well as Hibernate-created schemas. */
@Component
@RequiredArgsConstructor
public class TenantDashboardIndexMigration {
    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void migrate() {
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_receipt_tenant_issued ON payment_receipts (tenant_id, issued_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_dispute_payer_status ON dispute_cases (payer_id, status)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_dispute_payee_status ON dispute_cases (payee_id, status)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_closure_status_refund_due ON contract_closures (status, refund_due_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_sig_contract_status ON signature_requests (contract_id, status)");
    }
}
