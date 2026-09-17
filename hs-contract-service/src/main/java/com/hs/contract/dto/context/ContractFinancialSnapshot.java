package com.hs.contract.dto.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot điều kiện tài chính (giá thuê, cọc, kỳ thanh toán).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractFinancialSnapshot {

    private String amountValue;
    private String amountNumber;
    private String amountWords;
    private String paymentCycle;
    private String paymentDueDay;
    private String paymentMethod;
    private String depositAmountValue;
    private String depositAmountNumber;
    private String depositAmountWords;
    private String depositDescription;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amountValue", amountValue != null ? amountValue : "");
        m.put("amountNumber", amountNumber != null ? amountNumber : "");
        m.put("amountWords", amountWords != null ? amountWords : "");
        m.put("paymentCycle", paymentCycle != null ? paymentCycle : "");
        m.put("paymentDueDay", paymentDueDay != null ? paymentDueDay : "");
        m.put("paymentMethod", paymentMethod != null ? paymentMethod : "");
        m.put("depositAmountValue", depositAmountValue != null ? depositAmountValue : "");
        m.put("depositAmountNumber", depositAmountNumber != null ? depositAmountNumber : "");
        m.put("depositAmountWords", depositAmountWords != null ? depositAmountWords : "");
        m.put("depositDescription", depositDescription != null ? depositDescription : "");
        return m;
    }
}
