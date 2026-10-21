package com.hs.listing.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateRentalRequest(
        @NotBlank(message = "listingId is required")
        String listingId,

        @NotNull(message = "moveInDate is required")
        LocalDate moveInDate,

        @NotNull(message = "leaseMonths is required")
        @Min(value = 1, message = "leaseMonths must be at least 1")
        @Max(value = 240, message = "leaseMonths cannot exceed 240")
        Integer leaseMonths,

        @Min(value = 1, message = "occupantCount must be at least 1")
        @Max(value = 100, message = "occupantCount cannot exceed 100")
        Integer occupantCount,

        @Min(value = 0, message = "motorbikeCount cannot be negative")
        Integer motorbikeCount,

        @Min(value = 0, message = "carCount cannot be negative")
        Integer carCount,

        @NotBlank(message = "renterName is required")
        @Size(max = 100, message = "renterName cannot exceed 100 characters")
        String renterName,

        @NotBlank(message = "renterPhone is required")
        @Size(max = 20, message = "renterPhone cannot exceed 20 characters")
        String renterPhone,

        @Size(max = 150, message = "renterEmail cannot exceed 150 characters")
        String renterEmail,

        @DecimalMin(value = "0", message = "depositAmount cannot be negative")
        BigDecimal depositAmount,

        @Size(max = 500, message = "renterNote cannot exceed 500 characters")
        String renterNote,
        boolean reservationPolicyAccepted
) {
    public CreateRentalRequest(String listingId, java.time.LocalDate moveInDate,Integer leaseMonths,Integer occupantCount,
            Integer motorbikeCount,Integer carCount,String renterName,String renterPhone,String renterEmail,
            java.math.BigDecimal depositAmount,String renterNote) {
        this(listingId,moveInDate,leaseMonths,occupantCount,motorbikeCount,carCount,renterName,renterPhone,renterEmail,depositAmount,renterNote,false);
    }
}
