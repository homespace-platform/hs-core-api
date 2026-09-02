package com.hs.listing.dto.response;

import com.hs.listing.model.constant.AppointmentCancelledBy;
import com.hs.listing.model.constant.AppointmentStatus;
import com.hs.listing.model.constant.ListingEnums.ViewingSlot;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

@Builder
public record AppointmentResponse(
        String id,
        String listingId,
        String listingTitle,
        String listingAddress,
        String listingThumbnail,
        BigDecimal listingPrice,
        String ownerId,
        String renterId,
        String renterName,
        String renterPhone,
        String renterEmail,
        Integer visitorCount,
        String renterNote,
        LocalDate appointmentDate,
        LocalTime startTime,
        LocalTime endTime,
        ViewingSlot slotType,
        AppointmentStatus status,
        String ownerNote,
        String rejectReason,
        AppointmentCancelledBy cancelledBy,
        String cancelReason,
        Boolean rescheduleRequested,
        LocalDate proposedDate,
        LocalTime proposedStartTime,
        LocalTime proposedEndTime,
        ViewingSlot proposedSlotType,
        String rescheduleReason,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) {}
