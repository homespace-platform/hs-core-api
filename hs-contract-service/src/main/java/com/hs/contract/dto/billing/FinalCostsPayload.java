package com.hs.contract.dto.billing;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
public record FinalCostsPayload(@NotBlank @Size(max=36) String requestId,
    @DecimalMin("0") BigDecimal electricityEnd, @DecimalMin("0") BigDecimal waterEnd,
    @Valid @Size(max=50) List<IssueMonthlyInvoiceRequest.ExtraCharge> extraCharges) {}
