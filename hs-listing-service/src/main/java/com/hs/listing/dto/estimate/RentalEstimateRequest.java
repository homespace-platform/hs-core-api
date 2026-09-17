package com.hs.listing.dto.estimate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RentalEstimateRequest(
        @NotBlank(message = "listingId is required")
        String listingId,

        @NotNull(message = "moveInDate is required")
        LocalDate moveInDate,

        @NotNull(message = "leaseMonths is required")
        @Min(value = 1, message = "leaseMonths must be at least 1")
        Integer leaseMonths,

        @Min(value = 1, message = "occupantCount must be at least 1")
        Integer occupantCount,

        @Min(value = 0, message = "motorbikeCount cannot be negative")
        Integer motorbikeCount,

        @Min(value = 0, message = "carCount cannot be negative")
        Integer carCount,

        @DecimalMin(value = "0", message = "negotiatedDepositAmount cannot be negative")
        BigDecimal negotiatedDepositAmount
) {}
