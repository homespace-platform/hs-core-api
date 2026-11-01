package com.hs.listing.dto.response;

/** Published listing count for one hard-coded home metro (Vietmap cityId). */
public record CityListingCountResponse(
        String cityId,
        long count
) {
}
