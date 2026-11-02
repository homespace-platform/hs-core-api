package com.hs.payment.dto;
import com.hs.payment.model.constant.DisputeResolution;
import jakarta.validation.constraints.*;
public record DisputeCaseAction(@NotNull @Min(1) Long expectedRevision,
        @NotBlank @Size(min=10,max=500) String reason,
        DisputeResolution resolution,@Size(max=36) String evidenceStorageId) {}
