package com.hs.contract.model;

import com.hs.common.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "monthly_invoices", uniqueConstraints =
        @UniqueConstraint(name = "uk_monthly_invoice_contract_period", columnNames = {"contract_id", "period_index"}),
        indexes = {
                @Index(name = "idx_monthly_invoice_contract", columnList = "contract_id"),
                @Index(name = "idx_monthly_invoice_due", columnList = "status, due_at")
        })
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class MonthlyInvoice extends BaseEntity {
    @Id @Column(length = 36, updatable = false)
    private String id;
    @Column(name = "contract_id", nullable = false, length = 36)
    private String contractId;
    @Column(name = "period_index", nullable = false)
    private int periodIndex;
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;
    @Column(name = "period_end_exclusive", nullable = false)
    private LocalDate periodEndExclusive;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private MonthlyInvoiceStatus status;
    @Column(name = "electricity_start", precision = 15, scale = 3)
    private BigDecimal electricityStart;
    @Column(name = "electricity_end", precision = 15, scale = 3)
    private BigDecimal electricityEnd;
    @Column(name = "water_start", precision = 15, scale = 3)
    private BigDecimal waterStart;
    @Column(name = "water_end", precision = 15, scale = 3)
    private BigDecimal waterEnd;
    @Column(name = "line_items_snapshot", columnDefinition = "TEXT")
    private String lineItemsSnapshot;
    @Column(name = "draft_extra_charges_snapshot", columnDefinition = "TEXT")
    private String draftExtraChargesSnapshot;
    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount;
    @Column(name = "base_amount", precision = 18, scale = 2)
    private BigDecimal baseAmount;
    @Column(name = "late_fee_amount", precision = 18, scale = 2)
    private BigDecimal lateFeeAmount;
    @Column(name = "payment_request_id", length = 36)
    private String paymentRequestId;
    @Column(name = "issued_at")
    private Instant issuedAt;
    @Column(name = "due_at")
    private Instant dueAt;
    @Column(name = "paid_at")
    private Instant paidAt;
    @Column(name = "meter_reminder_logged_at")
    private Instant meterReminderLoggedAt;
    @Column(name = "meter_deadline_logged_at")
    private Instant meterDeadlineLoggedAt;
    @Column(name = "payment_reminder_logged_at")
    private Instant paymentReminderLoggedAt;
    @Column(name = "overdue_action_logged_at")
    private Instant overdueActionLoggedAt;

    @PrePersist
    void initialize() {
        if (id == null) id = UUID.randomUUID().toString();
        if (totalAmount == null) totalAmount = BigDecimal.ZERO;
        if (status == null) status = MonthlyInvoiceStatus.DRAFT;
    }
}
