package com.hs.contract.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** One current aggregate per rental and purpose; every reviewed version is retained in its audit. */
@Entity @Table(name="rental_workflow_records",uniqueConstraints=@UniqueConstraint(columnNames={"rental_request_id","kind"}))
@Getter @Setter @NoArgsConstructor
public class RentalWorkflowRecord {
    @Id @Column(length=64) private String id;
    @Column(name="rental_request_id",nullable=false,length=36) private String rentalRequestId;
    @Column(length=36) private String contractId;
    @Column(nullable=false,length=24) private String kind;
    @Column(nullable=false,length=24) private String status="EMPTY";
    private long revision;
    @Column(length=36) private String proposedBy;
    @Column(length=36) private String sourceRevisionId;
    private Instant respondBy;
    private Instant ownerConfirmedAt;
    private Instant tenantConfirmedAt;
    @Column(columnDefinition="text") private String payloadJson;
    @Column(columnDefinition="text") private String historyJson;
    @Column(columnDefinition="text") private String calculationJson;
    @Column(length=36) private String complaintId;
    @Version private Long lockVersion;
}
