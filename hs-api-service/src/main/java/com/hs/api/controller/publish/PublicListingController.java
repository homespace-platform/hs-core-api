package com.hs.api.controller.publish;

import com.hs.common.dto.PageResponse;
import com.hs.listing.dto.request.PublicListingSearchRequest;
import com.hs.listing.dto.response.PublicListingSummaryResponse;
import com.hs.listing.model.constant.FurnishingStatus;
import com.hs.listing.model.constant.ListingEnums.PositionType;
import com.hs.listing.model.constant.ListingEnums.RestroomType;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingSubtype;
import com.hs.listing.service.ListingPublicService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
            @RequestParam(required = false) Boolean hasVideo,
            @RequestParam(required = false) FurnishingStatus furnishingStatus,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String officeGrade,
            @RequestParam(required = false) PositionType positionType,
            @RequestParam(required = false) RestroomType restroomType,
            @RequestParam(required = false) Boolean hasMezzanine,
            @RequestParam(required = false) Boolean hasRooftop,
            @RequestParam(required = false) Boolean hasGarage,
            @RequestParam(defaultValue = "newest") String sort) {

        return listingPublicService.search(new PublicListingSearchRequest(
                page, size, category, subtype, keyword, provinceCode, wardCode,
                priceMin, priceMax, areaMin, areaMax, bedrooms, hasVideo,
                furnishingStatus, direction, officeGrade, positionType, restroomType,
                hasMezzanine, hasRooftop, hasGarage, sort));
    }
}
