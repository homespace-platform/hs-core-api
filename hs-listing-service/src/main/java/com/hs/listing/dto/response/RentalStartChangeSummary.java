package com.hs.listing.dto.response;

import com.hs.listing.model.RentalStartChange;
import com.hs.common.time.BillingTime;
import java.time.Instant;
import java.time.LocalDate;

/** Read-only card summary; opening a list never advances the onboarding workflow. */
public record RentalStartChangeSummary(String id, LocalDate previousDate, LocalDate requestedDate, String status,
        String reason, Instant requestedAt, Instant respondBy, Instant decidedAt, String responseNote) {
    public static RentalStartChangeSummary from(RentalStartChange change, Instant now) {
        if (change == null) return null;
        String status = change.getStatus();
        if ("REQUESTED".equals(status) && (!now.isBefore(change.getRespondBy())
                || change.getRequestedDate().isBefore(LocalDate.ofInstant(now, BillingTime.ZONE)))) status = "EXPIRED";
        return new RentalStartChangeSummary(change.getId(), change.getPreviousDate(), change.getRequestedDate(), status,
                change.getReason(), change.getRequestedAt(), change.getRespondBy(), change.getDecidedAt(), change.getResponseNote());
    }
}
