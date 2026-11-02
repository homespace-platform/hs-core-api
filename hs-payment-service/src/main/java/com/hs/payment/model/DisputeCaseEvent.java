package com.hs.payment.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="dispute_case_events",uniqueConstraints=@UniqueConstraint(name="uq_dispute_event_revision",columnNames={"case_id","revision"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DisputeCaseEvent {
    @Id @Column(length=36,updatable=false) private String id;
    @Column(name="case_id",nullable=false,length=36,updatable=false) private String caseId;
    @Column(nullable=false,updatable=false) private long revision;
    @Column(nullable=false,length=30,updatable=false) private String action;
    @Column(nullable=false,length=36,updatable=false) private String actorId;
    @Column(nullable=false,length=20,updatable=false) private String actorRole;
    @Column(nullable=false,length=500,updatable=false) private String reason;
    @Column(length=36,updatable=false) private String evidenceStorageId;
    @Column(columnDefinition="TEXT",updatable=false) private String metadataJson;
    @Column(nullable=false,updatable=false) private Instant occurredAt;
    @PrePersist void initialize(){if(id==null)id=UUID.randomUUID().toString();}
}
