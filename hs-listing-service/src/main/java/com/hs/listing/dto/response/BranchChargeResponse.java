package com.hs.listing.dto.response;

import com.hs.listing.model.constant.ListingEnums.BillingMethod;
import com.hs.listing.model.constant.ListingEnums.ChargeType;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BranchChargeResponse {
    private String id;
    private ChargeType chargeType;
    private BillingMethod billingMethod;
    private BigDecimal amount;
    private String currency;
    private String unit;
    private boolean includedInRent;
    private String customName;
    private String description;
    private Integer sortOrder;
}
