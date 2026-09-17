package com.hs.contract.dto.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot các chính sách, nội quy và điều khoản pháp lý của hợp đồng.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractPolicySnapshot {

    private String paymentDueDay;
    private String paymentCycle;
    private Integer gracePeriodDays;
    private String latePaymentPolicy;
    private Integer noticeDaysBeforeMoveOut;
    private Integer noticeDaysBeforeTermination;
    private Integer depositRefundDays;
    private String depositDeductionConditions;
    private String subleasePolicy;
    private String overnightGuestPolicy;
    private String petPolicy;
    private String smokingPolicy;
    private String propertyInspectionNotice;
    private String vatPolicy;
    private String disputeResolution;
    private String forceMajeure;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentDueDay", paymentDueDay != null ? paymentDueDay : "");
        m.put("paymentCycle", paymentCycle != null ? paymentCycle : "");
        m.put("gracePeriodDays", gracePeriodDays != null ? String.valueOf(gracePeriodDays) : "");
        m.put("latePaymentPolicy", latePaymentPolicy != null ? latePaymentPolicy : "");
        m.put("noticeDaysBeforeMoveOut", noticeDaysBeforeMoveOut != null ? String.valueOf(noticeDaysBeforeMoveOut) : "");
        m.put("noticeDaysBeforeTermination", noticeDaysBeforeTermination != null ? String.valueOf(noticeDaysBeforeTermination) : "");
        m.put("depositRefundDays", depositRefundDays != null ? String.valueOf(depositRefundDays) : "");
        m.put("depositDeductionConditions", depositDeductionConditions != null ? depositDeductionConditions : "");
        m.put("subleasePolicy", subleasePolicy != null ? subleasePolicy : "");
        m.put("overnightGuestPolicy", overnightGuestPolicy != null ? overnightGuestPolicy : "");
        m.put("petPolicy", petPolicy != null ? petPolicy : "");
        m.put("smokingPolicy", smokingPolicy != null ? smokingPolicy : "");
        m.put("propertyInspectionNotice", propertyInspectionNotice != null ? propertyInspectionNotice : "");
        m.put("vatPolicy", vatPolicy != null ? vatPolicy : "");
        m.put("disputeResolution", disputeResolution != null ? disputeResolution : "");
        m.put("forceMajeure", forceMajeure != null ? forceMajeure : "");
        return m;
    }
}
