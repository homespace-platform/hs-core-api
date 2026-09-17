package com.hs.contract.dto.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot của một dòng tài sản / trang thiết bị nội thất bàn giao.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractEquipmentSnapshot {

    private int index;
    private String name;
    private int quantity;
    private String condition;
    private String note;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("name", name != null ? name : "");
        m.put("quantity", quantity);
        m.put("condition", condition != null ? condition : "Tốt");
        m.put("note", note != null ? note : "");
        return m;
    }
}
