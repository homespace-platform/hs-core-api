package com.hs.listing.model;
import com.hs.common.persistence.AddressFields;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Entity @Getter @Setter
@Table(name="listing_addresses", indexes={@Index(name="idx_listing_address_region", columnList="city_id,ward_id,active")})
@org.hibernate.annotations.Check(constraints="(latitude is null and longitude is null) or (latitude between -90 and 90 and longitude between -180 and 180)")
public class ListingAddress extends AddressFields {
    @Column(name="listing_id", nullable=false, unique=true, length=36) private String listingId;
    @OneToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="listing_id", insertable=false, updatable=false) private Listing listing;
}
