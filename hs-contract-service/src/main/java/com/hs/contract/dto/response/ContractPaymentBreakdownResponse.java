package com.hs.contract.dto.response;

import com.hs.contract.model.constant.ContractPaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractPaymentBreakdownResponse {
    private String contractId;
    private BigDecimal monthlyRent;
    private BigDecimal deposit;
    private List<ChargeItem> charges;
    private BigDecimal chargesTotal;
    private BigDecimal totalAmount;
    private List<String> excludedMeterCharges;
    private ContractPaymentStatus paymentStatus;
    private Instant paidAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChargeItem {
        private String name;
        private BigDecimal amount;
    }
}
