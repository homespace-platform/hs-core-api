package com.hs.listing.service;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.dto.PageResponse;
import com.hs.listing.dto.request.CreateListingRequest;
import com.hs.listing.dto.request.UpdateListingRequest;
import com.hs.listing.dto.response.ListingResponse;
import com.hs.listing.model.Listing;
import com.hs.listing.model.constant.ListingMediaType;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.repository.ListingRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ListingService {

    private static final TypeReference<List<String>> URL_LIST_TYPE = new TypeReference<>() {};

    private final ListingRepository listingRepository;
    private final ListingMediaService listingMediaService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ListingResponse createDraft(String ownerId, CreateListingRequest request) {
        if (ownerId == null || ownerId.isBlank()) throw new AppException(ErrorCode.UNAUTHENTICATED);

        List<String> imageUrls = normalizeUrls(request.imageUrls());
        List<String> videoUrls = normalizeUrls(request.videoUrls());
        validateMediaUrls(ownerId, imageUrls, ListingMediaType.IMAGE);
        validateMediaUrls(ownerId, videoUrls, ListingMediaType.VIDEO);

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
                .imageUrlsJson(writeUrls(imageUrls))
                .videoUrlsJson(writeUrls(videoUrls))
                .build();

        return toResponse(listingRepository.save(listing));
    }

    @Transactional(readOnly = true)
    public ListingResponse getById(String viewerId, String listingId) {
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new AppException(ErrorCode.ROUTE_NOT_FOUND));
        boolean owner = viewerId != null && viewerId.equals(listing.getOwnerId());
        boolean visible = listing.getStatus() == ListingStatus.PUBLISHED
                || listing.getStatus() == ListingStatus.RENTED;
        if (!owner && !visible) throw new AppException(ErrorCode.ROUTE_NOT_FOUND);
        return toResponse(listing);
    }

    @Transactional(readOnly = true)
    public PageResponse<ListingResponse> getAll(Pageable pageable) {
        return new PageResponse<>(listingRepository.findAllByStatusIn(
                List.of(ListingStatus.PUBLISHED, ListingStatus.RENTED), pageable)
                .map(this::toResponse));
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
        if (request.imageUrls() != null) {
            List<String> imageUrls = normalizeUrls(request.imageUrls());
            validateMediaUrls(ownerId, imageUrls, ListingMediaType.IMAGE);
            listing.setImageUrlsJson(writeUrls(imageUrls));
        }
        if (request.videoUrls() != null) {
            List<String> videoUrls = normalizeUrls(request.videoUrls());
            validateMediaUrls(ownerId, videoUrls, ListingMediaType.VIDEO);
            listing.setVideoUrlsJson(writeUrls(videoUrls));
        }
        return toResponse(listingRepository.save(listing));
    }

    @Transactional
    public ListingResponse publish(String ownerId, String listingId) {
        Listing listing = findOwnedDraft(ownerId, listingId);
        listing.setStatus(ListingStatus.PUBLISHED);
        return toResponse(listingRepository.save(listing));
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
        return ListingResponse.from(
                listing,
                readDetails(listing.getDetailsJson()),
                readUrls(listing.getImageUrlsJson()),
                readUrls(listing.getVideoUrlsJson()));
    }

    private List<String> normalizeUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) return List.of();
        LinkedHashSet<String> uniqueUrls = new LinkedHashSet<>();
        for (String url : urls) {
            if (url == null || url.isBlank()) continue;
            uniqueUrls.add(url.trim());
        }
        return new ArrayList<>(uniqueUrls);
    }

    private void validateMediaUrls(String ownerId, List<String> urls, ListingMediaType mediaType) {
        for (String url : urls) {
            if (!listingMediaService.isAllowedMediaUrl(ownerId, url, mediaType)) {
                throw new AppException(ErrorCode.INVALID_REQUEST);
            }
        }
    }

    private String writeUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(urls);
        } catch (JacksonException exception) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
    }

    private List<String> readUrls(String urlsJson) {
        if (urlsJson == null || urlsJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(urlsJson, URL_LIST_TYPE);
        } catch (JacksonException exception) {
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
        }
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
