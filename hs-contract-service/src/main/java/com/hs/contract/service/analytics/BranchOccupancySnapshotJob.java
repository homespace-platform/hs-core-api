package com.hs.contract.service.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries missing previous-month snapshots daily; an existing snapshot is immutable. */
@Component @RequiredArgsConstructor @Slf4j
public class BranchOccupancySnapshotJob {
    private final JdbcTemplate jdbc;
    private final LandlordAnalyticsService analytics;

    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Ho_Chi_Minh")
    public void capture() {
        String after = "";
        while (true) {
            var batch = jdbc.query("select id, owner_id from property_branches where active = true and id > ? order by id limit 500",
                    (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, after);
            if (batch.isEmpty()) return;
            for (var branch : batch) {
                try { analytics.capturePreviousMonth(branch[1], branch[0]); }
                catch (Exception e) { log.error("OCCUPANCY_SNAPSHOT_FAILED branch={}", branch[0], e); }
            }
            after = batch.getLast()[0];
        }
    }
}
