package com.hs.listing.service;

import com.hs.listing.dto.response.RentalStartChangeSummary;
import com.hs.listing.model.RentalStartChange;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RentalStartChangeSummaryTest {
    Instant now=Instant.parse("2026-10-22T03:00:00Z");
    RentalStartChange change=RentalStartChange.builder().id("change").previousDate(LocalDate.of(2026,11,1))
        .requestedDate(LocalDate.of(2026,10,25)).status("REQUESTED").reason("Muốn nhận nhà sớm hơn")
        .requestedAt(now.minusSeconds(60)).respondBy(now.plusSeconds(60)).build();
    @Test void pendingSummaryContainsDatesAndDoesNotMutateEntity() {
        var result=RentalStartChangeSummary.from(change,now);
        assertEquals("REQUESTED",result.status());assertEquals(change.getRequestedDate(),result.requestedDate());
        assertEquals(change.getReason(),result.reason());
        assertEquals("EXPIRED",RentalStartChangeSummary.from(change,change.getRespondBy()).status());
        assertEquals("REQUESTED",change.getStatus());
    }
    @Test void vietnamDateAndDecidedHistoryAreRespected() {
        change.setRequestedDate(LocalDate.of(2026,10,22));change.setRespondBy(now.plusSeconds(200000));
        assertEquals("REQUESTED",RentalStartChangeSummary.from(change,Instant.parse("2026-10-22T16:59:59Z")).status());
        assertEquals("EXPIRED",RentalStartChangeSummary.from(change,Instant.parse("2026-10-22T17:00:00Z")).status());
        for(String status:new String[]{"APPROVED","REJECTED","WITHDRAWN","EXPIRED"}) {
            change.setStatus(status);assertEquals(status,RentalStartChangeSummary.from(change,now.plusSeconds(300000)).status());
        }
    }
}
