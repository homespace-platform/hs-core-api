package com.hs.contract.config;

import com.hs.contract.model.constant.ContractStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Refresh only Hibernate's known enum check; ddl-auto=update does not expand existing PostgreSQL checks. */
@Component @RequiredArgsConstructor @Slf4j
public class ContractStatusSchemaMigration {
    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void migrate() {
        var definitions=jdbc.queryForList("""
                select pg_get_constraintdef(c.oid) from pg_constraint c
                join pg_class t on t.oid=c.conrelid join pg_namespace n on n.oid=t.relnamespace
                where n.nspname=current_schema() and t.relname='contracts'
                and c.conname='contracts_status_check' and c.contype='c'
                """,String.class);
        if(definitions.isEmpty()) return; // Fresh databases already receive the full enum from Hibernate.
        String definition=definitions.get(0);
        if(Arrays.stream(ContractStatus.values()).allMatch(s->definition.contains("'"+s.name()+"'"))) return;
        String statuses=Arrays.stream(ContractStatus.values()).map(s->"'"+s.name()+"'").collect(Collectors.joining(","));
        // Atomic PostgreSQL DDL: failure to validate restores the old constraint, never drops protection permanently.
        jdbc.execute("ALTER TABLE contracts DROP CONSTRAINT contracts_status_check");
        jdbc.execute("ALTER TABLE contracts ADD CONSTRAINT contracts_status_check CHECK (status IN ("+statuses+"))");
        log.info("Refreshed contracts_status_check for lease expiration and settlement states");
    }
}
