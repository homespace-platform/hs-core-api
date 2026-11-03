package com.hs.contract.dto.complaint;
import com.hs.contract.dto.billing.ClosurePayload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
public record ComplaintAction(@NotNull @Min(1) Long expectedRevision,@NotBlank @Size(min=10,max=500) String reason,
    @Size(max=5) List<@NotBlank @Size(max=36) String> evidenceIds,
    @Pattern(regexp="RECORD_FINDING|REVISE_SETTLEMENT") String outcome,
    @Size(max=36) String requestedFrom,@Valid ClosurePayload settlement) {}
