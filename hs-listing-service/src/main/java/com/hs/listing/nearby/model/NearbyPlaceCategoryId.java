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
public class NearbyPlaceCategoryId implements Serializable {
    @Column(name = "place_id", nullable = false, length = 36)
    private String placeId;
    @Column(name = "category_code", nullable = false, length = 30)
    private String categoryCode;
}
