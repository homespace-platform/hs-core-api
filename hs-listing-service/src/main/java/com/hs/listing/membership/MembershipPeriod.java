package com.hs.listing.membership;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "membership_periods",
        indexes = {@Index(name = "idx_membership_periods_owner_end", columnList = "owner_id,end_at"),
                @Index(name = "idx_membership_periods_end", columnList = "end_at")}, // reminder scan across owners
        uniqueConstraints = @UniqueConstraint(name = "uk_membership_periods_owner_free_start",
                columnNames = {"owner_id", "free_start_at"}))
@Getter @Setter
public class MembershipPeriod {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(length = 36)
    private String id;
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
    private MembershipKind kind = MembershipKind.PLAN;
    /** Null for add-on rows. */
    @Enumerated(EnumType.STRING) @Column(length = 16)
    private MembershipTier tier;
    @Column(name = "start_at", nullable = false)
    private Instant startAt;
    @Column(name = "end_at", nullable = false)
    private Instant endAt;
    private int quota;
    private int used;
    private int reserved;
    private int priceVnd;
    // Set only on FREE rows. NULLs never collide, so other rows may share a start_at (simulated time is frozen).
    @Column(name = "free_start_at")
    private Instant freeStartAt;
    private Instant expiryReminderFor;
    private Instant expiredNoticeFor;

    public int remaining() {
        return Math.max(0, quota - used - reserved);
    }
}
