package com.hs.listing.config;

import com.hs.listing.model.constant.RentalRequestStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Expand only Hibernate's known enum check; existing request data is preserved. */
@Component @RequiredArgsConstructor
public class RentalRequestStatusSchemaMigration {
    private final JdbcTemplate jdbc;
    @EventListener(ApplicationReadyEvent.class) @Transactional
    public void migrate() {
        var definitions=jdbc.queryForList("""
            select pg_get_constraintdef(c.oid) from pg_constraint c
            join pg_class t on t.oid=c.conrelid join pg_namespace n on n.oid=t.relnamespace
            where n.nspname=current_schema() and t.relname='rental_requests'
            and c.conname='rental_requests_status_check' and c.contype='c'
            """,String.class);
        if(definitions.isEmpty() || Arrays.stream(RentalRequestStatus.values()).allMatch(s->definitions.getFirst().contains("'"+s.name()+"'")))return;
        var statuses=Arrays.stream(RentalRequestStatus.values()).map(s->"'"+s.name()+"'").collect(Collectors.joining(","));
        jdbc.execute("ALTER TABLE rental_requests DROP CONSTRAINT rental_requests_status_check");
        jdbc.execute("ALTER TABLE rental_requests ADD CONSTRAINT rental_requests_status_check CHECK (status IN ("+statuses+"))");
    }
}
