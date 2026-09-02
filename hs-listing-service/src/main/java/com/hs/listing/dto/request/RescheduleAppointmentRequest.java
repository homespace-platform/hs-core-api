package com.hs.listing.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

public record RescheduleAppointmentRequest(
        @NotNull(message = "proposedDate is required")
        LocalDate proposedDate,

        @NotNull(message = "proposedStartTime is required")
        LocalTime proposedStartTime,

        @NotNull(message = "proposedEndTime is required")
        LocalTime proposedEndTime,

        @Size(max = 500, message = "rescheduleReason cannot exceed 500 characters")
        String rescheduleReason
) {}
