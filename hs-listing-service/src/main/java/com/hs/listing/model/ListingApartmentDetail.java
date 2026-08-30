package com.hs.listing.model;

import com.hs.listing.model.constant.FurnishingStatus;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "listing_apartment_details")
@Getter
@Setter
@NoArgsConstructor
public class ListingApartmentDetail {
    @Id
    private String listingId;
    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_id")
    private Listing listing;
    @Column(name = "project_name", nullable = false)
    private String projectName;
    @Column(name = "building_block")
    private String buildingBlock;
    @Column(name = "unit_code")
    private String unitCode;
    @Column(name = "floor_number", nullable = false)
    private Integer floorNumber;
    @Column(name = "building_total_floors")
    private Integer buildingTotalFloors;
    @Column(name = "bedroom_count", nullable = false)
    private Integer bedroomCount;
    @Column(name = "bathroom_count", nullable = false)
    private Integer bathroomCount;
    @Column(name = "living_room_count")
    private Integer livingRoomCount;
    @Column(name = "kitchen_count")
    private Integer kitchenCount;
    @Enumerated(EnumType.STRING)
    @Column(name = "furnishing_status", nullable = false)
    private FurnishingStatus furnishingStatus;
    @Column(name = "main_door_direction")
    private String mainDoorDirection;
    @Column(name = "balcony_direction")
    private String balconyDirection;
    @Column(name = "view_description")
    private String viewDescription;
    @Column(name = "max_occupants")
    private Integer maxOccupants;
    @Column(name = "legal_status")
    private String legalStatus;
}
