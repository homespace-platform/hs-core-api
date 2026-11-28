package com.hs.contract.dto.statistics;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.time.BillingTime;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public record AdminStatisticsQuery(LocalDate from, LocalDate to, Granularity granularity) {
    public enum Granularity { DAY, MONTH }

    public static AdminStatisticsQuery parse(Map<String, String[]> params, LocalDate today) {
        try {
            for (var p : params.entrySet()) {
                if (!Set.of("from", "to", "granularity").contains(p.getKey()) || p.getValue().length != 1
                        || p.getValue()[0].isBlank()) throw new IllegalArgumentException();
            }
            LocalDate to = params.containsKey("to") ? LocalDate.parse(params.get("to")[0]) : today;
            LocalDate from = params.containsKey("from") ? LocalDate.parse(params.get("from")[0]) : to.minusDays(29);
            Granularity g = params.containsKey("granularity") ? Granularity.valueOf(params.get("granularity")[0]) : Granularity.DAY;
            var q = new AdminStatisticsQuery(from, to, g);
            q.validate(today);
            return q;
        } catch (IllegalArgumentException | DateTimeException ex) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
    }

    public void validate(LocalDate today) {
        if (from == null || to == null || granularity == null || from.getYear() < 1 || to.getYear() > 9999
                || from.isAfter(to) || to.isAfter(today)
                || granularity == Granularity.DAY && days() > 92
                || granularity == Granularity.MONTH && ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)) + 1 > 36)
            throw new AppException(ErrorCode.INVALID_REQUEST);
    }
    public long days() { return ChronoUnit.DAYS.between(from, to) + 1; }
    public Instant start() { return from.atStartOfDay(BillingTime.ZONE).toInstant(); }
    public Instant end() { return to.plusDays(1).atStartOfDay(BillingTime.ZONE).toInstant(); }
    public Instant previousStart() { return from.minusDays(days()).atStartOfDay(BillingTime.ZONE).toInstant(); }
    public List<String> buckets() {
        var result = new ArrayList<String>();
        LocalDate d = granularity == Granularity.DAY ? from : from.withDayOfMonth(1);
        while (!d.isAfter(to)) {
            result.add(granularity == Granularity.DAY ? d.toString() : YearMonth.from(d).toString());
            d = granularity == Granularity.DAY ? d.plusDays(1) : d.plusMonths(1);
        }
        return List.copyOf(result);
    }
}
