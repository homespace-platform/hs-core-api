package com.hs.listing.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;
import com.hs.listing.model.Listing;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.user.model.Address;
import com.hs.user.model.User;

public record ListingResponse(
        String id,
        String ownerId,
        String title,
        String description,
        ListingCategory category,
        ListingStatus status,
        BigDecimal priceMonthly,
        BigDecimal depositAmount,
        BigDecimal areaM2,
        Integer bedrooms,
        Integer bathrooms,
        ListingAddressResponse address,
        ListingOwnerResponse owner,
        JsonNode details,
        List<String> imageUrls,
        List<String> videoUrls,
        Instant createdAt,
        Instant updatedAt
) {

    public static ListingResponse from(
            Listing listing,
            Address address,
            User owner,
            JsonNode details,
            List<String> imageUrls,
            List<String> videoUrls) {
        return new ListingResponse(
                listing.getId(), listing.getOwnerId(), listing.getTitle(), listing.getDescription(),
                listing.getCategory(), listing.getStatus(), listing.getPriceMonthly(), listing.getDepositAmount(),
                listing.getAreaM2(), listing.getBedrooms(), listing.getBathrooms(),
                ListingAddressResponse.from(address), ListingOwnerResponse.from(owner), details, imageUrls,
                videoUrls, listing.getCreatedAt(), listing.getUpdatedAt());
    }
}
