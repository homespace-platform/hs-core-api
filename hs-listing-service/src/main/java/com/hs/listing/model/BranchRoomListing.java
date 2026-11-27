package com.hs.listing.model;

import jakarta.persistence.*;
import lombok.*;

/** Management-only link between a room and a listing: one room holds many listings, a listing has one room. */
@Entity
@Table(name = "branch_room_listings",
        indexes = @Index(name = "idx_branch_room_listings_room", columnList = "room_id"))
@Getter
@Setter
@NoArgsConstructor
public class BranchRoomListing {

    @Id
    @Column(name = "listing_id", length = 36, updatable = false)
    private String listingId;

    @Column(name = "room_id", nullable = false, length = 36)
    private String roomId;

    // Read-only associations: they only declare the foreign keys.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_id", insertable = false, updatable = false)
    private Listing listing;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", insertable = false, updatable = false)
    private BranchSlot room;

    public BranchRoomListing(String listingId, String roomId) {
        this.listingId = listingId;
        this.roomId = roomId;
    }
}
