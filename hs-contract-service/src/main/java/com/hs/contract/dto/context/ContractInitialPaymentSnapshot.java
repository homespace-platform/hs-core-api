package com.hs.contract.dto.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot thông tin thanh toán ban đầu của người thuê trước khi lập hợp đồng.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractInitialPaymentSnapshot {

    private String status;
    private String paidAt;
    private String provider;
    private String transactionCode;
    private String monthlyRent;
    private String monthlyCharges;
    private String depositAmount;
    private String totalAmount;
    private String currency;

    @Builder.Default
    private List<InitialPaymentRow> rows = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InitialPaymentRow {
        private String itemName;
        private String amountText;
        private String note;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status != null ? status : "");
        m.put("paidAt", paidAt != null ? paidAt : "");
        m.put("provider", provider != null ? provider : "");
        m.put("transactionCode", transactionCode != null ? transactionCode : "");
        m.put("monthlyRent", monthlyRent != null ? monthlyRent : "");
        m.put("monthlyCharges", monthlyCharges != null ? monthlyCharges : "");
        m.put("depositAmount", depositAmount != null ? depositAmount : "");
        m.put("totalAmount", totalAmount != null ? totalAmount : "");
        m.put("currency", currency != null ? currency : "VND");
        return m;
    }
}
