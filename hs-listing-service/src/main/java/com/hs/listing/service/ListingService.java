package com.hs.listing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.listing.dto.request.CreateListingRequest;
import com.hs.listing.dto.response.ListingResponse;
import com.hs.listing.model.Listing;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.repository.ListingRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ListingService {

    private final ListingRepository listingRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public ListingResponse createDraft(String ownerId, CreateListingRequest request) {
        if (ownerId == null || ownerId.isBlank()) throw new AppException(ErrorCode.UNAUTHENTICATED);

        Listing listing = Listing.builder()
                .title(request.title().trim())
                .description(request.description())
                .category(request.category())
                .status(ListingStatus.DRAFT)
                .ownerId(ownerId)
                .priceMonthly(request.priceMonthly())
                .depositAmount(request.depositAmount())
                .areaM2(request.areaM2())
                .bedrooms(request.bedrooms())
                .bathrooms(request.bathrooms())
                .provinceCode(request.provinceCode())
                .districtCode(request.districtCode())
                .wardCode(request.wardCode())
                .address(request.address())
                .detailsJson(writeDetails(request.details()))
                .build();

        return toResponse(listingRepository.save(listing));
    }

    private ListingResponse toResponse(Listing listing) {
        return ListingResponse.from(listing, readDetails(listing.getDetailsJson()));
    }

    private String writeDetails(JsonNode details) {
        if (details == null || details.isNull()) return null;
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException exception) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
    }

    private JsonNode readDetails(String detailsJson) {
        if (detailsJson == null || detailsJson.isBlank()) return null;
        try {
            return objectMapper.readTree(detailsJson);
        } catch (JsonProcessingException exception) {
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
        }
    }
}
