package com.hs.listing.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.listing.model.constant.ListingEnums.BillingMethod;
import com.hs.listing.model.constant.ListingEnums.ChargeType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "branch_charges")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BranchCharge extends BaseEntity {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private PropertyBranch branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", nullable = false)
    private ChargeType chargeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_method", nullable = false)
    private BillingMethod billingMethod;

    @Column(precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(length = 3)
    private String currency;

    private String unit;

    @Column(name = "included_in_rent", nullable = false)
    private boolean includedInRent;

    @Column(name = "custom_name")
    private String customName;

    private String description;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
    }
}
