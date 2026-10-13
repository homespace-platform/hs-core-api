package com.hs.contract.dto.billing;
import jakarta.validation.constraints.*;
public record RenewalPayload(@Min(1) @Max(36) int months, @NotBlank @Size(min=10,max=1000) String note) {}
