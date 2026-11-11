package com.hs.payment.membership;

import com.hs.listing.membership.MembershipTier;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "membership_orders", indexes = @Index(name = "idx_membership_orders_owner_status", columnList = "owner_id,status"))
@Getter @Setter
public class MembershipOrder {
    @Id @Column(length = 32)
    private String id;
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private MembershipTier tier;
    @Column(name = "amount_vnd", nullable = false)
    private int amountVnd;
    @Column(nullable = false)
    private boolean upgrade;
    private Instant upgradeDeadline;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private Status status;
    @Enumerated(EnumType.STRING) @Column(length = 16)
    private Provider provider;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant expiresAt;
    private Instant paidAt;
    @Column(length = 64)
    private String transactionNo;

    public enum Status { PENDING, PAID, FAILED, EXPIRED, CANCELLED, REVIEW }
    /** VNPAY retained for historical rows only. */
    public enum Provider { VNPAY, SEPAY, ETH }
}
