package com.hs.contract.dto.response;

import com.hs.contract.model.constant.ContractTemplateSource;
import com.hs.contract.model.constant.ContractTemplateStatus;
import com.hs.listing.model.constant.ListingCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractTemplateResponse {
    private String id;
    private String name;
    private String description;
    private ListingCategory category;
    private ContractTemplateSource source;
    private String ownerUserId;
    private ContractTemplateStatus status;
    private String latestPublishedVersionId;
    private int versionsCount;
    private Instant createdAt;
    private Instant updatedAt;
}
