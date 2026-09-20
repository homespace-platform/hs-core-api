package com.hs.payment.model;

import com.hs.common.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(
        name = "payment_line_items",
        indexes = {
                @Index(name = "idx_payment_line_item_request", columnList = "payment_request_id")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PaymentLineItem extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    String id;

    @Column(name = "payment_request_id", nullable = false, length = 36)
    String paymentRequestId;

    @Column(name = "type", nullable = false, length = 50)
    String type;

    @Column(name = "display_name", nullable = false, length = 200)
    String displayName;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    BigDecimal amount;

    @Builder.Default
    @Column(name = "quantity", nullable = false)
    Integer quantity = 1;

    @Column(name = "unit_price", precision = 15, scale = 2)
    BigDecimal unitPrice;

    @Column(name = "calculation_description", length = 500)
    String calculationDescription;

    @Column(name = "note", length = 500)
    String note;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    Integer sortOrder = 0;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (quantity == null) {
            quantity = 1;
        }
        if (sortOrder == null) {
            sortOrder = 0;
        }
    }
}
