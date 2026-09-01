package com.hs.listing.dto.response;

import com.hs.listing.dto.request.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.model.constant.ListingEnums.ViewingSlot;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ListingDetailResponse(
        String id,
        String ownerId,
        String title,
        String description,
        ListingCategory category,
        ListingSubtype subtype,
        RentalMode rentalMode,
        ListingStatus status,
        LocalDate availableFrom,
        BigDecimal areaM2,
        ListingPricingRequest pricing,
        ApartmentDetailRequest apartmentDetail,
        HouseDetailRequest houseDetail,
        OfficeDetailRequest officeDetail,
        CommercialDetailRequest commercialDetail,
        RoomDetailRequest roomDetail,
        List<ListingOptionItemResponse> amenities,
        List<String> customAmenities,
        List<ListingOptionItemResponse> furnishings,
        List<ListingChargeRequest> charges,
        ListingAddressResponse address,
        ListingOwnerResponse owner,
        List<ListingMediaResponse> media,
        List<DayOfWeek> viewingDays,
        List<ViewingSlot> viewingSlots,
        boolean active,
        String statusReason,
        Instant submittedAt,
        Instant publishedAt,
        Instant expiresAt,
        Instant statusChangedAt,
        String statusChangedBy,
        long version,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy,
        Long viewCount
) {
}
