package com.hs.contract.dto.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class RentalWorkflowPayloads {
    private RentalWorkflowPayloads() {}
    public record Action(@NotNull @PositiveOrZero Long expectedRevision,
        @NotBlank @Size(min=10,max=1000) String reason, boolean acknowledged, boolean tenantContacted) {}
    public record Cancellation(@NotNull @PositiveOrZero Long expectedRevision,
        @NotBlank @Size(min=10,max=1000) String reason,
        @Size(max=5) List<@NotBlank String> evidenceIds) {}
    public record RentRefund(@NotBlank String paymentId,@NotNull @DecimalMin("0.01") @Digits(integer=10,fraction=2) BigDecimal amount) {}
    public record Termination(@NotNull @PositiveOrZero Long expectedRevision,@NotNull LocalDate endExclusive,
        @NotBlank @Size(min=10,max=1000) String reason,@NotBlank @Size(min=10,max=1000) String handoverTerms,
        @NotBlank @Pattern(regexp="FULL_STARTED_PERIOD|DAILY_PRORATED") String finalRentPolicy,
        @NotNull @Size(max=50) List<@Valid RentRefund> rentRefunds,
        @Size(max=5) List<@NotBlank String> evidenceIds) {}
}
