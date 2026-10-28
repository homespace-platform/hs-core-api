package com.hs.listing.nearby.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;
import java.io.Serializable;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ListingNearbyPlaceId implements Serializable {
    @Column(name = "listing_id", nullable = false, length = 36)
    private String listingId;
    @Column(name = "place_id", nullable = false, length = 36)
    private String placeId;
}
