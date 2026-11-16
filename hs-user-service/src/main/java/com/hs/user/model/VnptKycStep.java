package com.hs.user.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name="vnpt_kyc_steps", indexes=@Index(name="idx_vnpt_step_attempt", columnList="attempt_id"))
@Getter @Setter @NoArgsConstructor
public class VnptKycStep {
    @Id @Column(length=64) private String id;
    @Column(name="attempt_id", nullable=false, length=36) private String attemptId;
    @Column(nullable=false, length=32) private String kind;
    @Column(nullable=false, length=32) private String state;
    @Column(length=512) private String frontHash;
    @Column(length=512) private String backHash;
    @Column(length=512) private String faceHash;
    @Column(length=64) private String imageDigest;
    @Column(length=64) private String nearDigest;
    @Column(length=64) private String farDigest;
    @Column(length=512) private String uploadHash;
    @Column(length=128) private String clientSession;
    @Column(length=16000) private String resultJson;
    @Column(nullable=false) private Instant createdAt;
}
