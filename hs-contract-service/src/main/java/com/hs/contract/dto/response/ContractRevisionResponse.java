package com.hs.contract.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractRevisionResponse {
    private String id;
    private String contractId;
    private Integer revisionNumber;
    private String templateVersionId;
    private Map<String, Object> landlord;
    private Map<String, Object> tenant;
    private Map<String, Object> property;
    private Map<String, Object> lease;
    private Map<String, Object> financial;
    private List<Map<String, Object>> charges;
    private List<Map<String, Object>> equipments;
    private Map<String, Object> meters;
    private String specialTerms;
    private String revisionNote;
    private Instant createdAt;
}
