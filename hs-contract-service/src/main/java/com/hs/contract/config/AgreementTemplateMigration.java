package com.hs.contract.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Additive migration; existing published lease templates remain lease templates. */
@Component @RequiredArgsConstructor
public class AgreementTemplateMigration {
    private final JdbcTemplate jdbc;
    @EventListener(ApplicationReadyEvent.class) @Transactional
    public void migrate() {
        jdbc.execute("ALTER TABLE contract_templates ADD COLUMN IF NOT EXISTS document_kind varchar(20) DEFAULT 'LEASE'");
        jdbc.update("UPDATE contract_templates SET document_kind='LEASE' WHERE document_kind IS NULL");
        jdbc.execute("ALTER TABLE contract_templates DROP CONSTRAINT IF EXISTS uq_contract_template_category");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_contract_template_kind_category ON contract_templates(document_kind,category)");
    }
}
