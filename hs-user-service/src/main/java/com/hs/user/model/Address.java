package com.hs.user.model;
import com.hs.common.persistence.AddressFields;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
/** Private profile address: no Listing or Branch IDs. */
@Entity @Table(name="user_addresses") @Getter @Setter
@org.hibernate.annotations.Check(constraints="(latitude is null and longitude is null) or (latitude between -90 and 90 and longitude between -180 and 180)")
public class Address extends AddressFields {
    @OneToOne(fetch=FetchType.LAZY, optional=false)
    @JoinColumn(name="user_id", nullable=false, unique=true)
    private User user;
}
