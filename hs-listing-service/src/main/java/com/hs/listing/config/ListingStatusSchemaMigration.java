package com.hs.listing.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hibernate ddl-auto=update does not refresh PostgreSQL CHECK constraints when enum values change.
 * This keeps listing status columns aligned with {@link com.hs.listing.model.constant.ListingStatus}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ListingStatusSchemaMigration {
    private static final String ALLOWED_STATUSES =
            "'DRAFT','PENDING_REVIEW','PUBLISHED','RESERVED','RENTED','RENTED_EXTERNALLY',"
                    + "'EXPIRED','REJECTED','HIDDEN','VIOLATION'";

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        refreshConstraint("listings", "status", "listings_status_check", false);
        refreshConstraint("listing_status_history", "from_status", "listing_status_history_from_status_check", true);
        refreshConstraint("listing_status_history", "to_status", "listing_status_history_to_status_check", false);
    }

    private void refreshConstraint(String table, String column, String constraintName, boolean nullable) {
        Integer exists = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                WHERE t.relname = ?
                  AND c.conname = ?
                  AND c.contype = 'c'
                """,
                Integer.class,
                table,
                constraintName);
        if (exists == null || exists == 0) {
            return;
        }

        String checkExpression = nullable
                ? column + " IS NULL OR " + column + " IN (" + ALLOWED_STATUSES + ")"
                : column + " IN (" + ALLOWED_STATUSES + ")";

        jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + constraintName);
        jdbcTemplate.execute(
                "ALTER TABLE " + table + " ADD CONSTRAINT " + constraintName + " CHECK (" + checkExpression + ")");
        log.info("Refreshed {} on {}.{}", constraintName, table, column);
    }
}
