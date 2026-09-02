package com.hs.listing.dto.request;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalTime;

public record CreateAppointmentRequest(
        @NotBlank(message = "listingId is required")
        String listingId,

        @NotNull(message = "appointmentDate is required")
        LocalDate appointmentDate,

        @NotNull(message = "startTime is required")
        LocalTime startTime,

        @NotNull(message = "endTime is required")
        LocalTime endTime,

        @NotBlank(message = "renterName is required")
        @Size(max = 100, message = "renterName cannot exceed 100 characters")
        String renterName,

        @NotBlank(message = "renterPhone is required")
        @Size(max = 20, message = "renterPhone cannot exceed 20 characters")
        String renterPhone,

        @Min(value = 1, message = "visitorCount must be at least 1")
        @Max(value = 10, message = "visitorCount cannot exceed 10")
        Integer visitorCount,

        @Size(max = 500, message = "renterNote cannot exceed 500 characters")
        String renterNote
) {}
