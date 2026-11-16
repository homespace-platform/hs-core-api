package com.hs.user.config.vnpt;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** ddl-auto=update does not expand an existing Hibernate enum CHECK. */
@Component @RequiredArgsConstructor
public class KycProviderSchemaMigration {
    private final JdbcTemplate jdbc;
    @EventListener(ApplicationReadyEvent.class) @Transactional
    public void migrate() {
        var definitions=jdbc.queryForList("select pg_get_constraintdef(c.oid) from pg_constraint c where c.conrelid = to_regclass('kyc_verifications') and c.conname = 'kyc_verifications_provider_check' and c.contype = 'c'",String.class);
        if(definitions.isEmpty() || (definitions.get(0).contains("'VNPT'") && definitions.get(0).contains("'ADMIN'")))return;
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        jdbc.execute("ALTER TABLE kyc_verifications DROP CONSTRAINT kyc_verifications_provider_check");
        jdbc.execute("ALTER TABLE kyc_verifications ADD CONSTRAINT kyc_verifications_provider_check CHECK (provider IN ('DIDIT','VNPT','ADMIN'))");
    }
}
