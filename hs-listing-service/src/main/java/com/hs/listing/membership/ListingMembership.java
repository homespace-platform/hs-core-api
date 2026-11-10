package com.hs.listing.membership;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "listing_memberships")
@Getter @Setter
public class ListingMembership {
    @Id @Column(name = "owner_id", length = 36)
    private String ownerId;
    @Enumerated(EnumType.STRING) @Column(length = 16)
    private MembershipTier tier;
    private Instant firstPaidAt;
    private Instant paidExpiresAt;
    private int paidPriceVnd;
    private int paidUsed;
    private int paidReserved;
    private int freeUsed;
    private int freeReserved;
}
