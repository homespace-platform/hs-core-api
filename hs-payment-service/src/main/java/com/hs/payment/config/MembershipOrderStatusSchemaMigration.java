package com.hs.payment.config;

import com.hs.payment.membership.MembershipOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Hibernate ddl-auto=update does not expand an existing PostgreSQL enum CHECK. */
@Component
@RequiredArgsConstructor
public class MembershipOrderStatusSchemaMigration {
    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void migrate() {
        var definitions = jdbc.queryForList("""
                select pg_get_constraintdef(c.oid) from pg_constraint c
                join pg_class t on t.oid=c.conrelid join pg_namespace n on n.oid=t.relnamespace
                where n.nspname=current_schema() and t.relname='membership_orders'
                and c.conname='membership_orders_status_check' and c.contype='c'
                """, String.class);
        if (definitions.isEmpty() || Arrays.stream(MembershipOrder.Status.values())
                .allMatch(status -> definitions.getFirst().contains("'" + status.name() + "'"))) return;

        String statuses = Arrays.stream(MembershipOrder.Status.values())
                .map(status -> "'" + status.name() + "'").collect(Collectors.joining(","));
        jdbc.execute("ALTER TABLE membership_orders DROP CONSTRAINT membership_orders_status_check");
        jdbc.execute("ALTER TABLE membership_orders ADD CONSTRAINT membership_orders_status_check CHECK (status IN (" + statuses + "))");
    }
}
