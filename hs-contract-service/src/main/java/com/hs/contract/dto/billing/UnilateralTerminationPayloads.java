package com.hs.contract.dto.billing;

import jakarta.validation.constraints.*;

public final class UnilateralTerminationPayloads {
    private UnilateralTerminationPayloads() {}
    public record Issue(@NotBlank @Size(min=10,max=1000) String reason) {}
    public record Review(@Min(1) int expectedVersion, @NotNull UnilateralTerminationNotice.Option option,
            @Min(1) @Max(30) Integer days, @NotBlank @Size(min=10,max=1000) String reason) {}
    public record Decision(@Min(1) int expectedVersion, @NotNull Boolean approve, @NotBlank @Size(min=10,max=1000) String reason) {}
    public record Version(@Min(1) int expectedVersion) {}
    public record Handover(@Min(1) int expectedVersion, @AssertTrue boolean vacantPossessionConfirmed, @AssertTrue boolean keysAndAssetsReturned) {}
}
