package com.hs.contract.dto.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot của một dòng chi phí dịch vụ.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractChargeSnapshot {

    private String name;
    private String amountAndMethod;
    private String note;
    private String estimatedMonthlyAmount;
    private String chargeType;
    private String billingMethod;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name != null ? name : "");
        m.put("amountAndMethod", amountAndMethod != null ? amountAndMethod : "");
        m.put("note", note != null ? note : "-");
        m.put("estimatedMonthlyAmount", estimatedMonthlyAmount);
        m.put("chargeType", chargeType);
        m.put("billingMethod", billingMethod);
        return m;
    }
}
