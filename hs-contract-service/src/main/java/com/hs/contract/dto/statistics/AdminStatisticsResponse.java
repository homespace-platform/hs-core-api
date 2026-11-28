package com.hs.contract.dto.statistics;

import java.time.Instant;
import java.util.*;

/** Aggregate dimensions only. No entity or personal identifier is serialized. */
public record AdminStatisticsResponse(Range range, Instant generatedAt, Instant businessAsOf,
        Map<String, Object> kpi, Map<String, Object> series, Map<String, Object> breakdown,
        Map<String, Object> funnel, Map<String, Source> sources, List<String> degraded) {
    public record Range(String from, String to, String granularity, String timezone) {}
    public record Source(boolean available, String source, String limit) {}
}
