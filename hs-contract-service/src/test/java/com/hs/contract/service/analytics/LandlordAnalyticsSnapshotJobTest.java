package com.hs.contract.service.analytics;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LandlordAnalyticsSnapshotJobTest {
    @Test void failedBranchDoesNotBlockRemainingBranchesAndCursorAdvances() {
        var jdbc=mock(JdbcTemplate.class);var analytics=mock(LandlordAnalyticsService.class);
        when(jdbc.query(anyString(),any(RowMapper.class),eq(""))).thenReturn(List.of(new String[]{"a","owner"},new String[]{"b","owner"}));
        when(jdbc.query(anyString(),any(RowMapper.class),eq("b"))).thenReturn(List.of());
        doThrow(new IllegalStateException("test")).when(analytics).capturePreviousMonth("owner","a");
        new BranchOccupancySnapshotJob(jdbc,analytics).capture();
        verify(analytics).capturePreviousMonth("owner","a");verify(analytics).capturePreviousMonth("owner","b");
        verify(jdbc).query(anyString(),any(RowMapper.class),eq("b"));
    }
}
