package com.hs.listing.dto.response; import com.hs.listing.model.constant.ListingStatus; import java.time.Instant;
public record CreateListingResponse(String id, ListingStatus status, String title, Instant publishedAt) {}
