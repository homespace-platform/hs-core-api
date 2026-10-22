package com.hs.listing.model;
import com.hs.common.persistence.AddressFields;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Entity @Getter @Setter
@Table(name="listing_addresses", indexes={@Index(name="idx_listing_address_region", columnList="city_id,ward_id,active")})
// Imported/demo locations may not have provider identity or a resolved administrative ward yet.
// User/branch addresses stay strict; normal listing writes still resolve through VietmapService.apply().
@AttributeOverrides({
    @AttributeOverride(name="refId", column=@Column(name="ref_id", nullable=true, columnDefinition="text")),
    @AttributeOverride(name="wardId", column=@Column(name="ward_id", nullable=true, length=20)),
    @AttributeOverride(name="resolvedAt", column=@Column(name="resolved_at", nullable=true))
})
@org.hibernate.annotations.Check(constraints="(latitude is null and longitude is null) or (latitude between -90 and 90 and longitude between -180 and 180)")
public class ListingAddress extends AddressFields {
    @Column(name="listing_id", nullable=false, unique=true, length=36) private String listingId;
    @OneToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="listing_id", insertable=false, updatable=false) private Listing listing;
}
