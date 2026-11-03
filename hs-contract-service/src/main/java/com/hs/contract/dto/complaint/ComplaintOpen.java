package com.hs.contract.dto.complaint;
import jakarta.validation.constraints.*;
import java.util.List;
public record ComplaintOpen(@NotNull ComplaintCategory category,@NotBlank @Size(min=10,max=500) String reason,
    @Size(max=5) List<@NotBlank @Size(max=36) String> evidenceIds,boolean urgent) {}
