package com.hs.listing.dto.request;

import jakarta.validation.constraints.Size;

public record RejectRentalRequest(
        @Size(max = 500, message = "rejectReason cannot exceed 500 characters")
        String rejectReason
) {}
