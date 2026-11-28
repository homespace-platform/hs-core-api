package com.hs.listing.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Compatible with Hibernate update and existing installations; unknown response times stay null. */
@Component @RequiredArgsConstructor
public class AnalyticsResponseSchemaMigration {
    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class) @Transactional
    public void migrate() {
        jdbc.execute("alter table rental_requests add column if not exists responded_at timestamp with time zone");
        jdbc.execute("alter table viewing_appointments add column if not exists responded_at timestamp with time zone");
        jdbc.execute("alter table rental_requests add column if not exists response_type varchar(10)");
        jdbc.execute("alter table viewing_appointments add column if not exists response_type varchar(10)");
    }
}
