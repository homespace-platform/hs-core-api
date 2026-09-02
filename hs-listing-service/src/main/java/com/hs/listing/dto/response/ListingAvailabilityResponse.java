package com.hs.listing.dto.response;

import com.hs.listing.model.constant.ListingEnums.ViewingSlot;
import lombok.Builder;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

@Builder
public record ListingAvailabilityResponse(
        String listingId,
        LocalDate date,
        DayOfWeek dayOfWeek,
        boolean isDayAvailable,
        List<DayOfWeek> allowedViewingDays,
        List<ViewingSlot> allowedViewingSlots,
        List<AvailabilitySlotResponse> slots,
        boolean hasExistingActiveBooking,
        String existingBookingId
) {}
