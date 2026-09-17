package com.hs.contract.dto.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot của một dòng tiện ích / quyền sử dụng trong hợp đồng.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractAmenitySnapshot {

    private int index;
    private String code;
    private String name;
    private String scope;        // "Riêng trong căn/phòng/nhà" | "Dùng chung" | "Theo phạm vi được bàn giao"
    private String costText;     // "Miễn phí" | "Đã bao gồm trong giá thuê" | "Thu theo biểu phí"
    private String conditionText;// Điều kiện áp dụng / ghi chú
    private String sourceType;   // "LISTING" | "SHARED_PROPERTY" | "CUSTOM" | "DERIVED_POLICY" (chỉ audit nội bộ)

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("code", code != null ? code : "");
        m.put("name", name != null ? name : "");
        m.put("scope", scope != null ? scope : "");
        m.put("costText", costText != null ? costText : "");
        m.put("conditionText", conditionText != null ? conditionText : "");
        m.put("sourceType", sourceType != null ? sourceType : "");
        return m;
    }
}
