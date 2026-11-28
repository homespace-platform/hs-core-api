package com.hs.contract.service.statistics;

import com.hs.common.advice.entity.AppException;
import com.hs.contract.dto.statistics.AdminStatisticsQuery;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.hs.contract.dto.statistics.AdminStatisticsQuery.Granularity.*;

class AdminStatisticsQueryTest {
    final LocalDate today=LocalDate.of(2026,11,28);
    @Test void defaultIsThirtyVietnamDaysAndPreviousIsSameLength() {
        var q=AdminStatisticsQuery.parse(Map.of(),today);
        assertEquals(LocalDate.of(2026,10,30),q.from()); assertEquals(today,q.to());
        assertEquals(30,q.buckets().size());
        assertEquals(Instant.parse("2026-10-29T17:00:00Z"),q.start());
        assertEquals(Duration.ofDays(30),Duration.between(q.previousStart(),q.start()));
        assertEquals(Instant.parse("2026-11-28T17:00:00Z"),q.end());
    }
    @Test void partialMonthsRemainPartialAndBucketsAreContinuous() {
        var q=new AdminStatisticsQuery(LocalDate.of(2026,1,31),LocalDate.of(2026,3,1),MONTH);
        q.validate(today); assertEquals(List.of("2026-01","2026-02","2026-03"),q.buckets());
        assertEquals(30,q.days());
        assertEquals(Instant.parse("2026-01-30T17:00:00Z"),q.start());
    }
    @Test void exactLimitsAcceptedAndInvalidRangesRejected() {
        new AdminStatisticsQuery(today.minusDays(91),today,DAY).validate(today);
        new AdminStatisticsQuery(LocalDate.of(2023,12,1),today,MONTH).validate(today);
        for(var q:List.of(new AdminStatisticsQuery(today,today.minusDays(1),DAY),
                new AdminStatisticsQuery(today,today.plusDays(1),DAY),
                new AdminStatisticsQuery(today.minusDays(92),today,DAY),
                new AdminStatisticsQuery(LocalDate.of(2023,11,30),today,MONTH),
                new AdminStatisticsQuery(LocalDate.of(0,1,1),today,MONTH)))
            assertThrows(AppException.class,()->q.validate(today));
    }
    @Test void rejectsUnknownDuplicatedEmptyAndMalformedParameters() {
        for(var p:List.of(Map.of("ownerId",new String[]{"other"}),Map.of("from",new String[]{"2026-01-01","2026-02-01"}),
                Map.of("granularity",new String[]{"day"}),Map.of("from",new String[]{"2026-02-30"}),
                Map.of("to",new String[]{""}),Map.of("to",new String[]{"2026-1-01"})))
            assertThrows(AppException.class,()->AdminStatisticsQuery.parse(p,today));
    }
}
