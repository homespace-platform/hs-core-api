package com.hs.listing.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.listing.model.constant.ListingCategory;
import jakarta.persistence.*;
import lombok.*;
import java.util.*;

@Entity
@Table(name = "amenities")
@Getter
@Setter
@NoArgsConstructor
public class Amenity extends BaseEntity {
    @Id
    @Column(length = 36)
    private String id;
    @Column(nullable = false, unique = true)
    private String code;
    @Column(nullable = false)
    private String name;
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "amenity_categories", joinColumns = @JoinColumn(name = "amenity_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "category")
    private Set<ListingCategory> categories = new HashSet<>();
}
