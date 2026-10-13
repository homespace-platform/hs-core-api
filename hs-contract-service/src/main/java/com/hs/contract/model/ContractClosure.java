package com.hs.contract.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hs.contract.dto.billing.ClosureDeduction;
import com.hs.contract.dto.billing.ClosureEvent;

@Entity @Table(name="contract_closures")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ContractClosure {
    @Id @Column(length=36) private String contractId;
    @Column(nullable=false,length=24) private String status;
    private int proposalVersion;
    @Column(precision=15,scale=3) private BigDecimal electricityFinal;
    @Column(precision=15,scale=3) private BigDecimal waterFinal;
    @Column(precision=18,scale=2) private BigDecimal utilityAdjustment;
    @Column(precision=18,scale=2) private BigDecimal otherDeduction;
    @Column(precision=18,scale=2) private BigDecimal refundAmount;
    @Column(precision=18,scale=2) private BigDecimal balanceAmount;
    @Column(length=2000) private String note;
    private Instant proposedAt;
    private Instant respondBy;
    private Instant responseOverdueAt;
    @JsonIgnore @Column(columnDefinition="text") private String deductionsSnapshot;
    @JsonIgnore @Column(columnDefinition="text") private String historySnapshot;
    @Transient private List<ClosureDeduction> deductions;
    @Transient private List<ClosureEvent> history;
    private Instant acceptedAt;
    private Instant refundDueAt;
    private Instant overdueLoggedAt;
    private Instant closedAt;
    @Column(length=36) private String refundPaymentId;
    @Column(length=36) private String balancePaymentId;
}
