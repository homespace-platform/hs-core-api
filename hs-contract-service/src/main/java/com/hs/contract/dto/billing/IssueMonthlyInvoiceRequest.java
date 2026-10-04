package com.hs.contract.dto.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record IssueMonthlyInvoiceRequest(
        @DecimalMin("0") BigDecimal electricityEnd,
        @DecimalMin("0") BigDecimal waterEnd,
        @Valid List<ExtraCharge> extraCharges) {
    public record ExtraCharge(@NotBlank @Size(max = 150) String description,
                              @NotNull @DecimalMin("0.01") BigDecimal amount) {}
}
