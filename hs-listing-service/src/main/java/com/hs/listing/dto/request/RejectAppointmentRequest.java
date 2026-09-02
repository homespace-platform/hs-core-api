package com.hs.listing.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectAppointmentRequest(
        @NotBlank(message = "rejectReason is required")
        @Size(max = 500, message = "rejectReason cannot exceed 500 characters")
        String rejectReason
) {}
