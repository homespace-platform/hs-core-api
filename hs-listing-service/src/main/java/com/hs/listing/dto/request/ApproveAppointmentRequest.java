package com.hs.listing.dto.request;

import jakarta.validation.constraints.Size;

public record ApproveAppointmentRequest(
        @Size(max = 500, message = "ownerNote cannot exceed 500 characters")
        String ownerNote
) {}
