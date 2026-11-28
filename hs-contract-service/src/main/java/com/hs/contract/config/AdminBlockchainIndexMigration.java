package com.hs.contract.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AdminBlockchainIndexMigration {
    private final JdbcTemplate jdbc;
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void migrate() {
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_proof_status_created ON contract_document_proofs(status,created_at desc,document_id desc)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_proof_created ON contract_document_proofs(created_at desc,document_id desc)");
    }
}
