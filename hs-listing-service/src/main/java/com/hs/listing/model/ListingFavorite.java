package com.hs.listing.model;

import com.hs.common.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "listing_favorites", uniqueConstraints = {
        @UniqueConstraint(name = "uk_listing_favorite_user", columnNames = {"user_id", "listing_id"})
}, indexes = {
        @Index(name = "idx_favorite_user", columnList = "user_id"),
        @Index(name = "idx_favorite_listing", columnList = "listing_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListingFavorite extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_id", nullable = false)
    private Listing listing;
}
