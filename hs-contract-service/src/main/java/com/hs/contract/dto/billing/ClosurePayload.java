package com.hs.contract.dto.billing;
import java.math.BigDecimal;
import jakarta.validation.constraints.*;
import java.util.List;
public record ClosurePayload(@DecimalMin("0") BigDecimal electricityFinal, @DecimalMin("0") BigDecimal waterFinal,
    @NotNull @DecimalMin("0") @DecimalMax("1000000000") BigDecimal otherDeduction,
    @NotBlank @Size(min=10,max=2000) String note, boolean vacantPossessionConfirmed,
    List<ClosureDeduction> deductions, Integer expectedProposalVersion) {
    public ClosurePayload(BigDecimal electricityFinal, BigDecimal waterFinal, BigDecimal otherDeduction,
                          String note, boolean vacantPossessionConfirmed) {
        this(electricityFinal, waterFinal, otherDeduction, note, vacantPossessionConfirmed, null, null);
    }
}
