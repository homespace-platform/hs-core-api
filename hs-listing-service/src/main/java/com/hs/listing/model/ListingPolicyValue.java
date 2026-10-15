package com.hs.listing.model;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity @Table(name="listing_policy_values",uniqueConstraints=@UniqueConstraint(columnNames={"listing_id","policy_code"}))
@Getter @Setter @NoArgsConstructor
public class ListingPolicyValue {
    @Id @Column(length=36) private String id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="listing_id",nullable=false) private Listing listing;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="policy_code",nullable=false) private ListingPolicyDefinition definition;
    @Column(name="policy_value",nullable=false,length=2000) private String value;
    @Column(length=1000) private String note;
    @PrePersist void pre() { if (id == null) id = UUID.randomUUID().toString(); }
}
