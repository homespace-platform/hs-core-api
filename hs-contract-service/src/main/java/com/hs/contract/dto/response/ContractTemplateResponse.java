package com.hs.contract.dto.response;

import com.hs.contract.model.constant.ContractTemplateStatus;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.RentalMode;
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
    private RentalMode rentalMode;
    private ContractTemplateStatus status;
    private String latestPublishedVersionId;
    private int versionsCount;
    private Instant createdAt;
    private Instant updatedAt;
}
