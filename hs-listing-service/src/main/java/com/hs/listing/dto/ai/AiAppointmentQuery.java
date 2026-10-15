package com.hs.listing.dto.ai;

import java.util.List;

/**
 * Raw query parameters of the AI appointment search/summary. Everything is a String on purpose: the service
 * validates and parses it so invalid input always yields one fixed message (no echo of values), instead of a
 * framework binding error. There is deliberately no user/owner/renter id here: identity is the signed-in caller.
 */
public record AiAppointmentQuery(
        String role,
        List<String> status,
        String datePreset,
        String dateFrom,
        String dateTo,
        String timeFrom,
        String timeTo,
        String listingId,
        String rescheduleRequested,
        List<String> timePhase,
        String q,
        String sort,
        String page,
        String size) {
}
