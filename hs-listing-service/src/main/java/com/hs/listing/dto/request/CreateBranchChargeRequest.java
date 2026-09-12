package com.hs.listing.dto.request;

import com.hs.listing.model.constant.ListingEnums.BillingMethod;
import com.hs.listing.model.constant.ListingEnums.ChargeType;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateBranchChargeRequest {
    @NotNull(message = "Loại phí không được để trống")
    private ChargeType chargeType;

    @NotNull(message = "Phương thức tính phí không được để trống")
    private BillingMethod billingMethod;

    private BigDecimal amount;
    private String currency;
    private String unit;
    private boolean includedInRent;
    private String customName;
    private String description;
    private Integer sortOrder;
}
