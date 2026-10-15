package com.hs.contract.dto.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tên tiện ích đã lưu; không có chi phí, phạm vi hoặc điều kiện suy diễn.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"scope", "costText", "conditionText"})
public class ContractAmenitySnapshot {

    private int index;
    private String code;
    private String name;
    private String sourceType;   // Audit metadata, not a rendered contractual term.

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("code", code != null ? code : "");
        m.put("name", name != null ? name : "");
        m.put("sourceType", sourceType != null ? sourceType : "");
        return m;
    }
}
