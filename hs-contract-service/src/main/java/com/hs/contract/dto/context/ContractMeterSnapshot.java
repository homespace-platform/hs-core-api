package com.hs.contract.dto.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot chỉ số công tơ điện / nước lúc bàn giao.
 * Đây là trường TÙY CHỌN (OPTIONAL) khi tạo và gửi hợp đồng; có thể hoàn tất tại biên bản bàn giao.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractMeterSnapshot {

    private String electricityInitial;
    private String waterInitial;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("electricityInitial", electricityInitial != null ? electricityInitial : "");
        m.put("waterInitial", waterInitial != null ? waterInitial : "");
        return m;
    }
}
