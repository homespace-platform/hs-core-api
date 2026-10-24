package com.hs.contract.deposit;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/** Deposit business identity and frozen D2 data; Contract is only the shared PDF/signature envelope. */
@Entity @Table(name="deposit_agreements",uniqueConstraints={
    @UniqueConstraint(name="uq_deposit_agreement_rental",columnNames="rental_request_id"),
    @UniqueConstraint(name="uq_deposit_agreement_payment",columnNames="source_payment_id"),
    @UniqueConstraint(name="uq_deposit_agreement_envelope",columnNames="signing_contract_id")})
@Getter @Setter @NoArgsConstructor
public class DepositAgreement {
    @Id @Column(length=36) private String id=UUID.randomUUID().toString();
    @Column(name="rental_request_id",nullable=false,length=36) private String rentalRequestId;
    @Column(name="source_payment_id",nullable=false,length=36) private String sourcePaymentId;
    @Column(name="signing_contract_id",nullable=false,length=36) private String signingContractId;
    @Column(name="snapshot_json",nullable=false,columnDefinition="TEXT") private String snapshotJson;
    @Column(name="snapshot_hash",nullable=false,length=64) private String snapshotHash;
    @Column(name="captured_at",nullable=false) private Instant capturedAt;
}
