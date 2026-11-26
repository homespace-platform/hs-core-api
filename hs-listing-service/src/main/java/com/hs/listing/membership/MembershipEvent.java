package com.hs.listing.membership;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import java.time.Instant;

/** Append-only ledger, written in the same transaction as the period change. delta = change to remaining uses. */
@Entity
@Immutable
@Table(name = "membership_events")
@Getter @NoArgsConstructor @AllArgsConstructor
public class MembershipEvent {
    public enum Type { RESERVE, CONSUME, RELEASE, PURCHASE, UPGRADE }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private Type type;
    private int delta;
    @Column(nullable = false, length = 36)
    private String periodId;
    @Column(length = 36)
    private String listingId;
    @Column(length = 32)
    private String orderId;
    @Enumerated(EnumType.STRING) @Column(length = 16)
    private MembershipTier oldTier;
    @Enumerated(EnumType.STRING) @Column(length = 16)
    private MembershipTier newTier;
    @Column(nullable = false)
    private Instant occurredAt;
}
