package com.hs.common.persistence;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** A selected Place v4 result, owned separately by User, Listing or Branch. */
@Getter @Setter @MappedSuperclass
public abstract class AddressFields extends BaseEntity {
    @Id @Column(length=36,nullable=false,updatable=false) private String id;
    @Column(name="ref_id",nullable=false,columnDefinition="text") private String refId;
    @Column(nullable=false,length=1000) private String display;
    @Column(length=255) private String name;
    @Column(name="house_number",length=100) private String houseNumber;
    @Column(nullable=false,length=255) private String street;
    @Column(nullable=false,length=500) private String address;
    @Column(name="city_id",nullable=false,length=20) private String cityId;
    @Column(nullable=false,length=100) private String city;
    @Column(name="district_id",length=20) private String districtId;
    @Column(length=100) private String district;
    @Column(name="ward_id",nullable=false,length=20) private String wardId;
    @Column(nullable=false,length=100) private String ward;
    @Column(nullable=false,precision=10,scale=7) private BigDecimal latitude;
    @Column(nullable=false,precision=10,scale=7) private BigDecimal longitude;
    @Column(name="address_note",length=255) private String addressNote;
    @Column(name="resolved_at",nullable=false) private Instant resolvedAt;
    @Version private Long version;
    @PrePersist protected void initializeId() { if(id==null || id.isBlank()) id=UUID.randomUUID().toString(); }
    public void copyAddressFrom(AddressFields s) {
        refId=s.refId; display=s.display; name=s.name; houseNumber=s.houseNumber; street=s.street;
        address=s.address; cityId=s.cityId; city=s.city; districtId=s.districtId; district=s.district;
        wardId=s.wardId; ward=s.ward; latitude=s.latitude; longitude=s.longitude;
        addressNote=s.addressNote; resolvedAt=s.resolvedAt;
    }
}
