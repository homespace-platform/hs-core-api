package com.hs.contract.dto.response;

import com.hs.contract.model.constant.ContractStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractResponse {
    private String id;
    private String contractNumber;
    private String rentalRequestId;
    private String listingId;
    private String landlordId;
    private String tenantId;
    private String templateId;
    private String templateVersionId;
    private String currentRevisionId;
    private ContractStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
