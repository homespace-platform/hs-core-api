package com.hs.listing.dto.response;
import com.hs.listing.model.constant.ListingCategory;
import java.util.List;
public record ListingOptionsResponse(ListingCategory category,List<ListingOptionItemResponse> amenities,
        List<ListingOptionItemResponse> furnishings,List<ListingPolicyOption> policies) {
    public ListingOptionsResponse(ListingCategory category,List<ListingOptionItemResponse> amenities,List<ListingOptionItemResponse> furnishings) {
        this(category,amenities,furnishings,List.of());
    }
}
