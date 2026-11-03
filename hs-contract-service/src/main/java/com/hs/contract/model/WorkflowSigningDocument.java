package com.hs.contract.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="workflow_signing_documents",uniqueConstraints=@UniqueConstraint(columnNames={"workflow_id","content_hash"}))
@Getter @Setter @NoArgsConstructor
public class WorkflowSigningDocument {
    @Id @Column(length=36) private String id;
    @Column(name="workflow_id",nullable=false,length=64) private String workflowId;
    @Column(nullable=false,length=36,unique=true) private String signingContractId;
    @Column(name="content_hash",nullable=false,length=64) private String contentHash;
    private long sourceRevision;
    @Column(nullable=false,columnDefinition="text") private String sourceSnapshotJson;
    private Instant preparedAt;
    private Instant signedAt;
    @PrePersist void init(){if(id==null)id=UUID.randomUUID().toString();}
}
