package com.hs.api.controller.publish;

import com.hs.common.dto.PageResponse;
import com.hs.listing.dto.request.PublicListingSearchRequest;
import com.hs.listing.dto.response.PublicListingSummaryResponse;
import com.hs.listing.model.constant.FurnishingStatus;
import com.hs.listing.model.constant.ListingEnums.PositionType;
import com.hs.listing.model.constant.ListingEnums.RestroomType;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingSubtype;
import com.hs.listing.dto.response.ListingDetailResponse;
import com.hs.listing.service.ListingPublicService;
import com.hs.listing.service.ListingQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/public/listings")
@RequiredArgsConstructor
@Validated
public class PublicListingController {

    private final ListingPublicService listingPublicService;
    private final ListingQueryService listingQueryService;

    @GetMapping("/{listingId}")
    public com.hs.common.dto.ApiResponse<ListingDetailResponse> getPublicListingDetail(@PathVariable String listingId) {
        return com.hs.common.dto.ApiResponse.<ListingDetailResponse>builder()
                .result(listingQueryService.getById(null, listingId))
                .build();
    }

    @GetMapping
    public PageResponse<PublicListingSummaryResponse> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            @RequestParam(required = false) ListingCategory category,
            @RequestParam(required = false) ListingSubtype subtype,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String provinceCode,
            @RequestParam(required = false) String wardCode,
            @RequestParam(required = false) BigDecimal priceMin,
            @RequestParam(required = false) BigDecimal priceMax,
            @RequestParam(required = false) BigDecimal areaMin,
            @RequestParam(required = false) BigDecimal areaMax,
            @RequestParam(required = false) Integer bedrooms,
            @RequestParam(required = false) Integer bathrooms,
            @RequestParam(required = false) Boolean hasVideo,
            @RequestParam(required = false) FurnishingStatus furnishingStatus,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String balconyDirection,
            @RequestParam(required = false) String officeGrade,
            @RequestParam(required = false) PositionType positionType,
            @RequestParam(required = false) RestroomType restroomType,
            @RequestParam(required = false) String kitchenType,
            @RequestParam(required = false) String accessType,
            @RequestParam(required = false) String legalStatus,
            @RequestParam(required = false) Boolean hasMezzanine,
            @RequestParam(required = false) Boolean hasRooftop,
            @RequestParam(required = false) Boolean hasGarage,
            @RequestParam(defaultValue = "newest") String sort) {

        return listingPublicService.search(new PublicListingSearchRequest(
                page, size, category, subtype, keyword, provinceCode, wardCode,
                priceMin, priceMax, areaMin, areaMax, bedrooms, bathrooms, hasVideo,
                furnishingStatus, direction, balconyDirection, officeGrade, positionType, restroomType,
                kitchenType, accessType, legalStatus,
                hasMezzanine, hasRooftop, hasGarage, sort));
    }

    @GetMapping("/owners/{ownerId}/count")
    public com.hs.common.dto.ApiResponse<Long> getOwnerListingCount(@org.springframework.web.bind.annotation.PathVariable String ownerId) {
        return com.hs.common.dto.ApiResponse.<Long>builder()
                .result(listingPublicService.getOwnerListingCount(ownerId))
                .build();
    }
}
