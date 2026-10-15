package com.hs.listing.model;

import com.hs.listing.model.constant.ListingCategory;
import jakarta.persistence.*;
import lombok.*;
import java.util.*;

@Entity @Table(name="listing_policy_definitions") @Getter @Setter @NoArgsConstructor
public class ListingPolicyDefinition {
    @Id @Column(length=64) private String code;
    @Column(nullable=false,length=150) private String label;
    @Column(nullable=false,length=20) private String valueType;
    @Column(length=500) private String helpText;
    @Column(length=30) private String unitLabel;
    private Integer minimumValue;
    private Integer maximumValue;
    @Column(nullable=false) private boolean required;
    @Column(nullable=false) private boolean active = true;
    @Column(nullable=false) private int sortOrder;
    @ElementCollection @CollectionTable(name="listing_policy_definition_categories",joinColumns=@JoinColumn(name="policy_code"),uniqueConstraints=@UniqueConstraint(columnNames={"policy_code","category"}))
    @Enumerated(EnumType.STRING) @Column(name="category",nullable=false,length=30)
    private Set<ListingCategory> categories = new HashSet<>();
}
