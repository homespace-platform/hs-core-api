package com.hs.listing.model;
import com.hs.common.persistence.AddressFields;
import jakarta.persistence.*;
/** Owned exclusively through PropertyBranch.address with cascade/orphan removal. */
@Entity @Table(name="branch_addresses")
@org.hibernate.annotations.Check(constraints="(latitude is null and longitude is null) or (latitude between -90 and 90 and longitude between -180 and 180)")
public class BranchAddress extends AddressFields {}
