package com.hs.listing.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelAppointmentRequest(
        @NotBlank(message = "cancelReason is required")
        @Size(max = 500, message = "cancelReason cannot exceed 500 characters")
        String cancelReason
) {}
