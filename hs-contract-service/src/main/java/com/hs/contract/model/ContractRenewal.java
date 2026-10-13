package com.hs.contract.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.util.UUID;

@Entity @Table(name="contract_renewals", indexes=@Index(name="idx_renewal_contract", columnList="contract_id"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ContractRenewal {
    @Id @Column(length=36) private String id;
    @Column(name="contract_id",nullable=false,length=36) private String contractId;
    @Column(nullable=false) private int months;
    @Column(nullable=false,length=20) private String status;
    private LocalDate startsOn;
    private LocalDate endExclusive;
    private Instant requestedAt;
    private Instant respondBy;
    private Instant signBy;
    private Instant decidedAt;
    private Instant appliedAt;
    @Column(length=36) private String agreementContractId;
    @Column(length=1000) private String note;
    @Transient private com.hs.contract.model.constant.ContractStatus agreementStatus;
    @PrePersist void init() { if(id==null) id=UUID.randomUUID().toString(); }
}
