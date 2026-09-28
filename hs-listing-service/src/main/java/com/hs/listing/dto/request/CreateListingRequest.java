package com.hs.listing.dto.request;

import com.hs.listing.model.constant.*;
import com.hs.listing.model.constant.ListingEnums.ViewingSlot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

public record CreateListingRequest(
        @Size(max = 36) String id,
        @Size(max = 36) String duplicateSourceListingId,
        @Size(max = 36) String branchId,
        @NotNull ListingSubmissionAction submissionAction,
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Size(max = 5000) String description,
        @NotNull ListingCategory category,
        @NotNull LocalDate availableFrom,
        @NotNull @DecimalMin("0.01") BigDecimal areaM2,
        Integer maxMotorbikeCount,
        Integer maxCarCount,
        @NotNull @Valid ListingPricingRequest pricing,
        @Valid ApartmentDetailRequest apartmentDetail,
        @Valid HouseDetailRequest houseDetail,
        @Valid OfficeDetailRequest officeDetail,
        @Valid CommercialDetailRequest commercialDetail,
        @Valid RoomDetailRequest roomDetail,
        List<String> amenityCodes,
        List<String> customAmenities,
        List<@Valid ListingFurnishingRequest> furnishings,
        List<@Valid ListingChargeRequest> charges,
        @NotNull @Valid ListingAddressSourceRequest addressSource,
        @NotEmpty List<@Valid ListingMediaRequest> media,
        List<DayOfWeek> viewingDays,
        List<ViewingSlot> viewingSlots) {

    public CreateListingRequest(
            String id,
            String branchId,
            ListingSubmissionAction submissionAction,
            String title,
            String description,
            ListingCategory category,
            LocalDate availableFrom,
            BigDecimal areaM2,
            Integer maxMotorbikeCount,
            Integer maxCarCount,
            ListingPricingRequest pricing,
            ApartmentDetailRequest apartmentDetail,
            HouseDetailRequest houseDetail,
            OfficeDetailRequest officeDetail,
            CommercialDetailRequest commercialDetail,
            RoomDetailRequest roomDetail,
            List<String> amenityCodes,
            List<String> customAmenities,
            List<ListingFurnishingRequest> furnishings,
            List<ListingChargeRequest> charges,
            ListingAddressSourceRequest addressSource,
            List<ListingMediaRequest> media,
            List<DayOfWeek> viewingDays,
            List<ViewingSlot> viewingSlots) {
        this(id, null, branchId, submissionAction, title, description, category, availableFrom, areaM2,
                maxMotorbikeCount, maxCarCount, pricing, apartmentDetail, houseDetail, officeDetail,
                commercialDetail, roomDetail, amenityCodes, customAmenities, furnishings, charges,
                addressSource, media, viewingDays, viewingSlots);
    }
}
