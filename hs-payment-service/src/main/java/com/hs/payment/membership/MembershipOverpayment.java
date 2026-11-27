package com.hs.payment.membership;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** A verified payment that was not applied (second payment, or not activatable). Awaits a manual refund. */
@Entity
@Table(name = "membership_overpayments",
        uniqueConstraints = @UniqueConstraint(name = "uq_membership_overpayment_tx", columnNames = {"provider", "transaction_no"}),
        indexes = @Index(name = "idx_membership_overpayments_status", columnList = "status,received_at"))
@Getter @Setter
public class MembershipOverpayment {
    @Id @Column(length = 32)
    private String id;
    @Column(name = "order_id", nullable = false, length = 32)
    private String orderId;
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private MembershipOrder.Provider provider;
    @Column(name = "transaction_no", nullable = false, length = 64)
    private String transactionNo;
    @Column(name = "amount_vnd", nullable = false)
    private int amountVnd;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private Status status = Status.REFUND_PENDING;
    private Instant refundedAt;
    @Column(length = 36)
    private String refundedBy;
    @Column(length = 500)
    private String note;

    public enum Status { REFUND_PENDING, REFUNDED }
}
