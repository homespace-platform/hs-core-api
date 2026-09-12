package com.hs.listing.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.user.model.Address;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "property_branches", indexes = {
        @Index(name = "idx_branches_owner", columnList = "owner_id"),
        @Index(name = "idx_branches_owner_active", columnList = "owner_id, active")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PropertyBranch extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 64)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ListingCategory category;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "building_rules", columnDefinition = "text")
    private String buildingRules;

    @Column(name = "total_units")
    @Builder.Default
    private Integer totalUnits = 0;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    @JoinColumn(name = "address_id")
    private Address address;

    @OneToMany(mappedBy = "branch", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<BranchCharge> defaultCharges = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "branch_amenities", joinColumns = @JoinColumn(name = "branch_id"), inverseJoinColumns = @JoinColumn(name = "amenity_id"))
    @Builder.Default
    private Set<Amenity> buildingAmenities = new HashSet<>();

    @OneToMany(mappedBy = "branch", fetch = FetchType.LAZY)
    @Builder.Default
    private List<Listing> listings = new ArrayList<>();
}
