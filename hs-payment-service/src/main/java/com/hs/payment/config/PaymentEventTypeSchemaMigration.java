package com.hs.payment.config;

import com.hs.payment.model.constant.PaymentEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.Arrays;
import java.util.stream.Collectors;

/** ddl-auto=update does not expand an existing PostgreSQL enum CHECK. Preserve all existing events. */
@Component @RequiredArgsConstructor @Slf4j
public class PaymentEventTypeSchemaMigration {
    private final JdbcTemplate jdbc;
    @EventListener(ApplicationReadyEvent.class) @Transactional
    public void migrate() {
        var definitions=jdbc.queryForList("""
                select pg_get_constraintdef(c.oid) from pg_constraint c
                join pg_class t on t.oid=c.conrelid join pg_namespace n on n.oid=t.relnamespace
                where n.nspname=current_schema() and t.relname='payment_events'
                and c.conname='payment_events_event_type_check' and c.contype='c'
                """,String.class);
        if(definitions.isEmpty())return;
        if(Arrays.stream(PaymentEventType.values()).allMatch(e->definitions.getFirst().contains("'"+e.name()+"'")))return;
        String types=Arrays.stream(PaymentEventType.values()).map(e->"'"+e.name()+"'").collect(Collectors.joining(","));
        // PostgreSQL DDL is transactional: validation failure restores the original CHECK.
        jdbc.execute("ALTER TABLE payment_events DROP CONSTRAINT payment_events_event_type_check");
        jdbc.execute("ALTER TABLE payment_events ADD CONSTRAINT payment_events_event_type_check CHECK (event_type IN ("+types+"))");
        log.info("Expanded payment event constraint for auditable dispute routing");
    }
}
