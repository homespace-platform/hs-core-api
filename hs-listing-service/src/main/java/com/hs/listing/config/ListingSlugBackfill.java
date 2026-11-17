package com.hs.listing.config;

import com.hs.listing.service.ListingSlugGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Idempotent: gives every listing without a slug one (e.g. rows inserted by the SQL seed). Plain SQL so the
 * backfill does not bump entity version/audit columns. Rows that already have a slug are never touched.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ListingSlugBackfill {
    static final int BATCH_SIZE = 200;

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        int total = 0;
        while (true) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, title FROM listings WHERE slug IS NULL ORDER BY id LIMIT ?", BATCH_SIZE);
            if (rows.isEmpty()) break;
            int assigned = 0;
            for (Map<String, Object> row : rows) {
                try {
                    String slug = ListingSlugGenerator.generate((String) row.get("title"), this::exists);
                    assigned += jdbcTemplate.update("UPDATE listings SET slug = ? WHERE id = ? AND slug IS NULL",
                            slug, row.get("id"));
                } catch (RuntimeException e) {
                    log.warn("Slug backfill skipped listing {}: {}", row.get("id"), e.getMessage());
                }
            }
            total += assigned;
            if (assigned == 0) break; // nothing progressed; avoid spinning, retry on next start
        }
        if (total > 0) log.info("Backfilled slug for {} listings", total);
    }

    private boolean exists(String slug) {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM listings WHERE slug = ?", Integer.class, slug);
        return n != null && n > 0;
    }
}
