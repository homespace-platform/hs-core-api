package com.hs.contract.dto.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot thời hạn thuê nhà.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractLeaseSnapshot {

    private String startDateText;
    private String endDateText;
    private Integer durationMonths;
    private String durationText;
    private String handoverDateText;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("startDateText", startDateText != null ? startDateText : "");
        m.put("endDateText", endDateText != null ? endDateText : "");
        m.put("durationMonths", durationMonths != null ? String.valueOf(durationMonths) : "");
        m.put("durationText", durationText != null ? durationText : "");
        m.put("handoverDateText", handoverDateText != null ? handoverDateText : "");
        return m;
    }
}
