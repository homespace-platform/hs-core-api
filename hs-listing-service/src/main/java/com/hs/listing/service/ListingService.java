package com.hs.listing.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.listing.dto.request.CreateListingRequest;
import com.hs.listing.dto.request.AddListingImageRequest;
import com.hs.listing.dto.request.UpdateListingRequest;
import com.hs.listing.dto.response.ListingResponse;
import com.hs.listing.dto.response.ListingImageResponse;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingImage;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.repository.ListingImageRepository;
import com.hs.listing.repository.ListingRepository;
import com.hs.storage.dto.response.StorageObjectResponse;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageStatus;
import com.hs.storage.service.StorageService;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ListingService {

    private final ListingRepository listingRepository;
    private final ListingImageRepository listingImageRepository;
    private final StorageService storageService;
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

    @Transactional
    public ListingResponse updateDraft(String ownerId, String listingId, UpdateListingRequest request) {
        Listing listing = findOwnedDraft(ownerId, listingId);
        if (request.title() != null) {
            if (request.title().isBlank()) throw new AppException(ErrorCode.INVALID_REQUEST);
            listing.setTitle(request.title().trim());
        }
        if (request.description() != null) listing.setDescription(request.description());
        if (request.priceMonthly() != null) listing.setPriceMonthly(request.priceMonthly());
        if (request.depositAmount() != null) listing.setDepositAmount(request.depositAmount());
        if (request.areaM2() != null) listing.setAreaM2(request.areaM2());
        if (request.bedrooms() != null) listing.setBedrooms(request.bedrooms());
        if (request.bathrooms() != null) listing.setBathrooms(request.bathrooms());
        if (request.provinceCode() != null) listing.setProvinceCode(request.provinceCode());
        if (request.districtCode() != null) listing.setDistrictCode(request.districtCode());
        if (request.wardCode() != null) listing.setWardCode(request.wardCode());
        if (request.address() != null) listing.setAddress(request.address());
        if (request.details() != null) listing.setDetailsJson(writeDetails(request.details()));
        return toResponse(listingRepository.save(listing));
    }

    @Transactional
    public ListingResponse addImage(String ownerId, String listingId, AddListingImageRequest request) {
        Listing listing = findOwnedDraft(ownerId, listingId);
        StorageObjectResponse storage = storageService.getById(request.storageId());
        if (storage == null
                || !ownerId.equals(storage.ownerId())
                || storage.purpose() != StoragePurpose.PROPERTY_IMAGE
                || storage.status() != StorageStatus.READY) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        if (listingImageRepository.existsByListingIdAndStorageId(listingId, request.storageId())) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        boolean cover = Boolean.TRUE.equals(request.cover());
        if (cover) listingImageRepository.clearCoverByListingId(listingId);
        listingImageRepository.save(ListingImage.builder()
                .listingId(listingId)
                .storageId(request.storageId())
                .sortOrder(request.sortOrder() == null ? 0 : request.sortOrder())
                .cover(cover)
                .build());
        return toResponse(listing);
    }

    private Listing findOwnedDraft(String ownerId, String listingId) {
        if (ownerId == null || ownerId.isBlank()) throw new AppException(ErrorCode.UNAUTHENTICATED);
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new AppException(ErrorCode.ROUTE_NOT_FOUND));
        if (!ownerId.equals(listing.getOwnerId())) throw new AppException(ErrorCode.UNAUTHORIZED);
        if (listing.getStatus() != ListingStatus.DRAFT) throw new AppException(ErrorCode.INVALID_REQUEST);
        return listing;
    }

    private ListingResponse toResponse(Listing listing) {
        return ListingResponse.from(listing, readDetails(listing.getDetailsJson()), readImages(listing.getId()));
    }

    private List<ListingImageResponse> readImages(String listingId) {
        if (listingId == null || listingId.isBlank()) return List.of();
        return listingImageRepository.findAllByListingIdOrderBySortOrderAsc(listingId).stream()
                .map(ListingImageResponse::from)
                .toList();
    }

    private String writeDetails(JsonNode details) {
        if (details == null || details.isNull()) return null;
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JacksonException exception) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
    }

    private JsonNode readDetails(String detailsJson) {
        if (detailsJson == null || detailsJson.isBlank()) return null;
        try {
            return objectMapper.readTree(detailsJson);
        } catch (JacksonException exception) {
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
        }
    }
}
