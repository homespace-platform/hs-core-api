package com.hs.contract.notification;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name="operational_incidents",uniqueConstraints=@UniqueConstraint(columnNames="source_key"))
@Getter @Setter @NoArgsConstructor
public class OperationalIncident {
    @Id @Column(length=36) private String id;
    @Column(name="source_key",nullable=false,length=200) private String sourceKey;
    @Column(nullable=false,length=16) private String component;
    @Column(length=36) private String resourceId;
    private boolean open;
    private Instant openedAt;
    private Instant resolvedAt;
}
