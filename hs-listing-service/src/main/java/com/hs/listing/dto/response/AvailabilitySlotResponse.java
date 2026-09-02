package com.hs.listing.dto.response;

import com.hs.listing.model.constant.ListingEnums.ViewingSlot;
import lombok.Builder;

import java.time.LocalTime;

@Builder
public record AvailabilitySlotResponse(
        LocalTime startTime,
        LocalTime endTime,
        ViewingSlot slotType,
        String status // AVAILABLE, LOCKED, PENDING_YOU, CONFIRMED_YOU, UNAVAILABLE
) {}
