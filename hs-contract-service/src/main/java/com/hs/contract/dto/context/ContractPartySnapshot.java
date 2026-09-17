package com.hs.contract.dto.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot thông tin các bên tham gia hợp đồng (Bên A và Bên B).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractPartySnapshot {

    private LandlordParty landlord;
    private TenantParty tenant;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LandlordParty {
        private String fullName;
        private String idNumber;
        private String permanentAddress;
        private String phone;
        private String email;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fullName", fullName != null ? fullName : "");
            m.put("idNumber", idNumber != null ? idNumber : "");
            m.put("permanentAddress", permanentAddress != null ? permanentAddress : "");
            m.put("phone", phone != null ? phone : "");
            m.put("email", email != null ? email : "");
            return m;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TenantParty {
        private String fullName;
        private String idNumber;
        private String permanentAddress;
        private String phone;
        private String email;
        private Integer occupantCount;
        private Integer motorbikeCount;
        private Integer carCount;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fullName", fullName != null ? fullName : "");
            m.put("idNumber", idNumber != null ? idNumber : "");
            m.put("permanentAddress", permanentAddress != null ? permanentAddress : "");
            m.put("phone", phone != null ? phone : "");
            m.put("email", email != null ? email : "");
            m.put("occupantCount", occupantCount != null ? occupantCount : 1);
            m.put("motorbikeCount", motorbikeCount != null ? motorbikeCount : 0);
            m.put("carCount", carCount != null ? carCount : 0);
            return m;
        }
    }
}
